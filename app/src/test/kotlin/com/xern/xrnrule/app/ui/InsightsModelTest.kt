package com.xern.xrnrule.app.ui

import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsModelTest {
    private val zone = ZoneId.of("Australia/Sydney")

    /** Thursday 19 Nov 2026, 21:40 in [zone]. */
    private val now = at(0, 21, 40)

    private fun at(daysBack: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 19, hour, minute).minusDays(daysBack.toLong()).atZone(zone).toInstant().toEpochMilli()

    private fun block(id: String, apps: Set<String> = emptySet(), sites: Set<String> = emptySet(), desktop: Set<String> = emptySet()) =
        Block(id, id, targetPackages = apps, targetSites = sites, targetDesktopApps = desktop, trigger = Trigger(TriggerType.CHECKLIST, 1, TriggerUnit.ITEMS), mode = BlockMode.ALLOWANCE)

    private val yt = block("yt", apps = setOf("com.youtube"), sites = setOf("youtube.com"))
    private val social = block("social", apps = setOf("com.insta", "com.youtube"))
    private val games = block("games", desktop = setOf("steam"))
    private val blocks = listOf(yt, social, games)

    private fun merge(local: List<TelemetryEvent> = emptyList(), laptop: List<TelemetryEvent> = emptyList(), emergencies: Map<String, Long> = emptyMap()) =
        InsightsModel.merge(local, laptop, emergencies, blocks)

    @Test
    fun eachEventSaysWhichDeviceItHappenedOn() {
        val events = merge(
            local = listOf(TelemetryEvent.Blocked("com.insta", at(0, 9))),
            laptop = listOf(TelemetryEvent.Blocked("steam", at(0, 10), "Steam"), TelemetryEvent.BreakTaken(900_000, at(0, 11))),
        )
        assertEquals(listOf(Device.PHONE, Device.LAPTOP, Device.LAPTOP), events.map { it.device })
        assertEquals(listOf(EventKind.BLOCKED, EventKind.BLOCKED, EventKind.BREAK), events.map { it.kind })
    }

    @Test
    fun aHabitBothDevicesLoggedIsOneOnTheDeviceThatLoggedItFirst() {
        // Done on the laptop at 7:00; the phone heard of it a few minutes later and logged it too.
        val events = merge(
            local = listOf(TelemetryEvent.HabitDone("social", at(0, 7, 4))),
            laptop = listOf(TelemetryEvent.HabitDone("social", at(0, 7, 0))),
        )
        assertEquals(1, events.size)
        assertEquals(Device.LAPTOP, events.single().device)
    }

    @Test
    fun twoHabitsOnTheSameDeviceStayTwo() {
        val events = merge(local = listOf(TelemetryEvent.HabitDone("social", at(0, 7, 0)), TelemetryEvent.HabitDone("social", at(0, 7, 5))))
        assertEquals(2, events.size)
    }

    @Test
    fun aHabitHoursApartOnTheOtherDeviceIsAnotherOne() {
        val events = merge(
            local = listOf(TelemetryEvent.HabitDone("social", at(0, 7))),
            laptop = listOf(TelemetryEvent.HabitDone("social", at(0, 19))),
        )
        assertEquals(listOf(Device.PHONE, Device.LAPTOP), events.map { it.device })
    }

    @Test
    fun anUnlockBothDevicesEndedCountsOnceBySession() {
        val start = at(0, 8)
        val events = merge(
            local = listOf(TelemetryEvent.UnlockUsed("social", 20 * 60_000, at(0, 8, 20), start)),
            laptop = listOf(TelemetryEvent.UnlockUsed("social", 20 * 60_000, at(0, 8, 21), start)),
        )
        assertEquals(1, events.size)
        assertEquals(Device.PHONE, events.single().device)
        val summary = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        assertEquals(20 * 60_000L, summary.unlockedMs)
        assertEquals(1, summary.unlocks)
    }

    @Test
    fun twoSessionsOfTheSameBlockAreTwoUnlocks() {
        val events = merge(
            local = listOf(TelemetryEvent.UnlockUsed("social", 60_000, at(0, 9), at(0, 8, 59))),
            laptop = listOf(TelemetryEvent.UnlockUsed("social", 60_000, at(0, 18), at(0, 17, 59))),
        )
        assertEquals(2, events.size)
    }

    @Test
    fun aBlockedTryGoesToTheFirstBlockInTheListThatCoversIt() {
        // com.youtube is in both YouTube and Social; YouTube comes first.
        val events = merge(local = listOf(TelemetryEvent.Blocked("com.youtube", at(0, 9)), TelemetryEvent.SiteBlocked("m.youtube.com", at(0, 10))))
        assertEquals(listOf("yt", "yt"), events.map { it.blockId })
        assertNull(merge(local = listOf(TelemetryEvent.Blocked("com.other", at(0, 9)))).single().blockId)
    }

    @Test
    fun theLaptopsSitesAreToldFromItsApps() {
        val events = merge(
            laptop = listOf(
                TelemetryEvent.Blocked("youtube.com", at(0, 9), "youtube.com"),
                TelemetryEvent.Blocked("steam", at(0, 10), "Steam"),
                TelemetryEvent.Blocked("news.example.com", at(0, 11), "news.example.com"),
            ),
        )
        assertEquals(listOf(true, false, true), events.map { it.isSite })
        assertEquals(listOf("yt", "games", null), events.map { it.blockId })
    }

    @Test
    fun theDeviceFilterShowsOneDeviceAndWhatBelongsToEvery() {
        val events = merge(
            local = listOf(TelemetryEvent.Blocked("com.insta", at(0, 9))),
            laptop = listOf(TelemetryEvent.Blocked("steam", at(0, 10), "Steam")),
            emergencies = mapOf("games@${at(0, 12)}" to at(0, 12)),
        )
        assertEquals(3, InsightsModel.select(events, DeviceFilter.ALL, null).size)
        assertEquals(listOf(EventKind.BLOCKED, EventKind.EMERGENCY), InsightsModel.select(events, DeviceFilter.PHONE, null).map { it.kind })
        assertEquals(listOf(Device.LAPTOP, null), InsightsModel.select(events, DeviceFilter.LAPTOP, null).map { it.device })
        assertEquals(listOf("games", "games"), InsightsModel.select(events, DeviceFilter.ALL, "games").map { it.blockId })
    }

    @Test
    fun aRangeStartsAtMidnightAndLeavesOutWhatCameBefore() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(0, 0, 10)),
                TelemetryEvent.Blocked("com.insta", at(1, 23, 50)),
                TelemetryEvent.Blocked("com.insta", at(6, 1)),
                TelemetryEvent.Blocked("com.insta", at(7, 23)),
                TelemetryEvent.Blocked("com.insta", at(29, 12)),
                TelemetryEvent.Blocked("com.insta", at(30, 12)),
            ),
        )
        assertEquals(1, InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone).tries)
        assertEquals(3, InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone).tries)
        assertEquals(5, InsightsModel.summarize(events, blocks, InsightRange.MONTH, now, zone).tries)
    }

    @Test
    fun barsAreHoursTodayAndDaysOtherwiseEachBlockInListOrder() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(0, 9, 5)),
                TelemetryEvent.Blocked("com.youtube", at(0, 9, 30)),
                TelemetryEvent.Blocked("com.youtube", at(0, 9, 50)),
                TelemetryEvent.Blocked("com.other", at(0, 9, 55)),
            ),
            laptop = listOf(TelemetryEvent.Blocked("steam", at(2, 20), "Steam")),
        )
        val today = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        assertEquals(24, today.bars.size)
        assertEquals(listOf(Segment("yt", 2), Segment("social", 1), Segment(null, 1)), today.bars[9].segments)
        val week = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(7, week.bars.size)
        assertEquals(LocalDate.of(2026, 11, 19), week.bars.last().date)
        assertEquals(4, week.bars.last().total)
        assertEquals(listOf(Segment("games", 1)), week.bars[4].segments)
        assertEquals(30, InsightsModel.summarize(events, blocks, InsightRange.MONTH, now, zone).bars.size)
    }

    @Test
    fun theHeatmapCountsTriesByWeekdayAndHour() {
        val events = merge(local = listOf(TelemetryEvent.Blocked("com.insta", at(0, 21)), TelemetryEvent.Blocked("com.insta", at(0, 21, 30)), TelemetryEvent.Blocked("com.insta", at(1, 8))))
        val heat = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone).heat
        assertEquals(2, heat[DayOfWeek.THURSDAY.value - 1][21])
        assertEquals(1, heat[DayOfWeek.WEDNESDAY.value - 1][8])
        assertEquals(3, heat.sumOf { it.sum() })
    }

    @Test
    fun theFiguresAddUpWhatHappenedInTheRange() {
        val events = merge(
            local = listOf(
                TelemetryEvent.HabitDone("social", at(0, 7)),
                TelemetryEvent.MinutesBought(10 * 60_000, at(0, 20), "yt,social"),
                TelemetryEvent.BreakTaken(15 * 60_000, at(0, 16)),
            ),
            laptop = listOf(TelemetryEvent.MinutesBought(5 * 60_000, at(1, 20), "yt")),
        )
        val s = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(1, s.habits)
        assertEquals(2, s.bought)
        assertEquals(15 * 60_000L, s.boughtMs)
        assertEquals(1, s.breaks)
        val ytCard = s.blocks.first { it.blockId == "yt" }
        assertEquals(2, ytCard.bought)
    }

    @Test
    fun cleanDaysRunBackToTheLastTryOrToWhereTheLogBegins() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.youtube", at(3, 21)),
                TelemetryEvent.Blocked("com.insta", at(0, 9)),
                TelemetryEvent.HabitDone("social", at(9, 7)),
            ),
        )
        val s = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(3, s.blocks.first { it.blockId == "yt" }.cleanDays) // today, yesterday, the day before
        assertEquals(0, s.blocks.first { it.blockId == "social" }.cleanDays) // tried today
        assertEquals(10, s.blocks.first { it.blockId == "games" }.cleanDays) // never tried: back to the log's first day
        assertEquals(0, InsightsModel.summarize(emptyList(), blocks, InsightRange.WEEK, now, zone).blocks.first().cleanDays)
    }

    @Test
    fun anEmergencyUnlockOrMinutesBoughtBreakAStreakToo() {
        val events = merge(
            local = listOf(TelemetryEvent.HabitDone("social", at(5, 7)), TelemetryEvent.MinutesBought(60_000, at(1, 20), "yt")),
            emergencies = mapOf("games@${at(2, 12)}" to at(2, 12)),
        )
        val s = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(1, s.blocks.first { it.blockId == "yt" }.cleanDays)
        assertEquals(2, s.blocks.first { it.blockId == "games" }.cleanDays)
    }

    @Test
    fun theHistoryIsDayByDayNewestFirst() {
        val events = merge(local = listOf(TelemetryEvent.Blocked("com.insta", at(1, 9)), TelemetryEvent.Blocked("com.insta", at(0, 8)), TelemetryEvent.Blocked("com.insta", at(0, 9))))
        val history = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone).history
        assertEquals(listOf(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18)), history.map { it.date })
        assertEquals(listOf(at(0, 9), at(0, 8)), history.first().events.map { it.atWallMs })
    }

    @Test
    fun topAppsAndSitesGoByPhoneTimeThenTriesOnEveryDevice() {
        val events = merge(
            local = listOf(TelemetryEvent.Blocked("com.insta", at(0, 9)), TelemetryEvent.SiteBlocked("www.youtube.com", at(0, 10))),
            laptop = listOf(TelemetryEvent.Blocked("youtube.com", at(0, 11), "youtube.com"), TelemetryEvent.Blocked("steam", at(0, 12), "Steam")),
        )
        val s = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        val (apps, sites) = InsightsModel.topTargets(
            appTime = mapOf("com.youtube" to ("YouTube" to 40 * 60_000L), "com.calc" to ("Calculator" to 30_000L)),
            siteTime = mapOf("youtube.com" to 10 * 60_000L),
            tries = s.targets,
            blocks = blocks,
            blockId = null,
        )
        // Under a minute and never tried: left out. Laptop apps come by their tries.
        assertEquals(listOf("com.youtube", "com.insta", "steam"), apps.map { it.target })
        assertEquals(listOf(0, 1, 1), apps.map { it.tries })
        assertEquals(listOf("Steam"), apps.filter { it.target == "steam" }.map { it.label })
        // The phone's www.youtube.com and the laptop's youtube.com are one site.
        assertEquals(listOf("youtube.com"), sites.map { it.target })
        assertEquals(2, sites.single().tries)
        assertEquals("yt", sites.single().blockId)

        val (gameApps, gameSites) = InsightsModel.topTargets(emptyMap(), mapOf("youtube.com" to 60_000L), s.targets, blocks, "games")
        assertEquals(listOf("steam"), gameApps.map { it.target })
        assertTrue(gameSites.isEmpty())
    }

    @Test
    fun blockColoursFollowTheListAndWrapAfterTheEighth() {
        val many = (0 until 10).map { block("b$it") }
        assertEquals(0xFFE8590C, BlockColors.argb(many, "b0"))
        assertEquals(0xFF1C6FD8, BlockColors.argb(many, "b1"))
        assertEquals(0xFF1283C4, BlockColors.argb(many, "b7"))
        assertEquals(BlockColors.argb(many, "b0"), BlockColors.argb(many, "b8"))
        assertEquals(BlockColors.argb(many, "b1"), BlockColors.argb(many, "b9"))
        assertNull(BlockColors.argb(many, "gone"))
        assertNull(BlockColors.argb(many, null))
        // A colour is a place in the list: moving a block moves its colour.
        assertEquals(0xFFE8590C, BlockColors.argb(many.reversed(), "b9"))
    }
}
