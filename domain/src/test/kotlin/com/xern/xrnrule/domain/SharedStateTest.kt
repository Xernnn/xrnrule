package com.xern.xrnrule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The projection and the merge table of contract §2.4 (I26). Scenarios across devices are in `InvariantTests`. */
class SharedStateTest {

    private val t = BASE_WALL

    private fun credit(id: String, earnedAt: Long) = EarnedCredit(id, 0, earnedAt, earnedAt + Defaults.CREDIT_TTL_MS, 5L * 60_000)
    private fun session(id: String, start: Long, ms: Long = 5L * 60_000) = UnlockSession(id, 0, start, ms)
    private fun lockdown(id: String, start: Long, ms: Long) = Lockdown(id, 0, start, ms)

    private fun merge(base: SharedState?, local: SharedState, remote: SharedState) = SharedState.merge(base, local, remote)

    @Test
    fun projection_dropsMonotonicStampsAndHabitTicks() {
        val clock = FakeClock(mono = 123_456)
        val block = checklistBlock("c1")
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1) + habitItem("c1", "meditate", order = 1)), clock)
            .copy(habits = listOf(habit("meditate", HabitKind.CHECK)))
        val engine = BlockEngine()
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.LogHabit("meditate", 1), clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)

        val shared = SharedState.of(world)

        assertEquals(BlockState.ARMED, shared.blocks.getValue("c1").state)
        assertEquals(0L, shared.credits.getValue("c1").earnedAtMonotonic)
        assertEquals(world.credits.getValue("c1").earnedAtWall, shared.credits.getValue("c1").earnedAtWall)
        val manual = shared.items.getValue("c1-i0")
        assertTrue(manual.done)
        assertNull(manual.doneAtMonotonic)
        assertFalse("a habit item's tick is each device's own", shared.items.getValue("c1-meditate").done)
    }

    @Test
    fun oneSidedChangesWinIncludingDeletes() {
        val base = SharedState(credits = mapOf("c1" to credit("c1", t)), lockdowns = mapOf("l1" to lockdown("l1", t, 60_000)))
        val local = base.copy(credits = emptyMap()) // spent here
        val remote = base.copy(sessions = mapOf("c2" to session("c2", t))) // something else happened there
        val merged = merge(base, local, remote)
        assertTrue(merged.credits.isEmpty())
        assertEquals(setOf("c2"), merged.sessions.keys)
        assertEquals(base.lockdowns, merged.lockdowns)
    }

    @Test
    fun firstSyncHasNoBaseSoNothingIsDeleted() {
        val local = SharedState(credits = mapOf("c1" to credit("c1", t)))
        val remote = SharedState(sessions = mapOf("c2" to session("c2", t)))
        val merged = merge(null, local, remote)
        assertEquals(setOf("c1"), merged.credits.keys)
        assertEquals(setOf("c2"), merged.sessions.keys)
    }

    @Test
    fun sessions_laterEndWins_andNewBeatsEnded() {
        val base = SharedState()
        val early = session("c1", t)
        val late = session("c1", t + 60_000)
        assertEquals(late, merge(base, SharedState(sessions = mapOf("c1" to early)), SharedState(sessions = mapOf("c1" to late))).sessions["c1"])
        assertEquals(late, merge(base, SharedState(sessions = mapOf("c1" to late)), SharedState(sessions = mapOf("c1" to early))).sessions["c1"])

        val ended = SharedState(sessions = mapOf("c1" to early))
        val replaced = merge(ended, SharedState(), SharedState(sessions = mapOf("c1" to late)))
        assertEquals(late, replaced.sessions["c1"])
    }

    @Test
    fun credits_earlierWins_andSpentBeatsChanged() {
        val first = credit("c1", t)
        val second = credit("c1", t + 1_000)
        assertEquals(first, merge(null, SharedState(credits = mapOf("c1" to second)), SharedState(credits = mapOf("c1" to first))).credits["c1"])
        val base = SharedState(credits = mapOf("c1" to first))
        assertTrue(merge(base, SharedState(), SharedState(credits = mapOf("c1" to second))).credits.isEmpty())
    }

    @Test
    fun lockdowns_laterEndWins() {
        val short = lockdown("l1", t, 30L * 60_000)
        val long = lockdown("l1", t, 60L * 60_000)
        assertEquals(long, merge(null, SharedState(lockdowns = mapOf("l1" to short)), SharedState(lockdowns = mapOf("l1" to long))).lockdowns["l1"])
    }

    @Test
    fun pause_firstBreakOfTheDayStands() {
        val first = Pause(0, t, Defaults.PAUSE_MS)
        val second = Pause(0, t + 60_000, Defaults.PAUSE_MS)
        val merged = merge(SharedState(), SharedState(pause = second, lastPauseEpochDay = 5), SharedState(pause = first, lastPauseEpochDay = 4))
        assertEquals(first, merged.pause)
        assertEquals(5L, merged.lastPauseEpochDay)
    }

    @Test
    fun blocks_laterEditWins_andEditBeatsDelete() {
        val block = checklistBlock("c1")
        val base = SharedState(blocks = mapOf("c1" to block))
        val renamedHere = block.copy(name = "here", editedAtWall = t + 2)
        val renamedThere = block.copy(name = "there", editedAtWall = t + 1)
        assertEquals("here", merge(base, base.copy(blocks = mapOf("c1" to renamedHere)), base.copy(blocks = mapOf("c1" to renamedThere))).blocks.getValue("c1").name)

        val deletedHere = SharedState()
        assertEquals("there", merge(base, deletedHere, base.copy(blocks = mapOf("c1" to renamedThere))).blocks.getValue("c1").name)
        assertTrue("deleted, untouched elsewhere: gone", merge(base, deletedHere, base).blocks.isEmpty())
    }

    @Test
    fun blocks_keepThisDevicesOrder() {
        val a = checklistBlock("a")
        val b = checklistBlock("b")
        val c = checklistBlock("c")
        val merged = merge(null, SharedState(blocks = linkedMapOf("b" to b, "a" to a)), SharedState(blocks = linkedMapOf("a" to a, "c" to c)))
        assertEquals(listOf("b", "a", "c"), merged.blocks.keys.toList())
    }

    @Test
    fun items_configFromTheSideThatChangedIt_tickOred_spentMaxed() {
        val item = items("c1", 1).single()
        val base = SharedState(items = mapOf(item.id to item))
        val here = item.copy(done = true)
        val there = item.copy(text = "renamed", spentEpochDay = 9)
        val merged = merge(base, SharedState(items = mapOf(item.id to here)), SharedState(items = mapOf(item.id to there))).items.getValue(item.id)
        assertEquals("renamed", merged.text)
        assertTrue(merged.done)
        assertEquals(9L, merged.spentEpochDay)

        val renamedHere = item.copy(text = "mine", spentEpochDay = 3)
        val tickedThere = item.copy(done = true)
        val merged2 = merge(base, SharedState(items = mapOf(item.id to renamedHere)), SharedState(items = mapOf(item.id to tickedThere))).items.getValue(item.id)
        assertEquals("mine", merged2.text)
        assertTrue(merged2.done)
        assertEquals(3L, merged2.spentEpochDay)
    }

    @Test
    fun settings_stricterValueWinsPerField() {
        val base = SharedState(settings = Settings())
        val here = base.copy(settings = Settings(dailyCapMs = 90L * 60_000, unlockWarningMs = 10_000))
        val there = base.copy(settings = Settings(dailyCapMs = 45L * 60_000, unlockWarningMs = 60_000))
        val merged = merge(base, here, there).settings
        assertEquals(45L * 60_000, merged.dailyCapMs)
        assertEquals(60_000L, merged.unlockWarningMs)
        assertEquals(Defaults.SKEW_TOLERANCE_MS, merged.skewToleranceMs)
    }

    @Test
    fun overrideSettings_stricterValueWinsPerField() {
        val base = SharedState(settings = Settings())
        val here = base.copy(settings = Settings(overridePhrase = "I really do choose to keep scrolling", overrideWaitMs = 30_000, overridesPerDay = 1, showClock = false))
        val there = base.copy(settings = Settings(overridePhrase = "ok fine", overrideWaitMs = 90_000, overrideGrantMs = 4L * 60_000, overridesPerDay = 3))
        val merged = merge(base, here, there).settings
        assertEquals("I really do choose to keep scrolling", merged.overridePhrase)
        assertEquals(90_000L, merged.overrideWaitMs)
        assertEquals(4L * 60_000, merged.overrideGrantMs)
        assertEquals(1, merged.overridesPerDay)
        assertFalse(merged.showClock)
    }

    @Test
    fun overrides_laterEndWins_andTheLogIsUnionedPerDay() {
        val early = OverrideGrant("s1", 0, t, 4L * 60_000, "phone")
        val late = OverrideGrant("s1", 0, t + 60_000, 4L * 60_000, "laptop")
        val here = SharedState(overrides = mapOf("s1" to early), overrideEpochDay = 9, overrideLog = mapOf(early.key to early.durationMs))
        val there = SharedState(overrides = mapOf("s1" to late), overrideEpochDay = 9, overrideLog = mapOf(late.key to late.durationMs))
        val merged = merge(null, here, there)
        assertEquals(late, merged.overrides["s1"])
        assertEquals(setOf(early.key, late.key), merged.overrideLog.keys)
        assertEquals(mapOf(late.key to late.durationMs), merge(null, here, there.copy(overrideEpochDay = 10)).overrideLog)
    }

    @Test
    fun unlockLog_unionOnTheSameDay_newerDayWins() {
        val here = SharedState(unlockEpochDay = 10, unlockLog = mapOf("a@1" to 100L, "b@2" to 50L))
        val there = SharedState(unlockEpochDay = 10, unlockLog = mapOf("a@1" to 100L, "c@3" to 70L))
        assertEquals(mapOf("a@1" to 100L, "b@2" to 50L, "c@3" to 70L), merge(null, here, there).unlockLog)

        val tomorrow = SharedState(unlockEpochDay = 11, unlockLog = mapOf("d@4" to 10L))
        val merged = merge(null, here, tomorrow)
        assertEquals(11L, merged.unlockEpochDay)
        assertEquals(mapOf("d@4" to 10L), merged.unlockLog)
    }

    @Test
    fun allowance_perDevice_newerDayThenMore() {
        val here = SharedState(allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 5), "laptop" to AllowanceUse(t, 9))))
        val there = SharedState(allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 7), "laptop" to AllowanceUse(t - 86_400_000, 30))))
        val merged = merge(null, here, there).allowance.getValue("a1")
        assertEquals(AllowanceUse(t, 7), merged["phone"])
        assertEquals(AllowanceUse(t, 9), merged["laptop"])
    }

    @Test
    fun roundTripsThroughJson() {
        val json = Json { encodeDefaults = true }
        val state = SharedState(
            blocks = mapOf("c1" to checklistBlock("c1").copy(targetDesktopApps = setOf("steam"), editedAtWall = t)),
            items = mapOf("c1-i0" to items("c1", 1).single()),
            credits = mapOf("c1" to credit("c1", t)),
            sessions = mapOf("c2" to session("c2", t)),
            lockdowns = mapOf("l1" to lockdown("l1", t, 60_000)),
            pause = Pause(0, t, Defaults.PAUSE_MS),
            lastPauseEpochDay = 3,
            unlockEpochDay = 3,
            unlockLog = mapOf("c2@$t" to 1_000L),
            allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 5, RunUse(5, t, t + 60_000), t))),
            overrides = mapOf("s1" to OverrideGrant("s1", 0, t, 60_000, "phone")),
            overrideEpochDay = 3,
            overrideLog = mapOf("s1@$t" to 60_000L),
        )
        val decoded = json.decodeFromString(SharedState.serializer(), json.encodeToString(SharedState.serializer(), state))
        assertEquals(state, decoded)
        // Unknown fields from a newer device must not break an older one.
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(SharedState(), lenient.decodeFromString(SharedState.serializer(), """{"somethingNew":1}"""))
    }

    /** [state] as a device running an older xrnrule writes it back: without the fields it doesn't know. */
    private fun asAnOlderDeviceWritesIt(state: SharedState, blockFields: Set<String>, useFields: Set<String> = emptySet()): String {
        val json = Json { encodeDefaults = true }
        val doc = json.encodeToJsonElement(SharedState.serializer(), state).jsonObject
        fun JsonObject.without(fields: Set<String>) = JsonObject(this - fields)
        val blocks = JsonObject(doc.getValue("blocks").jsonObject.mapValues { it.value.jsonObject.without(blockFields) })
        val allowance = JsonObject(doc.getValue("allowance").jsonObject.mapValues { (_, byDevice) ->
            JsonObject(byDevice.jsonObject.mapValues { it.value.jsonObject.without(useFields) })
        })
        return JsonObject(doc + ("blocks" to blocks) + ("allowance" to allowance)).toString()
    }

    @Test
    fun aFieldAnOlderDeviceLeftOutIsNotTakenAsChanged() {
        val lock = BlockLock(untilWall = t + 7 * DAY_MS, setAtWall = t)
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000)
            .copy(opensPerDay = 3, openWaitS = 10, lock = lock, editedAtWall = t)
        val other = checklistBlock("c1").copy(editedAtWall = t)
        val agreed = SharedState(
            blocks = mapOf("a1" to limit, "c1" to other),
            allowance = mapOf("a1" to mapOf("phone" to AllowanceUse(t, 60_000, opens = 2))),
        )
        // The older device renamed the limit, deleted the other block, made one of its own, and
        // wrote back what it knows: no opens, no wait (before 628cb07), no lock (before Oct 24).
        val itsOwn = checklistBlock("new").copy(editedAtWall = t + 2)
        val older = agreed.copy(blocks = mapOf("a1" to limit.copy(name = "renamed", editedAtWall = t + 1), "new" to itsOwn))
        val text = asAnOlderDeviceWritesIt(older, setOf("opensPerDay", "openWaitS", "lock"), setOf("opens"))

        val naive = merge(agreed, agreed, Json { ignoreUnknownKeys = true }.decodeFromString(SharedState.serializer(), text))
        assertEquals("how it went before: the limit's opens were gone", 0, naive.blocks.getValue("a1").opensPerDay)

        val merged = merge(agreed, agreed, SharedState.fromServer(text, agreed, agreed))
        val a1 = merged.blocks.getValue("a1")
        assertEquals("renamed", a1.name)
        assertEquals(3, a1.opensPerDay)
        assertEquals(10, a1.openWaitS)
        assertEquals(lock, a1.lock)
        assertEquals(2, merged.allowance.getValue("a1").getValue("phone").opens)
        assertEquals("a block missing from the map is still deleted", setOf("a1", "new"), merged.blocks.keys)
        assertEquals("nothing to keep for a block it made", itsOwn, merged.blocks.getValue("new"))
    }

    @Test
    fun withoutABaseWhatAnOlderDeviceLeftOutComesFromHere() {
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000).copy(opensPerDay = 3, editedAtWall = t)
        val here = SharedState(blocks = mapOf("a1" to limit))
        val text = asAnOlderDeviceWritesIt(SharedState(blocks = mapOf("a1" to limit.copy(name = "renamed", editedAtWall = t + 1))), setOf("opensPerDay"))
        val merged = merge(null, here, SharedState.fromServer(text, null, here))
        assertEquals("renamed", merged.blocks.getValue("a1").name)
        assertEquals(3, merged.blocks.getValue("a1").opensPerDay)
    }

    @Test
    fun anotherRecordUnderTheSameKeyKeepsNothingOfTheOld() {
        val json = Json { encodeDefaults = true }
        val agreed = SharedState(sessions = mapOf("c1" to session("c1", t).copy(selfPaced = true, usedMs = mapOf("phone" to 60_000L))))
        fun older(s: UnlockSession): String {
            val doc = json.encodeToJsonElement(SharedState.serializer(), SharedState(sessions = mapOf("c1" to s))).jsonObject
            val sessions = JsonObject(doc.getValue("sessions").jsonObject.mapValues { JsonObject(it.value.jsonObject - setOf("selfPaced", "usedMs")) })
            return JsonObject(doc + ("sessions" to sessions)).toString()
        }
        // The same unlock, written back by a device that doesn't know self-paced ones: still self-paced.
        assertEquals(agreed.sessions, SharedState.fromServer(older(session("c1", t)), agreed, agreed).sessions)
        // A new unlock it made: nothing of the old one.
        val new = session("c1", t + 60 * 60_000)
        assertEquals(new, SharedState.fromServer(older(new), agreed, agreed).sessions.getValue("c1"))
    }

    @Test
    fun aCopyWithEveryFieldIsReadAsItIs() {
        val json = Json { encodeDefaults = true }
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000).copy(opensPerDay = 3, editedAtWall = t)
        val there = SharedState(blocks = mapOf("a1" to limit.copy(opensPerDay = 0, lock = null)))
        val here = SharedState(blocks = mapOf("a1" to limit.copy(lock = BlockLock(t + DAY_MS, t))))
        assertEquals(there, SharedState.fromServer(json.encodeToString(SharedState.serializer(), there), here, here))
    }

    @Test
    fun habitItemsSpendTheLaterDayOrTheMostThatDay() {
        val item = habitItem("c1", "push")
        val base = SharedState(items = mapOf(item.id to item))
        fun spent(day: Long, amount: Long) = SharedState(items = mapOf(item.id to item.copy(spentEpochDay = day, spentAmount = amount)))
        assertEquals(20L, merge(base, spent(5, 20), spent(5, 10)).items.getValue(item.id).spentAmount)
        assertEquals(10L, merge(base, spent(5, 20), spent(6, 10)).items.getValue(item.id).spentAmount)
        assertEquals(6L, merge(base, spent(5, 20), spent(6, 10)).items.getValue(item.id).spentEpochDay)
    }
}
