package com.xern.xrnrule.domain

/** Website targets (I18): normalizing what a user types or a browser shows, and matching. */
object Sites {

    /**
     * "https://www.YouTube.com/watch?v=x" -> "youtube.com". Returns null for anything that isn't
     * a plausible host — a search query typed into the address bar, a bare word, an empty bar.
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        s = s.replaceFirst(SCHEME, "")
        s = s.takeWhile { it != '/' && it != '?' && it != '#' }
        s = s.substringAfterLast('@').substringBefore(':').trimEnd('.')
        s = s.removePrefix("www.")
        if (s.length > 253) return null
        val labels = s.split('.')
        if (labels.size < 2) return null
        if (labels.any { !validLabel(it) }) return null
        val tld = labels.last()
        // toIntOrNull: a label of digits can be too long for an Int ("1.1.1.99999999999").
        val isIpv4 = labels.size == 4 && labels.all { l -> l.all(Char::isDigit) && (l.toIntOrNull() ?: 256) <= 255 }
        val tldOk = (tld.length >= 2 && tld.all { it in 'a'..'z' }) || tld.startsWith("xn--")
        return if (isIpv4 || tldOk) s else null
    }

    /** Short and old names of a site, by the main one they stand for. Only for showing: each still blocks on its own. */
    private val ALIASES = mapOf(
        "redd.it" to "reddit.com",
        "twitter.com" to "x.com",
        "t.co" to "x.com",
        "fb.com" to "facebook.com",
        "fb.me" to "facebook.com",
        "fb.watch" to "facebook.com",
        "youtu.be" to "youtube.com",
        "instagr.am" to "instagram.com",
    )

    /**
     * [sites] as a block shows them, one per site: a short or old name (`redd.it`) is left out
     * when its main one (`reddit.com`) is there too. Order is kept.
     */
    fun shown(sites: Collection<String>): List<String> {
        val all = sites.toSet()
        return sites.filter { ALIASES[it]?.let { main -> main !in all } ?: true }
    }

    /**
     * I46: a target or a page as this app keeps them: [normalize]'s host, plus the path under it
     * if there is one ("https://www.YouTube.com/Shorts/" -> "youtube.com/shorts"). Null for
     * anything [normalize] refuses.
     */
    fun normalizeTarget(input: String): String? {
        val host = normalize(input) ?: return null
        val rest = input.trim().replaceFirst(SCHEME, "")
        val at = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (at < 0 || rest[at] != '/') return host
        val path = rest.substring(at).takeWhile { it != '?' && it != '#' }.lowercase().trimEnd('/')
        return host + path.take(MAX_PATH)
    }

    /** The host of a target or page ("youtube.com/shorts" -> "youtube.com"). */
    fun hostOf(location: String): String = location.substringBefore('/')

    /**
     * `youtube.com` covers `youtube.com` and `m.youtube.com/anything`, but not `notyoutube.com`.
     * I46: `youtube.com/shorts` covers that path and what's under it, on the same hosts.
     */
    fun matches(location: String, target: String): Boolean {
        val host = hostOf(location)
        val targetHost = hostOf(target)
        if (host != targetHost && !host.endsWith(".$targetHost")) return false
        val want = target.drop(targetHost.length)
        if (want.isEmpty()) return true
        val path = location.drop(host.length)
        return path == want || path.startsWith("$want/")
    }

    private const val MAX_PATH = 200

    /**
     * "https://" in front, "view-source:https://" too. Only in front: a "://" further in belongs
     * to the path or the query, and what follows it isn't the page's host.
     */
    private val SCHEME = Regex("^(?:[a-z][a-z0-9+.-]*:)+//", RegexOption.IGNORE_CASE)

    /**
     * The override phrase as compared (I34): no accents (a Vietnamese keyboard may add them), no
     * punctuation, case and spacing ignored.
     */
    fun normalizePhrase(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun validLabel(label: String): Boolean =
        label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}
