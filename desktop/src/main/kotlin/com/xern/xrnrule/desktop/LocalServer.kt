package com.xern.xrnrule.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.startOfLocalDayMs
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.ExtensionApi
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.LinkedDay
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.Settings
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.appliesHere
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val LOCK_END = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

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
    private val habitClient: HabitClient,
    private val siteIcons: SiteIconCache,
    private val telemetry: FileTelemetry,
    /** Both devices' history on the server, with sync on (the Insights page). */
    remote: RemoteHistory? = shared.takeIf { config.syncConfigured },
) {
    private val guards = ConcurrentHashMap<String, Guard>()
    private val history = History(telemetry, remote, store.clock)

    /**
     * A loosening change waiting out its 60 s (contract §2.3); applied only if confirmed in time,
     * and only if [refusal], asked then, finds nothing in the way.
     */
    private class Guard(val startedAtMono: Long, val refusal: () -> String?, val apply: suspend () -> Unit)

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
        // Every write needs X-Xrnrule, and so does the rule list: asking for it says the extension
        // is there (I49), which a page polling it would otherwise say for a browser with it off.
        val needsHeader = ex.requestMethod == "POST" || path == "/ext/rules"
        if (needsHeader && ex.requestHeaders.getFirst("X-Xrnrule") == null) return send(ex, 403, "missing X-Xrnrule")
        when {
            ex.requestMethod == "GET" && path == "/api/state" -> send(ex, 200, json.encodeToString(UiState.serializer(), view(query["app"], query["site"])))
            ex.requestMethod == "GET" && path == "/api/habits" -> send(ex, 200, habits())
            ex.requestMethod == "GET" && path == "/api/history" ->
                send(ex, 200, history.json(store.world.value, query["days"]?.toIntOrNull() ?: History.KEEP_DAYS))
            ex.requestMethod == "GET" && path == "/api/site-icon" -> siteIcon(ex, query["host"].orEmpty())
            ex.requestMethod == "POST" && path == "/api/block" -> send(ex, 200, saveBlock(body(ex)))
            ex.requestMethod == "POST" && path == "/api/block/delete" -> send(ex, 200, deleteBlock(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "POST" && path == "/api/item" -> send(ex, 200, item(json.parseToJsonElement(body(ex)).jsonObject))
            ex.requestMethod == "GET" && path == "/api/apps" -> send(ex, 200, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DesktopApps.App.serializer()), DesktopApps.all()))
            ex.requestMethod == "GET" && path == "/ext/check" -> send(ex, 200, siteVerdict(query["host"]))
            ex.requestMethod == "GET" && path == "/ext/rules" -> {
                foreground.heardExtension(query["focused"]?.let { it == "1" })
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
                val blockers = store.engine.blockersOf(world, body["app"]?.jsonPrimitive?.contentOrNull, body["site"]?.jsonPrimitive?.contentOrNull?.let(Sites::normalizeTarget), store.clock)
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
        "NOT_OVERRIDABLE" -> "This can't be bought off: a habit, a lockdown, a hardcore or locked block, or a forced break is in the way."
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
            blockOtherBrowsers = body["blockOtherBrowsers"]?.jsonPrimitive?.booleanOrNull ?: old.blockOtherBrowsers,
        )
        val apply: suspend () -> Unit = { store.edit { w -> w.copy(settings = settingsFrom(w.settings, new)) } }
        if (!BlockEdits.settingsLoosen(old, new)) {
            runBlocking { apply() }
            return "{}"
        }
        return guarded(apply = apply)
    }

    /** Only the fields this form edits, on top of whatever the settings are by the time it applies. */
    private fun settingsFrom(current: Settings, form: Settings) = current.copy(
        overridePhrase = form.overridePhrase,
        overrideWaitMs = form.overrideWaitMs,
        overrideGrantMs = form.overrideGrantMs,
        overridesPerDay = form.overridesPerDay,
        showClock = form.showClock,
        blockOtherBrowsers = form.blockOtherBrowsers,
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
            // Shared habits (I35, I37): logged here, they count on the phone too.
            "log" -> BlockEvent.LogHabit(body["habitId"]!!.jsonPrimitive.content, body["amount"]!!.jsonPrimitive.long)
            "set" -> BlockEvent.SetHabitEntry(
                body["habitId"]!!.jsonPrimitive.content,
                body["day"]!!.jsonPrimitive.long,
                body["value"]!!.jsonPrimitive.long,
            )
            "focus" -> BlockEvent.StartFocus(body["habitId"]!!.jsonPrimitive.content)
            "focusPause" -> BlockEvent.PauseFocus()
            "focusEnd" -> BlockEvent.EndFocus
            "endPause" -> BlockEvent.EndPause
            // A break lifts blocks: it waits like any loosening (contract §2.3, I25).
            "pause" -> return guarded { store.dispatchAndGet(BlockEvent.StartPause()) }
            else -> return """{"error":"unknown event"}"""
        }
        runBlocking { store.dispatchAndGet(event) }
        return "{}"
    }

    /**
     * Which laptop apps and sites a block covers. Taking any away waits 60 s, and so does giving a
     * habit block one another habit block holds (I6); a hardcore block only loosens on the phone.
     */
    private fun laptopTargets(body: JsonObject): String {
        val id = body["blockId"]!!.jsonPrimitive.content
        val apps = body["desktopApps"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.isNotBlank() }.toSet()
        val sites = body["sites"]!!.jsonArray.mapNotNull { Sites.normalizeTarget(it.jsonPrimitive.content) }.toSet()
        val old = store.world.value.block(id) ?: return error("That block is gone.")
        val new = old.copy(targetDesktopApps = apps, targetSites = sites)
        // What this change takes away and adds, applied to the block as it is by then: an app or
        // site the phone added during the wait stays, rather than going without a wait of its own.
        val (appsGone, appsAdded) = (old.targetDesktopApps - apps) to (apps - old.targetDesktopApps)
        val (sitesGone, sitesAdded) = (old.targetSites - sites) to (sites - old.targetSites)
        return loosening(old, new) {
            store.edit { w ->
                w.copy(blocks = w.blocks.map { b ->
                    if (b.id != id) b else b.copy(targetDesktopApps = b.targetDesktopApps - appsGone + appsAdded, targetSites = b.targetSites - sitesGone + sitesAdded)
                })
            }
        }
    }

    /**
     * Applies a change to [old] at once, unless it loosens the block ([new] null: always), or its
     * unlock would open more of what other habit blocks block ([BlockEdits.opens], I6): then it
     * waits out the guard of each (contract §2.3), and a hardcore one only loosens on the phone.
     */
    private fun loosening(old: Block, new: Block?, apply: suspend () -> Unit): String {
        val loosens = new == null || BlockEdits.loosens(old, new)
        val opened = new?.let { BlockEdits.opens(store.world.value, old, it, loosens) }.orEmpty()
        return guardedBy(listOfNotNull(old.takeIf { loosens }), opened, apply)
    }

    /**
     * Applies a change at once unless it makes [loosened] easier to get past, or opens what
     * [opened] block (I6): then the guard of each that has one (§2.3). A locked one refuses it
     * (I39), a hardcore one sends it to the phone, which has the password; the rest wait 60 s.
     */
    private fun guardedBy(loosened: List<Block>, opened: List<Block>, apply: suspend () -> Unit): String {
        val world = store.world.value
        fun held(blocks: List<Block>) = blocks.filter { BlockEdits.guardNeeded(store.engine, world, it, store.clock) }
        val own = held(loosened)
        val others = held(opened).filter { o -> own.none { it.id == o.id } }
        if (own.isEmpty() && others.isEmpty()) {
            runBlocking { apply() }
            return "{}"
        }
        own.firstNotNullOfOrNull { lockedError(it) }?.let { return it }
        others.firstNotNullOfOrNull { lockedError(it, opened = true) }?.let { return it }
        own.firstNotNullOfOrNull { hardcoreError(it) }?.let { return it }
        others.firstNotNullOfOrNull { hardcoreError(it, opened = true) }?.let { return it }
        val label = others.takeIf { it.isNotEmpty() }?.let { o -> "Saving (its unlock would open what ${o.joinToString(" and ") { it.name }} blocks too)" }
        // As the blocks are at confirming: a lock set on the phone during the wait still holds
        // (I39), and a block made hardcore there only loosens on the phone, with its password.
        val refusal = {
            own.firstNotNullOfOrNull { lockedError(it) ?: hardcoreError(it) }
                ?: others.firstNotNullOfOrNull { lockedError(it, opened = true) ?: hardcoreError(it, opened = true) }
        }
        return guarded(label, refusal, apply)
    }

    /** A hardcore block, as it is now, loosens only on the phone, which has the password. */
    private fun hardcoreError(old: Block, opened: Boolean = false): String? {
        val current = store.world.value.block(old.id) ?: old
        if (current.enforcementTier != EnforcementTier.HARDCORE) return null
        return error(
            if (opened) "Its unlock would open what ${current.name} blocks, a hardcore block: do this on the phone, with its password."
            else "This is a hardcore block: loosen it on the phone, with its password.",
        )
    }

    /**
     * I39: a locked block refuses every loosening, and every change whose unlock would open what it
     * blocks ([opened], I6); only the phone has emergency unlocks (I40).
     */
    private fun lockedError(old: Block, opened: Boolean = false): String? {
        val current = store.world.value.block(old.id) ?: old
        if (!BlockEdits.locked(current, store.clock.nowWallMs())) return null
        val until = LOCK_END.format(Instant.ofEpochMilli(current.lock!!.untilWall).atZone(store.clock.zone()))
        val what = if (opened) "Its unlock would open what ${current.name} blocks, and that is locked" else "This block is locked"
        return error("$what until $until: nothing can make it easier before then, except an emergency unlock on the phone.")
    }

    private fun error(text: String) = """{"error":${json.encodeToString(String.serializer(), text)}}"""

    /** The habits a block can ask for (contract §2.5): not archived, and able to open one (I36). */
    private fun habits(): String {
        val list = store.world.value.habits.filter { !it.archived && it.unlockUnit != null }.sortedBy { it.position }
            .map { buildJsonObject { put("uuid", it.id); put("name", it.name) } }
        return JsonArray(list).toString()
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
            val made = when (val r = BlockForms.create(world, form, store.clock)) {
                is BlockForms.Result.Invalid -> return error(r.reason)
                is BlockForms.Result.Ok -> r.block
            }
            // I6: a new habit block on another one's targets is a way past that one, so it asks that one's guard.
            // Built again inside the edit, against the world as it is by then.
            return guardedBy(emptyList(), BlockEdits.opens(world, null, made)) {
                store.edit { w -> (BlockForms.create(w, form, store.clock) as? BlockForms.Result.Ok)?.world ?: w }
            }
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
                "{}"
            }
            "remove" -> {
                val itemId = body["itemId"]!!.jsonPrimitive.content
                // Its own task only: this block's wait, lock and tier are the ones checked below,
                // and those of the habit blocks it shares a target with, made easier with it (I6).
                if (store.world.value.items(blockId).none { it.id == itemId }) return error("That task is gone.")
                val opened = BlockEdits.opens(store.world.value, old, old, loosened = true)
                guardedBy(listOf(old), opened) { store.edit { w -> BlockForms.removeItem(w, blockId, itemId) } }
            }
            else -> error("unknown change")
        }
    }

    /** Starts a 60 s wait for [apply]; [label] says what it is for, if the page's own words don't. */
    private fun guarded(label: String? = null, refusal: () -> String? = { null }, apply: suspend () -> Unit): String {
        val now = store.clock.nowMonotonicMs()
        // A wait nobody confirmed or cancelled (the page went before it could say) goes once it
        // can't be confirmed any more, rather than staying for as long as the daemon runs.
        guards.values.removeIf { now - it.startedAtMono > GUARD_MS + CONFIRM_MS }
        val token = UUID.randomUUID().toString()
        guards[token] = Guard(now, refusal, apply)
        val said = label?.let { ""","label":${json.encodeToString(String.serializer(), it)}""" }.orEmpty()
        return """{"token":"$token","waitMs":$GUARD_MS$said}"""
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
        if (waited > GUARD_MS + CONFIRM_MS) return """{"error":"That wait ran out. Start again."}"""
        guard.refusal()?.let { return it }
        runBlocking { guard.apply() }
        return "{}"
    }

    // ---- view ----------------------------------------------------------------------------

    @Serializable
    /** [used]: a linked item whose tick already opened the block (I24). */
    data class UiItem(
        val id: String,
        val text: String,
        val done: Boolean,
        /** Set on a habit item (I23, I36): the habit and where it stands. */
        val habit: UiHabit? = null,
        /** An NFC tag item (I43): only the phone, reading the tag, ticks it. */
        val tag: Boolean = false,
    )

    /**
     * A habit as a block's item shows it (I23, I36). Amounts are thousandths of the unit (minutes
     * for a timer); a check's [amount] and [unlock] are 1. [amount] counts toward this block's
     * next unlock, [spent] is what earlier ones used on [day], the habit day the item counts, and
     * [dayValue] is that day's entry. [unlock] is null for an "at most" habit, which never opens.
     */
    @Serializable
    data class UiHabit(
        val id: String,
        val name: String,
        val kind: String,
        val unit: String,
        val unlock: Long?,
        val amount: Long,
        val spent: Long,
        val day: Long,
        val dayValue: Long,
        val yesterday: Boolean,
        val focusing: Boolean,
        val onBreak: Boolean,
    )

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
        /** [sites] as the page lists them: `redd.it` folded into `reddit.com` ([Sites.shown]). */
        val shownSites: List<String>,
        val exceptSites: List<String>,
        val desktopApps: List<String>,
        val trigger: String,
        val requiredMs: Long,
        val requiredItems: Long,
        val combineOr: Boolean,
        val unlockMs: Long,
        val creditExpiresAtWall: Long?,
        /** A timed unlock's end; null for a self-paced one, which has [useLeftMs] instead (I41). */
        val openUntilWall: Long?,
        /** I42: BOTH, PHONE or LAPTOP; [here] false for a phone-only block, whose status is the phone's. */
        val devices: String,
        val here: Boolean,
        val selfPaced: Boolean,
        val useLeftMs: Long?,
        val lockdownRemainingMs: Long,
        val lockdownMs: Long,
        val allowanceMs: Long,
        val allowanceUsedMs: Long,
        /** The row rule (I32): its length and break, 0 when off; how much of this row is used; a running break. */
        val rowMs: Long,
        val rowBreakMs: Long,
        val rowUsedMs: Long,
        val rowBreakRemainingMs: Long,
        val opensPerDay: Int,
        val opensUsed: Int,
        val openWaitS: Int,
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
        /** I44: what's keeping it blocked (a [BlockReason]'s name), null when it isn't blocking. */
        val reason: String?,
        val headline: UiHeadline,
        val hasLimit: Boolean,
        val limitFirst: Boolean,
        val habitFirst: Boolean,
        /** Hours blocked outright (I44); null days when it has none, a null start when they are whole days. */
        val hoursText: String?,
        val hoursDays: List<Int>?,
        val hoursStart: Int?,
        val hoursEnd: Int?,
        val hoursMore: List<UiWindow>,
        /** I39: when its lock ends, while it is locked. */
        val lockedUntilWall: Long? = null,
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
        /** What the block page leads with: when it's back, and why it isn't now. */
        val back: String? = null,
        val why: String? = null,
        /** I53: it's only pausing before it opens by itself. */
        val opening: Boolean = false,
    )

    @Serializable
    data class UiSettings(
        val phrase: String,
        val waitMs: Long,
        val grantMs: Long,
        val perDay: Int,
        val showClock: Boolean,
        val blockOtherBrowsers: Boolean,
    )

    @Serializable
    data class UiHeadline(val text: String, val tone: String)

    @Serializable
    data class UiDay(val habitsDone: Int, val timesBlocked: Int, val unlockMs: Long)

    @Serializable
    data class UiState(
        val nowWall: Long,
        val blocks: List<UiBlock>,
        val pauseRemainingMs: Long,
        val canPause: Boolean,
        /** Whether a break would lift anything: not with every soft block locked (I50). */
        val breakLifts: Boolean,
        /** Set when the phone has stopped reaching the server. */
        val phoneQuiet: String?,
        val unlockUsedMs: Long,
        val dailyCapMs: Long,
        val sync: SyncStatus?,
        val loop: SyncStatus?,
        val syncConfigured: Boolean,
        val habitsUrl: String?,
        val kwinSeenAgoMs: Long?,
        /** What reports the windows: "kwin" or "hyprland". */
        val windowSystem: String = "kwin",
        val extensionMissing: Boolean,
        /** The extension's version, and the one this daemon was built with when it's older. */
        val summary: String? = null,
        val today: UiDay,
        /** When the home server last answered, 304s and long polls included. */
        val lastContactWall: Long? = null,
        val extensionVersion: String?,
        val extensionNoIncognito: Boolean = false,
        val extensionLatest: String?,
        val blockedApps: List<String>,
        val blockedSites: List<String>,
        val siteExceptions: Map<String, List<String>>,
        val appNames: Map<String, String>,
        val settings: UiSettings,
        /** A clock or time zone set by hand, which blocks leave out (ClockGuard, I54). */
        val clockNote: String? = null,
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
            breakLifts = world.blocks.any { engine.pausable(it, clock) },
            phoneQuiet = shared.phoneQuietMs(System.currentTimeMillis())?.let(PhoneWatch::text),
            unlockUsedMs = engine.unlockUsedTodayMs(world, clock),
            dailyCapMs = world.settings.dailyCapMs,
            sync = shared.status.value,
            loop = habitClient.status.value,
            syncConfigured = config.syncConfigured,
            habitsUrl = if (config.syncConfigured) "${config.loopUrl}/" else null,
            kwinSeenAgoMs = enforcer.kwinSeenAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            windowSystem = enforcer.windowSystem.name,
            extensionMissing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() },
            summary = BlockTexts.summary(engine, world, clock),
            today = today(world),
            lastContactWall = shared.lastContactWall,
            extensionVersion = foreground.extensionVersion,
            extensionNoIncognito = foreground.incognitoAllowed == false,
            extensionLatest = BUILT_WITH_EXTENSION?.takeIf { latest -> foreground.extensionVersion?.let { ExtensionApi.isOlder(it, latest) } == true },
            blockedApps = world.appliedShield.blockedPackages.filter { it in appIds },
            blockedSites = world.appliedShield.blockedSites.toList(),
            siteExceptions = world.appliedShield.siteExceptions.mapValues { it.value.sorted() },
            appNames = appIds.associateWith(DesktopApps::name),
            settings = world.settings.let { UiSettings(it.overridePhrase, it.overrideWaitMs, it.overrideGrantMs, it.overridesPerDay, it.showClock, it.blockOtherBrowsers) },
            subject = if (app == null && site == null) null else subject(world, app, site?.let(Sites::normalizeTarget)),
            clockNote = (clock as? ClockGuard)?.note(),
        )
    }

    private fun subject(world: World, app: String?, site: String?): UiSubject {
        val blockers = store.engine.blockersOf(world, app, site, store.clock)
        val ids = blockers.map { it.id }.toSet()
        val pending = world.pendingOverride?.takeIf { it.blockIds == ids }
        val lead = BlockTexts.leading(store.engine, world, blockers, store.clock)
        val back = lead?.let { BlockTexts.back(store.engine, world, it, store.clock) }
        return UiSubject(
            blocked = blockers.isNotEmpty(),
            blockers = ids.toList(),
            overrideRejection = if (blockers.isEmpty()) null else store.engine.overrideRejection(world, blockers, store.clock)?.name,
            overrideReadyAtWall = pending?.let { it.sinceWall + world.settings.overrideWaitMs },
            back = back?.at,
            why = BlockTexts.why(back, lead),
            opening = lead != null && store.engine.blockReason(world, lead, store.clock) == BlockReason.WAIT,
        )
    }

    private fun uiBlock(here: World, b: Block): UiBlock {
        // A phone-only block shows how it stands on the phone, not "not blocking" (I42).
        val world = if (here.appliesHere(b)) here else here.copy(deviceKind = null)
        val engine = store.engine
        val clock = store.clock
        val credit = world.credits[b.id]
        val session = world.sessions[b.id]
        val progress = world.progress[b.id]
        val w = b.schedule.activeWindow
        val days = b.schedule.activeDays
        val hours = b.blockedHours
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
            shownSites = Sites.shown(b.targetSites.sorted()),
            exceptSites = b.exceptSites.sorted(),
            desktopApps = b.targetDesktopApps.sorted(),
            trigger = b.trigger.type.name,
            requiredMs = if (b.trigger.type == TriggerType.CHECKLIST) 0 else b.trigger.requiredAmount,
            requiredItems = if (b.trigger.type == TriggerType.CHECKLIST) b.trigger.requiredAmount else Defaults.ALL_ITEMS,
            combineOr = b.trigger.combineMode.name == "OR",
            unlockMs = b.reward.unlockDurationMs,
            creditExpiresAtWall = credit?.expiresAtWall,
            openUntilWall = session?.takeUnless { it.selfPaced }?.let { it.startedAtWall + it.durationMs },
            devices = b.devices.name,
            here = here.appliesHere(b),
            selfPaced = b.selfPaced,
            useLeftMs = session?.takeIf { it.selfPaced }?.leftMs(clock),
            lockdownRemainingMs = engine.lockdownRemainingMs(world, b.id, clock),
            lockdownMs = b.lockdownMs,
            allowanceMs = b.dailyAllowanceMs,
            allowanceUsedMs = if (b.hasLimit) engine.allowanceUsedMs(world, b, clock) else 0,
            rowMs = b.sessionMs,
            rowBreakMs = b.sessionBreakMs,
            rowUsedMs = engine.rowUsedMs(world, b, clock),
            rowBreakRemainingMs = engine.rowBreakRemainingMs(world, b, clock),
            opensPerDay = b.opensPerDay,
            opensUsed = engine.opensUsed(world, b, clock),
            openWaitS = b.openWaitS,
            perDevice = b.perDevice,
            overrideRemainingMs = engine.overrideRemainingMs(world, b.id, clock),
            overridesLeft = engine.overridesLeftToday(world, b.id, clock),
            timerMs = progress?.accumulatedMs ?: 0,
            timerRunning = progress?.timerRunning == true,
            items = world.items(b.id).map { item ->
                val habit = item.habitId?.let(world::habit)
                UiItem(
                    item.id, habit?.name ?: item.text, item.done,
                    tag = item.tag != null,
                    habit = habit?.let { h ->
                        val focus = world.focus?.takeIf { it.habitId == h.id }
                        val day = engine.itemDay(world, item, clock)
                        UiHabit(
                            id = h.id,
                            name = h.name,
                            kind = h.kind.name,
                            unit = h.unit,
                            unlock = h.unlockUnit,
                            amount = engine.habitAvailable(world, item, clock),
                            spent = item.spentOn(day),
                            day = day,
                            dayValue = when {
                                h.numerical -> engine.habitAmount(world, h.id, day, clock)
                                item.countsGoal -> engine.checkValue(world, h, day)
                                else -> world.entry(h.id, day)?.value ?: HabitEntry.UNKNOWN
                            },
                            yesterday = item.linkedDay == LinkedDay.YESTERDAY,
                            focusing = focus?.running == true,
                            onBreak = focus?.resumeAtWall != null,
                        )
                    },
                )
            },
            redeemRejection = if (b.state == BlockState.EARNED) engine.redeemRejection(world, b.id, clock)?.name else null,
            scheduleText = scheduleText(b.schedule),
            scheduleDays = days.sorted(),
            scheduleStart = w?.startMinuteOfDay,
            scheduleEnd = w?.endMinuteOfDay,
            moreWindows = b.schedule.moreWindows.map { UiWindow(it.days.sorted(), it.window.startMinuteOfDay, it.window.endMinuteOfDay) },
            reason = engine.blockReason(world, b, clock)?.name,
            headline = BlockTexts.headline(engine, world, b, clock).let { UiHeadline(it.text, it.tone.name) },
            hasLimit = b.hasLimit,
            limitFirst = b.limitComesFirst,
            habitFirst = b.habitFirst,
            hoursText = hours?.let { scheduleText(it) ?: "all day" },
            hoursDays = hours?.activeDays?.sorted(),
            hoursStart = hours?.activeWindow?.startMinuteOfDay,
            hoursEnd = hours?.activeWindow?.endMinuteOfDay,
            hoursMore = hours?.moreWindows.orEmpty().map { UiWindow(it.days.sorted(), it.window.startMinuteOfDay, it.window.endMinuteOfDay) },
            lockedUntilWall = b.lock?.untilWall?.takeIf { BlockEdits.locked(b, clock.nowWallMs()) },
        )
    }

    /** A schedule in words; null when it's simply always. */
    private fun scheduleText(s: Schedule): String? = BlockTexts.schedule(s).takeUnless { s == Schedule.ALWAYS }

    /** Today on this laptop, from its own log; read at most every 30 s, since the page asks every second. */
    private var todayRead: Pair<Long, UiDay>? = null

    private fun today(world: World): UiDay {
        val now = store.clock.nowMonotonicMs()
        todayRead?.takeIf { now - it.first < 30_000 }?.let { return it.second }
        val events = telemetry.eventsAfter(store.clock.startOfLocalDayMs())
        fun kind(t: String) = events.filter { it["t"]?.jsonPrimitive?.contentOrNull == t }
        val day = UiDay(
            habitsDone = kind("habit").size,
            timesBlocked = kind("blocked").size,
            unlockMs = kind("unlock").sumOf { it["ms"]?.jsonPrimitive?.longOrNull ?: 0 },
        )
        todayRead = now to day
        return day
    }

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
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
        ex.responseHeaders.add("Content-Type", type)
        // Fonts never change within a version; everything else is read fresh.
        ex.responseHeaders.add("Cache-Control", if (type == "font/woff2") "private, max-age=86400" else "no-store")
        secure(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun body(ex: HttpExchange): String = ex.requestBody.use { it.readBytes().decodeToString() }

    /**
     * A website's own icon ([SiteIconCache]); 404 until the laptop has it. Only for a site a block
     * names, or a host under one (the block page shows the host it stands in for): an `<img>` on
     * any web page can ask, and would have the daemon fetch any host's home page and keep a file
     * for each. [secure] keeps such a page from telling by the answer which sites are blocked.
     */
    private fun siteIcon(ex: HttpExchange, host: String) {
        val named = store.world.value.blocks.any { b -> (b.targetSites + b.exceptSites).any { Sites.matches(host, Sites.hostOf(it)) } }
        val icon = (if (named) siteIcons.icon(host) else null) ?: return send(ex, 404, "no icon yet")
        ex.responseHeaders.add("Content-Type", icon.type) // a raster type, from the bytes themselves
        ex.responseHeaders.add("Cache-Control", "private, max-age=86400")
        secure(ex)
        ex.sendResponseHeaders(200, icon.bytes.size.toLong())
        ex.responseBody.use { it.write(icon.bytes) }
    }

    private fun send(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", if (text.startsWith("{") || text.startsWith("[")) "application/json" else "text/plain; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        secure(ex)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * No other site may frame these pages (a click there could press our buttons), run scripts
     * in them that aren't ours, learn from a Referer where a blocked tab went back from, or load
     * what we answer as its own image or script, which would tell it, for one, an icon from a 404.
     */
    private fun secure(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Security-Policy", CONTENT_POLICY)
        ex.responseHeaders.add("Cross-Origin-Resource-Policy", "same-origin")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    companion object {
        const val GUARD_MS = 60_000L

        /** How long after its wait a loosening can still be confirmed. */
        private const val CONFIRM_MS = 5 * 60_000L
        private const val CONTENT_POLICY = "default-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; " +
            "base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

        /** The extension's version when this daemon was built (`extension-manifest.json`, see the build). */
        private val BUILT_WITH_EXTENSION: String? = runCatching {
            val text = LocalServer::class.java.getResourceAsStream("/extension-manifest.json")!!.use { it.readBytes().decodeToString() }
            Regex("\"version\"\\s*:\\s*\"([0-9.]+)\"").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }
}
