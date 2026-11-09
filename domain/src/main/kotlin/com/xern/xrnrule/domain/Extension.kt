package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable

/**
 * What the browser extension is told, the same on the phone and the laptop: each serves it on
 * `127.0.0.1` (`POST /ext/heartbeat`, `GET /ext/rules`, `GET /ext/check`). The extension
 * decides nothing itself; between answers it only holds on to the last [SiteRules], so a site
 * that was blocked stays blocked while the app is unreachable.
 */
object ExtensionApi {

    /**
     * Whether extension [version] is older than [than], part by part ("2.1.0" < "2.1.1" < "2.10").
     * A version that isn't dotted numbers is never called old: nothing to warn about.
     */
    fun isOlder(version: String, than: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: return false }
        val b = than.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    /** One site rule: [domain] and its subdomains, except [except] (I18, I31). */
    @Serializable
    data class SiteRule(val domain: String, val except: List<String> = emptyList())

    /**
     * Every site blocked right now, for the extension's redirect rules. [page] is where a blocked
     * tab goes ("?site=…" is added, and the page it left as the hash): the laptop daemon's block
     * page, or on the phone the app's copy of the extension's stand-in page (its block screen is
     * the app's overlay); null means the extension's own. [v] changes whenever any of it does.
     */
    @Serializable
    data class SiteRules(
        val v: String,
        val blocked: List<SiteRule>,
        val page: String? = null,
        val showClock: Boolean = true,
        /** I48: every site with a rule here, which the extension blocks while xrnrule is gone. */
        val watched: List<SiteRule> = emptyList(),
    )

    @Serializable
    data class Left(val ms: Long, val kind: ClockKind)

    /**
     * About one host: [blocked], else what runs out first ([left], on the block named [label]).
     * [rules] is the current [SiteRules.v], so the extension knows when to fetch them again.
     * [leave] (the phone): you chose to leave this site on the block screen, so the extension's
     * stand-in page steps back, or closes its tab, instead of keeping the browser behind it.
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
        val leave: Boolean = false,
        /** While blocked: the block keeping it so, when it's back and why, for a page to say so. */
        val block: String? = null,
        val back: String? = null,
        val why: String? = null,
    )

    fun rules(world: World, page: String?): SiteRules {
        val plan = world.appliedShield
        val blocked = plan.blockedSites.sorted().map { SiteRule(it, plan.siteExceptions[it].orEmpty().sorted()) }
        val watched = watched(world)
        val v = listOf(blocked, page, world.settings.showClock, watched).hashCode().toUInt().toString(16)
        return SiteRules(v, blocked, page, world.settings.showClock, watched)
    }

    /**
     * I48: the blocks that could block something here, for when xrnrule can't be asked. A lockdown
     * only while it runs: one not started blocks nothing, and nothing can start it meanwhile.
     */
    private fun watched(world: World): List<SiteRule> {
        val blocks = world.blocks.filter {
            it.enabled && world.appliesHere(it) && (it.mode != BlockMode.LOCKDOWN || it.id in world.lockdowns)
        }
        return blocks.flatMap { it.targetSites }.toSortedSet().map { site ->
            val covering = blocks.filter { site in it.targetSites }
            SiteRule(site, covering.map { it.exceptSites }.reduce { a, b -> a intersect b }.sorted())
        }
    }

    /** I49: whether a browser without the extension gets past anything here. */
    fun otherBrowsersMatter(world: World): Boolean =
        world.settings.blockOtherBrowsers && world.blocks.any { it.enabled && world.appliesHere(it) && it.targetSites.isNotEmpty() }

    fun verdict(engine: BlockEngine, world: World, host: String?, clock: Clock, page: String?): SiteVerdict {
        val site = host?.let { Sites.normalizeTarget(it) }
        val rules = rules(world, page)
        if (site == null) return SiteVerdict(null, blocked = false, page = page, showClock = rules.showClock, rules = rules.v)
        val blocked = engine.shieldPlan(world, clock).siteBlocked(site)
        val left = if (blocked) null else engine.timeLeft(world, null, site, clock)
        val label = left?.blockId?.let { world.block(it)?.name }
            ?: world.blocks.firstOrNull { it.coversSite(site) }?.name
        val lead = if (blocked) BlockTexts.leading(engine, world, engine.blockersOf(world, null, site, clock), clock) else null
        val back = lead?.let { BlockTexts.back(engine, world, it, clock) }
        return SiteVerdict(
            site = site,
            blocked = blocked,
            page = page,
            left = left?.let { Left(it.ms, it.kind) },
            label = label,
            showClock = rules.showClock,
            rules = rules.v,
            block = lead?.name,
            back = back?.at,
            why = BlockTexts.why(back, lead),
        )
    }
}
