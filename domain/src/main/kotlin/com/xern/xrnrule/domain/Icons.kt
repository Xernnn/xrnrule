package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The logos of what blocks name, shared through the sync server (`GET /icons`, `PUT /icons/<key>`)
 * so each device's pages can show the others' too: the phone's apps on the laptop, the laptop's
 * apps on the status page. A key says what a logo is of: `site:<host>` (lower case, as a block
 * names it), `app:<Android package>` or `desk:<laptop app id>` (a `.desktop` id, as in
 * [Block.targetDesktopApps]). Platform-free: the phone and the laptop each find their own logos and
 * send them by these rules.
 */
object Icons {
    /** The largest icon the server takes. */
    const val MAX_BYTES = 64 * 1024

    /** The most a user's server keeps (the oldest written go first); the most a device keeps of its copies. */
    const val MAX_STORED = 400

    /** The most a device sends in one go; what's left goes the next time. */
    const val MAX_PER_PASS = 20

    /**
     * How soon an icon is sent again after it was sent or refused, when the server still hasn't
     * got it the same: a server that keeps refusing it, a server that has lost it, or a copy that
     * comes out different every time it's made, costs one upload a day.
     */
    const val RESEND_MS = 24 * 60 * 60 * 1000L

    private val KEY = Regex("site:[a-z0-9.-]{1,253}|app:[A-Za-z0-9._]{1,200}|desk:[A-Za-z0-9._ +-]{1,200}")

    fun valid(key: String): Boolean = KEY.matches(key)

    fun site(host: String): String? = "site:${host.lowercase()}".takeIf(::valid)

    fun app(packageName: String): String? = "app:$packageName".takeIf(::valid)

    fun desk(appId: String): String? = "desk:$appId".takeIf(::valid)

    /** The server's name for [bytes]: the first 12 hex digits of their SHA-256. */
    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }

    /** [key] as a URL's last path segment: everything but letters, digits, `.`, `-` and `_` %-encoded. */
    fun urlPart(key: String): String = URLEncoder.encode(key, "UTF-8").replace("+", "%20").replace("*", "%2A")

    /** The hosts [blocks] name, as sites to block or to leave alone. */
    fun sitesNamed(blocks: List<Block>): Set<String> =
        blocks.flatMap { it.targetSites + it.exceptSites }.map(Sites::hostOf).toSortedSet()

    /** `GET /icons`'s answer, key to [hash], without what isn't a key; null when it isn't one. */
    fun index(text: String): Map<String, String>? = runCatching {
        val doc = Json.parseToJsonElement(text) as? JsonObject ?: return null
        doc.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString && valid(k) }?.let { k to it.content } }.toMap()
    }.getOrNull()

    /** An icon a device sent (or had refused), and when. */
    @Serializable
    data class Sent(val hash: String, val atWall: Long)

    /**
     * Whether a device should send its copy of an icon, [hash], to a server whose list has [there]
     * for it: when the server has none, or (with [replace]) another one; but not within
     * [RESEND_MS] of [last], the last time it was sent or refused, whatever came of that.
     */
    fun due(hash: String, there: String?, replace: Boolean, last: Sent?, nowWall: Long): Boolean {
        if (there == hash) return false
        if (there != null && !replace) return false
        return last == null || nowWall - last.atWall !in 0 until RESEND_MS
    }

    /**
     * Whether a copy sent at [last] still needn't be made again to be compared: the server has
     * what was sent, within [RESEND_MS] of sending it. Saves drawing every app's icon every time.
     */
    fun stillThere(there: String?, last: Sent?, nowWall: Long): Boolean =
        last != null && there == last.hash && nowWall - last.atWall in 0 until RESEND_MS
}
