package com.xern.xrnrule.domain

import java.time.Instant
import java.time.ZoneId

/** A fixed instant well away from any DST edge, used as the base wall clock in tests. */
val BASE_WALL: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
const val DAY_MS: Long = 24L * 60 * 60 * 1000

class FakeClock(
    private var mono: Long = 0L,
    private var wall: Long = BASE_WALL,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock {
    override fun nowMonotonicMs(): Long = mono
    override fun nowWallMs(): Long = wall
    override fun zone(): ZoneId = zoneId

    /** Advance real time: both clocks move together. */
    fun advance(ms: Long) {
        mono += ms
        wall += ms
    }

    fun advanceWallOnly(ms: Long) {
        wall += ms
    }

    fun advanceMonoOnly(ms: Long) {
        mono += ms
    }

    fun setMono(value: Long) {
        mono = value
    }
}

class InMemoryPersist(initial: World) : PersistPort {
    var world: World = initial
        private set

    override fun loadWorld(): World = world
    override fun save(world: World) {
        this.world = world
    }
}

/** Persist + shield that append to a shared log so ordering (I7) can be asserted. */
class RecordingPersist(initial: World, private val log: MutableList<String>) : PersistPort {
    var world: World = initial
        private set

    override fun loadWorld(): World = world
    override fun save(world: World) {
        this.world = world
        log += "persist"
    }
}

class RecordingShield(private val log: MutableList<String>) : ShieldPort {
    var last: ShieldPlan? = null
        private set

    override fun apply(plan: ShieldPlan) {
        last = plan
        log += "shield"
    }
}

object NoopTelemetry : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = Unit
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = Unit
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) = Unit
}

// ---- builders -------------------------------------------------------------------------

fun timerBlock(
    id: String = "t1",
    target: String = "com.target",
    requiredMs: Long = 30L * 60_000,
    habitPackages: Set<String> = emptySet(),
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Timer $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.TIMER, requiredMs, TriggerUnit.MS, habitPackages = habitPackages),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

fun checklistBlock(
    id: String = "c1",
    target: String = "com.target",
    required: Long = Defaults.ALL_ITEMS,
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Checklist $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.CHECKLIST, required, TriggerUnit.ITEMS),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

fun combinedBlock(
    id: String = "x1",
    target: String = "com.target",
    requiredMs: Long = 30L * 60_000,
    combineMode: CombineMode = CombineMode.AND,
    habitPackages: Set<String> = emptySet(),
    unlockMs: Long = 5L * 60_000,
    ttlMs: Long = Defaults.CREDIT_TTL_MS,
    state: BlockState = BlockState.ARMED,
) = Block(
    id = id,
    name = "Combined $id",
    targetPackages = setOf(target),
    trigger = Trigger(TriggerType.COMBINED, requiredMs, TriggerUnit.MS, combineMode, habitPackages),
    reward = Reward(unlockMs, ttlMs),
    state = state,
)

/** A non-HABIT block (contract §2.2); the trigger is required by the model but never read. */
fun modeBlock(
    id: String,
    mode: BlockMode,
    targetPackages: Set<String> = setOf("com.target"),
    targetSites: Set<String> = emptySet(),
    dailyAllowanceMs: Long = 0,
    lockdownMs: Long = 0,
    schedule: Schedule = Schedule.ALWAYS,
) = Block(
    id = id,
    name = "$mode $id",
    targetPackages = targetPackages,
    targetSites = targetSites,
    trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
    schedule = schedule,
    mode = mode,
    dailyAllowanceMs = dailyAllowanceMs,
    lockdownMs = lockdownMs,
)

fun everyDay(startMinute: Int, endMinute: Int) = Schedule((1..7).toSet(), TimeWindow(startMinute, endMinute))

fun items(blockId: String, count: Int, done: Int = 0): List<ChecklistItem> =
    (0 until count).map { i ->
        ChecklistItem(id = "$blockId-i$i", blockId = blockId, text = "todo $i", order = i, done = i < done)
    }

/**
 * A linked item from before the habit list moved into xrnrule (I23: the sweep makes it a habit
 * item). Id is `"$blockId-$habitId"`.
 */
fun linkedItem(blockId: String, habitId: String, order: Int = 0, day: LinkedDay = LinkedDay.TODAY) =
    ChecklistItem(
        id = "$blockId-$habitId",
        blockId = blockId,
        text = habitId,
        order = order,
        externalHabitId = habitId,
        linkedDay = day,
    )

/** [n] whole units (a count, or minutes for a TIMER) in the thousandths amounts are kept in (§1). */
fun units(n: Long): Long = n * 1000

/** A habit (§2.5); [goal] in whole units per [freqDen] days. Seq 1: as if read from the habit server. */
fun habit(
    id: String,
    kind: HabitKind = HabitKind.COUNT,
    goal: Long = 10,
    freqNum: Int = 1,
    freqDen: Int = 1,
    targetType: TargetType = TargetType.AT_LEAST,
) = Habit(
    id = id,
    name = id,
    kind = kind,
    goal = if (kind == HabitKind.CHECK) 0 else units(goal),
    targetType = targetType,
    freqNum = freqNum,
    freqDen = freqDen,
    unit = if (kind == HabitKind.TIMER) "min" else "",
    seq = 1,
)

/** A checklist item asking for habit [habitId] (I36). Id is `"$blockId-$habitId"`. */
fun habitItem(
    blockId: String,
    habitId: String,
    order: Int = 0,
    day: LinkedDay = LinkedDay.TODAY,
    countsGoal: Boolean = false,
) = ChecklistItem(
    id = "$blockId-$habitId",
    blockId = blockId,
    text = habitId,
    order = order,
    habitId = habitId,
    linkedDay = day,
    countsGoal = countsGoal,
)

fun buildWorld(
    blocks: List<Block>,
    checklist: Map<String, List<ChecklistItem>> = emptyMap(),
    clock: Clock,
    foregroundPkg: String? = null,
    settings: Settings = Settings(),
): World {
    val progress = blocks.associate { b ->
        b.id to HabitProgress(
            blockId = b.id,
            windowStart = clock.startOfLocalDayMs(),
            lastUpdated = clock.nowMonotonicMs(),
        )
    }
    val world = World(
        blocks = blocks,
        progress = progress,
        checklist = checklist,
        dailyUsageEpochDay = clock.epochDay(),
        foregroundPkg = foregroundPkg,
        settings = settings,
    )
    // As if the shield had already been told about this starting world.
    return world.copy(appliedShield = BlockEngine().shieldPlan(world, clock))
}

// ---- several devices (contract §2.4) ---------------------------------------------------

/** The sync server: it only stores the last state pushed, and counts up with every write. */
class FakeServer(var state: SharedState = SharedState()) {
    var version = 0L
        private set
    val etag: String get() = "\"$version\""

    fun write(next: SharedState) {
        state = next
        version++
    }

    /** I45: its file went, or it came back from a backup taken at [atVersion]. */
    fun wentBack(to: SharedState = SharedState(), atVersion: Long = 0) {
        state = to
        version = atVersion
    }
}

/**
 * One device in a multi-device test: its own world and clock, and the shared state it last
 * agreed on with the server. Give each device a different monotonic start: only wall time is
 * shared between them.
 */
class Device(
    val id: String,
    var world: World,
    val clock: FakeClock,
) {
    private val engine = BlockEngine()
    var base: SharedState? = null
        private set
    private var baseEtag: String? = null

    init {
        world = world.copy(deviceId = id)
    }

    fun on(event: BlockEvent): Outcome = engine.reduce(world, event, clock).also { world = it.world }

    /** One round: merge with what the server has, adopt it, push the result. True if that wrote anything. */
    fun sync(server: FakeServer): Boolean {
        on(BlockEvent.SharedStateMerged(SharedState.baseFor(base, baseEtag, server.etag), server.state))
        val pushed = SharedState.of(world)
        val wrote = pushed != server.state
        if (wrote) server.write(pushed)
        base = pushed
        baseEtag = server.etag
        return wrote
    }

    fun block(id: String): Block = world.block(id)!!
}

/**
 * The habit server (`xrnloops-sync.py`, §2.5): a record per habit and per entry, each write takes
 * the next seq, and a write naming the version it was made from wins only if that is still the
 * stored one. Everything crosses as JSON, both ways, as on the wire.
 */
class FakeHabitServer(var dayStartHour: Int = 0) {
    var seq = 0L
        private set
    val habits = linkedMapOf<String, HabitRecord>()
    val entries = linkedMapOf<String, EntryRecord>()
    private val json = HabitRecords.json

    /** The server's epoch (§2.5): a client that has read past [seq] (a restore) gets everyone a new one. */
    var epoch = 1L
        private set

    fun changes(since: Long, clientEpoch: Long? = null): HabitChanges {
        if (clientEpoch == epoch && since > seq) epoch++
        val out = HabitChanges(
            seq = seq,
            habits = habits.values.filter { it.seq > since }.sortedBy { it.seq },
            entries = entries.values.filter { it.seq > since }.sortedBy { it.seq },
            dayStartHour = dayStartHour,
            epoch = epoch,
        )
        return json.decodeFromString(HabitChanges.serializer(), json.encodeToString(HabitChanges.serializer(), out))
    }

    fun write(sent: HabitWrite): HabitWriteResult {
        val write = json.decodeFromString(HabitWrite.serializer(), json.encodeToString(HabitWrite.serializer(), sent))
        val rejected = HabitWriteResult.HabitRows()
        var rejectedHabits = rejected.habits
        var rejectedEntries = rejected.entries
        val acceptedHabits = mutableListOf<HabitWriteResult.HabitSeq>()
        val acceptedEntries = mutableListOf<HabitWriteResult.EntrySeq>()
        for (h in write.habits) {
            val stored = habits[h.uuid]
            val data = h.data ?: stored?.data ?: HabitData()
            when {
                stored == null -> {
                    habits[h.uuid] = HabitRecord(h.uuid, data, h.mtime, h.deleted, ++seq)
                    acceptedHabits += HabitWriteResult.HabitSeq(h.uuid, seq)
                }
                stored.data == data && stored.deleted == h.deleted -> acceptedHabits += HabitWriteResult.HabitSeq(h.uuid, stored.seq)
                wins(h.baseSeq, stored.seq, h.mtime, stored.mtime) -> {
                    habits[h.uuid] = HabitRecord(h.uuid, data, h.mtime, h.deleted, ++seq)
                    acceptedHabits += HabitWriteResult.HabitSeq(h.uuid, seq)
                }
                else -> rejectedHabits = rejectedHabits + stored
            }
        }
        for (e in write.entries) {
            val key = "${e.habit}@${e.date}"
            val stored = entries[key]
            when {
                stored == null -> {
                    entries[key] = e.copy(seq = ++seq, baseSeq = null)
                    acceptedEntries += HabitWriteResult.EntrySeq(e.habit, e.date, seq)
                }
                stored.value == e.value && stored.notes == e.notes -> acceptedEntries += HabitWriteResult.EntrySeq(e.habit, e.date, stored.seq)
                wins(e.baseSeq, stored.seq, e.mtime, stored.mtime) -> {
                    entries[key] = e.copy(seq = ++seq, baseSeq = null)
                    acceptedEntries += HabitWriteResult.EntrySeq(e.habit, e.date, seq)
                }
                else -> rejectedEntries = rejectedEntries + stored
            }
        }
        val out = HabitWriteResult(
            seq,
            HabitWriteResult.HabitRows(rejectedHabits, rejectedEntries),
            HabitWriteResult.Accepted(acceptedHabits, acceptedEntries),
        )
        return json.decodeFromString(HabitWriteResult.serializer(), json.encodeToString(HabitWriteResult.serializer(), out))
    }

    /** A copy of everything, to [restore] later: a server put back from its backup (§2.5). */
    fun backup(): Triple<Long, Map<String, HabitRecord>, Map<String, EntryRecord>> = Triple(seq, LinkedHashMap(habits), LinkedHashMap(entries))

    /** The epoch comes back with the rest: it is stored with the records. */
    fun restore(backup: Triple<Long, Map<String, HabitRecord>, Map<String, EntryRecord>>) {
        seq = backup.first
        habits.clear(); habits.putAll(backup.second)
        entries.clear(); entries.putAll(backup.third)
    }

    /** Another client (xrnloops, the web app) writing without a base version: the later mtime wins. */
    fun put(entry: EntryRecord) = write(HabitWrite(emptyList(), listOf(entry)))

    fun put(habit: HabitRecord) = write(HabitWrite(listOf(habit), emptyList()))

    private fun wins(baseSeq: Long?, storedSeq: Long, mtime: Long, storedMtime: Long) =
        if (baseSeq != null) baseSeq == storedSeq else mtime > storedMtime
}

/** One habit round (§2.5): read what's new, then send what changed here; again while refused. */
fun Device.syncHabits(server: FakeHabitServer) {
    repeat(3) {
        val changes = server.changes(world.habitSyncSeq, world.habitServerEpoch)
        if (HabitRecords.wentBack(world, changes)) {
            val all = HabitRecords.readAll(10) { server.changes(it) }
            on(BlockEvent.HabitServerPulled(all.habits, all.entries, all.seq, all.dayStartHour, full = true, epoch = all.epoch))
        } else if (!HabitRecords.nothingNew(world, changes)) { // as the phone and the laptop do
            on(BlockEvent.HabitServerPulled(changes.habits, changes.entries, changes.seq, changes.dayStartHour, epoch = changes.epoch))
        }
        val (habits, entries, sent) = HabitRecords.pending(world)
        if (sent.isEmpty) return
        val result = server.write(HabitWrite(habits, entries))
        on(BlockEvent.HabitServerPushed(sent, result.acceptance(), result.rejected.habits, result.rejected.entries))
        if (result.rejected.habits.isEmpty() && result.rejected.entries.isEmpty()) return
    }
}

/** Moves every device's clocks forward together. */
fun advanceAll(ms: Long, vararg devices: Device) = devices.forEach { it.clock.advance(ms) }
