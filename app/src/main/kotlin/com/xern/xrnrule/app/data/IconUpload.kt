package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Icons
import com.xern.xrnrule.domain.SiteIcons
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Sends the sync server the logos of what this phone's blocks name, for the laptop's page and the
 * status page ([Icons]): each named app's own icon (`app:<package>`), and each named site's from
 * [SiteIconCache] where the server has none yet (`site:<host>`: the laptop's copy, when it has
 * sent one, stays). As PNGs of at most 96 px under [Icons.MAX_BYTES], drawn by [render].
 *
 * A pass runs after a sync round that worked, at most every [PASS_MS], and after one where the
 * blocks name something new; [Icons.due] and what was sent ([sentFile]) keep a logo from going
 * again and again. Nothing here throws.
 */
class IconUpload(
    private val server: () -> Server?,
    private val world: StateFlow<World>,
    private val render: Render,
    private val sentFile: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where xrnrule-sync is (its `/xrnrule` URL) and the login. */
    class Server(val url: String, val user: String, val password: String)

    /** This phone's logos, each a PNG of at most 96 px, or null when it has none. */
    interface Render {
        fun app(packageName: String): ByteArray?
        fun site(host: String): ByteArray?
    }

    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastPassWall: Long? = null

    @Volatile
    private var lastNamed: Set<String>? = null

    private val sent: MutableMap<String, Icons.Sent> by lazy {
        runCatching { json.decodeFromString(SENT, sentFile.readText()) }.getOrDefault(emptyMap()).toMutableMap()
    }

    /** Passes as the class says, [syncStatus] saying when a sync round has worked. */
    fun start(syncStatus: StateFlow<SharedSyncStatus?>) {
        scope.launch(Dispatchers.IO) {
            for (p in passes) {
                val named = named(world.value)
                val last = lastPassWall
                if (last != null && now() - last in 0 until PASS_MS && named == lastNamed) continue
                try {
                    pass()
                } catch (_: Throwable) {
                    // Offline, a server without /icons yet, a strange answer: the next pass tries again.
                }
                lastPassWall = now()
                lastNamed = named
            }
        }
        scope.launch { syncStatus.collect { if (it?.ok == true) passes.trySend(Unit) } }
        scope.launch { world.map(::named).distinctUntilChanged().collect { passes.trySend(Unit) } }
    }

    /** One round: read the server's list, send what it lacks. How many went. Blocking. */
    fun pass(): Int {
        val to = server() ?: return 0
        val listed = index(to)
        val blocks = world.value.blocks
        var sending = 0
        val wall = now()
        for (pkg in blocks.flatMap { it.targetPackages }.toSortedSet()) {
            if (sending >= Icons.MAX_PER_PASS) break
            val key = Icons.app(pkg) ?: continue
            // Sent, and the server has it as sent: no need to draw it again to compare, for a day.
            if (Icons.stillThere(listed[key], sent[key], wall)) continue
            val png = render.app(pkg) ?: continue
            if (send(to, key, png, listed[key], replace = true)) sending++
        }
        for (host in Icons.sitesNamed(blocks)) {
            if (sending >= Icons.MAX_PER_PASS) break
            val key = Icons.site(host)?.takeIf { SiteIcons.fetchable(host) } ?: continue
            if (listed[key] != null) continue // the laptop's, or one sent before: only gaps are filled
            val png = render.site(host) ?: continue
            if (send(to, key, png, null, replace = false)) sending++
        }
        val named = named(world.value)
        if (sent.keys.retainAll(named) || sending > 0) save()
        return sending
    }

    /** Sends [png] as [key]'s, if it's due; whether it went (refused too: it isn't sent again for a day). */
    private fun send(to: Server, key: String, png: ByteArray, there: String?, replace: Boolean): Boolean {
        if (png.size > Icons.MAX_BYTES || SiteIcons.imageType(png) != "image/png") return false
        val hash = Icons.hash(png)
        if (!Icons.due(hash, there, replace, sent[key], now())) return false
        val code = put(to, key, png)
        if (code >= 500) return false // the server's trouble: the next pass tries again
        sent[key] = Icons.Sent(hash, now())
        return true
    }

    private fun save() {
        val tmp = File(sentFile.path + ".tmp")
        tmp.writeText(json.encodeToString(SENT, sent.toMap()))
        if (!tmp.renameTo(sentFile)) tmp.delete()
    }

    /** `GET /icons`: what the server has. */
    private fun index(to: Server): Map<String, String> {
        val conn = open(to, "${to.url}/icons", "GET")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("server answered $code")
            val text = conn.inputStream.use { it.readBytes() }.decodeToString()
            return Icons.index(text) ?: throw IOException("not a list of icons")
        } finally {
            conn.disconnect()
        }
    }

    /** `PUT /icons/<key>`: its status. */
    private fun put(to: Server, key: String, png: ByteArray): Int {
        val conn = open(to, "${to.url}/icons/${Icons.urlPart(key)}", "PUT")
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(png.size)
            conn.setRequestProperty("Content-Type", "image/png")
            conn.outputStream.use { it.write(png) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun open(to: Server, url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${to.user}:${to.password}".toByteArray()))
            setRequestProperty("X-Device", "phone")
        }

    companion object {
        /** How often a pass runs while the blocks name nothing new. */
        const val PASS_MS = 30 * 60_000L
        private const val TIMEOUT_MS = 15_000
        private val SENT = MapSerializer(String.serializer(), Icons.Sent.serializer())

        /** The keys of what [world]'s blocks name that this phone can have a logo of. */
        fun named(world: World): Set<String> =
            world.blocks.flatMap { it.targetPackages }.mapNotNull(Icons::app).toSet() +
                Icons.sitesNamed(world.blocks).filter(SiteIcons::fetchable).mapNotNull(Icons::site)
    }
}
