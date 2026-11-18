package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.AllowanceUse
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/**
 * The Insights page's history (`/api/history`): this laptop's log and the server's log of both
 * devices, each event once; the server's limit record with today's use from here; asked of the
 * server at most once a minute.
 */
class HistoryTest {
    private class TestClock(var mono: Long = 1_000_000, var wall: Long = 1_800_000_000_000) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = ZoneId.of("Asia/Tokyo")
    }

    private val dir = Files.createTempDirectory("xrnrule-history").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private class FakeServer(var events: List<JsonObject>? = emptyList(), var limits: JsonObject? = null) : RemoteHistory {
        val asked = mutableListOf<Long>()
        override fun events(sinceMs: Long): List<JsonObject>? {
            asked += sinceMs
            return events?.filter { it["at"]!!.jsonPrimitive.long >= sinceMs }
        }

        override fun limits(): JsonObject? = limits
    }

    private fun event(d: String, t: String, at: Long, vararg extra: Pair<String, Any>) = buildJsonObject {
        put("d", d)
        put("t", t)
        put("at", at)
        for ((k, v) in extra) if (v is Long) put(k, v) else put(k, v.toString())
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private val block = Block(
        "yt", "YouTube", targetPackages = setOf("com.google.android.youtube"), targetSites = setOf("youtube.com"), exceptSites = setOf("music.youtube.com"),
        targetDesktopApps = setOf("freetube"), mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 1_800_000, perDevice = true,
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
    )

    @Test
    fun theLaptopsOwnLogAndTheServersAreOneHistoryEachEventOnce() {
        val clock = TestClock()
        val now = clock.wall
        val log = FileTelemetry(File(dir, "telemetry.log"))
        log.onSiteBlocked("youtube.com", now - 60_000) // sent to the server already
        log.onBlockedImpression("steam", now - 30_000) // not yet
        val server = FakeServer(
            listOf(
                event("laptop", "blocked", now - 60_000, "target" to "youtube.com", "name" to "youtube.com"),
                event("phone", "blocked", now - 50_000, "target" to "com.google.android.youtube", "name" to "YouTube"),
                event("phone", "unlock", now - 40_000, "block" to "yt", "ms" to 300_000L, "start" to now - 400_000),
            ),
        )
        val history = History(log, server, clock) { it() }
        val got = parse(history.json(World(blocks = listOf(block)), 30))
        val events = got["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("laptop youtube.com", "phone com.google.android.youtube", "phone yt", "laptop steam"),
            events.map { "${it["d"]!!.jsonPrimitive.content} ${(it["target"] ?: it["block"])!!.jsonPrimitive.content}" },
        )
        assertEquals("Asia/Tokyo", got["zone"]!!.jsonPrimitive.content)
        assertTrue(got["reached"]!!.jsonPrimitive.content.toBoolean())
        // The blocks in list order, with what they block, to tell a try's block by.
        val b = got["blocks"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("com.google.android.youtube", "freetube"), b["apps"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("music.youtube.com"), b["except"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1_800_000, b["dailyMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun emergencyUnlocksComeFromTheSharedStateWhichSaysNoDevice() {
        val clock = TestClock()
        val recent = "yt@${clock.wall - 3_600_000}"
        val old = "yt@${clock.wall - 40 * 86_400_000L}"
        val world = World(blocks = listOf(block), emergencyLog = mapOf(recent to clock.wall - 3_600_000, old to clock.wall - 40 * 86_400_000L))
        val got = parse(History(FileTelemetry(File(dir, "t.log")), null, clock) { it() }.json(world, 30))
        assertEquals("the range's only", setOf(recent), got["emergencies"]!!.jsonObject.keys)
    }

    @Test
    fun withoutSyncOrWithTheServerOutOfReachItsTheLaptopsLogAlone() {
        val clock = TestClock()
        val log = FileTelemetry(File(dir, "telemetry.log"))
        log.onBreakTaken(900_000, clock.wall - 1_000)
        val alone = parse(History(log, null, clock) { it() }.json(World(), 7))
        assertEquals(1, alone["events"]!!.jsonArray.size)
        assertFalse(alone["synced"]!!.jsonPrimitive.content.toBoolean())
        val down = parse(History(log, FakeServer(events = null), clock) { it() }.json(World(), 7))
        assertEquals(1, down["events"]!!.jsonArray.size)
        assertFalse(down["reached"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun theServerIsAskedAtMostOnceAMinuteAndItsWholeLogEveryHalfHour() {
        val clock = TestClock()
        val server = FakeServer()
        val history = History(FileTelemetry(File(dir, "telemetry.log")), server, clock) { it() }
        val week = clock.wall - 7 * 86_400_000L
        history.json(World(), 7)
        history.json(World(), 7)
        clock.mono += 30_000
        history.json(World(), 7)
        assertEquals("once in the first minute", listOf(week), server.asked)
        clock.mono += 31_000
        history.json(World(), 7)
        assertEquals("then the last two days", clock.wall - 2 * 86_400_000L, server.asked.last())
        clock.mono += 30 * 60_000
        history.json(World(), 7)
        assertEquals("and the whole of it again", week, server.asked.last())
        // A longer range than it has is asked at once.
        history.json(World(), 30)
        assertEquals(clock.wall - 30 * 86_400_000L, server.asked.last())
    }

    @Test
    fun aLimitsUseIsTheServersRecordWithTodaysAsTheLaptopHasItNow() {
        val clock = TestClock()
        val today = 1_799_990_000_000L
        val record = buildJsonObject {
            put("yt", buildJsonObject {
                put("phone-1", buildJsonObject { put((today - 86_400_000).toString(), 1_200_000); put(today.toString(), 300_000) })
            })
        }
        val world = World(blocks = listOf(block), otherAllowance = mapOf("yt" to mapOf("phone-1" to AllowanceUse(today, 600_000))))
        val got = parse(History(FileTelemetry(File(dir, "t.log")), FakeServer(limits = record), clock) { it() }.json(world, 7))
        val days = got["limits"]!!.jsonObject["yt"]!!.jsonObject["phone-1"]!!.jsonObject
        assertEquals(JsonPrimitive(1_200_000), days[(today - 86_400_000).toString()])
        assertEquals("the newer, larger use", JsonPrimitive(600_000), days[today.toString()])
    }
}
