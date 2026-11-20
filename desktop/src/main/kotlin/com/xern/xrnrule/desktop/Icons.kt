package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Icons
import com.xern.xrnrule.domain.SiteIcons
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Icons as the pages and the server take them: raster images ([SiteIcons.imageType]), scaled down
 * to [SIDE] px when they are bigger, as PNG. Nothing here throws.
 */
object Raster {
    const val SIDE = 128

    /** No picture wider than this is opened: an icon has no need, and the daemon's heap is small. */
    private const val MAX_OPEN_PX = 1024

    init {
        // ImageIO and BufferedImage need no display; nothing here may go looking for one.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
        ImageIO.setUseCache(false) // no temporary files
    }

    /** [bytes] at most [side] px across: as they are when they already are, or can't be scaled. */
    fun fit(bytes: ByteArray, side: Int = SIDE): ByteArray {
        val px = SiteIcons.pixelSize(bytes)
        if (px != null && px <= side) return bytes
        return scaled(bytes, side) ?: bytes
    }

    /**
     * [bytes] as the server takes them: as they are, within [Icons.MAX_BYTES]; else scaled to
     * [SIDE] px, if this JVM can open them (PNG, JPEG, GIF) and that fits; else null.
     */
    fun forServer(bytes: ByteArray): ByteArray? {
        if (bytes.size <= Icons.MAX_BYTES && SiteIcons.imageType(bytes) != null) return bytes
        return scaled(bytes, SIDE)?.takeIf { it.size <= Icons.MAX_BYTES }
    }

    /** [bytes] scaled to fit [side] px (never up) as a PNG; null when they can't be opened. */
    fun scaled(bytes: ByteArray, side: Int): ByteArray? = runCatching {
        val px = SiteIcons.pixelSize(bytes) ?: return null
        if (px > MAX_OPEN_PX || SiteIcons.imageType(bytes) == null) return null
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val (w, h) = src.width to src.height
        val scale = minOf(1.0, side.toDouble() / maxOf(w, h))
        val (tw, th) = maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
        val from = src.getRGB(0, 0, w, h, null, 0, w)
        val to = IntArray(tw * th)
        // Each new pixel the average of the ones it covers, weighed by how opaque they are.
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = maxOf(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * w / tw)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = from[y * w + x]
                    val pa = (p ushr 24).toLong()
                    a += pa
                    r += ((p shr 16) and 0xff) * pa
                    g += ((p shr 8) and 0xff) * pa
                    b += (p and 0xff) * pa
                }
                val n = (y1 - y0) * (x1 - x0)
                to[ty * tw + tx] = if (a == 0L) 0 else ((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            }
        }
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, tw, th, to, 0, tw) }
        ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }.getOrNull()
}

/**
 * Laptop apps' own icons, for `desk:` keys: what the app's `.desktop` entry names in `Icon=` (the
 * app's id itself for an app with none), looked up the way the icon theme spec says, in the
 * hicolor theme every app installs into under each XDG data dir (flatpak's exports among them),
 * then in `pixmaps`; or the file it names. Raster files only, 48 to 128 px first and a bigger one
 * scaled down; an app with nothing but an SVG has it drawn as a PNG by [rsvg] (`rsvg-convert`),
 * when the laptop has it. Each answer is kept for [FRESH_MS].
 */
class LaptopAppIcons(
    private val iconName: (appId: String) -> String? = DesktopApps::iconOf,
    private val dataDirs: List<File> = xdgDataDirs(),
    private val rsvg: String? = "rsvg-convert",
    /** The icon dir the spec puts before the data dirs'. */
    private val homeIcons: File? = File(System.getProperty("user.home"), ".icons"),
) {
    private val found = ConcurrentHashMap<String, Pair<Long, SiteIcons.Icon?>>()

    /** [appId]'s icon, or null when it has none the page can show. */
    fun icon(appId: String): SiteIcons.Icon? {
        if (Icons.desk(appId) == null) return null
        val now = System.currentTimeMillis()
        found[appId]?.takeIf { now - it.first < FRESH_MS }?.let { return it.second }
        val icon = runCatching { look(iconName(appId) ?: appId) }.getOrNull()
        if (found.size >= MAX_APPS) found.clear() // the page can ask for any id
        found[appId] = now to icon
        return icon
    }

    private fun look(name: String): SiteIcons.Icon? {
        if (name.startsWith("/")) return file(File(name))
        val bare = name.removeSuffix(".png").removeSuffix(".svg").removeSuffix(".xpm")
        if (!NAME.matches(bare) || bare.contains("..")) return null
        val roots = listOfNotNull(homeIcons) + dataDirs.map { File(it, "icons") }
        for (size in SIZES) for (root in roots) file(File(root, "hicolor/$size/apps/$bare.png"))?.let { return it }
        for (dir in dataDirs) file(File(dir, "pixmaps/$bare.png"))?.let { return it }
        for (root in roots) file(File(root, "hicolor/scalable/apps/$bare.svg"))?.let { return it }
        for (dir in dataDirs) file(File(dir, "pixmaps/$bare.svg"))?.let { return it }
        return null
    }

    private fun file(f: File): SiteIcons.Icon? {
        if (!f.isFile || f.length() > MAX_FILE) return null
        val bytes = if (f.name.endsWith(".svg", ignoreCase = true)) drawn(f) else f.inputStream().use { it.readNBytes(MAX_FILE) }
        bytes ?: return null
        val type = SiteIcons.imageType(bytes) ?: return null
        val fitted = Raster.fit(bytes)
        return SiteIcons.Icon(fitted, SiteIcons.imageType(fitted) ?: type)
    }

    /** An SVG drawn as a [Raster.SIDE] px PNG by [rsvg], in at most a few seconds; null without it. */
    private fun drawn(svg: File): ByteArray? {
        val tool = rsvg ?: return null
        val out = File.createTempFile("xrnrule-icon", ".png")
        return try {
            val p = ProcessBuilder(tool, "-w", "${Raster.SIDE}", "-h", "${Raster.SIDE}", "-a", "-f", "png", svg.path)
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(out)
                .start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0 || out.length() > MAX_FILE) null else out.readBytes()
        } catch (_: Exception) {
            null // no rsvg-convert here, or it couldn't start
        } finally {
            out.delete()
        }
    }

    companion object {
        /** Sizes in the order they are taken: the page draws them at 28-64 px. */
        private val SIZES = listOf(
            "128x128", "96x96", "64x64@2", "48x48@2", "256x256", "192x192", "128x128@2", "512x512", "64x64", "72x72", "48x48",
        )
        private val NAME = Regex("[A-Za-z0-9._@+-]{1,200}")
        private const val MAX_FILE = 1024 * 1024
        private const val MAX_APPS = 200
        private const val FRESH_MS = 10 * 60_000L

        /** Where apps install their icons, the user's own first (the XDG base dir spec), with flatpak's. */
        fun xdgDataDirs(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): List<File> {
            val user = env["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") } ?: "$home/.local/share"
            val system = env["XDG_DATA_DIRS"]?.split(':')?.filter { it.startsWith("/") }?.takeIf { it.isNotEmpty() }
                ?: listOf("/usr/local/share", "/usr/share")
            return (listOf(user) + system + listOf("$home/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share"))
                .map { it.trimEnd('/') }.distinct().map(::File)
        }
    }
}

/**
 * The laptop's side of the logos on the sync server (`GET /icons`, `PUT /icons/<key>`, see
 * [Icons]). A pass reads the server's list, sends it the logos of the sites and laptop apps the
 * blocks name ([own]) that it lacks or has another copy of, and keeps a copy of each other logo on
 * the list, the phone's apps above all, in [dir] for the page ([icon]). One runs every [PASS_MS]
 * and after a sync round at least that long after the last, and at once when the blocks name
 * something new; [Icons.due] keeps a logo from going again and again.
 */
class ServerIcons(
    private val dir: File,
    private val ruleUrl: String,
    private val http: Http?,
    private val world: StateFlow<World>,
    /** The keys of what [World]'s blocks name, each with the laptop's own copy, or null when it has none (yet). */
    private val own: (World) -> Map<String, () -> ByteArray?>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val sent = ConcurrentHashMap<String, Icons.Sent>()
    private val prepared = ConcurrentHashMap<String, Pair<ByteArray, ByteArray?>>() // key -> (own copy, as sent)
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    /** The server's list as last read. */
    @Volatile
    var index: Map<String, String>? = null
        private set

    @Volatile
    private var lastPassWall: Long? = null

    @Volatile
    private var lastNamed: Set<String> = emptySet()

    @Volatile
    private var lastFailure: String? = null

    /** The copies kept: key -> hash, mirrored to `index.json` in [dir]. */
    private val copies: MutableMap<String, String> by lazy {
        ConcurrentHashMap(runCatching { json.decodeFromString(COPIES, File(dir, "index.json").readText()) }.getOrDefault(emptyMap()).filterKeys(Icons::valid))
    }

    /** Passes as the class says, [syncStatus] saying when a sync round has worked. */
    fun start(syncStatus: StateFlow<SyncStatus?>) {
        if (http == null) return
        scope.launch(Dispatchers.IO) {
            for (p in passes) {
                val named = runCatching { own(world.value).keys }.getOrDefault(emptySet())
                val last = lastPassWall
                if (last != null && now() - last in 0 until PASS_MS && named == lastNamed) continue
                try {
                    pass()
                    lastFailure = null
                } catch (e: Exception) {
                    // Once, not every pass: sync's own status says when the server is out of reach.
                    if (e.toString() != lastFailure) System.err.println("icons: $e")
                    lastFailure = e.toString()
                    lastPassWall = now()
                    lastNamed = named
                }
            }
        }
        scope.launch {
            while (true) {
                passes.trySend(Unit)
                delay(PASS_MS)
            }
        }
        scope.launch { syncStatus.collect { if (it?.ok == true) passes.trySend(Unit) } }
        scope.launch { world.map { own(it).keys }.distinctUntilChanged().collect { passes.trySend(Unit) } }
    }

    /** The server's copy of [key], if the laptop keeps one; one on the list that it hasn't got yet is fetched. */
    fun icon(key: String): SiteIcons.Icon? {
        if (!Icons.valid(key)) return null
        val hash = copies[key]
        val listed = index?.get(key)
        if (listed != null && hash != listed && http != null && wanted.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    fetch(key)
                } catch (_: Exception) {
                    // offline: the next pass fetches it
                } finally {
                    wanted.remove(key)
                }
            }
        }
        if (hash == null) return null
        val bytes = runCatching { File(dir, fileName(key)).readBytes() }.getOrNull() ?: return null
        return SiteIcons.imageType(bytes)?.let { SiteIcons.Icon(bytes, it) }
    }

    /** One round with the server: read its list, send what it lacks, fetch what the laptop lacks. */
    fun pass() {
        val http = http ?: return
        val (code, body) = http.bytes("$ruleUrl/icons")
        check(code == 200) { "server answered $code" }
        val listed = Icons.index(body.decodeToString())?.toMutableMap() ?: error("the server's list of icons isn't one")
        val mine = own(world.value)
        var sending = 0
        for ((key, copy) in mine) {
            if (sending >= Icons.MAX_PER_PASS) break
            val bytes = copy()?.let { prepare(key, it) } ?: continue
            val hash = Icons.hash(bytes)
            if (!Icons.due(hash, listed[key], replace = true, last = sent[key], nowWall = now())) continue
            // Out of reach part way: the rest wait for the next pass, rather than each for its own timeout.
            val (status, _) = runCatching { http.bytes("$ruleUrl/icons/${Icons.urlPart(key)}", "PUT", bytes) }.getOrNull() ?: break
            if (status >= 500) continue // the server's trouble: the next pass tries again
            sent[key] = Icons.Sent(hash, now())
            sending++
            if (status in 200..299) listed[key] = hash else System.err.println("icons: the server refused $key ($status)")
        }
        index = listed
        // The phone's logos, and any other the page may ask for; not the laptop's own, which it has.
        var fetching = 0
        for ((key, hash) in listed) {
            if (fetching >= MAX_FETCHES) break
            if (copies[key] == hash || prepared[key]?.second?.let(Icons::hash) == hash) continue
            if (fetch(key)) fetching++
        }
        val gone = copies.keys - listed.keys
        if (gone.isNotEmpty()) {
            gone.forEach(::forget)
            saveCopies()
        }
        prepared.keys.retainAll(mine.keys)
        sent.keys.retainAll(mine.keys)
        lastNamed = mine.keys
        lastPassWall = now()
    }

    /** [bytes] as sent, worked out once for each copy the laptop has. */
    private fun prepare(key: String, bytes: ByteArray): ByteArray? {
        prepared[key]?.takeIf { it.first === bytes }?.let { return it.second }
        return Raster.forServer(bytes).also { prepared[key] = bytes to it }
    }

    /** Fetches the server's copy of [key]; whether one came. */
    private fun fetch(key: String): Boolean {
        val (status, bytes) = http?.bytes("$ruleUrl/icons/${Icons.urlPart(key)}") ?: return false
        return status == 200 && keep(key, bytes)
    }

    /** Keeps [bytes] as the server's copy of [key]; false for what isn't a raster icon, or with no room left. */
    internal fun keep(key: String, bytes: ByteArray): Boolean {
        if (!Icons.valid(key) || bytes.size > Icons.MAX_BYTES || SiteIcons.imageType(bytes) == null) return false
        synchronized(this) {
            if (copies.size >= Icons.MAX_STORED && key !in copies) return false
            dir.mkdirs()
            val part = File(dir, fileName(key) + ".part")
            part.writeBytes(bytes)
            part.renameTo(File(dir, fileName(key)))
            copies[key] = Icons.hash(bytes)
            saveCopies()
        }
        return true
    }

    private fun forget(key: String) = synchronized(this) {
        copies.remove(key)
        File(dir, fileName(key)).delete()
    }

    private fun saveCopies() = synchronized(this) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.part")
        tmp.writeText(json.encodeToString(COPIES, copies.toMap()))
        tmp.renameTo(File(dir, "index.json"))
    }

    companion object {
        /** How often a pass runs while sync rounds come and the blocks name nothing new. */
        const val PASS_MS = 10 * 60_000L

        /** The most of the server's logos fetched in one pass. */
        const val MAX_FETCHES = 50

        private val COPIES = MapSerializer(String.serializer(), String.serializer())

        private fun fileName(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * The laptop's own logos of what [world]'s blocks name: each site's from [sites] (only a
         * host a block names is ever fetched there), each laptop app's from [apps].
         */
        fun owned(world: World, sites: SiteIconCache, apps: LaptopAppIcons): Map<String, () -> ByteArray?> {
            val out = LinkedHashMap<String, () -> ByteArray?>()
            for (host in Icons.sitesNamed(world.blocks)) {
                if (!SiteIcons.fetchable(host)) continue
                Icons.site(host)?.let { out[it] = { sites.icon(host)?.bytes } }
            }
            for (id in world.blocks.flatMap { it.targetDesktopApps }.toSortedSet()) {
                Icons.desk(id)?.let { out[it] = { apps.icon(id)?.bytes } }
            }
            return out
        }
    }
}
