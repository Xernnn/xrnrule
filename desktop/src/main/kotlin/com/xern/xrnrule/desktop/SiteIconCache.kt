package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.SiteIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Websites' own icons for the local page ([SiteIcons]): asked of each site, never of an icon
 * service, and kept in [dir] (refreshed after [SiteIcons.FRESH_MS]; a site without one is asked
 * again after [SiteIcons.RETRY_MS]). Served from the page's own origin, since its CSP allows
 * images from nowhere else; only raster images ([SiteIcons.imageType]) are kept, so what is
 * served can't carry script.
 */
class SiteIconCache(private val dir: File, private val scope: CoroutineScope) {
    private val memory = ConcurrentHashMap<String, SiteIcons.Icon>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    // No login is ever sent with these, and Redirect.NORMAL never follows https to http.
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(3)

    /** [host]'s icon if the laptop has it; otherwise null, and it is looked for (once per run). */
    fun icon(host: String): SiteIcons.Icon? {
        if (!SiteIcons.fetchable(host)) return null
        memory[host]?.let { return it }
        if (!asked.add(host)) return null
        val file = File(dir, host)
        val miss = File(dir, "$host.miss")
        val kept = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?.let { bytes -> SiteIcons.imageType(bytes)?.let { SiteIcons.Icon(bytes, it) } }
        if (kept != null) memory[host] = kept
        val now = System.currentTimeMillis()
        val fresh = kept != null && now - file.lastModified() < SiteIcons.FRESH_MS
        val gaveUp = kept == null && miss.isFile && now - miss.lastModified() < SiteIcons.RETRY_MS
        if (!fresh && !gaveUp) scope.launch(io) { fetch(host, file, miss, had = kept != null) }
        return kept
    }

    private fun fetch(host: String, file: File, miss: File, had: Boolean) {
        try {
            val icon = SiteIcons.find(host, ::get)
            dir.mkdirs()
            if (icon == null) {
                if (!had) miss.writeBytes(ByteArray(0))
                return
            }
            val part = File(dir, "$host.part")
            part.writeBytes(icon.bytes)
            part.renameTo(file)
            miss.delete()
            memory[host] = icon
        } catch (e: Exception) {
            System.err.println("icon of $host: $e")
        }
    }

    /** At most [maxBytes] of [url], over https only, or null. */
    private fun get(url: String, maxBytes: Int): SiteIcons.Fetched? {
        if (!url.startsWith("https://")) return null
        return try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) xrnrule")
                .header("Accept", "text/html,image/*;q=0.9,*/*;q=0.5")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                if (response.statusCode() != 200 || response.uri().scheme != "https") return null
                SiteIcons.Fetched(response.uri().toString(), input.readNBytes(maxBytes))
            }
        } catch (_: Exception) {
            null
        }
    }
}
