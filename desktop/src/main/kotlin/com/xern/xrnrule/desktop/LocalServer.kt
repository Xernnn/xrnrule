package com.xern.xrnrule.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.ExtensionApi
import com.xern.xrnrule.domain.Settings
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The daemon's door on `127.0.0.1`: the local page (what's blocked, unlock, laptop setup), the
 * block page blocked apps and sites land on, and the browser extension's calls.
 *
 * Every write needs an `X-Xrnrule` header. A web page elsewhere can't add one without a CORS
 * preflight, which this server never approves, so only our own page and the extension can
 * change anything; and a request must name this server as its Host (DNS rebinding). (It's a self-control tool, not a lock: anyone at the keyboard can stop it.)
 */
class LocalServer(
    private val config: Config,
    private val store: Store,
    private val foreground: Foreground,
    private val enforcer: Enforcer,
    private val shared: SharedSyncClient,
    private val loop: LoopClient,
) {
    private val guards = ConcurrentHashMap<String, Guard>()

    /** A loosening change waiting out its 60 s (contract §2.3); applied only if confirmed in time. */
    private class Guard(val startedAtMono: Long, val apply: suspend () -> Unit)

    fun start() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 32)
        server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "http").apply { isDaemon = true } }
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                send(ex, 500, """{"error":${json.encodeToString(String.serializer(), e.message ?: e.toString())}}""")
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        // DNS rebinding: a site that points its own name at 127.0.0.1 is same-origin with itself,
        // so it needs no preflight to add the header; but its requests carry its name as Host.
        val host = ex.requestHeaders.getFirst("Host").orEmpty()
        if (host != "127.0.0.1:${config.port}" && host != "localhost:${config.port}") return send(ex, 403, "wrong host")
        val path = ex.requestURI.path
        val query = parseQuery(ex.requestURI.rawQuery)
        if (ex.requestMethod == "OPTIONS") return send(ex, 403, "no") // never approve a cross-origin preflight
        if (ex.requestMethod == "POST" && ex.requestHeaders.getFirst("X-Xrnrule") == null) return send(ex, 403, "missing X-Xrnrule")
        when {
            ex.requestMethod == "GET" && path == "/api/state" -> send(ex, 200, json.encodeToString(UiState.serializer(), view(query["app"], query["site"])))
            ex.requestMethod == "GET" && path == "/api/habits" -> send(ex, 200, habits())
            ex.requestMethod == "POST" && path == "/api/block" -> send(ex, 200, saveBlock(body(ex)))
            ex.requestMethod == "POST" && path == "/api/block/delete" -> send(ex, 200, deleteBlock(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/item" -> send(ex, 200, item(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "GET" && path == "/api/apps" -> send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DesktopApps.App.serializer()), DesktopApps.all()))
            ex.requestMethod == "GET" && path == "/ext/check" -> send(ex, 200, siteVerdict(query["host"]))
            ex.requestMethod == "GET" && path == "/ext/rules" -> {
                send(ex, 200, json.encodeToString(ExtensionApi.SiteRules.serializer(), ExtensionApi.rules(store.world.value, blockPage)))
            }
            ex.requestMethod == "POST" && path == "/ext/heartbeat" -> {
                val beat = json.decodeFromString(Foreground.Heartbeat.serializer(), body(ex))
                foreground.onHeartbeat(beat.copy(fromBlockPage = false))
                send(ex, 200, siteVerdict(beat.host))
            }
            ex.requestMethod == "POST" && path == "/api/heartbeat" -> {
                val beat = json.decodeFromString(Foreground.Heartbeat.serializer(), body(ex))
                foreground.onHeartbeat(beat.copy(fromBlockPage = true))
                send(ex, 200, "{}")
            }
            ex.requestMethod == "POST" && path == "/api/event" -> send(ex, 200, event(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/laptop" -> send(ex, 200, laptopTargets(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/confirm" -> send(ex, 200, confirm(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/override" -> send(ex, 200, override(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/settings" -> send(ex, 200, settings(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/sync" -> {
                shared.request()
                send(ex, 200, "{}")
            }
            ex.requestMethod == "GET" -> static(ex, path)
            else -> send(ex, 404, "not found")
        }
    }

    // ---- extension -----------------------------------------------------------------------

    /** Where the extension sends a blocked tab: this daemon's block page. */
    private val blockPage get() = "${config.localUrl}/block"

    private fun siteVerdict(host: String?): String =
        json.encodeToString(ExtensionApi.SiteVerdict.serializer(), ExtensionApi.verdict(store.engine, store.world.value, host, store.clock, blockPage))

    // ---- buying minutes (contract I34) -----------------------------------------------------

    /** Starts, finishes or drops buying minutes past whatever blocks [app] or [site]. */
    private fun override(body: JsonObject): String {
        val world = store.world.value
        return when (body["op"]?.jsonPrimitive?.contentOrNull) {
            "begin" -> {
                val blockers = store.engine.blockersOf(world, body["app"]?.jsonPrimitive?.contentOrNull, body["site"]?.jsonPrimitive?.contentOrNull?.let(Sites::normalize), store.clock)
                store.engine.overrideRejection(world, blockers, store.clock)?.let { return error(overrideText(it.name)) }
                runBlocking { store.dispatchAndGet(BlockEvent.BeginOverride(blockers.map { it.id }.toSet())) }
                "{}"
            }
            "claim" -> {
                val phrase = body["phrase"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val outcome = runBlocking { store.dispatchOutcome(BlockEvent.ClaimOverride(phrase)) }
                outcome.effects.filterIsInstance<Effect.OverrideRejected>().firstOrNull()?.let { error(overrideText(it.reason.name)) } ?: "{}"
            }
            "cancel" -> {
                runBlocking { store.dispatchAndGet(BlockEvent.CancelOverride) }
                "{}"
            }
            else -> error("unknown change")
        }
    }

    private fun overrideText(reason: String): String = when (reason) {
        "OFF" -> "Buying minutes is turned off."
        "NOT_OVERRIDABLE" -> "This can't be bought off: a habit, a lockdown, a hardcore block or a forced break is in the way."
        "NONE_LEFT" -> "Today's bought minutes for this are used up."
        "NOT_STARTED" -> "Start again."
        "TOO_EARLY" -> "Not yet: wait for the countdown."
        "TOO_LATE" -> "That took too long. Start again."
        "WRONG_PHRASE" -> "That isn't the sentence. Type it exactly."
        else -> reason
    }

    /** The override settings and the corner clock; anything that makes buying minutes easier waits 60 s. */
    private fun settings(body: JsonObject): String {
        val old = store.world.value.settings
        val new = old.copy(
            overridePhrase = body["phrase"]?.jsonPrimitive?.contentOrNull?.trim() ?: old.overridePhrase,
            overrideWaitMs = body["waitSec"]?.jsonPrimitive?.longOrNull?.let { it.coerceIn(0, 3600) * 1000 } ?: old.overrideWaitMs,
            overrideGrantMs = body["grantMin"]?.jsonPrimitive?.longOrNull?.let { it.coerceIn(1, 120) * 60_000 } ?: old.overrideGrantMs,
            overridesPerDay = body["perDay"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 20) ?: old.overridesPerDay,
            showClock = body["showClock"]?.jsonPrimitive?.booleanOrNull ?: old.showClock,
        )
        val apply: suspend () -> Unit = { store.edit { w -> w.copy(settings = settingsFrom(w.settings, new)) } }
        if (!BlockEdits.settingsLoosen(old, new)) {
            runBlocking { apply() }
            return "{}"
        }
        return guarded(apply)
    }

    /** Only the fields this form edits, on top of whatever the settings are by the time it applies. */
    private fun settingsFrom(current: Settings, form: Settings) = current.copy(
        overridePhrase = form.overridePhrase,
        overrideWaitMs = form.overrideWaitMs,
        overrideGrantMs = form.overrideGrantMs,
        overridesPerDay = form.overridesPerDay,
        showClock = form.showClock,
    )

    // ---- actions -------------------------------------------------------------------------

    private fun event(body: JsonObject): String {
        val blockId = body["blockId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val event = when (body["type"]?.jsonPrimitive?.contentOrNull) {
            "redeem" -> BlockEvent.Redeem(blockId)
            "lockdown" -> BlockEvent.StartLockdown(blockId)
            "timer" -> BlockEvent.TimerControl(blockId, body["running"]!!.jsonPrimitive.boolean)
            "toggle" -> BlockEvent.ToggleChecklistItem(body["itemId"]!!.jsonPrimitive.content, body["done"]!!.jsonPrimitive.boolean)
            "reset" -> BlockEvent.UserResetEarned(blockId)
            "endPause" -> BlockEvent.EndPause
            // A break lifts blocks: it waits like any loosening (contract §2.3, I25).
            "pause" -> return guarded { store.dispatchAndGet(BlockEvent.StartPause()) }
            else -> return """{"error":"unknown event"}"""
        }
        runBlocking { store.dispatchAndGet(event) }
        return "{}"
    }

    /** Which laptop apps and sites a block covers. Taking any away waits 60 s; a hardcore block only loosens on the phone. */
    private fun laptopTargets(body: JsonObject): String {
        val id = body["blockId"]!!.jsonPrimitive.content
        val apps = body["desktopApps"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.isNotBlank() }.toSet()
        val sites = body["sites"]!!.jsonArray.mapNotNull { Sites.normalize(it.jsonPrimitive.content) }.toSet()
        val old = store.world.value.block(id) ?: return error("That block is gone.")
        val new = old.copy(targetDesktopApps = apps, targetSites = sites)
        return loosening(old, new) { store.edit { w -> w.copy(blocks = w.blocks.map { b -> if (b.id == id) b.copy(targetDesktopApps = apps, targetSites = sites) else b }) } }
    }

    /**
     * Applies a change to [old] at once, unless it loosens the block ([new] null: always): then it
     * waits out the guard (contract §2.3), and a hardcore block only loosens on the phone.
     */
    private fun loosening(old: Block, new: Block?, apply: suspend () -> Unit): String {
        val loosens = new == null || BlockEdits.loosens(old, new)
        if (!loosens || !BlockEdits.guardNeeded(store.engine, store.world.value, old, store.clock)) {
            runBlocking { apply() }
            return "{}"
        }
        if (old.enforcementTier == EnforcementTier.HARDCORE) return error("This is a hardcore block: loosen it on the phone, with its password.")
        return guarded(apply)
    }

    private fun error(text: String) = """{"error":${json.encodeToString(String.serializer(), text)}}"""

    /** The habits a checklist item can be linked to, from xrnloops-sync. */
    private fun habits(): String = try {
        val list = loop.habits().filter { it["archived"]?.jsonPrimitive?.booleanOrNull != true }
            .map { buildJsonObject { put("uuid", it["uuid"]!!.jsonPrimitive.content); put("name", it["name"]!!.jsonPrimitive.content) } }
        JsonArray(list).toString()
    } catch (e: Exception) {
        error("Can't read your habits: ${describe(e)}")
    }

    /** Creates a block, or edits one ([BlockForms]); an edit that loosens it waits. */
    private fun saveBlock(raw: String): String {
        val form = try {
            json.decodeFromString(BlockForms.Form.serializer(), raw)
        } catch (_: Exception) {
            return error("That form didn't make sense.")
        }
        val world = store.world.value
        if (form.id == null) {
            (BlockForms.create(world, form, store.clock) as? BlockForms.Result.Invalid)?.let { return error(it.reason) }
            // Built again inside the edit, against the world as it is by then.
            runBlocking { store.edit { w -> (BlockForms.create(w, form, store.clock) as? BlockForms.Result.Ok)?.world ?: w } }
            if (form.linked.isNotEmpty()) loop.kick()
            return "{}"
        }
        val old = world.block(form.id) ?: return error("That block is gone.")
        val new = when (val r = BlockForms.edit(world, form)) {
            is BlockForms.Result.Invalid -> return error(r.reason)
            is BlockForms.Result.Ok -> r.block
        }
        return loosening(old, new) { store.edit { w -> (BlockForms.edit(w, form) as? BlockForms.Result.Ok)?.world ?: w } }
    }

    /** Deleting a block always loosens it. */
    private fun deleteBlock(body: JsonObject): String {
        val id = body["blockId"]!!.jsonPrimitive.content
        val old = store.world.value.block(id) ?: return "{}"
        return loosening(old, null) { store.edit { w -> BlockForms.delete(w, id) } }
    }

    /** A checklist item added, linked to a habit (both at once), or removed (that waits). */
    private fun item(body: JsonObject): String {
        val blockId = body["blockId"]!!.jsonPrimitive.content
        val old = store.world.value.block(blockId) ?: return error("That block is gone.")
        if (old.mode != BlockMode.HABIT || old.trigger.type == TriggerType.TIMER) return error("This block doesn't unlock with tasks.")
        return when (body["op"]?.jsonPrimitive?.contentOrNull) {
            "add" -> {
                val text = body["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return error("Write the task first.")
                runBlocking { store.edit { w -> BlockForms.addItem(w, blockId, text) } }
                "{}"
            }
            "link" -> {
                val habit = json.decodeFromJsonElement(BlockForms.LinkedHabit.serializer(), body["habit"]!!)
                runBlocking { store.edit { w -> BlockForms.linkHabit(w, blockId, habit) } }
                loop.kick()
                "{}"
            }
            "remove" -> {
                val itemId = body["itemId"]!!.jsonPrimitive.content
                loosening(old, null) { store.edit { w -> BlockForms.removeItem(w, itemId) } }
            }
            else -> error("unknown change")
        }
    }

    private fun guarded(apply: suspend () -> Unit): String {
        val token = UUID.randomUUID().toString()
        guards[token] = Guard(store.clock.nowMonotonicMs(), apply)
        return """{"token":"$token","waitMs":$GUARD_MS}"""
    }

    private fun confirm(body: JsonObject): String {
        val token = body["token"]?.jsonPrimitive?.contentOrNull ?: return """{"error":"no token"}"""
        if (body["cancel"]?.jsonPrimitive?.booleanOrNull == true) {
            guards.remove(token)
            return "{}"
        }
        val guard = guards[token] ?: return """{"error":"That wait was cancelled. Start again."}"""
        val waited = store.clock.nowMonotonicMs() - guard.startedAtMono
        if (waited < GUARD_MS) return """{"error":"Not yet: ${(GUARD_MS - waited) / 1000} s to go."}"""
        guards.remove(token)
        if (waited > GUARD_MS + 5 * 60_000) return """{"error":"That wait ran out. Start again."}"""
        runBlocking { guard.apply() }
        return "{}"
    }

    // ---- view ----------------------------------------------------------------------------

    @Serializable
    /** [used]: a linked item whose tick already opened the block (I24). */
    data class UiItem(val id: String, val text: String, val done: Boolean, val linked: Boolean, val yesterday: Boolean, val used: Boolean = false)

    @Serializable
    data class UiWindow(val days: List<Int>, val startMin: Int, val endMin: Int)

    @Serializable
    data class UiBlock(
        val id: String,
        val name: String,
        val mode: String,
        val hardcore: Boolean,
        val enabled: Boolean,
        val state: String,
        val blocking: Boolean,
        val phoneApps: Int,
        val sites: List<String>,
        val exceptSites: List<String>,
        val desktopApps: List<String>,
        val trigger: String,
        val requiredMs: Long,
        val requiredItems: Long,
        val combineOr: Boolean,
        val unlockMs: Long,
        val creditExpiresAtWall: Long?,
        val openUntilWall: Long?,
        val lockdownRemainingMs: Long,
        val lockdownMs: Long,
        val allowanceMs: Long,
        val allowanceUsedMs: Long,
        /** The row rule (I32): its length and break, 0 when off; how much of this row is used; a running break. */
        val rowMs: Long,
        val rowBreakMs: Long,
        val rowUsedMs: Long,
        val rowBreakRemainingMs: Long,
        val perDevice: Boolean,
        /** Bought minutes left on this block (I34), and how many more can be bought today. */
        val overrideRemainingMs: Long,
        val overridesLeft: Int,
        val timerMs: Long,
        val timerRunning: Boolean,
        val items: List<UiItem>,
        val redeemRejection: String?,
        val scheduleText: String?,
        val scheduleDays: List<Int>,
        val scheduleStart: Int?,
        val scheduleEnd: Int?,
        val moreWindows: List<UiWindow>,
    )

    /** The block page's app or site: what blocks it, and whether minutes can be bought past it (I34). */
    @Serializable
    data class UiSubject(
        val blocked: Boolean,
        val blockers: List<String>,
        /** Why minutes can't be bought, or null if they can. */
        val overrideRejection: String?,
        /** A wait under way for these blockers: when it's over (wall ms). */
        val overrideReadyAtWall: Long?,
    )

    @Serializable
    data class UiSettings(val phrase: String, val waitMs: Long, val grantMs: Long, val perDay: Int, val showClock: Boolean)

    @Serializable
    data class UiState(
        val nowWall: Long,
        val blocks: List<UiBlock>,
        val pauseRemainingMs: Long,
        val canPause: Boolean,
        val unlockUsedMs: Long,
        val dailyCapMs: Long,
        val sync: SyncStatus?,
        val loop: SyncStatus?,
        val syncConfigured: Boolean,
        val habitsUrl: String?,
        val kwinSeenAgoMs: Long?,
        val extensionMissing: Boolean,
        val blockedApps: List<String>,
        val blockedSites: List<String>,
        val siteExceptions: Map<String, List<String>>,
        val appNames: Map<String, String>,
        val settings: UiSettings,
        val subject: UiSubject?,
    )

    private fun view(app: String? = null, site: String? = null): UiState {
        val world = store.world.value
        val engine = store.engine
        val clock = store.clock
        val blocks = world.blocks.map { b -> uiBlock(world, b) }
        val appIds = world.blocks.flatMap { it.targetDesktopApps }.toSet()
        return UiState(
            nowWall = clock.nowWallMs(),
            blocks = blocks,
            pauseRemainingMs = engine.pauseRemainingMs(world, clock),
            canPause = engine.canPause(world, clock),
            unlockUsedMs = engine.unlockUsedTodayMs(world, clock),
            dailyCapMs = world.settings.dailyCapMs,
            sync = shared.status.value,
            loop = loop.status.value,
            syncConfigured = config.syncConfigured,
            habitsUrl = if (config.syncConfigured) "${config.loopUrl}/" else null,
            kwinSeenAgoMs = enforcer.kwinSeenAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            extensionMissing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() },
            blockedApps = world.appliedShield.blockedPackages.filter { it in appIds },
            blockedSites = world.appliedShield.blockedSites.toList(),
            siteExceptions = world.appliedShield.siteExceptions.mapValues { it.value.sorted() },
            appNames = appIds.associateWith(DesktopApps::name),
            settings = world.settings.let { UiSettings(it.overridePhrase, it.overrideWaitMs, it.overrideGrantMs, it.overridesPerDay, it.showClock) },
            subject = if (app == null && site == null) null else subject(world, app, site?.let(Sites::normalize)),
        )
    }

    private fun subject(world: World, app: String?, site: String?): UiSubject {
        val blockers = store.engine.blockersOf(world, app, site, store.clock)
        val ids = blockers.map { it.id }.toSet()
        val pending = world.pendingOverride?.takeIf { it.blockIds == ids }
        return UiSubject(
            blocked = blockers.isNotEmpty(),
            blockers = ids.toList(),
            overrideRejection = if (blockers.isEmpty()) null else store.engine.overrideRejection(world, blockers, store.clock)?.name,
            overrideReadyAtWall = pending?.let { it.sinceWall + world.settings.overrideWaitMs },
        )
    }

    private fun uiBlock(world: World, b: Block): UiBlock {
        val engine = store.engine
        val clock = store.clock
        val credit = world.credits[b.id]
        val session = world.sessions[b.id]
        val progress = world.progress[b.id]
        val w = b.schedule.activeWindow
        val days = b.schedule.activeDays
        fun windowText(days: Set<Int>, start: Int, end: Int) = "%02d:%02d–%02d:%02d%s".format(
            start / 60, start % 60, end / 60, end % 60,
            if (days.size == 7) "" else ", ${dayNames(days)}",
        )
        val scheduleText = when {
            w == null && days.size == 7 -> null
            w == null -> "On ${dayNames(days)}"
            else -> (listOf(windowText(days, w.startMinuteOfDay, w.endMinuteOfDay)) +
                b.schedule.moreWindows.map { windowText(it.days, it.window.startMinuteOfDay, it.window.endMinuteOfDay) }).joinToString("; ")
        }
        return UiBlock(
            id = b.id,
            name = b.name,
            mode = b.mode.name,
            hardcore = b.enforcementTier == EnforcementTier.HARDCORE,
            enabled = b.enabled,
            state = b.state.name,
            blocking = engine.isBlocking(world, b, clock),
            phoneApps = b.targetPackages.size,
            sites = b.targetSites.sorted(),
            exceptSites = b.exceptSites.sorted(),
            desktopApps = b.targetDesktopApps.sorted(),
            trigger = b.trigger.type.name,
            requiredMs = if (b.trigger.type == TriggerType.CHECKLIST) 0 else b.trigger.requiredAmount,
            requiredItems = if (b.trigger.type == TriggerType.CHECKLIST) b.trigger.requiredAmount else Defaults.ALL_ITEMS,
            combineOr = b.trigger.combineMode.name == "OR",
            unlockMs = b.reward.unlockDurationMs,
            creditExpiresAtWall = credit?.expiresAtWall,
            openUntilWall = session?.let { it.startedAtWall + it.durationMs },
            lockdownRemainingMs = engine.lockdownRemainingMs(world, b.id, clock),
            lockdownMs = b.lockdownMs,
            allowanceMs = b.dailyAllowanceMs,
            allowanceUsedMs = if (b.mode == BlockMode.ALLOWANCE) engine.allowanceUsedMs(world, b, clock) else 0,
            rowMs = b.sessionMs,
            rowBreakMs = b.sessionBreakMs,
            rowUsedMs = engine.rowUsedMs(world, b, clock),
            rowBreakRemainingMs = engine.rowBreakRemainingMs(world, b, clock),
            perDevice = b.perDevice,
            overrideRemainingMs = engine.overrideRemainingMs(world, b.id, clock),
            overridesLeft = engine.overridesLeftToday(world, b.id, clock),
            timerMs = progress?.accumulatedMs ?: 0,
            timerRunning = progress?.timerRunning == true,
            items = world.items(b.id).map { UiItem(it.id, it.text, it.done, it.externalHabitId != null, it.linkedDay.name == "YESTERDAY", it.tickSpent()) },
            redeemRejection = if (b.state == BlockState.EARNED) engine.redeemRejection(world, b.id, clock)?.name else null,
            scheduleText = scheduleText,
            scheduleDays = days.sorted(),
            scheduleStart = w?.startMinuteOfDay,
            scheduleEnd = w?.endMinuteOfDay,
            moreWindows = b.schedule.moreWindows.map { UiWindow(it.days.sorted(), it.window.startMinuteOfDay, it.window.endMinuteOfDay) },
        )
    }

    private fun dayNames(days: Set<Int>) = days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }

    // ---- plumbing ------------------------------------------------------------------------

    private fun static(ex: HttpExchange, path: String) {
        val name = when (path) {
            "/", "/block" -> "index.html"
            else -> path.removePrefix("/")
        }
        if (name.contains("..") || name.contains('/')) return send(ex, 404, "not found")
        val bytes = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: return send(ex, 404, "not found")
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun body(ex: HttpExchange): String = ex.requestBody.use { it.readBytes().decodeToString() }

    private fun send(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", if (text.startsWith("{") || text.startsWith("[")) "application/json" else "text/plain; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    companion object {
        const val GUARD_MS = 60_000L
    }
}
