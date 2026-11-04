package com.xern.xrnrule.app.data

import android.content.Context
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.hardcore.createHardcoreGuard
import com.xern.xrnrule.app.loop.LoopHabits
import com.xern.xrnrule.app.update.AppUpdates
import com.xern.xrnrule.app.update.createAppUpdates
import com.xern.xrnrule.app.web.ExtensionServer
import com.xern.xrnrule.app.web.ExtensionWebsiteMonitor
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.World
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. Created once in [com.xern.xrnrule.app.XrnruleApp]. */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wall time with hand-set clock changes taken back out; see [ClockGuard]. */
    val clockGuard = ClockGuard(context, AndroidClock())
    val clock: Clock = clockGuard

    val engine = BlockEngine()
    private val worldFile = FilePersistPort(File(context.filesDir, "world.json"))
    val persist = ThrottledPersist(worldFile, clock)
    val telemetry = LocalTelemetry(File(context.filesDir, "telemetry.jsonl"))

    /** Set when world.json couldn't be read at start; the blocks are in this copy. */
    val unreadableWorldCopy: File? get() = worldFile.unreadableCopy

    /** The single source of truth for [World]. UI observes it, the service drives it. */
    val store = WorldStore(engine, persist, telemetry, clock, appScope)

    /** Anti-bypass (brainstorm §4.5) — real in the `sideload` flavor, a no-op in `play`. */
    val hardcoreGuard: HardcoreGuard = createHardcoreGuard(context)

    /** Websites come from the xrnrule extension in the phone's browser, through [extensionServer]. */
    val websites = ExtensionWebsiteMonitor(context)
    val extensionServer = ExtensionServer(store, websites, context.assets, appScope)

    val siteUsage = SiteUsageLog(File(context.filesDir, "site_usage.json"), clock)

    /** Websites' own icons, asked of each site and kept on the phone. */
    val siteIcons = SiteIconCache(context, appScope)

    /** Unlocks, breaks and blocks shared with the laptop through the home server (contract §2.4). */
    val shared = SharedSync(context, store, telemetry, appScope)

    /** Habits and their entries, shared with xrnloops, the web app and the laptop (contract §2.5). */
    val habits = HabitSync(context, store, shared.settings, appScope)

    /** xrnloops, the habit tracker (our Loop fork, `../xrnloops`): its ticks reach us through the habit server. */
    val loop = LoopHabits(context, habits, appScope)

    /** New versions of the app from the same home server — real in `sideload`, a no-op in `play`. */
    val updates: AppUpdates = createAppUpdates(context, shared.settings, appScope)

    /** The fingerprint step in front of the app's screens. */
    val appLock = AppLock(context)

    init {
        // This install's name in the shared state (whose allowance use is whose, §2.4).
        if (store.world.value.deviceId.isEmpty()) {
            val id = "phone-" + UUID.randomUUID().toString().take(8)
            store.edit { it.copy(deviceId = id) }
        }
        // Which blocks hold here (I42): those for the phone, and those for both.
        if (store.world.value.deviceKind != DeviceKind.PHONE) store.edit { it.copy(deviceKind = DeviceKind.PHONE) }
        shared.start()
        habits.start()
        extensionServer.start()
        updates.start()
    }
}
