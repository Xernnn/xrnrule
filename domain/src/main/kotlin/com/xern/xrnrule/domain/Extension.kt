package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable

/**
 * What the browser extension is told, the same on the phone and the laptop: each serves it on
 * `127.0.0.1` (`POST /ext/heartbeat`, `GET /ext/rules`, `GET /ext/check`). The extension
 * decides nothing itself; between answers it only holds on to the last [SiteRules], so a site
 * that was blocked stays blocked while the app is unreachable.
 */
object ExtensionApi {

    /** One site rule: [domain] and its subdomains, except [except] (I18, I31). */
    @Serializable
    data class SiteRule(val domain: String, val except: List<String> = emptyList())

    /**
     * Every site blocked right now, for the extension's redirect rules. [page] is where a blocked
     * tab goes ("?site=…" is added, and the page it left as the hash); null means the extension's
     * own block page, which the phone uses because its block screen is the app's overlay. [v]
     * changes whenever any of it does.
     */
    @Serializable
    data class SiteRules(val v: String, val blocked: List<SiteRule>, val page: String? = null, val showClock: Boolean = true)

    @Serializable
    data class Left(val ms: Long, val kind: ClockKind)

    /**
     * About one host: [blocked], else what runs out first ([left], on the block named [label]).
     * [rules] is the current [SiteRules.v], so the extension knows when to fetch them again.
     */
    @Serializable
    data class SiteVerdict(
        val site: String?,
        val blocked: Boolean,
        val page: String? = null,
        val left: Left? = null,
        val label: String? = null,
        val showClock: Boolean = true,
        val rules: String = "",
    )

    fun rules(world: World, page: String?): SiteRules {
        val plan = world.appliedShield
        val blocked = plan.blockedSites.sorted().map { SiteRule(it, plan.siteExceptions[it].orEmpty().sorted()) }
        val v = listOf(blocked, page, world.settings.showClock).hashCode().toUInt().toString(16)
        return SiteRules(v, blocked, page, world.settings.showClock)
    }

    fun verdict(engine: BlockEngine, world: World, host: String?, clock: Clock, page: String?): SiteVerdict {
        val site = host?.let { Sites.normalize(it) }
        val rules = rules(world, page)
        if (site == null) return SiteVerdict(null, blocked = false, page = page, showClock = rules.showClock, rules = rules.v)
        val blocked = engine.shieldPlan(world, clock).siteBlocked(site)
        val left = if (blocked) null else engine.timeLeft(world, null, site, clock)
        val label = left?.blockId?.let { world.block(it)?.name }
            ?: world.blocks.firstOrNull { it.coversSite(site) }?.name
        return SiteVerdict(
            site = site,
            blocked = blocked,
            page = page,
            left = left?.let { Left(it.ms, it.kind) },
            label = label,
            showClock = rules.showClock,
            rules = rules.v,
        )
    }
}
