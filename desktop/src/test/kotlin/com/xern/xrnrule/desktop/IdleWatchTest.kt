package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Clock
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWatchTest {

    private object FixedClock : Clock {
        override fun nowMonotonicMs() = 1_000_000L
        override fun nowWallMs() = 1_800_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    /** The requests a client sent, read off the socket as the compositor would. */
    private class Inbox(private val channel: SocketChannel) {
        private val conn = Wayland.Connection(channel)
        fun next(): Wayland.Message = conn.next() ?: error("the client hung up")
    }

    /**
     * A compositor of the smallest kind, on a socket in a temp dir: it lists [globals], answers the
     * sync, then checks what the client binds and asks for, and sends it [then] (object, opcode).
     */
    private fun compositor(globals: List<Pair<String, Int>>, then: List<Pair<Int, Int>>, seen: MutableList<String>): File {
        val socket = File(Files.createTempDirectory("wl").toFile(), "wayland-9")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        thread(isDaemon = true) {
            server.accept().use { client ->
                val inbox = Inbox(client)
                fun send(bytes: ByteArray) {
                    val b = ByteBuffer.wrap(bytes)
                    while (b.hasRemaining()) client.write(b)
                }
                repeat(2) {
                    val m = inbox.next()
                    seen += "display ${m.objectId}:${m.opcode} ${m.body.int}"
                }
                // Every global, then the sync's callback, all in one write: the client must split them.
                send(globals.mapIndexed { i, (iface, ver) -> Wayland.request(2, 0, i + 1, iface, ver) }.fold(ByteArray(0)) { a, b -> a + b } + Wayland.request(3, 0, 0))
                val names = globals.mapIndexed { i, (iface, _) -> iface to i + 1 }.toMap()
                if ("ext_idle_notifier_v1" in names && "wl_seat" in names) {
                    repeat(2) {
                        val m = inbox.next()
                        val name = m.body.int
                        val iface = Wayland.readString(m.body)
                        val version = m.body.int
                        val id = m.body.int
                        seen += "bind ${m.objectId}:${m.opcode} $iface=${names[iface] == name} v$version as $id"
                    }
                    val m = inbox.next()
                    seen += "notify ${m.objectId}:${m.opcode} id ${m.body.int} after ${m.body.int} ms on ${m.body.int}"
                    for ((obj, op) in then) send(Wayland.request(obj, op))
                }
            }
            server.close()
        }
        return socket
    }

    @Test
    fun asksForTheIdleNoticeAndReportsIt() {
        val seen = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        val socket = compositor(
            listOf("wl_compositor" to 6, "wl_seat" to 9, "zwp_idle_inhibit_manager_v1" to 1, "ext_idle_notifier_v1" to 2),
            listOf(6 to 0, 6 to 1),
            seen,
        )
        val watch = IdleWatch({ socket }, FixedClock, onIdle = { events += "idle since $it" }, onActive = { events += "active" })
        assertTrue(watch.watchOnce()) // until the compositor goes
        assertEquals(
            listOf(
                "display 1:1 2", // get_registry
                "display 1:0 3", // sync
                "bind 2:0 wl_seat=true v1 as 4",
                "bind 2:0 ext_idle_notifier_v1=true v1 as 5",
                "notify 5:1 id 6 after 180000 ms on 4",
            ),
            seen,
        )
        // Idle since the last input: the notice comes three minutes after it.
        assertEquals(listOf("idle since ${1_000_000L - IdleWatch.IDLE_MS}", "active"), events)
    }

    @Test
    fun aCompositorWithoutTheIdleNoticeIsToldApart() {
        val seen = CopyOnWriteArrayList<String>()
        val socket = compositor(listOf("wl_compositor" to 6, "wl_seat" to 9), emptyList(), seen)
        val watch = IdleWatch({ socket }, FixedClock, onIdle = { error("no notice to give") }, onActive = {})
        assertFalse(watch.watchOnce())
    }

    @Test
    fun noDisplayIsAnError() {
        val watch = IdleWatch({ null }, FixedClock, onIdle = {}, onActive = {})
        assertTrue(runCatching { watch.watchOnce() }.isFailure)
    }

    @Test
    fun stringsAreLengthNulAndPadding() {
        // "wl_seat" is 7 bytes and its NUL: 8, so no padding.
        assertEquals(12, Wayland.string("wl_seat").size)
        // "ext_idle_notifier_v1" is 20 bytes and its NUL: 21, padded to 24.
        val s = Wayland.string("ext_idle_notifier_v1")
        assertEquals(28, s.size)
        assertEquals("ext_idle_notifier_v1", Wayland.readString(ByteBuffer.wrap(s).order(java.nio.ByteOrder.nativeOrder())))
    }

    @Test
    fun aMessageSplitAcrossReadsWaitsForItsRest() {
        val whole = Wayland.request(2, 0, 7, "wl_seat", 9)
        val buf = ByteBuffer.allocate(64)
        buf.put(whole, 0, 10).flip()
        assertTrue(Wayland.take(buf).isEmpty())
        buf.compact().put(whole, 10, whole.size - 10).flip()
        val got = Wayland.take(buf).single()
        assertEquals(2, got.objectId)
        assertEquals(0, got.opcode)
        assertEquals(7, got.body.int)
        assertEquals("wl_seat", Wayland.readString(got.body))
        assertEquals(9, got.body.int)
        assertFalse(buf.hasRemaining())
    }

    @Test
    fun theHeaderHoldsTheSizeAndTheOpcode() {
        val bytes = Wayland.request(5, 1, 6, 180_000, 4)
        val b = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        assertEquals(5, b.int)
        assertEquals((20 shl 16) or 1, b.int)
        assertArrayEquals(intArrayOf(6, 180_000, 4), intArrayOf(b.int, b.int, b.int))
    }

    @Test
    fun theSocketIsTheSessionsDisplay() {
        val run = Files.createTempDirectory("run").toFile()
        assertEquals(File(run, "wayland-1"), IdleWatch.socketOf(mapOf("XDG_RUNTIME_DIR" to run.path, "WAYLAND_DISPLAY" to "wayland-1")))
        assertEquals(File("/tmp/elsewhere"), IdleWatch.socketOf(mapOf("XDG_RUNTIME_DIR" to run.path, "WAYLAND_DISPLAY" to "/tmp/elsewhere")))
        // A service started without it finds the one there, not its lock file.
        assertNull(IdleWatch.socketOf(mapOf("XDG_RUNTIME_DIR" to run.path)))
        File(run, "wayland-1.lock").writeText("")
        File(run, "wayland-1").writeText("")
        assertEquals(File(run, "wayland-1"), IdleWatch.socketOf(mapOf("XDG_RUNTIME_DIR" to run.path)))
    }
}
