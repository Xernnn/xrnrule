package com.xern.xrnrule.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xern.xrnrule.domain.SiteIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Websites' own icons ([SiteIcons]), asked of each site once and kept in `filesDir/site-icons/`
 * (refreshed after [SiteIcons.FRESH_MS]; a site without one is asked again after
 * [SiteIcons.RETRY_MS]). [icon] answers from memory and fetches in the background; [loaded]
 * moves on when one arrives, so the screens draw it. Nothing here may throw: a site can send
 * anything, and an uncaught throw would take the blocker down with it.
 */
class SiteIconCache(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "site-icons")
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    /**
     * Whether what is kept was found by this [SiteIcons.VERSION]; if not, every kept icon is
     * fetched again once (and shown meanwhile), and the marker moves on.
     */
    private val upToDate: Boolean by lazy {
        val marker = File(dir, ".version")
        val same = runCatching { marker.readText().trim() == SiteIcons.VERSION.toString() }.getOrDefault(false)
        if (!same) runCatching { dir.mkdirs(); marker.writeText(SiteIcons.VERSION.toString()) }
        same
    }

    private val _loaded = MutableStateFlow(0)

    /** Moves on each time an icon arrives. */
    val loaded: StateFlow<Int> = _loaded.asStateFlow()

    /** [host]'s icon if this phone has it; otherwise null, and it is looked for (once per run). */
    fun icon(host: String): ImageBitmap? {
        memory[host]?.let { return it }
        if (SiteIcons.fetchable(host) && asked.add(host)) scope.launch(io) { load(host) }
        return null
    }

    /** The icon file this phone keeps for [host], as the site sent it ([IconUpload]); null when it has none. */
    fun kept(host: String): ByteArray? = runCatching {
        File(dir, host).takeIf { SiteIcons.fetchable(host) && it.isFile && it.length() <= SiteIcons.ICON_BYTES }?.readBytes()
    }.getOrNull()

    private fun load(host: String) {
        try {
            val file = File(dir, host)
            val miss = File(dir, "$host.miss")
            val now = System.currentTimeMillis()
            val kept = file.takeIf { it.isFile }?.let { runCatching { decode(it.readBytes()) }.getOrNull() }
            if (kept != null) arrived(host, kept)
            val current = upToDate
            val fresh = current && kept != null && now - file.lastModified() < SiteIcons.FRESH_MS
            val gaveUp = current && kept == null && miss.isFile && now - miss.lastModified() < SiteIcons.RETRY_MS
            if (fresh || gaveUp) return

            val icon = SiteIcons.find(host, ::get, accept = { icon -> decode(icon.bytes) != null })
            val drawn = icon?.let { decode(it.bytes) }
            dir.mkdirs()
            if (icon == null) {
                if (kept == null) miss.writeBytes(ByteArray(0))
                return
            }
            File(dir, "$host.part").apply { writeBytes(icon.bytes) }.renameTo(file)
            miss.delete()
            drawn?.let { arrived(host, it) }
        } catch (_: Throwable) {
            // Offline, a strange answer, out of memory for a huge image: the globe stays.
        }
    }

    private fun arrived(host: String, bitmap: ImageBitmap) {
        memory[host] = bitmap
        _loaded.update { it + 1 }
    }

    /** At most [maxBytes] of [url], over https only, or null. */
    private fun get(url: String, maxBytes: Int): SiteIcons.Fetched? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", SiteIcons.USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,image/*;q=0.9,*/*;q=0.5")
            if (conn.responseCode != 200 || !conn.url.toString().startsWith("https://")) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < maxBytes) {
                    val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            SiteIcons.Fetched(conn.url.toString(), bytes)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Drawn at about [SIDE] px; null for what Android can't draw, or a picture too big to be an icon. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0 || side > 4096) return null
        var sample = 1
        while (side / (sample * 2) >= SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val SIDE = 128
    }
}
