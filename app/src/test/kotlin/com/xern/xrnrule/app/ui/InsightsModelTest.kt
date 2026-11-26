package com.xern.xrnrule.app.ui

import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.app.data.UsageDay
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
        assertEquals(listOf(at(0, 9), at(0, 8)), history.first().rows.map { it.event.atWallMs })
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
    fun theLaptopsTimeAddsToThePhonesInTheSameRows() {
        val (apps, sites) = InsightsModel.topTargets(
            appTime = mapOf("com.youtube" to ("YouTube" to 40 * 60_000L)),
            siteTime = mapOf("www.youtube.com" to 10 * 60_000L),
            tries = emptyList(),
            blocks = blocks,
            blockId = null,
            laptopApps = mapOf("zen" to ("Zen Browser" to 90 * 60_000L), "steam" to ("Steam" to 30 * 60_000L)),
            laptopSites = mapOf("youtube.com" to 25 * 60_000L, "example.org" to 5 * 60_000L),
        )
        // A laptop app is its own row, under its laptop name and glyph.
        assertEquals(listOf("zen", "com.youtube", "steam"), apps.map { it.target })
        val zen = apps.first()
        assertEquals(listOf("Zen Browser", 0L, 90 * 60_000L, true), listOf(zen.label, zen.phoneMs, zen.laptopMs, zen.laptopOnly))
        // A site is one row: the phone's part and the laptop's beside it.
        val yt = sites.first { it.target == "youtube.com" }
        assertEquals(listOf(35 * 60_000L, 10 * 60_000L, 25 * 60_000L), listOf(yt.ms, yt.phoneMs, yt.laptopMs))
        assertEquals(listOf("youtube.com", "example.org"), sites.map { it.target })
    }

    @Test
    fun screenTimeIsEachDaysAppsOnEachDevice() {
        val today = LocalDate.of(2026, 11, 19)
        val phoneApps = mapOf(today to mapOf("com.youtube" to 30 * 60_000L, "com.insta" to 10 * 60_000L), today.minusDays(1) to mapOf("com.youtube" to 5 * 60_000L))
        val phoneSites = mapOf(today to mapOf("youtube.com" to 7 * 60_000L))
        val laptop = mapOf(
            today to UsageDay(mapOf("zen" to 60 * 60_000L, "steam" to 20 * 60_000L), mapOf("youtube.com" to 15 * 60_000L)),
            today.minusDays(9) to UsageDay(mapOf("zen" to 999 * 60_000L)), // before the week
        )
        val week = InsightsModel.screenTime(phoneApps, phoneSites, laptop, blocks, null, InsightRange.WEEK, DeviceFilter.ALL, now, zone)
        assertEquals(7, week.days.size)
        assertEquals(ScreenDay(today, 40 * 60_000L, 80 * 60_000L), week.days.last()) // sites are part of the apps' time
        assertEquals(ScreenDay(today.minusDays(1), 5 * 60_000L, 0L), week.days[5])
        assertEquals(125 * 60_000L, week.totalMs)
        // One device.
        val laptopOnly = InsightsModel.screenTime(phoneApps, phoneSites, laptop, blocks, null, InsightRange.TODAY, DeviceFilter.LAPTOP, now, zone)
        assertEquals(listOf(ScreenDay(today, 0L, 80 * 60_000L)), laptopOnly.days)
        // A block: its apps and its sites, on each device.
        val yt = InsightsModel.screenTime(phoneApps, phoneSites, laptop, blocks, "yt", InsightRange.TODAY, DeviceFilter.ALL, now, zone)
        assertEquals(listOf(ScreenDay(today, 37 * 60_000L, 15 * 60_000L)), yt.days)
        val games = InsightsModel.screenTime(phoneApps, phoneSites, laptop, blocks, "games", InsightRange.TODAY, DeviceFilter.ALL, now, zone)
        assertEquals(listOf(ScreenDay(today, 0L, 20 * 60_000L)), games.days)
    }

    @Test
    fun blockColoursFollowTheListAndWrapAfterTheEighth() {
        val many = (0 until 10).map { block("b$it") }
        // The muted tones the web pages use, in the old palette's hue order (clay where orange was).
        assertEquals(
            listOf(0xFFD27A4A, 0xFF5B8FD6, 0xFF4E9E68, 0xFF8F7AD8, 0xFFCE6488, 0xFF3E9E92, 0xFFAE8834, 0xFF4C98C4),
            BlockColors.PALETTE,
        )
        assertEquals(0xFFD27A4A, BlockColors.argb(many, "b0"))
        assertEquals(0xFF5B8FD6, BlockColors.argb(many, "b1"))
        assertEquals(0xFF4C98C4, BlockColors.argb(many, "b7"))
        assertEquals(BlockColors.argb(many, "b0"), BlockColors.argb(many, "b8"))
        assertEquals(BlockColors.argb(many, "b1"), BlockColors.argb(many, "b9"))
        assertNull(BlockColors.argb(many, "gone"))
        assertNull(BlockColors.argb(many, null))
        // A colour is a place in the list: moving a block moves its colour.
        assertEquals(0xFFD27A4A, BlockColors.argb(many.reversed(), "b9"))
    }

    // ---- the stretch before -------------------------------------------------------------------

    @Test
    fun theStretchBeforeIsYesterdayForTodayAndTheSameNumberOfDaysBeforeOtherwise() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(0, 9)),
                TelemetryEvent.Blocked("com.insta", at(1, 9)),
                TelemetryEvent.Blocked("com.insta", at(1, 23, 59)),
                TelemetryEvent.Blocked("com.insta", at(2, 9)),
                TelemetryEvent.Blocked("com.insta", at(7, 0, 1)),
                TelemetryEvent.Blocked("com.insta", at(13, 23)),
                TelemetryEvent.Blocked("com.insta", at(14, 9)),
                TelemetryEvent.Blocked("com.insta", at(59, 9)),
                TelemetryEvent.Blocked("com.insta", at(60, 9)),
                TelemetryEvent.UnlockUsed("social", 20 * 60_000, at(1, 8), at(1, 7, 40)),
                TelemetryEvent.MinutesBought(10 * 60_000, at(1, 20), "yt"),
                TelemetryEvent.BreakTaken(15 * 60_000, at(8, 16)),
                TelemetryEvent.HabitDone("social", at(1, 7)),
            ),
        )
        val today = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        assertEquals(1, today.tries)
        assertEquals(Figures(tries = 2, unlocks = 1, unlockedMs = 20 * 60_000L, bought = 1, boughtMs = 10 * 60_000L, habits = 1), today.previous)
        // 7 days: 13 to 19 Nov; before them 6 to 12 Nov.
        val week = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(4, week.tries)
        assertEquals(2, week.previous?.tries)
        assertEquals(1, week.previous?.breaks)
        // 30 days, and the 30 before them: the 60th day back is outside both.
        val month = InsightsModel.summarize(events, blocks, InsightRange.MONTH, now, zone)
        assertEquals(7, month.tries)
        assertEquals(1, month.previous?.tries)
    }

    @Test
    fun thereIsNoStretchBeforeUnlessTheLogReachesBackToItsFirstDay() {
        val events = merge(local = listOf(TelemetryEvent.Blocked("com.insta", at(2, 9)), TelemetryEvent.Blocked("com.insta", at(0, 9))))
        assertNull(InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone).previous)
        // The week before is 6 to 12 Nov: a log begun on the 6th covers it, one begun on the 7th doesn't.
        val sixth = merge(local = listOf(TelemetryEvent.Blocked("com.insta", at(13, 21)), TelemetryEvent.Blocked("com.insta", at(0, 9))))
        assertEquals(1, InsightsModel.summarize(sixth, blocks, InsightRange.WEEK, now, zone).previous?.tries)
        val seventh = merge(local = listOf(TelemetryEvent.Blocked("com.insta", at(12, 0, 5)), TelemetryEvent.Blocked("com.insta", at(0, 9))))
        assertNull(InsightsModel.summarize(seventh, blocks, InsightRange.WEEK, now, zone).previous)
        // Yesterday was logged, so today has a yesterday to set beside it, even an empty one.
        assertEquals(Figures(), InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone).previous)
        assertNull(InsightsModel.summarize(emptyList(), blocks, InsightRange.TODAY, now, zone).previous)
        // A block picked: the whole log says how far back it reaches, not that block's events.
        val games = InsightsModel.select(events, DeviceFilter.ALL, "games")
        assertEquals(Figures(), InsightsModel.summarize(games, blocks, InsightRange.TODAY, now, zone, logStartMs = events.first().atWallMs).previous)
    }

    // ---- by block -----------------------------------------------------------------------------

    @Test
    fun theRangesTriesSplitByBlockInListOrderAndEachBlockHasItsTrend() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(0, 9)),
                TelemetryEvent.Blocked("com.insta", at(2, 9)),
                TelemetryEvent.Blocked("com.youtube", at(2, 10)),
                TelemetryEvent.Blocked("com.other", at(3, 10)),
            ),
            laptop = listOf(TelemetryEvent.Blocked("steam", at(0, 20), "Steam")),
        )
        val week = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone)
        assertEquals(listOf(Segment("yt", 1), Segment("social", 2), Segment("games", 1), Segment(null, 1)), week.split)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 1), week.blocks.first { it.blockId == "social" }.trend)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0), week.blocks.first { it.blockId == "yt" }.trend)
        val today = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        assertEquals(24, today.blocks.first().trend.size)
        assertEquals(1, today.blocks.first { it.blockId == "games" }.trend[20])
        assertEquals(21, today.nowHour)
    }

    @Test
    fun mostTriedCountsEachDevicesTries() {
        val events = merge(
            local = listOf(TelemetryEvent.SiteBlocked("www.youtube.com", at(0, 9)), TelemetryEvent.SiteBlocked("youtube.com", at(0, 10))),
            laptop = listOf(TelemetryEvent.Blocked("youtube.com", at(0, 11), "youtube.com")),
        )
        val t = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone).targets.single()
        assertEquals(3, t.tries)
        assertEquals(2, t.phone)
        assertEquals(1, t.laptop)
    }

    @Test
    fun anAppOnlyEverMetOnTheLaptopIsToldSo() {
        val events = merge(
            local = listOf(TelemetryEvent.Blocked("com.insta", at(0, 9))),
            laptop = listOf(TelemetryEvent.Blocked("steam", at(0, 12), "Steam")),
        )
        val s = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone)
        val (apps, _) = InsightsModel.topTargets(emptyMap(), emptyMap(), s.targets, blocks, null)
        assertEquals(mapOf("com.insta" to false, "steam" to true), apps.associate { it.target to it.laptopOnly })
    }

    // ---- limits -------------------------------------------------------------------------------

    private fun dayStart(daysBack: Int) = at(daysBack, 0)
    private val limited = yt.copy(dailyAllowanceMs = 30 * 60_000L)

    @Test
    fun aLimitsUseADayIsOverTheDaysSomeDeviceReported() {
        val record = mapOf(
            "phone-1" to mapOf(dayStart(1) to 20 * 60_000L, dayStart(2) to 10 * 60_000L, dayStart(9) to 30 * 60_000L),
            "laptop-1" to mapOf(dayStart(1) to 15 * 60_000L),
        )
        val today = BlockToday(phoneMs = 12 * 60_000L, laptopMs = 3 * 60_000L, allowanceMs = 30 * 60_000L)
        val week = InsightsModel.limitUse(limited, record, today, InsightRange.WEEK, DeviceFilter.ALL, now, zone)
        // Today 15, yesterday 35 (both devices), the day before 10; 9 days back is outside the week.
        assertEquals(3, week.days)
        assertEquals(60 * 60_000L, week.usedMs)
        assertEquals(20 * 60_000L, week.perDayMs)
        assertEquals(1, week.over)
        assertEquals(false, week.todayOnly)
        // One device's part only.
        val phone = InsightsModel.limitUse(limited, record, today, InsightRange.WEEK, DeviceFilter.PHONE, now, zone)
        assertEquals((12 + 20 + 10) * 60_000L, phone.usedMs)
        val laptop = InsightsModel.limitUse(limited, record, today, InsightRange.WEEK, DeviceFilter.LAPTOP, now, zone)
        assertEquals(2, laptop.days)
        assertEquals((3 + 15) * 60_000L, laptop.usedMs)
        // Today alone: the engine's figure, newer than the record's.
        val todayOnly = InsightsModel.limitUse(limited, record + ("phone-1" to mapOf(dayStart(0) to 5 * 60_000L)), today, InsightRange.TODAY, DeviceFilter.ALL, now, zone)
        assertEquals(1, todayOnly.days)
        assertEquals(15 * 60_000L, todayOnly.usedMs)
        assertTrue(todayOnly.todayOnly)
    }

    @Test
    fun aLimitOnEachDeviceGoesByTheDeviceThatUsedTheMost() {
        val perDevice = limited.copy(perDevice = true)
        val record = mapOf("phone-1" to mapOf(dayStart(1) to 20 * 60_000L), "laptop-1" to mapOf(dayStart(1) to 25 * 60_000L))
        val use = InsightsModel.limitUse(perDevice, record, null, InsightRange.WEEK, DeviceFilter.ALL, now, zone)
        assertEquals(1, use.days)
        assertEquals(25 * 60_000L, use.usedMs)
    }

    @Test
    fun withNothingReportedALimitHasNoDays() {
        val use = InsightsModel.limitUse(limited, emptyMap(), null, InsightRange.MONTH, DeviceFilter.ALL, now, zone)
        assertEquals(0, use.days)
        assertEquals(0L, use.perDayMs)
        assertEquals(0f, use.fraction)
    }

    @Test
    fun aMeterIsTheDataColourThenAmberFromEightyPercentThenRedOnceUsedUp() {
        assertEquals(MeterTone.DATA, MeterTone.of(0f))
        assertEquals(MeterTone.DATA, MeterTone.of(0.79f))
        assertEquals(MeterTone.NEAR, MeterTone.of(0.8f))
        assertEquals(MeterTone.NEAR, MeterTone.of(0.99f))
        assertEquals(MeterTone.OVER, MeterTone.of(1f))
        assertEquals(MeterTone.OVER, MeterTone.of(1.6f))
        assertEquals(MeterTone.NEAR, MeterTone.of(LimitUse(30 * 60_000L, 2 * 25 * 60_000L, 2).fraction))
    }

    // ---- history ------------------------------------------------------------------------------

    @Test
    fun triesAtOneThingInARunAreOneHistoryRow() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(0, 21, 0)),
                TelemetryEvent.Blocked("com.insta", at(0, 21, 15)),
                TelemetryEvent.Blocked("com.insta", at(0, 21, 30)),
                // Over half an hour before the run's first: a row of its own.
                TelemetryEvent.Blocked("com.insta", at(0, 20, 20)),
                TelemetryEvent.SiteBlocked("www.reddit.com", at(0, 9, 0)),
                TelemetryEvent.SiteBlocked("reddit.com", at(0, 9, 5)),
            ),
            // The same thing on the other device is another row.
            laptop = listOf(TelemetryEvent.Blocked("reddit.com", at(0, 9, 2), "reddit.com")),
        )
        val day = InsightsModel.summarize(events, blocks, InsightRange.TODAY, now, zone).history.single()
        assertEquals(listOf(3, 1, 1, 1, 1), day.rows.map { it.n })
        assertEquals(at(0, 21, 0), day.rows.first().fromMs)
        assertEquals(at(0, 21, 30), day.rows.first().event.atWallMs)
        assertEquals(7, day.events)
    }

    @Test
    fun aDaysHeadHasItsTriesUnlocksAndTheirSplit() {
        val events = merge(
            local = listOf(
                TelemetryEvent.Blocked("com.insta", at(1, 9)),
                TelemetryEvent.Blocked("com.youtube", at(1, 10)),
                TelemetryEvent.Blocked("com.insta", at(1, 11)),
                TelemetryEvent.UnlockUsed("social", 20 * 60_000, at(1, 8), at(1, 7, 40)),
                TelemetryEvent.UnlockUsed("social", 5 * 60_000, at(1, 18), at(1, 17, 55)),
                TelemetryEvent.HabitDone("social", at(0, 7)),
            ),
        )
        val history = InsightsModel.summarize(events, blocks, InsightRange.WEEK, now, zone).history
        assertEquals(listOf(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18)), history.map { it.date })
        val yesterday = history[1]
        assertEquals(3, yesterday.tries)
        assertEquals(2, yesterday.unlocks)
        assertEquals(25 * 60_000L, yesterday.unlockedMs)
        assertEquals(listOf(Segment("yt", 1), Segment("social", 2)), yesterday.split)
        assertEquals(0, history[0].tries)
    }
}
