package com.xern.xrnrule.domain

/**
 * Services that are both a website and a phone app. Blocking one and not the other is how a block
 * gets walked around without meaning to, so the form offers the other half, and a block's tile
 * shows the pair as one.
 */
object Companions {
    private val PAIRS: Map<String, List<String>> = mapOf(
        "youtube.com" to listOf("com.google.android.youtube"),
        "instagram.com" to listOf("com.instagram.android"),
        "facebook.com" to listOf("com.facebook.katana", "com.facebook.lite"),
        "x.com" to listOf("com.twitter.android"),
        "reddit.com" to listOf("com.reddit.frontpage"),
        "tiktok.com" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "pinterest.com" to listOf("com.pinterest"),
        "snapchat.com" to listOf("com.snapchat.android"),
        "twitch.tv" to listOf("tv.twitch.android.app"),
        "discord.com" to listOf("com.discord"),
        "netflix.com" to listOf("com.netflix.mediaclient"),
    )

    /** The installed apps of the sites in [sites] that aren't in [apps]. A path covers only part of a site: it doesn't count. */
    fun appsFor(sites: Set<String>, apps: Set<String>, installed: Set<String>): Set<String> =
        sites.filter { '/' !in it }.flatMapTo(sortedSetOf()) { site ->
            val pairs = PAIRS.entries.filter { Sites.matches(site, it.key) }.flatMap { it.value }
            if (pairs.any { it in apps }) emptyList() else pairs.filter { it in installed }
        }

    /** The sites of the apps in [apps] that nothing in [sites] covers. */
    fun sitesFor(apps: Set<String>, sites: Set<String>): Set<String> =
        PAIRS.filterValues { packages -> packages.any { it in apps } }.keys
            .filterTo(sortedSetOf()) { site -> sites.none { Sites.matches(site, it) } }

    /** [sites] without those whose app is in [apps]: one tile for one service. */
    fun withoutPaired(sites: List<String>, apps: Collection<String>): List<String> = sites.filterNot { site ->
        PAIRS.entries.any { (domain, packages) -> Sites.matches(site, domain) && packages.any { it in apps } }
    }
}
