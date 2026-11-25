package com.xern.xrnrule.app.block

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xern.xrnrule.app.R
import com.xern.xrnrule.app.ui.AppLinks
import com.xern.xrnrule.app.ui.MainActivity
import com.xern.xrnrule.app.ui.formatClockTime
import com.xern.xrnrule.app.ui.formatRemaining
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.ClockKind
import com.xern.xrnrule.domain.World

/**
 * The service's notifications: the ongoing status line that keeps it in the foreground (what's
 * open and until when, a break, a habit ready to cash in), and alerts for things worth knowing
 * right away — an unlock about to end, blocking that has stopped working.
 */
class Notifications(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastStatus: Status? = null

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    /** [endsInMs] counts down to that moment; [sinceMs] counts up from that long ago. */
    data class Status(
        val title: String,
        val text: String,
        val endsInMs: Long? = null,
        val sinceMs: Long? = null,
        /** Tapping it opens this habit's focus timer instead of the app's first screen. */
        val focusHabitId: String? = null,
    )

    /** What the ongoing notification says now; [front] is the app in front when no block screen is over it. */
    fun status(world: World, engine: BlockEngine, clock: Clock, front: String? = null): Status =
        statusOf(world, engine, clock, context.getString(R.string.fgs_notification_title), front)

    companion object {
        const val STATUS_ID = 1
        const val ALERT_ENDING = 10
        const val ALERT_TAMPER = 11
        const val ALERT_BLOCKING_OFF = 12
        const val ALERT_WEBSITES_OFF = 13
        const val ALERT_WORLD_UNREADABLE = 14
        const val ALERT_SYNC_OFF = 15
        const val ALERT_UPDATE = 16
        const val ALERT_AUTOSTART_OFF = 17
        private const val CHANNEL_STATUS = "enforcement"
        private const val CHANNEL_ALERTS = "alerts"

        /**
         * The status line, pure so it's tested without a phone: the focus timer, then what's left on
         * the app in [front] (blocked hours coming, the limit, the row), a break, an open unlock, a
         * habit done, or how many blocks hold. [clockText] says when a moment so far from now is.
         */
        fun statusOf(
            world: World,
            engine: BlockEngine,
            clock: Clock,
            idleTitle: String,
            front: String? = null,
            clockText: (Long) -> String = { formatClockTime(System.currentTimeMillis() + it) },
        ): Status {
            focusStatus(world, engine, clock, clockText)?.let { return it }
            frontStatus(world, engine, clock, front, clockText)?.let { return it }
            val pauseMs = engine.pauseRemainingMs(world, clock)
            if (pauseMs > 0) return Status("On a break", "Blocks come back at ${clockText(pauseMs)}", pauseMs)

            val open = world.sessions.values
                .map { s -> s to s.leftMs(clock) }
                .filter { (_, left) -> left > 0 }
                .minByOrNull { (s, left) -> if (s.selfPaced) Long.MAX_VALUE else left } // a clock that runs comes first
            if (open != null) {
                val (session, left) = open
                val name = world.block(session.blockId)?.name ?: "Unlock"
                // I41: use left doesn't run down by itself, so no countdown and no time it locks at.
                if (session.selfPaced) return Status("$name is open", "${formatRemaining(left)} of use left today")
                return Status("$name is open", "Locks again at ${clockText(left)}", left)
            }

            world.blocks.firstOrNull { it.state == BlockState.EARNED }?.let { block ->
                return Status("${block.name}: habit done", "Open it from the block screen or the app")
            }

            val active = world.blocks.count { engine.isBlocking(world, it, clock) }
            val text = when (active) {
                0 -> if (world.blocks.isEmpty()) "No blocks yet" else "Nothing is blocked right now"
                1 -> "1 block active"
                else -> "$active blocks active"
            }
            return Status(idleTitle, text)
        }

        /** What runs out first on the app in front, when it's something of its own (not a break or an unlock, said below). */
        private fun frontStatus(world: World, engine: BlockEngine, clock: Clock, front: String?, clockText: (Long) -> String): Status? {
            val left = front?.let { engine.timeLeft(world, it, null, clock) }?.takeIf { it.ms > 0 } ?: return null
            val name = left.blockId?.let(world::block)?.name ?: return null
            val text = when (left.kind) {
                ClockKind.HOURS -> "Blocks at ${clockText(left.ms)}"
                ClockKind.LIMIT -> "${formatRemaining(left.ms)} left today"
                ClockKind.ROW -> "${formatRemaining(left.ms)} until a break"
                ClockKind.OVERRIDE -> "Bought time ends at ${clockText(left.ms)}"
                ClockKind.UNLOCK, ClockKind.BREAK -> return null
            }
            return Status(name, text, endsInMs = left.ms)
        }

        /** The focus timer (contract I37), while it runs or is on a break: it's what you're doing. */
        private fun focusStatus(world: World, engine: BlockEngine, clock: Clock, clockText: (Long) -> String): Status? {
            val run = world.focus ?: return null
            val habit = world.habit(run.habitId) ?: return null
            // Amounts are thousandths of a minute (contract §1): x 60 is ms. The goal is the day's share.
            val total = engine.habitAmount(world, habit.id, run.epochDay, clock) * 60
            val left = (habit.unlockUnit ?: habit.goal) * 60 - total
            val breakLeft = run.resumeAtWall?.let { it - clock.nowWallMs() }
            return when {
                run.running && left > 0 -> Status("Focus: ${habit.name}", "Goal at ${clockText(left)}", endsInMs = left, focusHabitId = habit.id)
                run.running -> Status("Focus: ${habit.name}", "Goal reached · today's total", sinceMs = total, focusHabitId = habit.id)
                breakLeft != null -> Status("Focus: ${habit.name} · break", "Back to it at ${clockText(breakLeft)}", endsInMs = breakLeft, focusHabitId = habit.id)
                else -> null // paused: nothing running to show
            }
        }
    }

    fun buildStatus(status: Status): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setSmallIcon(R.drawable.ic_stat_xrnrule)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(status.focusHabitId?.let(::openFocus) ?: openApp())
        status.sinceMs?.let { since ->
            builder.setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - since)
        }
        status.endsInMs?.let { left ->
            // The chronometer runs on the device's own clock, so anchor it there.
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() + left)
        }
        return builder.build()
    }

    /** Re-posts the status notification only when what it says has changed. */
    fun updateStatus(status: Status) {
        // Compared by the moments the clocks count to or from, which stay put while the time passes.
        val now = System.currentTimeMillis()
        val comparable = status.copy(
            endsInMs = status.endsInMs?.let { (now + it) / 10_000 },
            sinceMs = status.sinceMs?.let { (now - it) / 10_000 },
        )
        if (comparable == lastStatus) return
        lastStatus = comparable
        nm.notify(STATUS_ID, buildStatus(status))
    }

    /** [tap] opens the app unless it says otherwise (an update's install confirmation). */
    /** [gone]: takes itself away after this long, for what's only true for a moment. */
    fun alert(id: Int, title: String, text: String, tap: PendingIntent = openApp(), gone: Long = 0) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setTimeoutAfter(gone)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_xrnrule)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        nm.notify(id, notification)
    }

    fun cancel(id: Int) = nm.cancel(id)

    private fun openFocus(habitId: String): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        AppLinks.focusIntent(context, habitId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens Settings → Updates, once past the app lock. */
    fun openUpdates(): PendingIntent = PendingIntent.getActivity(
        context,
        2,
        AppLinks.updatesIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
