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
import android.os.PowerManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * anyway) and handed to [PackageInstaller]. From Android 12 an app allowed to "install unknown
 * apps" may update itself without asking; where Android (or the phone maker) still wants a tap,
 * a notification says the update is ready.
 *
 * The background check runs every [CHECK_EVERY_H] hours and installs only while the screen is
 * off, so the restart an update brings never lands mid-use: Android replaces the process, and
 * BootReceiver brings the service back on MY_PACKAGE_REPLACED. Check now installs at once.
 */
class ApkUpdater(context: Context, private val sync: SyncSettings, private val scope: CoroutineScope) : AppUpdates {
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
            prefs.edit {
                remove(KEY_INSTALLING)
                remove(KEY_READY)
            }
            Notifications(context).cancel(Notifications.ALERT_UPDATE)
            report(true, "Updated to $installedVersion")
            dir.deleteRecursively()
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(CHECK_EVERY_H, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UpdateWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** The worker's look: fetch a newer build, and install it now if the screen is already off. */
    suspend fun checkInBackground() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val ready = fetch(manual = false) ?: return@withLock
                if (screenOn()) {
                    report(true, "Version ${ready.release.label} is downloaded: it installs when the screen goes off.")
                } else {
                    install(ready, manual = false)
                }
            }
        }
    }

    override fun onScreenOff() {
        if (prefs.getString(KEY_READY, null) == null) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock { readyDownload()?.let { if (!screenOn()) install(it, manual = false) } }
        }
    }

    override suspend fun checkNow() {
        withContext(Dispatchers.IO) {
            mutex.withLock { fetch(manual = true)?.let { install(it, manual = true) } }
        }
    }

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
            prefs.edit { remove(KEY_READY) }
            report(true, "Up to date")
            return null
        }
        // A build Android refused, or that wasn't confirmed, waits for Check now or a newer one.
        if (!manual && release.versionCode == prefs.getLong(KEY_FAILED, 0)) return null
        if (!canInstall()) {
            report(false, "Version ${release.label} is out, but Android doesn't let xrnrule install it yet: allow it above.")
            return null
        }
        val file = File(dir, release.apk)
        try {
            if (!(file.isFile && sha256(file) == release.sha256)) download(release, file)
        } catch (e: IOException) {
            report(false, "Couldn't download version ${release.label}: ${e.message}")
            return null
        }
        problemWith(file, release)?.let { problem ->
            file.delete()
            prefs.edit { putLong(KEY_FAILED, release.versionCode) }
            report(false, problem)
            return null
        }
        prefs.edit { putString(KEY_READY, json.encodeToString(Release.serializer(), release)) }
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

    private fun install(ready: Ready, manual: Boolean) {
        val installer = context.packageManager.packageInstaller
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
                prefs.edit {
                    putLong(KEY_INSTALLING, ready.release.versionCode)
                    putBoolean(KEY_MANUAL, manual)
                }
                report(true, "Installing version ${ready.release.label}: the app closes, and blocking is back by itself in a few seconds.")
                session.commit(UpdateResultReceiver.sender(context, sessionId))
            }
        } catch (e: Exception) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            report(false, "Couldn't start installing version ${ready.release.label}: ${e.message}")
        }
    }

    /** From [UpdateResultReceiver]: Android (or the phone maker) wants a tap before it installs. */
    fun needsConfirmation(confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // From Check now the app is in front, where it may open the confirmation itself.
        if (prefs.getBoolean(KEY_MANUAL, false)) runCatching { context.startActivity(confirm) }
        // A notification either way: from the background Android won't show it.
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notifications(context).apply { createChannels() }
            .alert(Notifications.ALERT_UPDATE, "xrnrule update ready", "Tap to install the new version.", tap)
        report(true, "The new version is ready: tap its notification to install it.")
    }

    /** From [UpdateResultReceiver]. Usually Android replaces the process first, and [start] says so. */
    fun installSucceeded() {
        prefs.edit {
            remove(KEY_INSTALLING)
            remove(KEY_READY)
        }
        report(true, "Updated to $installedVersion")
    }

    /** From [UpdateResultReceiver]. */
    fun installFailed(status: Int, message: String?) {
        val code = prefs.getLong(KEY_INSTALLING, 0)
        prefs.edit {
            remove(KEY_INSTALLING)
            putLong(KEY_FAILED, code)
        }
        Notifications(context).cancel(Notifications.ALERT_UPDATE)
        report(
            false,
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update wasn't confirmed. Check now offers it again."
            else "Android refused the update: ${message ?: "status $status"}",
        )
    }

    /** The download waiting for the screen to go off, if it's still whole and still newer. */
    private fun readyDownload(): Ready? {
        val release = prefs.getString(KEY_READY, null)
            ?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }
            ?: return null
        val file = File(dir, release.apk)
        if (release.versionCode <= installedCode() || !file.isFile || sha256(file) != release.sha256) {
            prefs.edit { remove(KEY_READY) }
            return null
        }
        return Ready(release, file)
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
    private fun screenOn() = context.getSystemService(PowerManager::class.java)?.isInteractive == true

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
        /** The [Release] whose download waits for the screen to go off. */
        private const val KEY_READY = "ready"
        /** The build handed to the installer; the next process compares itself with it. */
        private const val KEY_INSTALLING = "installing"
        private const val KEY_MANUAL = "installing_manual"
        /** A build Android refused or that wasn't confirmed: not retried in the background. */
        private const val KEY_FAILED = "failed"

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
