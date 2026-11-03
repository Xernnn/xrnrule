/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of xrnrule, and its cases and expected values are ported from Loop Habit
 * Tracker's tests (uhabits-core's EntryListTest, ScoreTest, ScoreListTest and StreakListTest).
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package com.xern.xrnrule.domain

import com.xern.xrnrule.domain.HabitEntry.Companion.NO
import com.xern.xrnrule.domain.HabitEntry.Companion.SKIP
import com.xern.xrnrule.domain.HabitEntry.Companion.UNKNOWN
import com.xern.xrnrule.domain.HabitEntry.Companion.YES_AUTO
import com.xern.xrnrule.domain.HabitEntry.Companion.YES_MANUAL
import com.xern.xrnrule.domain.LoopRules.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Loop's own test cases, run against [LoopRules]. "Today" is Loop's test day, 2015-01-25. */
class LoopRulesTest {

    private val today = LocalDate.of(2015, 1, 25).toEpochDay()

    private fun day(offset: Int): Long = today - offset

    private val check = Habit(id = "meditate", name = "Meditate")

    private fun numerical(targetType: TargetType) =
        Habit(id = "run", name = "Run", kind = HabitKind.COUNT, goal = 2000, targetType = targetType, unit = "miles")

    /** Loop's `habit.scores[today - k]` for k = 0, 1, ...: computed over Loop's own range. */
    private fun scores(habit: Habit, original: Map<Long, Long>): (Int) -> Double {
        val computed = LoopRules.computeEntries(habit, original)
        val range = LoopRules.range(computed, today)
        val values = LoopRules.scores(habit, computed, range.first, range.last)
        return { k -> (today - k - range.first).let { i -> if (i < 0) 0.0 else values[i.toInt()] } }
    }

    private fun assertScores(expected: DoubleArray, habit: Habit, original: Map<Long, Long>) {
        val score = scores(habit, original)
        expected.forEachIndexed { k, value -> assertClose("day -$k", value, score(k), E) }
    }

    private fun assertClose(message: String, expected: Double, actual: Double, epsilon: Double) =
        assertTrue("$message: expected $expected ± $epsilon, got $actual", abs(expected - actual) <= epsilon)

    private fun yes(range: IntRange): Map<Long, Long> = range.associate { day(it) to YES_MANUAL }

    private fun amounts(range: IntRange, value: Long): Map<Long, Long> = range.associate { day(it) to value }

    // ---- EntryListTest ------------------------------------------------------------------------

    @Test
    fun computeBoolean() {
        val original = mapOf(day(4) to YES_MANUAL, day(9) to YES_MANUAL, day(10) to YES_MANUAL)
        val expected = mapOf(
            day(2) to YES_AUTO, day(3) to YES_AUTO, day(4) to YES_MANUAL,
            day(7) to YES_AUTO, day(8) to YES_AUTO, day(9) to YES_MANUAL,
            day(10) to YES_MANUAL, day(11) to YES_AUTO, day(12) to YES_AUTO,
        )
        assertEquals(expected, LoopRules.computeEntries(check.copy(freqNum = 1, freqDen = 3), original))
        assertEquals(emptyMap<Long, Long>(), LoopRules.computeEntries(check.copy(freqNum = 1, freqDen = 3), emptyMap()))
    }

    @Test
    fun computeNumericalKeepsTheEntries() {
        val original = mapOf(day(1) to 300L, day(4) to 0L)
        assertEquals(original, LoopRules.computeEntries(numerical(TargetType.AT_LEAST).copy(freqDen = 7), original))
    }

    @Test
    fun buildEntriesFromIntervals() {
        val entries = listOf(
            day(1) to YES_MANUAL, day(2) to NO, day(4) to NO,
            day(5) to YES_MANUAL, day(10) to YES_MANUAL, day(11) to NO,
        )
        val intervals = listOf(
            Interval(day(2), day(2), day(1)),
            Interval(day(6), day(5), day(4)),
            Interval(day(10), day(8), day(8)),
        )
        val expected = listOf(
            day(1) to YES_MANUAL, day(2) to YES_AUTO, day(3) to UNKNOWN, day(4) to YES_AUTO,
            day(5) to YES_MANUAL, day(6) to YES_AUTO, day(7) to UNKNOWN, day(8) to YES_AUTO,
            day(9) to YES_AUTO, day(10) to YES_MANUAL, day(11) to NO,
        )
        assertEquals(expected, LoopRules.buildEntriesFromIntervals(entries, intervals))
    }

    @Test
    fun snapIntervalsTogether() {
        val first = arrayListOf(
            Interval(day(8), day(8), day(2)),
            Interval(day(12), day(12), day(6)),
            Interval(day(20), day(20), day(14)),
            Interval(day(27), day(27), day(21)),
        )
        LoopRules.snapIntervalsTogether(first)
        assertEquals(
            listOf(
                Interval(day(8), day(8), day(2)),
                Interval(day(15), day(12), day(9)),
                Interval(day(22), day(20), day(16)),
                Interval(day(29), day(27), day(23)),
            ),
            first,
        )
        val second = arrayListOf(Interval(day(6), day(4), day(0)), Interval(day(11), day(8), day(5)))
        LoopRules.snapIntervalsTogether(second)
        assertEquals(listOf(Interval(day(6), day(4), day(0)), Interval(day(13), day(8), day(7))), second)
    }

    @Test
    fun buildIntervals() {
        val three = listOf(day(8) to YES_MANUAL, day(18) to YES_MANUAL, day(23) to YES_MANUAL)
        assertEquals(
            listOf(Interval(day(8), day(8), day(2)), Interval(day(18), day(18), day(12)), Interval(day(23), day(23), day(17))),
            LoopRules.buildIntervals(1, 7, three),
        )
        assertEquals(
            listOf(Interval(day(8), day(8), day(8)), Interval(day(18), day(18), day(18)), Interval(day(23), day(23), day(23))),
            LoopRules.buildIntervals(1, 1, three),
        )
        val five = listOf(day(8), day(15), day(18), day(22), day(23)).map { it to YES_MANUAL }
        assertEquals(
            listOf(Interval(day(18), day(15), day(12)), Interval(day(22), day(18), day(16)), Interval(day(23), day(22), day(17))),
            LoopRules.buildIntervals(2, 7, five),
        )
        val skipped = listOf(day(10) to YES_MANUAL, day(20) to SKIP, day(30) to YES_MANUAL)
        assertEquals(
            listOf(Interval(day(10), day(10), day(8)), Interval(day(30), day(30), day(28))),
            LoopRules.buildIntervals(1, 3, skipped),
        )
    }

    // ---- ScoreTest ----------------------------------------------------------------------------

    @Test
    fun scoreStep() {
        assertClose("daily", 0.051922, LoopRules.score(1.0, 0.0, 1.0), E)
        assertClose("daily", 0.525961, LoopRules.score(1.0, 0.5, 1.0), E)
        assertClose("daily", 0.762981, LoopRules.score(1.0, 0.75, 1.0), E)
        assertClose("daily", 0.474039, LoopRules.score(1.0, 0.5, 0.0), E)
        assertClose("daily", 0.711058, LoopRules.score(1.0, 0.75, 0.0), E)
        assertClose("non-daily", 0.030314, LoopRules.score(1 / 3.0, 0.0, 1.0), E)
        assertClose("non-daily", 0.515157, LoopRules.score(1 / 3.0, 0.5, 1.0), E)
        assertClose("non-daily", 0.757578, LoopRules.score(1 / 3.0, 0.75, 1.0), E)
        assertClose("non-daily", 0.484842, LoopRules.score(1 / 3.0, 0.5, 0.0), E)
        assertClose("non-daily", 0.727263, LoopRules.score(1 / 3.0, 0.75, 0.0), E)
    }

    // ---- ScoreListTest ------------------------------------------------------------------------

    private val twentyDays = doubleArrayOf(
        0.655747, 0.636894, 0.617008, 0.596033, 0.573910, 0.550574, 0.525961, 0.500000,
        0.472617, 0.443734, 0.413270, 0.381137, 0.347244, 0.311495, 0.273788, 0.234017,
        0.192067, 0.147820, 0.101149, 0.051922, 0.000000, 0.000000, 0.000000,
    )

    @Test
    fun yesNoScores() {
        assertScores(twentyDays, check, yes(0 until 20))
    }

    @Test
    fun yesNoScoresWithSkips() {
        val expected = doubleArrayOf(
            0.596033, 0.573910, 0.550574, 0.525961, 0.500000, 0.472617, 0.472617, 0.443734,
            0.413270, 0.381137, 0.347244, 0.347244, 0.347244, 0.311495, 0.273788, 0.234017,
            0.192067, 0.147820, 0.101149, 0.051922, 0.000000, 0.000000, 0.000000,
        )
        assertScores(expected, check, yes(0 until 20) + listOf(5, 10, 11).associate { day(it) to SKIP })
        assertScores(
            doubleArrayOf(0.041949, 0.044247, 0.046670, 0.049226, 0.051922, 0.051922, 0.0),
            check,
            mapOf(day(5) to YES_MANUAL, day(4) to SKIP),
        )
    }

    @Test
    fun yesNoScoresImperfectNonDaily() {
        // Twice a week out of three: the score settles at 2/3; out of four, at 1/2.
        val week = listOf(YES_MANUAL, YES_MANUAL, NO, NO, NO, NO, NO)
        val values = (0 until 700).associate { day(it) to week[it % 7] }.filterValues { it == YES_MANUAL }
        assertClose("3 a week", 2 / 3.0, scores(check.copy(freqNum = 3, freqDen = 7), values)(0), E)
        assertClose("4 a week", 0.5, scores(check.copy(freqNum = 4, freqDen = 7), values)(0), E)
    }

    @Test
    fun yesNoScoresIrregularNonDaily() {
        // Once a week, on different weekdays: still 100%.
        val fortnight = List(14) { if (it == 0 || it == 13) YES_MANUAL else NO }
        val values = (0 until 1400).associate { day(it) to fortnight[it % 14] }.filterValues { it == YES_MANUAL }
        assertClose("weekly", 1.0, scores(check.copy(freqNum = 1, freqDen = 7), values)(0), 1e-3)
    }

    @Test
    fun yesNoScoresReachTheTopInReasonableTime() {
        assertTrue(scores(check, yes(0..89))(0) > 0.99)
        assertTrue(scores(check.copy(freqDen = 7), (0..38).associate { day(7 * it) to YES_MANUAL })(0) > 0.99)
        val weeklyAndMonthly = (0..38).associate { day(7 * it) to YES_MANUAL } + (0..17).associate { day(30 * it) to YES_MANUAL }
        assertTrue(scores(check.copy(freqDen = 30), weeklyAndMonthly)(0) > 0.99)
    }

    @Test
    fun yesNoScoresFollowTheFrequency() {
        assertClose("nothing", 0.0, scores(check, emptyMap())(0), E)
        assertClose("daily", 0.101149, scores(check, yes(0 until 2))(0), E)
        assertClose("every other day", 0.054816, scores(check.copy(freqDen = 2), yes(0 until 2))(0), E)
        assertClose("taken back", 0.0, scores(check, mapOf(today to UNKNOWN))(0), E)
    }

    @Test
    fun atLeastScores() {
        assertScores(twentyDays, numerical(TargetType.AT_LEAST), amounts(0 until 20, 2000))
        assertTrue(scores(numerical(TargetType.AT_LEAST).copy(goal = 0), amounts(0..10, 100))(0).isFinite())
    }

    @Test
    fun atLeastScoresWithSkips() {
        val expected = doubleArrayOf(
            0.617008, 0.596033, 0.573910, 0.550574, 0.525961, 0.500000, 0.472617, 0.443734,
            0.413270, 0.381137, 0.347244, 0.347244, 0.311495, 0.273788, 0.234017, 0.192067,
            0.192067, 0.147820, 0.101149, 0.051922, 0.000000, 0.000000, 0.000000,
        )
        val original = amounts(0 until 20, 2000) + mapOf(day(10) to SKIP, day(15) to SKIP)
        assertScores(expected, numerical(TargetType.AT_LEAST), original)
    }

    @Test
    fun atLeastSkipsDoNotChangeTheScore() {
        val habit = numerical(TargetType.AT_LEAST)
        val initial = scores(habit, amounts(0 until 500, 1000))(0)
        assertClose("skipped after", initial, scores(habit, amounts(0 until 500, 1000) + amounts(500 until 1000, SKIP))(0), E)
        val gaps = amounts(0 until 300, 1000) + amounts(300 until 500, SKIP) + amounts(500 until 700, 1000) + amounts(700 until 1000, SKIP)
        assertClose("skipped between", initial, scores(habit, gaps)(0), E)
    }

    @Test
    fun atMostScores() {
        val expected = doubleArrayOf(
            0.344253, 0.363106, 0.382992, 0.403967, 0.426090, 0.449426, 0.474039, 0.500000,
            0.527383, 0.556266, 0.586730, 0.618863, 0.652756, 0.688505, 0.726212, 0.765983,
            0.807933, 0.852180, 0.898851, 0.948078, 1.0, 0.0, 0.0,
        )
        assertScores(expected, numerical(TargetType.AT_MOST), amounts(0 until 20, 5000) + (day(20) to 1000L))
        assertClose("nothing yet", 1.0, scores(numerical(TargetType.AT_MOST), emptyMap())(0), E)
        assertClose("two days over", 0.898850, scores(numerical(TargetType.AT_MOST), amounts(0 until 2, 5000))(0), E)
        assertTrue(scores(numerical(TargetType.AT_MOST).copy(goal = 0), amounts(0..10, 100))(0).isFinite())
    }

    // ---- StreakListTest -----------------------------------------------------------------------

    private val longHabitMarks = intArrayOf(
        0, 1, 3, 5, 7, 8, 9, 10, 12, 14, 15, 17, 19, 20, 26, 27,
        28, 50, 51, 52, 53, 54, 58, 60, 63, 65, 70, 71, 72, 73, 74, 75, 80,
        81, 83, 89, 90, 91, 95, 102, 103, 108, 109, 120,
    )

    private fun streaks(habit: Habit, original: Map<Long, Long>): List<LoopRules.Streak> {
        val computed = LoopRules.computeEntries(habit, original)
        val range = LoopRules.range(computed, today)
        return LoopRules.streaks(habit, computed, range.first, range.last)
    }

    @Test
    fun bestStreaks() {
        val all = streaks(check, longHabitMarks.associate { day(it) to YES_MANUAL })
        assertEquals(listOf(4, 3, 5, 6), LoopRules.bestStreaks(all, 4).map { it.length })
        assertEquals(listOf(5, 6), LoopRules.bestStreaks(all, 2).map { it.length })
    }

    @Test
    fun bestStreaksWithUnknownDays() {
        val all = streaks(check, mapOf(today to YES_MANUAL, day(5) to NO))
        assertEquals(listOf(1), LoopRules.bestStreaks(all, 5).map { it.length })
    }

    @Test
    fun streaksOfAmounts() {
        val all = streaks(numerical(TargetType.AT_LEAST), mapOf(day(0) to 2000L, day(1) to 2500L, day(2) to 1999L, day(3) to 3000L))
        assertEquals(listOf(LoopRules.Streak(day(1), day(0)), LoopRules.Streak(day(3), day(3))), all)
        val atMost = streaks(numerical(TargetType.AT_MOST), mapOf(day(0) to 0L, day(1) to 5000L))
        assertEquals("a day not logged isn't kept", listOf(LoopRules.Streak(day(0), day(0))), atMost)
    }

    private companion object {
        const val E = 1e-6
    }
}
