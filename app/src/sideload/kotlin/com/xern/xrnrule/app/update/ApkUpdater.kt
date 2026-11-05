package com.xern.xrnrule.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xern.xrnrule.app.block.Notifications
import com.xern.xrnrule.app.data.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps the sideloaded app current from the home server, at the address and with the login of
 * Sync with laptop: `GET <url>/update/app.json` names the newest build, its APK beside it and the
 * APK's SHA-256 (published by `scripts/release.mjs app`). A newer build is downloaded, checked
 * (hash, package, build number, and signed with this app's own key, which Android insists on
 * anyway) and kept until asked for.
 *
 * The background check runs every [CHECK_EVERY_H] hours and only gets a build ready: [ready] puts
 * a banner on the Blocks tab, a notification says so once per build, and both open Settings →
 * Updates, whose button is the only way in. An install nobody asked for can't be answered:
 * the phone may meet it with a question (HyperOS's installer, Play Protect) that waits for the
 * next unlock, and on HyperOS one that goes through turns the overlay off with nobody there to
 * notice. A tap also means the restart never lands mid-use: Android replaces the process, and
 * BootReceiver brings the service back on MY_PACKAGE_REPLACED.
 */
class ApkUpdater(context: Context, private val sync: SyncSettings) : AppUpdates {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "update")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** What `app.json` says: the newest build, and the name of its APK under `/update/`. */
    @Serializable
    private data class Release(val versionCode: Long, val versionName: String = "", val apk: String, val size: Long, val sha256: String) {
        val label: String get() = "$versionName ($versionCode)"
    }

    /** A release downloaded and checked, waiting to be installed. */
    private class Ready(val release: Release, val file: File)

    private val _status = MutableStateFlow(savedStatus())
    override val status: StateFlow<UpdateStatus?> = _status.asStateFlow()
    private val _ready = MutableStateFlow(savedReady()?.label)
    override val ready: StateFlow<String?> = _ready.asStateFlow()
    override val isSupported = true
    override val installedVersion: String
        get() = packageInfo().let { "${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})" }

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    override fun installPermissionIntent() =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    override fun start() {
        val installing = prefs.getLong(KEY_INSTALLING, 0)
        if (installing > 0 && installedCode() >= installing) {
            // This process is the update: say so, and drop the download it came from.
            prefs.edit { remove(KEY_INSTALLING) }
            report(true, "Updated to $installedVersion")
            dir.deleteRecursively()
        }
        // However this build got here, what waited for it is over.
        if (savedReady().let { it == null || it.versionCode <= installedCode() }) dropReady()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(CHECK_EVERY_H, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UpdateWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The worker's look: get a newer build ready, and say so once. */
    suspend fun checkInBackground() = locked {
        val ready = fetch(manual = false) ?: return@locked
        if (prefs.getLong(KEY_ANNOUNCED, 0) == ready.release.versionCode) return@locked
        prefs.edit { putLong(KEY_ANNOUNCED, ready.release.versionCode) }
        val notifications = Notifications(context).apply { createChannels() }
        notifications.alert(Notifications.ALERT_UPDATE, "xrnrule update ready", "Version ${ready.release.label}. Tap to install it.", notifications.openUpdates())
    }

    override suspend fun checkNow() = locked {
        // Whoever asked is looking at the answer: no notification for this build as well.
        fetch(manual = true)?.let { prefs.edit { putLong(KEY_ANNOUNCED, it.release.versionCode) } }
        Unit
    }

    override suspend fun installNow() = locked {
        // What's already here still installs when the server can't be reached.
        val ready = fetch(manual = true) ?: readyDownload() ?: return@locked
        if (canInstall()) {
            install(ready)
        } else {
            report(false, "Version ${ready.release.label} is ready, but Android doesn't let xrnrule install it yet: allow it above.")
        }
    }

    /** One at a time, off the main thread: a check and an install share the download. */
    private suspend fun locked(work: suspend () -> Unit) = withContext(Dispatchers.IO) { mutex.withLock { work() } }

    /** A newer build, downloaded and checked; null, with [status] saying why, when there's none to install. */
    private fun fetch(manual: Boolean): Ready? {
        if (!sync.isConfigured) {
            report(false, "Updates come from the server in Sync with laptop: set that up first.")
            return null
        }
        val release = try {
            latest()
        } catch (e: IOException) {
            report(false, "Couldn't ask the home server: ${e.message}")
            return null
        }
        if (release == null) {
            report(false, "The home server has no release of the app yet.")
            return null
        }
        if (release.versionCode <= installedCode()) {
            dropReady()
            report(true, "Up to date")
            return null
        }
        // A bad build isn't fetched again on the background check's own account.
        if (!manual && release.versionCode == prefs.getLong(KEY_REFUSED, 0)) return null
        val file = File(dir, release.apk)
        try {
            if (!(file.isFile && sha256(file) == release.sha256)) download(release, file)
        } catch (e: IOException) {
            report(false, "Couldn't download version ${release.label}: ${e.message}")
            return null
        }
        problemWith(file, release)?.let { problem ->
            file.delete()
            prefs.edit { putLong(KEY_REFUSED, release.versionCode) }
            report(false, problem)
            return null
        }
        prefs.edit { putString(KEY_READY, json.encodeToString(Release.serializer(), release)) }
        _ready.value = release.label
        report(true, "Version ${release.label} is ready to install.")
        return Ready(release, file)
    }

    /** The server's `app.json`, or null when it has none. */
    private fun latest(): Release? {
        val conn = open("app.json")
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw IOException("it refused the sync login")
                else -> throw IOException("it answered $code")
            }
            val release = try {
                json.decodeFromString(Release.serializer(), conn.inputStream.use { it.readBytes().decodeToString() })
            } catch (e: IllegalArgumentException) {
                throw IOException("its app.json doesn't read", e)
            }
            if (!APK_NAME.matches(release.apk) || release.size !in 1..MAX_APK_BYTES) {
                throw IOException("its app.json names no usable APK")
            }
            return release
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release, file: File) {
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it != file) it.delete() } // one build is enough to keep
        val part = File(dir, "${release.apk}.part")
        val conn = open(release.apk)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("the server answered ${conn.responseCode}")
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > release.size) throw IOException("it's bigger than app.json says")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() != release.size || sha256(part) != release.sha256) {
            part.delete()
            throw IOException("it arrived damaged")
        }
        if (!part.renameTo(file)) throw IOException("couldn't keep it")
    }

    /** Why [file] mustn't go over this app, or null. Android checks the key itself; this says it plainly. */
    private fun problemWith(file: File, release: Release): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION") // the flags overload is Android 13+
        val archive = pm.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
            ?: return "Version ${release.label} isn't an app Android can read."
        if (archive.packageName != context.packageName) return "The download is another app (${archive.packageName})."
        val built = PackageInfoCompat.getLongVersionCode(archive)
        if (built != release.versionCode) return "The download is build $built, not the ${release.versionCode} the server named."
        val theirs = signers(archive)
        @Suppress("DEPRECATION")
        val mine = signers(pm.getPackageInfo(context.packageName, SIGNATURE_FLAGS))
        if (theirs.isNotEmpty() && theirs != mine) {
            return "Version ${release.label} is signed with another key, so Android won't install it over this one."
        }
        return null
    }

    private fun install(ready: Ready) {
        val installer = context.packageManager.packageInstaller
        // An older try may still be waiting for an answer; only the newest may ask.
        installer.mySessions.forEach { old -> runCatching { installer.abandonSession(old.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(ready.file.length())
            // Android 12+: an app updating itself, and allowed to install apps, needn't ask each time.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("xrnrule.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                report(true, "Installing version ${ready.release.label}: the app closes in a moment.")
                // On disk before the commit: Android can end this process before a later write lands.
                prefs.edit(commit = true) {
                    putLong(KEY_INSTALLING, ready.release.versionCode)
                    putInt(KEY_SESSION, sessionId)
                }
                session.commit(UpdateResultReceiver.sender(context, sessionId))
            }
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            report(false, "Couldn't start installing version ${ready.release.label}: ${e.message}")
        }
    }

    /** Whether [sessionId] is the newest try: abandoning an older one reports back as well. */
    fun isCurrent(sessionId: Int) = sessionId == prefs.getInt(KEY_SESSION, -1)

    /** From [UpdateResultReceiver]: Android (or the phone maker) wants a tap before it installs. */
    fun needsConfirmation(confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A tap started this, so the app is in front and may open the confirmation itself.
        runCatching { context.startActivity(confirm) }
        // A notification as well: if the app has left the front since, Android won't show it.
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notifications(context).apply { createChannels() }
            .alert(Notifications.ALERT_UPDATE, "xrnrule update ready", "Tap to install the new version.", tap)
        report(true, "Waiting for you to confirm the update.")
    }

    /** From [UpdateResultReceiver]. Usually Android replaces the process first, and [start] says so. */
    fun installSucceeded() {
        prefs.edit { remove(KEY_INSTALLING) }
        dropReady()
        report(true, "Updated to $installedVersion")
    }

    /** From [UpdateResultReceiver]. */
    fun installFailed(status: Int, message: String?) {
        prefs.edit { remove(KEY_INSTALLING) }
        Notifications(context).cancel(Notifications.ALERT_UPDATE)
        report(
            false,
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update wasn't confirmed. It stays ready for when you want it."
            else "Android refused the update: ${message ?: "status $status"}",
        )
    }

    /** The build waiting for a tap, if its download is still whole and still newer. */
    private fun readyDownload(): Ready? {
        val release = savedReady() ?: return null
        val file = File(dir, release.apk)
        if (release.versionCode <= installedCode() || !file.isFile || sha256(file) != release.sha256) {
            dropReady()
            return null
        }
        return Ready(release, file)
    }

    private fun savedReady(): Release? = prefs.getString(KEY_READY, null)
        ?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }

    /** Nothing waits any more: the banner and the notification go. */
    private fun dropReady() {
        prefs.edit { remove(KEY_READY) }
        _ready.value = null
        Notifications(context).cancel(Notifications.ALERT_UPDATE)
    }

    private fun open(name: String): HttpURLConnection =
        (URL("${sync.url}/update/$name").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${sync.user}:${sync.password}".toByteArray(), Base64.NO_WRAP))
            setRequestProperty("X-Device", "phone")
        }

    private fun report(ok: Boolean, message: String) {
        val status = UpdateStatus(System.currentTimeMillis(), ok, message)
        _status.value = status
        prefs.edit {
            putLong(KEY_STATUS_AT, status.atWallMs)
            putBoolean(KEY_STATUS_OK, ok)
            putString(KEY_STATUS_TEXT, message)
        }
    }

    private fun savedStatus(): UpdateStatus? {
        val at = prefs.getLong(KEY_STATUS_AT, 0)
        if (at == 0L) return null
        return UpdateStatus(at, prefs.getBoolean(KEY_STATUS_OK, true), prefs.getString(KEY_STATUS_TEXT, "").orEmpty())
    }

    private fun packageInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    private fun installedCode(): Long = PackageInfoCompat.getLongVersionCode(packageInfo())

    companion object {
        const val CHECK_EVERY_H = 6L
        private const val TIMEOUT_MS = 30_000
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        /** A plain file name, as the server's /update/ serves them: no path can sneak out of [dir]. */
        private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
        private val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

        private const val KEY_STATUS_AT = "status_at"
        private const val KEY_STATUS_OK = "status_ok"
        private const val KEY_STATUS_TEXT = "status_text"
        /** The [Release] whose download waits for a tap. */
        private const val KEY_READY = "ready"
        /** The build handed to the installer; the next process compares itself with it. */
        private const val KEY_INSTALLING = "installing"
        /** The newest build the notification has told about. */
        private const val KEY_ANNOUNCED = "announced"
        /** The newest build that failed its checks. */
        private const val KEY_REFUSED = "refused"
        /** The session of that try; results of any other are stale. */
        private const val KEY_SESSION = "session"

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun signers(info: PackageInfo): Set<Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
            else @Suppress("DEPRECATION") info.signatures?.toSet().orEmpty()
    }
}
