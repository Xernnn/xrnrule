package com.xern.xrnrule.app.remind

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import com.xern.xrnrule.app.R
import com.xern.xrnrule.app.data.HabitSync
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.loop.LoopHabits
import com.xern.xrnrule.app.loop.LoopStatus
import com.xern.xrnrule.app.ui.AppLinks
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.Reminders
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Habit reminders (xrnloops merge, stage 3), as Loop does them: an alarm per habit at its
 * reminder time on its days ([Reminders]). When it rings: a habit sync round first (at most
 * [SYNC_FIRST_MS]; a tick on the laptop or in the web app then keeps it quiet, as xrnloops does),
 * then a notification unless the habit is done: Done / Not today for a check, Enter for an
 * amount, and Snooze. A tick anywhere takes the notification away. While xrnloops is installed it
 * reminds instead, unless turned on here, so nothing rings twice.
 *
 * Alarms ring on the device clock (they're about the time on the screen); the habit day they
 * tick is the store's.
 */
class HabitReminders(
    private val context: Context,
    private val store: WorldStore,
    private val habits: HabitSync,
    private val loop: LoopHabits,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)

    private val _changed = MutableStateFlow(0)

    /** Moves on when [enabled] is set, for the screens. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    /** Whether xrnrule reminds: as chosen in Settings, else only while xrnloops isn't installed. */
    val enabled: Boolean
        get() = if (prefs.contains(KEY_ON)) prefs.getBoolean(KEY_ON, true) else loop.status() == LoopStatus.NOT_INSTALLED

    /** Whether [enabled] was chosen, rather than following xrnloops. */
    val chosen: Boolean get() = prefs.contains(KEY_ON)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _changed.update { it + 1 }
        scope.launch {
            scheduleAll()
            if (!on) quiet { true }
        }
    }

    /** Whether alarms ring on the minute; without it (Android 12+ can refuse) they may come a little late. */
    fun exact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun start() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            store.world.map { w -> w.habits.map { Triple(it.id, it.reminder, it.archived) } }
                .distinctUntilChanged()
                .collect { scheduleAll() }
        }
        // A tick anywhere (here, a block card, the laptop, the web app) takes its reminder away.
        scope.launch {
            store.world.distinctUntilChanged { a, b -> a.habitEntries === b.habitEntries && a.habits === b.habits }
                .collect { world -> quiet { id -> world.habit(id)?.let { reminding(it) && !isDone(world, it) } != true } }
        }
    }

    /** Sets the next alarm of every habit with a reminder, and cancels the rest. Cheap; call it on any change. */
    @Synchronized
    fun scheduleAll() {
        val world = store.world.value
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val wanted = HashMap<String, Pair<Long, Boolean>>()
        if (enabled) {
            for (habit in world.habits) {
                if (!reminding(habit)) continue
                val snoozed = prefs.getLong(snoozeKey(habit.id), 0).takeIf { it > now }
                if (snoozed != null) wanted[habit.id] = snoozed to true
                else Reminders.next(habit, now, zone)?.let { wanted[habit.id] = it to false }
            }
        }
        val before = prefs.getStringSet(KEY_SET, emptySet()).orEmpty()
        for (id in before - wanted.keys) {
            ringIntent(id, 0, false).let { alarms.cancel(it); it.cancel() }
        }
        for ((id, ring) in wanted) set(ring.first, ringIntent(id, ring.first, ring.second))
        prefs.edit().putStringSet(KEY_SET, HashSet(wanted.keys)).apply()
    }

    /** [ReminderReceiver]'s work: an alarm or a notification button. Calls [finish] when done. */
    fun receive(intent: Intent, finish: () -> Unit) {
        val habitId = intent.getStringExtra(EXTRA_HABIT)
        scope.launch {
            try {
                if (habitId != null) {
                    when (intent.action) {
                        ACTION_RING -> ring(habitId, intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()), intent.getBooleanExtra(EXTRA_SNOOZED, false))
                        ACTION_DONE -> answer(habitId, intent.getLongExtra(EXTRA_DAY, -1), HabitEntry.YES_MANUAL)
                        ACTION_NO -> answer(habitId, intent.getLongExtra(EXTRA_DAY, -1), HabitEntry.NO)
                        ACTION_SNOOZE -> {
                            nm.cancel(TAG, notificationId(habitId))
                            prefs.edit().putLong(snoozeKey(habitId), System.currentTimeMillis() + SNOOZE_MS).apply()
                            scheduleAll()
                        }
                    }
                }
            } finally {
                finish()
            }
        }
    }

    private suspend fun ring(habitId: String, at: Long, snoozed: Boolean) {
        if (snoozed) prefs.edit().remove(snoozeKey(habitId)).apply()
        if (enabled) {
            // Not withTimeout around the round itself: its HTTP calls block, and joining can be cut short.
            val round = scope.launch { habits.syncNow() }
            withTimeoutOrNull(SYNC_FIRST_MS) { round.join() }
            val world = store.world.value
            val habit = world.habit(habitId)
            val reminder = habit?.reminder
            val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
            if (habit != null && reminder != null && reminding(habit) && (snoozed || Reminders.ringsOn(reminder, date)) && !isDone(world, habit)) {
                show(world, habit, at)
            }
        }
        scheduleAll()
    }

    private suspend fun answer(habitId: String, day: Long, value: Long) {
        nm.cancel(TAG, notificationId(habitId))
        if (day < 0) return
        store.dispatchAndGet(BlockEvent.SetHabitEntry(habitId, day, value))
    }

    private fun reminding(habit: Habit) = habit.reminder != null && !habit.archived

    private fun isDone(world: World, habit: Habit): Boolean {
        val day = world.habitDay(store.clock)
        val value = if (habit.numerical) store.engine.habitAmount(world, habit.id, day, store.clock) else store.engine.checkValue(world, habit, day)
        return Reminders.done(habit, value)
    }

    private fun show(world: World, habit: Habit, at: Long) {
        val day = world.habitDay(store.clock)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xrnrule)
            .setContentTitle(habit.name)
            .setContentText(habit.question.ifBlank { if (habit.numerical) "How much today?" else "Have you done it today?" })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openHabit(habit.id, null))
            .addExtras(bundleOf(EXTRA_HABIT to habit.id))
        if (habit.numerical) {
            builder.addAction(0, "Enter", openHabit(habit.id, day))
        } else {
            builder.addAction(0, "Done", button(ACTION_DONE, habit.id, day))
            builder.addAction(0, "Not today", button(ACTION_NO, habit.id, day))
        }
        builder.addAction(0, "Snooze ${SNOOZE_MS / 60_000} min", button(ACTION_SNOOZE, habit.id, day))
        // Some Xiaomi phones throw here over notification sounds (Loop retries without); nothing may escape.
        runCatching { nm.notify(TAG, notificationId(habit.id), builder.build()) }
    }

    /** Takes away the reminders shown for habits [gone] says no longer need one. */
    private fun quiet(gone: (habitId: String) -> Boolean) {
        val shown = runCatching { nm.activeNotifications }.getOrNull() ?: return
        for (n in shown) {
            if (n.tag != TAG) continue
            val id = n.notification.extras.getString(EXTRA_HABIT) ?: continue
            if (gone(id)) nm.cancel(TAG, n.id)
        }
    }

    private fun set(at: Long, intent: PendingIntent) {
        try {
            if (exact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    // One PendingIntent per habit and action: they differ by action and by data.
    private fun receiverIntent(action: String, habitId: String) =
        Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setData(Uri.fromParts("xrnrule-reminder", habitId, null))
            .putExtra(EXTRA_HABIT, habitId)

    private fun ringIntent(habitId: String, at: Long, snoozed: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        receiverIntent(ACTION_RING, habitId).putExtra(EXTRA_AT, at).putExtra(EXTRA_SNOOZED, snoozed),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun button(action: String, habitId: String, day: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        receiverIntent(action, habitId).putExtra(EXTRA_DAY, day),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openHabit(habitId: String, amountDay: Long?): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        AppLinks.habitIntent(context, habitId, amountDay),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationId(habitId: String) = habitId.hashCode()

    private fun snoozeKey(habitId: String) = "snooze.$habitId"

    companion object {
        private const val CHANNEL = "reminders"
        private const val TAG = "reminder"
        private const val KEY_ON = "on"
        private const val KEY_SET = "scheduled"
        private const val SNOOZE_MS = 15 * 60_000L
        private const val SYNC_FIRST_MS = 6_000L
        const val ACTION_RING = "com.xern.xrnrule.app.reminder.RING"
        const val ACTION_DONE = "com.xern.xrnrule.app.reminder.DONE"
        const val ACTION_NO = "com.xern.xrnrule.app.reminder.NO"
        const val ACTION_SNOOZE = "com.xern.xrnrule.app.reminder.SNOOZE"
        private const val EXTRA_HABIT = "habit"
        private const val EXTRA_AT = "at"
        private const val EXTRA_SNOOZED = "snoozed"
        private const val EXTRA_DAY = "day"
    }
}

/** An alarm or a reminder's button; [HabitReminders] does the work. Not exported: only our own PendingIntents reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? com.xern.xrnrule.app.XrnruleApp ?: return
        val pending = goAsync()
        app.container.reminders.receive(intent) { pending.finish() }
    }
}
