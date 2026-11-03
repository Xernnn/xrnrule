/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 * Copyright (C) 2026 the xrnrule authors
 *
 * This file is part of xrnrule, and is ported from Loop Habit Tracker (uhabits-core's EntryList,
 * ScoreList, StreakList and Score).
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

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loop Habit Tracker's rules: the ticks Loop fills in by itself for a check that isn't daily
 * ("3 times a week"), the habit score, and streaks. Days are epoch days (habit days, contract
 * §1) and values are [HabitEntry]'s. `LoopRulesTest` runs Loop's own test cases against it.
 */
object LoopRules {

    /** A stretch of days a check's goal covers, newest first in lists, as in Loop. */
    data class Interval(val begin: Long, val center: Long, val end: Long)

    data class Streak(val start: Long, val end: Long) {
        val length: Int get() = (end - start + 1).toInt()
    }

    /**
     * Loop's "computed entries": for a check that isn't daily, days covered by its goal get
     * [HabitEntry.YES_AUTO]; an amount habit's entries are its own. [original] maps days to
     * values; days missing from the result are unknown.
     */
    fun computeEntries(habit: Habit, original: Map<Long, Long>): Map<Long, Long> {
        if (habit.numerical) return original
        val (num, den) = frequency(habit)
        val known = original.entries.sortedByDescending { it.key }.map { it.key to it.value }
        val intervals = buildIntervals(num, den, known)
        snapIntervalsTogether(intervals)
        return buildEntriesFromIntervals(known, intervals)
            .filter { it.second != HabitEntry.UNKNOWN }
            .toMap()
    }

    /** Loop's [Frequency] normalises "n times in n days" to daily. */
    private fun frequency(habit: Habit): Pair<Int, Int> {
        val num = habit.freqNum.coerceAtLeast(1)
        val den = habit.freqDen.coerceAtLeast(1)
        return if (num == den) 1 to 1 else num to den
    }

    /** [original] newest first, as `(day, value)`. */
    fun buildIntervals(num: Int, den: Int, original: List<Pair<Long, Long>>): ArrayList<Interval> {
        val filtered = original.filter { it.second == HabitEntry.YES_MANUAL }
        val intervals = arrayListOf<Interval>()
        for (i in num - 1 until filtered.size) {
            val begin = filtered[i].first
            val center = filtered[i - num + 1].first
            var size = den.toLong()
            if (den == 30 || den == 31) {
                val date = LocalDate.ofEpochDay(begin)
                size = if (date.dayOfMonth == date.lengthOfMonth()) {
                    date.plusDays(1).lengthOfMonth().toLong()
                } else {
                    date.lengthOfMonth().toLong()
                }
            }
            if (center - begin < size) intervals.add(Interval(begin, center, begin + size - 1))
        }
        return intervals
    }

    /** Slides intervals (newest first) back into the past, so gaps close and streaks grow. */
    fun snapIntervalsTogether(intervals: ArrayList<Interval>) {
        for (i in 1 until intervals.size) {
            val curr = intervals[i]
            val next = intervals[i - 1]
            val gapNextToCurrent = curr.end - next.begin
            val gapCenterToEnd = curr.end - curr.center
            if (gapNextToCurrent >= 0) {
                val shift = min(gapCenterToEnd, gapNextToCurrent + 1)
                intervals[i] = Interval(curr.begin - shift, curr.center, curr.end - shift)
            }
        }
    }

    /**
     * Days inside an interval become [HabitEntry.YES_AUTO] unless [original] has a manual tick or
     * a skip there; days outside every interval keep [original]'s value, or are unknown. Newest
     * first, every day from the oldest to the newest one known.
     */
    fun buildEntriesFromIntervals(original: List<Pair<Long, Long>>, intervals: List<Interval>): List<Pair<Long, Long>> {
        if (original.isEmpty()) return emptyList()
        var from = original.minOf { it.first }
        var to = original.maxOf { it.first }
        for (interval in intervals) {
            if (interval.begin < from) from = interval.begin
            if (interval.end > to) to = interval.end
        }
        val result = LongArray((to - from + 1).toInt()) { HabitEntry.UNKNOWN }
        for (interval in intervals) {
            var day = interval.end
            while (day >= interval.begin) {
                result[(to - day).toInt()] = HabitEntry.YES_AUTO
                day--
            }
        }
        for ((day, value) in original) {
            val offset = (to - day).toInt()
            result[offset] = if (result[offset] == HabitEntry.UNKNOWN || value == HabitEntry.SKIP || value == HabitEntry.YES_MANUAL) {
                value
            } else {
                HabitEntry.YES_AUTO
            }
        }
        return result.indices.map { offset -> (to - offset) to result[offset] }
    }

    /** One step of Loop's score: an exponential average whose speed follows the frequency. */
    fun score(frequency: Double, previous: Double, checkmark: Double): Double {
        val multiplier = 0.5.pow(sqrt(frequency) / 13.0)
        return previous * multiplier + checkmark * (1 - multiplier)
    }

    /**
     * Loop's score for every day from [from] to [to] (index 0 is [from]), from [computed]
     * entries ([computeEntries]).
     */
    fun scores(habit: Habit, computed: Map<Long, Long>, from: Long, to: Long): DoubleArray {
        if (from > to) return DoubleArray(0)
        val (num0, den0) = frequency(habit)
        var numerator = num0
        var denominator = den0
        val freq = numerator.toDouble() / denominator
        val values = LongArray((to - from + 1).toInt()) { i -> computed[to - i] ?: HabitEntry.UNKNOWN }
        val isAtMost = habit.targetType == TargetType.AT_MOST
        val targetValue = habit.goal / 1000.0
        // For checks that aren't daily, Loop doubles both to smooth out irregular schedules.
        if (!habit.numerical && freq < 1.0) {
            numerator *= 2
            denominator *= 2
        }
        var rollingSum = 0.0
        var previous = if (habit.numerical && isAtMost) 1.0 else 0.0
        val out = DoubleArray(values.size)
        for (i in values.indices) {
            val offset = values.size - i - 1
            if (habit.numerical) {
                rollingSum += max(0L, values[offset])
                if (offset + denominator < values.size) rollingSum -= max(0L, values[offset + denominator])
                val normalized = rollingSum / 1000
                if (values[offset] != HabitEntry.SKIP) {
                    val completed = if (!isAtMost) {
                        if (targetValue > 0) min(1.0, normalized / targetValue) else 1.0
                    } else {
                        if (targetValue > 0) (1 - ((normalized - targetValue) / targetValue)).coerceIn(0.0, 1.0)
                        else if (normalized > 0) 0.0 else 1.0
                    }
                    previous = score(freq, previous, completed)
                }
            } else {
                if (values[offset] == HabitEntry.YES_MANUAL) rollingSum += 1.0
                if (offset + denominator < values.size && values[offset + denominator] == HabitEntry.YES_MANUAL) rollingSum -= 1.0
                if (values[offset] != HabitEntry.SKIP) {
                    previous = score(freq, previous, min(1.0, rollingSum / numerator))
                }
            }
            out[i] = previous
        }
        return out
    }

    /** Whether [value] counts as the habit done that day, as Loop's streaks and "completed" do. */
    fun succeeded(habit: Habit, value: Long): Boolean =
        if (habit.numerical) {
            when (habit.targetType) {
                TargetType.AT_LEAST -> value / 1000.0 >= habit.goal / 1000.0
                TargetType.AT_MOST -> value != HabitEntry.UNKNOWN && value / 1000.0 <= habit.goal / 1000.0
            }
        } else {
            value > 0
        }

    /** Every streak from [from] to [to] in [computed] entries, newest first. */
    fun streaks(habit: Habit, computed: Map<Long, Long>, from: Long, to: Long): List<Streak> {
        val days = (to downTo from).filter { succeeded(habit, computed[it] ?: HabitEntry.UNKNOWN) }
        if (days.isEmpty()) return emptyList()
        val out = mutableListOf<Streak>()
        var begin = days[0]
        var end = days[0]
        for (i in 1 until days.size) {
            val day = days[i]
            if (day == begin - 1) {
                begin = day
            } else {
                out += Streak(begin, end)
                begin = day
                end = day
            }
        }
        out += Streak(begin, end)
        return out
    }

    /** Loop's "best streaks": the [limit] longest (the newer on a tie), newest first. */
    fun bestStreaks(streaks: List<Streak>, limit: Int): List<Streak> =
        streaks.sortedWith(compareByDescending<Streak> { it.length }.thenByDescending { it.end })
            .take(limit)
            .sortedByDescending { it.end }

    /**
     * Loop's range for a habit's scores and streaks: from its oldest computed entry (or [today])
     * to 30 days past [today].
     */
    fun range(computed: Map<Long, Long>, today: Long): LongRange {
        val to = today + 30
        val from = (computed.keys.minOrNull() ?: today).coerceAtMost(to)
        return from..to
    }
}
