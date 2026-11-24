package com.xern.xrnrule.app.block

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay

/**
 * Strategy behind the foreground-app signal. Phase A ships [UsageStatsDetector] only (Option C
 * in brainstorm §4.1): it survives Android 17 Advanced Protection Mode and carries no Play
 * policy risk. An `AccessibilityDetector` accelerator can be slotted in later without touching
 * callers.
 */
interface ForegroundAppDetector {
    /** Emits the current foreground package, or null before the first event is ever seen. */
    val foregroundPackage: Flow<String?>
}

/**
 * Tracks the foreground app by consuming `UsageEvents` incrementally from a moving cursor and
 * updating a single retained `current` value from every RESUMED / MOVE_TO_FOREGROUND and PAUSED
 * (see [ForegroundTracker] for why the pauses matter).
 *
 * The retained value is the whole point: an earlier version re-queried a fixed 10 s window each
 * tick and returned `null` whenever that window happened to contain no resume events — which is
 * exactly what happens once you've sat in an app for ten seconds. The overlay treated that
 * `null` as "nothing is blocked" and dismissed itself. Here `current` only ever moves to
 * another package (the launcher's own RESUMED when you press Home, the next app you open); it is
 * never cleared, so the block never evaporates on its own.
 *
 * The cursor and `current` live on the detector, not in one collection of the flow: the service
 * stops collecting while the screen is off (polling then is pure battery drain) and the next
 * collection carries on from where the last one stopped, catching up on everything in between.
 *
 * It polls every [fastPollMs] while [fast] says a block could need covering at once, and only
 * every [slowPollMs] otherwise (no block yet: the site-time log is all that listens).
 */
class UsageStatsDetector(
    context: Context,
    private val fastPollMs: Long = 300L,
    private val slowPollMs: Long = 2_000L,
    private val fast: () -> Boolean = { true },
) : ForegroundAppDetector {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val tracker = ForegroundTracker(::moves)

    override val foregroundPackage: Flow<String?> = flow {
        while (true) {
            emit(tracker.poll())
            delay(if (fast()) fastPollMs else slowPollMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * The app in front is a browser showing a Custom Tab: some other app's login or payment page.
     * It has no address bar, so it's no way around a block, and it mustn't be taken for one (I49).
     */
    val customTab: Boolean get() = tracker.customTab

    private fun moves(from: Long, to: Long): List<ForegroundTracker.Move> {
        val events = usm.queryEvents(from, to)
        val event = UsageEvents.Event()
        val out = mutableListOf<ForegroundTracker.Move>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // Same values: ACTIVITY_RESUMED and ACTIVITY_PAUSED (API 29) renamed MOVE_TO_FOREGROUND
            // and MOVE_TO_BACKGROUND, which minSdk 26 still needs.
            val front = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND -> true
                UsageEvents.Event.ACTIVITY_PAUSED, @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND, ACTIVITY_STOPPED -> false
                else -> continue
            }
            out += ForegroundTracker.Move(event.packageName, event.className, front)
        }
        return out
    }

    private companion object {
        /** UsageEvents.Event.ACTIVITY_STOPPED, API 29; earlier phones never send it. */
        const val ACTIVITY_STOPPED = 23
    }
}

/**
 * [UsageStatsDetector]'s reading of the events, pure so it can be tested without a phone:
 * [moves] gives the activities brought to the front ([Move.front]) or paused or stopped in a span
 * of wall time, oldest first.
 *
 * The app in front is the one most lately resumed that hasn't paused since. A resume alone isn't
 * enough: on HyperOS a swipe to the home screen and straight back (2026-11-24) brought the
 * launcher up for a second without pausing the browser under it, so the browser never resumed
 * again. Taking the last resume, the launcher stayed "in front" for the next 36 minutes of
 * YouTube in Quetta: nothing counted, and the corner clock went back to 15:00 at every answer.
 * With the pauses, the launcher's pause hands the front back to the browser.
 *
 * The first look finds the app in front however long ago it came there. It may well be hours:
 * an OEM battery killer stops xrnrule in the middle of a video, and when it starts again the
 * video app's resume is long past. Looking back a minute only, nothing was in front then, so
 * there was no block screen and no minutes counted until the next switch. The spans grow, so a
 * usual start still reads a minute of events. After that, each look reads what came since the last.
 */
class ForegroundTracker(
    private val moves: (from: Long, to: Long) -> List<Move>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** [pkg]'s activity [className] came to the front ([front]), or paused or stopped. */
    data class Move(val pkg: String, val className: String?, val front: Boolean = true)

    private var cursor: Long? = null
    private var current: String? = null

    /** Activities resumed and not paused since, the latest last. */
    private val resumed = LinkedHashMap<Pair<String, String?>, Unit>()

    @Volatile
    var customTab: Boolean = false
        private set

    @Synchronized
    fun poll(): String? {
        val now = now()
        val since = cursor
        if (since == null) {
            // The shortest span that shows something resumed and still in front; failing that, the
            // last resume any of them shows (an app whose pause came after it in the same span).
            var fallback: Move? = null
            for (span in FIRST_LOOK_MS) {
                resumed.clear()
                val seen = moves((now - span).coerceAtLeast(0L), now)
                seen.forEach(::apply)
                if (resumed.isNotEmpty()) break
                fallback = fallback ?: seen.lastOrNull { it.front }
            }
            if (resumed.isEmpty()) fallback?.let { take(it.pkg, it.className) }
        } else {
            // Overlap the previous window slightly so an event landing between polls is not lost.
            // Applying an event twice changes nothing: each activity's state is its last event's.
            moves((since - 1_000L).coerceAtLeast(0L), now).forEach(::apply)
        }
        cursor = now
        return current
    }

    private fun apply(move: Move) {
        val key = move.pkg to move.className
        resumed.remove(key)
        if (move.front) resumed[key] = Unit
        // With nothing resumed (between one app's pause and the next one's resume) the last app
        // stays in front: `current` only ever moves to another app, it is never cleared.
        resumed.keys.lastOrNull()?.let { (pkg, className) -> take(pkg, className) }
    }

    private fun take(pkg: String, className: String?) {
        current = pkg
        customTab = className?.contains("customtabs", ignoreCase = true) == true
    }

    private companion object {
        /** A minute, then ten, an hour, six, a day, a week. */
        val FIRST_LOOK_MS = listOf(60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L, 604_800_000L)
    }
}
