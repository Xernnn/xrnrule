package com.xern.xrnrule.app.data

import com.sun.net.httpserver.HttpServer
import com.xern.xrnrule.domain.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone sending its screen time ([UsageUpload]) to a made-up sync server on a free port, which
 * takes `POST /usage` as `server/xrnrule-sync.py` does (docs/laptop-screen-time.md).
 */
class UsageUploadTest {
    private val posts = CopyOnWriteArrayList<String>()
    private val devices = CopyOnWriteArrayList<String?>()

    /** What the server answers a good login with. */
    @Volatile
    private var answer = 200
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).apply {
        createContext("/xrnrule/usage") { ex ->
            val code = when {
                ex.requestHeaders.getFirst("Authorization") != login("alex") && ex.requestHeaders.getFirst("Authorization") != login("sam") -> 401
                ex.requestMethod != "POST" -> 405
                else -> answer
            }
            val body = ex.requestBody.readBytes().decodeToString()
            if (code == 200) {
                posts += body
                devices += ex.requestHeaders.getFirst("X-Device")
            }
            val out = """{"ok": true}""".toByteArray()
            ex.sendResponseHeaders(code, out.size.toLong())
            ex.responseBody.use { it.write(out) }
            ex.close()
        }
        start()
    }
    private fun login(user: String) = "Basic " + Base64.getEncoder().encodeToString("$user:pw".toByteArray())
    private val url = "http://127.0.0.1:${http.address.port}/xrnrule"
    private var server: IconUpload.Server? = IconUpload.Server(url, "alex", "pw")

    private val zone = ZoneId.of("Asia/Tokyo")
    private var today = LocalDate.of(2026, 11, 26)
    private var mono = 5_000_000L
    private val clock = object : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        override fun zone() = zone
    }
    private val prefs = MemoryPrefs()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The phone's own time, made up: [use] by day; [asked], the spans a pass asked for. */
    private class FakeSource : UsageUpload.Source {
        val use = HashMap<LocalDate, UsageDay>()
        val asked = CopyOnWriteArrayList<Pair<LocalDate, LocalDate>>()

        override fun days(first: LocalDate, last: LocalDate): Map<LocalDate, UsageDay> {
            asked += first to last
            return use.filterKeys { !it.isBefore(first) && !it.isAfter(last) }
        }

        override fun name(pkg: String): String = mapOf("com.example.reader" to "Reader", "com.example.chat" to "Chat")[pkg] ?: pkg
    }

    private val source = FakeSource()
    private val upload = UsageUpload({ server }, source, prefs, clock, scope)

    private fun day(back: Long) = LocalDate.of(2026, 11, 26).minusDays(back)

    @After
    fun stop() {
        scope.cancel()
        http.stop(0)
    }

    @Test
    fun aPassSendsThePhonesDaysInTheLaptopsFormatAsThePhone() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 600_000L, "com.example.chat" to 60_000L), mapOf("example.org" to 300_000L))
        source.use[day(1)] = UsageDay(mapOf("com.example.tool" to 1_000L, "com.example.none" to 0L))
        source.use[day(2)] = UsageDay(mapOf("com.example.none" to 0L)) // nothing to say
        assertTrue(upload.pass())
        assertEquals(listOf("phone"), devices)
        assertEquals(
            """{"days":{"2026-11-25":{"apps":{"com.example.tool":1000},"sites":{}},""" +
                """"2026-11-26":{"apps":{"com.example.chat":60000,"com.example.reader":600000},"sites":{"example.org":300000}}},""" +
                """"names":{"com.example.chat":"Chat","com.example.reader":"Reader"}}""",
            posts.single(),
        )
        // The first pass goes as far back as Insights does.
        assertEquals(listOf(day(UsageUpload.BACK_DAYS - 1L) to day(0)), source.asked)
    }

    @Test
    fun laterPassesSendYesterdayAndToday() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        upload.pass()
        upload.pass() // later the same day: yesterday too, for what came after a pass the day before
        today = today.plusDays(1)
        upload.pass()
        assertEquals(listOf(day(29) to day(0), day(1) to day(0), day(0) to day(-1)), source.asked)
    }

    @Test
    fun offlineTheDaysWaitAndGoWhenTheServerAnswers() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        upload.pass()
        answer = 503
        for (d in 1..3) {
            today = today.plusDays(1)
            source.use[today] = UsageDay(mapOf("com.example.reader" to d * 60_000L))
            assertRefused { upload.pass() }
        }
        // Then nobody there at all.
        val real = server
        server = IconUpload.Server("http://127.0.0.1:${closedPort()}/xrnrule", "alex", "pw")
        today = today.plusDays(1)
        assertRefused { upload.pass() }
        server = real
        assertEquals(1, posts.size)
        // Back: every day since the last that went, that one included.
        answer = 200
        assertTrue(upload.pass())
        assertEquals(day(0) to day(-4), source.asked.last())
        for (d in 26..29) assertTrue("2026-11-$d", "\"2026-11-$d\"" in posts.last())
    }

    private fun assertRefused(pass: () -> Unit) {
        try {
            pass()
            fail("a server that refuses, or isn't there, is no send")
        } catch (_: IOException) {
        }
    }

    private fun closedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun aPhoneOfflineForLongerCatchesUpAMonthAtMost() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        upload.pass()
        today = today.plusDays(45)
        upload.pass()
        assertEquals(today.minusDays(UsageUpload.BACK_DAYS - 1L) to today, source.asked.last())
    }

    @Test
    fun anotherServerOrUserGetsTheWholeMonth() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        upload.pass()
        server = IconUpload.Server(url, "sam", "pw")
        upload.pass()
        assertEquals(day(29) to day(0), source.asked.last())
    }

    @Test
    fun withSyncOffNothingGoesNorIsCounted() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        server = null
        assertFalse(upload.pass())
        assertTrue(posts.isEmpty())
        assertTrue("its usage isn't even read", source.asked.isEmpty())
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun aDayWithNoTimeIsNoBody() {
        assertNull(UsageUpload.body(mapOf(day(0) to UsageDay(mapOf("com.example.reader" to 0L))), { it }))
        assertTrue(upload.pass())
        assertTrue("nothing to send", posts.isEmpty())
    }

    @Test
    fun syncRoundsThatWorkBringAPassAtMostSoOften() {
        source.use[day(0)] = UsageDay(mapOf("com.example.reader" to 60_000L))
        val status = MutableStateFlow<SharedSyncStatus?>(null)
        upload.start(status)
        status.value = SharedSyncStatus(1, ok = false, message = "Can't reach the server")
        Thread.sleep(200)
        assertTrue("a round that failed brings none", source.asked.isEmpty())
        status.value = SharedSyncStatus(2, ok = true, message = "Up to date")
        await("the first pass") { posts.size == 1 }
        // Rounds every minute: no pass until PASS_MS has gone by.
        repeat(4) {
            mono += 60_000
            status.value = SharedSyncStatus(3L + it, ok = true, message = "Up to date")
            Thread.sleep(100)
        }
        assertEquals(1, source.asked.size)
        mono += UsageUpload.PASS_MS
        status.value = SharedSyncStatus(10, ok = true, message = "Up to date")
        await("a pass after the wait") { posts.size == 2 }
    }

    private fun await(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }
}
