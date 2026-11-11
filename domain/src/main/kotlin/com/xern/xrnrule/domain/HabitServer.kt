package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/*
 * The habit server's records (contract §2.5) and how this device's habits and entries merge
 * with them (I38). The shapes match `xrnloops-sync.py`'s JSON; the phone and the laptop both
 * send and read them, so they live here, next to the rules that use them.
 */

/** Loop's habit fields as the habit server stores them, plus xrnrule's `icon` and `timer`. */
@Serializable
data class HabitData(
    val name: String = "",
    val question: String = "",
    val description: String = "",
    val color: Int = 8,
    val freqNum: Int = 1,
    val freqDen: Int = 1,
    /** 0 = yes/no, 1 = numerical. */
    val type: Int = 0,
    /** 0 = at least, 1 = at most. */
    val targetType: Int = 0,
    val targetValue: Double = 0.0,
    val unit: String = "",
    val archived: Boolean = false,
    val position: Int = 0,
    val reminder: Reminder? = null,
    val icon: String = "",
    val timer: Boolean = false,
)

@Serializable
data class HabitRecord(
    val uuid: String,
    /** Left out of a deletion: the server keeps the fields a write leaves out. */
    val data: HabitData? = null,
    val mtime: Long = 0,
    val deleted: Boolean = false,
    val seq: Long = 0,
    /** On a write: the version it was made from (§2.5). */
    val baseSeq: Long? = null,
)

@Serializable
data class EntryRecord(
    val habit: String,
    /** The habit day, `YYYY-MM-DD`. */
    val date: String,
    val value: Long,
    val notes: String = "",
    val mtime: Long = 0,
    val seq: Long = 0,
    val baseSeq: Long? = null,
)

/** `GET /api/changes`: a page of records after a seq. */
@Serializable
data class HabitChanges(
    val seq: Long,
    val more: Boolean = false,
    val habits: List<HabitRecord> = emptyList(),
    val entries: List<EntryRecord> = emptyList(),
    val dayStartHour: Int? = null,
    /** Changes when the server's history went back (restored from a backup), §2.5. */
    val epoch: Long? = null,
)

/** `POST /api/changes`: what this device changed. */
@Serializable
data class HabitWrite(val habits: List<HabitRecord>, val entries: List<EntryRecord>)

@Serializable
data class HabitWriteResult(
    val seq: Long = 0,
    val rejected: HabitRows = HabitRows(),
    val accepted: Accepted = Accepted(),
) {
    @Serializable
    data class HabitRows(val habits: List<HabitRecord> = emptyList(), val entries: List<EntryRecord> = emptyList())

    @Serializable
    data class Accepted(val habits: List<HabitSeq> = emptyList(), val entries: List<EntrySeq> = emptyList())

    @Serializable
    data class HabitSeq(val uuid: String, val seq: Long)

    @Serializable
    data class EntrySeq(val habit: String, val date: String, val seq: Long)

    fun acceptance() = HabitAcceptance(
        habits = accepted.habits.associate { it.uuid to it.seq },
        entries = accepted.entries.mapNotNull { e -> HabitRecords.day(e.date)?.let { HabitEntry.key(e.habit, it) to e.seq } }.toMap(),
    )
}

/** What one push sent (I38): habits by id (null for a deletion) and entries by [HabitEntry.key]. */
data class HabitPush(val habits: Map<String, Habit?>, val entries: Map<String, HabitEntry>) {
    val isEmpty: Boolean get() = habits.isEmpty() && entries.isEmpty()
}

/** What the server took from a push, with the seq it stored each at (I38). */
data class HabitAcceptance(val habits: Map<String, Long> = emptyMap(), val entries: Map<String, Long> = emptyMap())

object HabitRecords {
    /**
     * The habit server's JSON. Every field is written, defaults too: a habit write keeps the
     * stored value of a field left out, so leaving out `archived: false` would never un-archive.
     */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The epoch day of a record's `YYYY-MM-DD`, or null for one that isn't a date: that record is
     * left out, rather than the whole round thrown away (and read again next time) with it.
     */
    fun day(date: String): Long? = try {
        LocalDate.parse(date).toEpochDay()
    } catch (_: DateTimeParseException) {
        null
    }

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    fun habit(record: HabitRecord): Habit {
        val d = record.data ?: HabitData()
        val kind = when {
            d.type == 0 -> HabitKind.CHECK
            d.timer -> HabitKind.TIMER
            else -> HabitKind.COUNT
        }
        return Habit(
            id = record.uuid,
            name = d.name,
            kind = kind,
            goal = Math.round(d.targetValue * 1000),
            targetType = if (d.targetType == 1) TargetType.AT_MOST else TargetType.AT_LEAST,
            freqNum = d.freqNum.coerceAtLeast(1),
            freqDen = d.freqDen.coerceAtLeast(1),
            unit = d.unit,
            paletteColor = d.color.coerceIn(0, 19),
            icon = d.icon,
            question = d.question,
            description = d.description,
            archived = d.archived,
            position = d.position,
            reminder = d.reminder,
            editedAtWall = record.mtime,
            seq = record.seq,
            dirty = false,
        )
    }

    fun data(habit: Habit): HabitData = HabitData(
        name = habit.name,
        question = habit.question,
        description = habit.description,
        color = habit.paletteColor,
        freqNum = habit.freqNum,
        freqDen = habit.freqDen,
        type = if (habit.numerical) 1 else 0,
        targetType = if (habit.targetType == TargetType.AT_MOST) 1 else 0,
        targetValue = habit.goal / 1000.0,
        unit = habit.unit,
        archived = habit.archived,
        position = habit.position,
        reminder = habit.reminder,
        icon = habit.icon,
        timer = habit.kind == HabitKind.TIMER,
    )

    /** Everything changed here, as records to send, and what was sent (I38). */
    /**
     * §2.5 "the server went back": everything the server has, read again from the start a page at
     * a time and joined into one, for a single `HabitServerPulled(full = true)`.
     */
    /**
     * §2.5: whether [page] says the server went back since this device last read it: another epoch,
     * or (a server without epochs) a seq below what this device has read.
     */
    fun wentBack(world: World, page: HabitChanges): Boolean =
        page.seq < world.habitSyncSeq ||
            (page.epoch != null && world.habitServerEpoch != null && page.epoch != world.habitServerEpoch)

    /**
     * §2.5: whether [page] has nothing this device doesn't know: no records past what it has read,
     * the same day start, and the same epoch (one not yet known is kept, so a restore is noticed).
     */
    fun nothingNew(world: World, page: HabitChanges): Boolean =
        page.seq <= world.habitSyncSeq && page.habits.isEmpty() && page.entries.isEmpty() &&
            (page.dayStartHour == null || page.dayStartHour == world.habitDayStartHour) &&
            (page.epoch == null || page.epoch == world.habitServerEpoch)

    inline fun readAll(maxPages: Int, page: (since: Long) -> HabitChanges): HabitChanges {
        val habits = ArrayList<HabitRecord>()
        val entries = ArrayList<EntryRecord>()
        var since = 0L
        repeat(maxPages) {
            val p = page(since)
            habits += p.habits
            entries += p.entries
            if (!p.more || p.seq <= since) return HabitChanges(p.seq, false, habits, entries, p.dayStartHour, p.epoch)
            since = p.seq
        }
        throw IllegalStateException("the server has more than $maxPages pages")
    }

    fun pending(world: World): Triple<List<HabitRecord>, List<EntryRecord>, HabitPush> {
        val habits = world.habits.filter { it.dirty }
        val sentHabits = habits.associate { it.id to it } + world.habitTombstones.keys.associateWith { null }
        val habitRecords = habits.map { HabitRecord(it.id, data(it), it.editedAtWall, false, baseSeq = it.seq) } +
            world.habitTombstones.map { (id, t) -> HabitRecord(id, null, t.atWall, deleted = true, baseSeq = t.seq) }
        val entries = world.habitEntries.filterValues { it.dirty }
        val entryRecords = entries.map { (key, e) ->
            EntryRecord(HabitEntry.habitId(key), date(HabitEntry.day(key)!!), e.value, e.notes, e.mtime, baseSeq = e.seq)
        }
        return Triple(habitRecords, entryRecords, HabitPush(sentHabits, entries))
    }
}

/**
 * I38: takes the server's records into this world. A record not changed here is taken as is; one
 * changed here keeps this device's change on top: an amount added here is added to the server's
 * new amount, a check or an edit goes to the later writer.
 */
fun World.mergeHabitRecords(habits: List<HabitRecord>, entries: List<EntryRecord>, full: Boolean = false): World =
    (if (full) resendWhatTheServerLost(habits, entries) else this).mergeRecords(habits, entries)

/**
 * §2.5 "the server went back": [habits] and [entries] are everything a server restored from a
 * backup still has. What it no longer has, or has only in a version older than the one this
 * device saw (a higher `seq` and a later `mtime`), is marked to be sent again; a delete still
 * stands (its tombstone moves to the server's version), unless the server never had the habit.
 */
private fun World.resendWhatTheServerLost(habits: List<HabitRecord>, entries: List<EntryRecord>): World {
    val serverHabits = habits.associateBy { it.uuid }
    val list = this.habits.map { local ->
        val record = serverHabits[local.id]
        when {
            record == null -> local.copy(dirty = true, seq = 0)
            !local.dirty && local.seq > record.seq && local.editedAtWall > record.mtime -> local.copy(dirty = true, seq = record.seq)
            else -> local
        }
    }
    val serverEntries = entries.mapNotNull { e -> HabitRecords.day(e.date)?.let { HabitEntry.key(e.habit, it) to e } }.toMap()
    val kept = list.mapTo(HashSet()) { it.id }
    val merged = habitEntries.mapValues { (key, local) ->
        val record = serverEntries[key]
        when {
            HabitEntry.habitId(key) !in kept -> local
            record == null -> if (local.seq > 0 || local.dirty) local.copy(dirty = true, seq = 0, baseValue = null) else local
            !local.dirty && local.seq > record.seq && local.mtime > record.mtime ->
                local.copy(dirty = true, seq = record.seq, baseValue = record.value)
            else -> local
        }
    }
    return copy(
        habits = list,
        // A delete of a version the server lost still stands: based on what it has now.
        habitTombstones = habitTombstones.mapNotNull { (id, t) ->
            val record = serverHabits[id] ?: return@mapNotNull null
            id to if (record.seq < t.seq) t.copy(seq = record.seq) else t
        }.toMap(),
        habitEntries = merged,
    )
}

private fun World.mergeRecords(habits: List<HabitRecord>, entries: List<EntryRecord>): World {
    var list = this.habits
    val tombstones = habitTombstones.toMutableMap()
    for (record in habits) {
        val local = list.firstOrNull { it.id == record.uuid }
        val tombstone = tombstones[record.uuid]
        when {
            record.deleted -> {
                tombstones -= record.uuid
                if (local != null) {
                    list = if (local.dirty && local.editedAtWall > record.mtime) {
                        list.map { if (it.id == local.id) it.copy(seq = record.seq) else it }
                    } else {
                        list.filter { it.id != local.id }
                    }
                }
            }
            tombstone != null && local == null -> {
                // Deleted here: still deleted unless someone edited it since (an edit beats a delete).
                if (record.seq != tombstone.seq) {
                    tombstones -= record.uuid
                    list = list + HabitRecords.habit(record)
                }
            }
            local == null -> list = list + HabitRecords.habit(record)
            !local.dirty || record.mtime > local.editedAtWall ->
                list = list.map { if (it.id == local.id) HabitRecords.habit(record) else it }
            else -> list = list.map { if (it.id == local.id) it.copy(seq = record.seq) else it }
        }
    }

    val merged = habitEntries.toMutableMap()
    for (record in entries) {
        val habitId = record.habit
        val day = HabitRecords.day(record.date) ?: continue
        val key = HabitEntry.key(habitId, day)
        val local = merged[key]
        val remote = HabitEntry(record.value, record.notes, record.mtime, record.seq)
        merged[key] = when {
            local == null || !local.dirty -> remote
            list.firstOrNull { it.id == habitId }?.numerical == true -> {
                val added = local.value.coerceAtLeast(0) - (local.baseValue ?: 0).coerceAtLeast(0)
                val value = (record.value.coerceAtLeast(0) + added).coerceAtLeast(0)
                val notes = if (local.mtime >= record.mtime) local.notes else record.notes
                if (value == record.value && notes == record.notes) remote
                else local.copy(value = value, notes = notes, seq = record.seq, baseValue = record.value)
            }
            local.mtime >= record.mtime -> local.copy(seq = record.seq, baseValue = record.value)
            else -> remote
        }
    }
    return copy(habits = list, habitTombstones = tombstones, habitEntries = merged)
}

/**
 * I38: what the server took is clean here, unless it changed again while it was on its way; what
 * it refused comes back newer and merges like a pull.
 */
fun World.afterHabitPush(
    sent: HabitPush,
    accepted: HabitAcceptance,
    rejectedHabits: List<HabitRecord>,
    rejectedEntries: List<EntryRecord>,
): World {
    var list = habits
    val tombstones = habitTombstones.toMutableMap()
    for ((id, seq) in accepted.habits) {
        if (!sent.habits.containsKey(id)) continue
        val was = sent.habits[id]
        if (was == null) {
            tombstones -= id
            continue
        }
        list = list.map { h ->
            when {
                h.id != id -> h
                h == was -> h.copy(seq = seq, dirty = false)
                else -> h.copy(seq = seq)
            }
        }
    }
    val entries = habitEntries.toMutableMap()
    for ((key, seq) in accepted.entries) {
        val was = sent.entries[key] ?: continue
        val now = entries[key] ?: continue
        entries[key] = if (now.value == was.value && now.notes == was.notes) {
            now.copy(seq = seq, dirty = false, baseValue = null)
        } else {
            now.copy(seq = seq, baseValue = was.value)
        }
    }
    return copy(habits = list, habitTombstones = tombstones, habitEntries = entries)
        .mergeHabitRecords(rejectedHabits, rejectedEntries)
}

/** A habit added or edited here: dirty until the habit server takes it (I38). New ones go last. */
fun World.withHabit(habit: Habit, nowWall: Long): World {
    val existing = habit(habit.id)
    val edited = habit.copy(
        editedAtWall = nowWall,
        dirty = true,
        seq = existing?.seq ?: habit.seq,
        position = existing?.position ?: ((habits.maxOfOrNull { it.position } ?: -1) + 1),
    )
    return copy(
        habits = if (existing == null) habits + edited else habits.map { if (it.id == habit.id) edited else it },
        habitTombstones = habitTombstones - habit.id,
    )
}

/**
 * A habit deleted here: gone from the list and the focus timer, and deleted on the habit server
 * next time (a tombstone, if the server has it). Blocks keep their items for it, which can no
 * longer be done (I36); its entries stay with the history.
 */
fun World.withoutHabit(id: String, nowWall: Long): World {
    val habit = habit(id) ?: return this
    return copy(
        habits = habits.filter { it.id != id },
        habitTombstones = if (habit.seq > 0) habitTombstones + (id to HabitTombstone(habit.seq, nowWall)) else habitTombstones,
        focus = focus?.takeIf { it.habitId != id },
    )
}
