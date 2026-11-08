package com.xern.xrnrule.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loop's habit reminders (xrnloops merge, stage 3): a habit with a [Reminder] rings at its time on
 * its days, unless it is archived or already done. Platform-free; the phone sets the alarms.
 * [Reminder.days] keeps Loop's packing, since it syncs through the habit server to xrnloops and
 * the web app: bit 0 is Saturday, bit 1 Sunday, bit 2 Monday … bit 6 Friday.
 */
object Reminders {
    const val EVERY_DAY = 127

    /** The bit of [Reminder.days] for [date]'s weekday. */
    fun dayBit(date: LocalDate): Int = 1 shl dayIndex(date.dayOfWeek)

    /** Loop's index of a weekday in [Reminder.days]: Saturday 0, Sunday 1 … Friday 6. */
    fun dayIndex(day: java.time.DayOfWeek): Int = (day.value % 7 + 1) % 7

    /** Whether [reminder] rings on [date]. */
    fun ringsOn(reminder: Reminder, date: LocalDate): Boolean = reminder.days and dayBit(date) != 0

    /**
     * The first time after [nowWall] that [habit]'s reminder rings, in [zone]; null without a
     * reminder, with no days left in it, or archived (Loop doesn't remind archived habits).
     */
    fun next(habit: Habit, nowWall: Long, zone: ZoneId): Long? {
        val reminder = habit.reminder ?: return null
        if (habit.archived || reminder.days and EVERY_DAY == 0) return null
        var date = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate()
        repeat(8) {
            // atZone moves a time that a clock change skips to just after the gap.
            val at = date.atTime(reminder.hour.coerceIn(0, 23), reminder.minute.coerceIn(0, 59)).atZone(zone).toInstant().toEpochMilli()
            if (at > nowWall && ringsOn(reminder, date)) return at
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * Loop's "completed today", which keeps a reminder quiet: a check ticked, skipped or filled
     * in ([value] from [BlockEngine.checkValue]); an "at least" amount at its goal ([value] from
     * [BlockEngine.habitAmount], so a running focus timer counts). An "at most" habit always
     * rings, since its day isn't over.
     */
    fun done(habit: Habit, value: Long): Boolean =
        if (habit.numerical) {
            habit.targetType == TargetType.AT_LEAST && value >= habit.goal
        } else {
            value != HabitEntry.NO && value != HabitEntry.UNKNOWN
        }
}
