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
 * updating a single retained `current` value on every RESUMED / MOVE_TO_FOREGROUND.
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
    private val tracker = ForegroundTracker(::resumes)

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

    private fun resumes(from: Long, to: Long): List<ForegroundTracker.Resume> {
        val events = usm.queryEvents(from, to)
        val event = UsageEvents.Event()
        val out = mutableListOf<ForegroundTracker.Resume>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // Same value: ACTIVITY_RESUMED (API 29) renamed MOVE_TO_FOREGROUND, which minSdk 26 still needs.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                out += ForegroundTracker.Resume(event.packageName, event.className)
            }
        }
        return out
    }
}

/**
 * [UsageStatsDetector]'s reading of the events, pure so it can be tested without a phone:
 * [resumes] gives the apps brought to the front in a span of wall time, oldest first.
 *
 * The first look finds the app in front however long ago it came there. It may well be hours:
 * an OEM battery killer stops xrnrule in the middle of a video, and when it starts again the
 * video app's resume is long past. Looking back a minute only, nothing was in front then, so
 * there was no block screen and no minutes counted until the next switch. The spans grow, so a
 * usual start still reads a minute of events. After that, each look reads what came since the last.
 */
class ForegroundTracker(
    private val resumes: (from: Long, to: Long) -> List<Resume>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Resume(val pkg: String, val className: String?)

    private var cursor: Long? = null
    private var current: String? = null

    @Volatile
    var customTab: Boolean = false
        private set

    @Synchronized
    fun poll(): String? {
        val now = now()
        val since = cursor
        if (since == null) {
            FIRST_LOOK_MS.firstNotNullOfOrNull { span -> resumes((now - span).coerceAtLeast(0L), now).lastOrNull() }?.let(::take)
        } else {
            // Overlap the previous window slightly so an event landing between polls is not lost.
            resumes((since - 1_000L).coerceAtLeast(0L), now).forEach(::take)
        }
        cursor = now
        return current
    }

    private fun take(r: Resume) {
        current = r.pkg
        customTab = r.className?.contains("customtabs", ignoreCase = true) == true
    }

    private companion object {
        /** A minute, then ten, an hour, six, a day, a week. */
        val FIRST_LOOK_MS = listOf(60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L, 604_800_000L)
    }
}
