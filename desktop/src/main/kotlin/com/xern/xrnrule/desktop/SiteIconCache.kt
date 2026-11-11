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
class SiteIconCache(
    private val dir: File,
    private val scope: CoroutineScope,
    /** What fetches a URL (at most `maxBytes` of it, or null): null is the web, over https. */
    private val fetcher: ((url: String, maxBytes: Int) -> SiteIcons.Fetched?)? = null,
) {
    private val memory = ConcurrentHashMap<String, SiteIcons.Icon>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    // No login is ever sent with these, and Redirect.NORMAL never follows https to http.
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(6))
        .build()

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

    /**
     * [host]'s icon if the laptop has it; otherwise null, and it is looked for (once per run, for
     * at most [MAX_HOSTS] hosts a run).
     */
    fun icon(host: String): SiteIcons.Icon? {
        if (!SiteIcons.fetchable(host)) return null
        memory[host]?.let { return it }
        if (!admit(host)) return null
        val file = File(dir, host)
        val miss = File(dir, "$host.miss")
        val kept = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?.let { bytes -> SiteIcons.imageType(bytes)?.let { SiteIcons.Icon(bytes, it) } }
        if (kept != null) memory[host] = kept
        val now = System.currentTimeMillis()
        val current = upToDate
        val fresh = current && kept != null && now - file.lastModified() < SiteIcons.FRESH_MS
        val gaveUp = current && kept == null && miss.isFile && now - miss.lastModified() < SiteIcons.RETRY_MS
        if (!fresh && !gaveUp) scope.launch(io) { fetch(host, file, miss, had = kept != null) }
        return kept
    }

    /** Not looked for yet this run, and room for it: a page can name endless subdomains of a blocked site. */
    @Synchronized
    private fun admit(host: String): Boolean = host !in asked && asked.size < MAX_HOSTS && asked.add(host)

    /** At most [MAX_HOSTS] hosts stay on disk: those found or missed longest ago go first. */
    private fun makeRoom(host: String) {
        val byHost = dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            .groupBy { it.name.removeSuffix(".miss") }
        if (host in byHost || byHost.size < MAX_HOSTS) return
        byHost.entries.sortedBy { (_, files) -> files.maxOf { it.lastModified() } }.take(byHost.size - MAX_HOSTS + 1)
            .forEach { (old, files) ->
                files.forEach { it.delete() }
                memory.remove(old)
            }
    }

    private fun fetch(host: String, file: File, miss: File, had: Boolean) {
        try {
            val icon = SiteIcons.find(host, fetcher ?: ::get)
            synchronized(this) { // room made and taken at once
                dir.mkdirs()
                makeRoom(host)
                if (icon == null) {
                    if (!had) miss.writeBytes(ByteArray(0))
                    return
                }
                val part = File(dir, "$host.part")
                part.writeBytes(icon.bytes)
                part.renameTo(file)
                miss.delete()
                memory[host] = icon
            }
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
                .header("User-Agent", SiteIcons.USER_AGENT)
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

    companion object {
        /** The most hosts looked for in a run, and kept on disk. */
        const val MAX_HOSTS = 200
    }
}
