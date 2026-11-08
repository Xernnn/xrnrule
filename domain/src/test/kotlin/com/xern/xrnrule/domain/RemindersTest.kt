package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RemindersTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun at(date: String, time: String) = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    @Test
    fun daysArePackedAsInLoop() {
        // Loop's WeekdayList + NotificationTray: bit 0 is Saturday, bit 1 Sunday, … bit 6 Friday.
        assertEquals(0, Reminders.dayIndex(DayOfWeek.SATURDAY))
        assertEquals(1, Reminders.dayIndex(DayOfWeek.SUNDAY))
        assertEquals(2, Reminders.dayIndex(DayOfWeek.MONDAY))
        assertEquals(6, Reminders.dayIndex(DayOfWeek.FRIDAY))
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            .sumOf { 1 shl Reminders.dayIndex(it) }
        assertEquals(0b1111100, weekdays)
        val r = Reminder(9, 0, weekdays)
        assertTrue(Reminders.ringsOn(r, LocalDate.parse("2026-11-02"))) // Monday
        assertFalse(Reminders.ringsOn(r, LocalDate.parse("2026-11-07"))) // Saturday
        assertFalse(Reminders.ringsOn(r, LocalDate.parse("2026-11-08"))) // Sunday
    }

    @Test
    fun theNextRingIsLaterTodayOrOnTheNextDayItRingsOn() {
        val h = Habit("h", "Read", reminder = Reminder(23, 0))
        assertEquals(at("2026-11-03", "23:00"), Reminders.next(h, at("2026-11-03", "12:00"), zone))
        // At the minute itself it has rung: the next one is tomorrow's.
        assertEquals(at("2026-11-04", "23:00"), Reminders.next(h, at("2026-11-03", "23:00"), zone))
        val weekdaysOnly = h.copy(reminder = Reminder(9, 30, 0b1111100))
        // Friday evening → Monday morning.
        assertEquals(at("2026-11-09", "09:30"), Reminders.next(weekdaysOnly, at("2026-11-06", "18:00"), zone))
    }

    @Test
    fun noReminderNoDaysOrArchivedNeverRings() {
        val now = at("2026-11-03", "12:00")
        assertNull(Reminders.next(Habit("h", "Read"), now, zone))
        assertNull(Reminders.next(Habit("h", "Read", reminder = Reminder(9, 0, 0)), now, zone))
        assertNull(Reminders.next(Habit("h", "Read", reminder = Reminder(9, 0), archived = true), now, zone))
    }

    @Test
    fun aClockChangeDoesNotLoseTheRing() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 2026-03-29 02:30 doesn't exist there (02:00 → 03:00): it rings just after the gap.
        val h = Habit("h", "Read", reminder = Reminder(2, 30))
        val before = LocalDateTime.parse("2026-03-29T01:00").atZone(berlin).toInstant().toEpochMilli()
        val ring = Reminders.next(h, before, berlin)!!
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), java.time.Instant.ofEpochMilli(ring).atZone(berlin).toLocalDateTime())
    }

    @Test
    fun doneKeepsItQuietAsLoopsCompletedToday() {
        val check = Habit("c", "Read")
        assertTrue(Reminders.done(check, HabitEntry.YES_MANUAL))
        assertTrue(Reminders.done(check, HabitEntry.YES_AUTO))
        assertTrue(Reminders.done(check, HabitEntry.SKIP))
        assertFalse(Reminders.done(check, HabitEntry.NO))
        assertFalse(Reminders.done(check, HabitEntry.UNKNOWN))
        val atLeast = Habit("p", "Push-ups", kind = HabitKind.COUNT, goal = 20_000)
        assertFalse(Reminders.done(atLeast, 19_000))
        assertTrue(Reminders.done(atLeast, 20_000))
        assertFalse(Reminders.done(atLeast, HabitEntry.UNKNOWN))
        val atMost = atLeast.copy(targetType = TargetType.AT_MOST)
        assertFalse(Reminders.done(atMost, 0))
    }
}
