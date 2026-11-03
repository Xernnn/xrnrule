package com.xern.xrnrule.domain

import java.net.URI
import java.util.Base64

/**
 * A website's own icon, for showing a blocked site the way its app would be shown. Asked of the
 * site itself, never of an icon service (that would hand one company the list of blocked sites).
 * Platform-free: the phone and the laptop pass in how to fetch a URL.
 */
object SiteIcons {
    /** How much of the home page is read looking for its `<link rel="icon">` tags. */
    const val PAGE_BYTES = 512 * 1024

    /** The largest icon file taken. */
    const val ICON_BYTES = 512 * 1024

    /** How many candidate URLs are tried before giving up. */
    const val MAX_TRIES = 5

    /** How long a found icon is kept before it is fetched again (it is shown meanwhile). */
    const val FRESH_MS = 30L * 24 * 60 * 60 * 1000

    /** How long after finding nothing a site is asked again. */
    const val RETRY_MS = 3L * 24 * 60 * 60 * 1000

    /** A page or file as fetched: [url] is where it ended up after redirects. */
    class Fetched(val url: String, val bytes: ByteArray)

    /** An icon's bytes and their media type (one of [imageType]'s). */
    class Icon(val bytes: ByteArray, val type: String)

    private val HOST = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    /**
     * Whether [host] is worth asking: a name with a letters-only top-level domain. Not an IP
     * address, `localhost` or anything with a port or path, so nothing on the local network is
     * fetched, and the name is safe as a file name.
     */
    fun fetchable(host: String): Boolean = HOST.matches(host)

    /**
     * Where [host]'s icon is: the icons its home page names (the best first), then
     * `/favicon.ico` where the home page ended up and on [host] itself. [get] fetches a URL,
     * at most `maxBytes` of it, or answers null; [accept] can turn down an icon (one the platform
     * can't draw), and the next is tried.
     */
    fun find(
        host: String,
        get: (url: String, maxBytes: Int) -> Fetched?,
        accept: (Icon) -> Boolean = { true },
    ): Icon? {
        if (!fetchable(host)) return null
        val home = "https://$host/"
        val page = get(home, PAGE_BYTES)
        val landed = page?.url?.let(::origin) ?: "https://$host"
        val urls = (page?.let { candidates(it.bytes.decodeToString(), it.url) } ?: emptyList()) +
            "$landed/favicon.ico" + "https://$host/favicon.ico"
        for (url in urls.distinct().take(MAX_TRIES)) {
            val bytes = if (url.startsWith("data:")) dataBytes(url) else get(url, ICON_BYTES)?.bytes
            val type = bytes?.takeIf { it.size <= ICON_BYTES }?.let(::imageType) ?: continue
            val icon = Icon(bytes, type)
            if (accept(icon)) return icon
        }
        return null
    }

    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val SIZE = Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * The icons [html] (found at [pageUrl]) names, the best first: the largest declared size,
     * an Apple touch icon (180 px) when none is declared, a PNG before an ICO. SVG and Safari's
     * mask icon are left out: the phone can't draw SVG, and an SVG served from the laptop's own
     * page origin could carry script. Only https and inline `data:image/…;base64` icons.
     */
    fun candidates(html: String, pageUrl: String): List<String> {
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        data class Candidate(val url: String, val size: Int, val png: Boolean, val order: Int)
        val found = LINK.findAll(html).mapIndexedNotNull { order, tag ->
            val attrs = ATTR.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to (m.groups[3] ?: m.groups[4] ?: m.groups[5])!!.value
            }
            val rel = attrs["rel"]?.lowercase()?.split(Regex("\\s+"))?.toSet() ?: return@mapIndexedNotNull null
            val touch = "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel
            if (!touch && "icon" !in rel) return@mapIndexedNotNull null
            val href = attrs["href"]?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val type = attrs["type"]?.lowercase().orEmpty()
            if ("svg" in type || href.substringBefore('?').substringBefore('#').lowercase().endsWith(".svg")) return@mapIndexedNotNull null
            val url = if (href.startsWith("data:", ignoreCase = true)) {
                href.takeIf { it.startsWith("data:image/", ignoreCase = true) && !it.startsWith("data:image/svg", ignoreCase = true) }
            } else {
                runCatching { base.resolve(href).toString() }.getOrNull()?.takeIf { it.startsWith("https://") }
            } ?: return@mapIndexedNotNull null
            val declared = attrs["sizes"]?.let { s -> SIZE.findAll(s).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } }
            val size = declared ?: if (touch) 180 else 0
            val png = "png" in type || href.substringBefore('?').lowercase().endsWith(".png")
            Candidate(url, size, png, order)
        }.toList()
        return found
            .sortedWith(compareByDescending<Candidate> { it.size }.thenByDescending { it.png }.thenBy { it.order })
            .map { it.url }
    }

    /** The media type of [bytes] by their first bytes, or null when they aren't an image the pages show. */
    fun imageType(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        fun ascii(i: Int, s: String) = s.indices.all { at(i + it) == s[it].code }
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> "image/x-icon"
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            else -> null
        }
    }

    private fun origin(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme != "https" || u.host.isNullOrEmpty()) null else "https://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()

    private fun dataBytes(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).endsWith(";base64", ignoreCase = true)) return null
        if ((url.length - comma) / 4 * 3 > ICON_BYTES) return null
        return runCatching { Base64.getDecoder().decode(url.substring(comma + 1).filterNot { it.isWhitespace() }) }.getOrNull()
    }

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"").replace("&#x2F;", "/").replace("&#47;", "/")
}
