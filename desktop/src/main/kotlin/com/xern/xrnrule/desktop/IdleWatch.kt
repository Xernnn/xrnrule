package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Clock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * When the laptop's user stops touching it, for screen time ([UsageLog]): the compositor's own
 * idle notice, through the standard Wayland protocol `ext-idle-notify-v1`, which idle daemons use
 * and KWin and Hyprland both have. It needs no permission: any program of the session may ask.
 * The compositor won't call it idle while an app inhibits idle, as a browser does while a video
 * plays.
 *
 * This is a Wayland client of the smallest kind: it binds the seat and the idle notifier, asks for
 * one notification after [timeoutMs] without input, and listens. It makes no surface and draws
 * nothing. Statistics only: if it fails, idle time counts as use, which is more, never less.
 */
class IdleWatch(
    private val socket: () -> File?,
    private val clock: Clock,
    private val onIdle: (sinceMono: Long) -> Unit,
    private val onActive: () -> Unit,
    private val timeoutMs: Long = IDLE_MS,
) {
    fun start() {
        thread(isDaemon = true, name = "idle-watch") {
            var told: String? = null
            while (true) {
                val outcome = try {
                    if (watchOnce()) "lost the compositor" else "the compositor has no idle notice (ext-idle-notify-v1)"
                } catch (e: Exception) {
                    "can't ask the compositor ($e)"
                }
                // Idle or not is unknown now: count, as if no idle notice existed.
                onActive()
                if (outcome != told) System.err.println("Screen time: $outcome; idle time counts as use until it's back")
                told = outcome
                Thread.sleep(RETRY_MS)
            }
        }
    }

    /**
     * Connects, asks for the notice and reports it until the compositor goes (true), or returns
     * false at once when the compositor has no idle notice. Throws when there's no compositor.
     */
    fun watchOnce(): Boolean {
        val path = socket() ?: throw IOException("no Wayland display")
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path.toPath()))
            val conn = Wayland.Connection(channel)
            conn.send(Wayland.request(DISPLAY, 1, REGISTRY)) // wl_display.get_registry
            conn.send(Wayland.request(DISPLAY, 0, CALLBACK)) // wl_display.sync: done once every global is told
            val globals = HashMap<String, Int>()
            while (true) {
                val m = conn.next() ?: return true
                when {
                    m.objectId == REGISTRY && m.opcode == 0 -> { // wl_registry.global(name, interface, version)
                        val name = m.body.int
                        val iface = Wayland.readString(m.body)
                        globals.putIfAbsent(iface, name)
                    }
                    m.objectId == CALLBACK && m.opcode == 0 -> break
                    m.objectId == DISPLAY && m.opcode == 0 -> throw IOException(Wayland.error(m))
                }
            }
            val seat = globals["wl_seat"] ?: return false
            val notifier = globals[NOTIFIER] ?: return false
            conn.send(Wayland.request(REGISTRY, 0, seat, "wl_seat", 1, SEAT)) // wl_registry.bind
            conn.send(Wayland.request(REGISTRY, 0, notifier, NOTIFIER, 1, IDLE_NOTIFIER))
            // ext_idle_notifier_v1.get_idle_notification(id, timeout ms, seat): idle inhibitors count.
            conn.send(Wayland.request(IDLE_NOTIFIER, 1, NOTIFICATION, timeoutMs.toInt(), SEAT))
            while (true) {
                val m = conn.next() ?: return true
                when {
                    m.objectId == NOTIFICATION && m.opcode == 0 -> onIdle(clock.nowMonotonicMs() - timeoutMs) // idled
                    m.objectId == NOTIFICATION && m.opcode == 1 -> onActive() // resumed
                    m.objectId == DISPLAY && m.opcode == 0 -> throw IOException(Wayland.error(m))
                }
            }
        }
    }

    companion object {
        /** No input this long is idle; ActivityWatch's default too. */
        const val IDLE_MS = 3 * 60_000L
        private const val RETRY_MS = 30_000L
        private const val NOTIFIER = "ext_idle_notifier_v1"

        // Our object ids, in the order they're made; the display is always 1.
        private const val DISPLAY = 1
        private const val REGISTRY = 2
        private const val CALLBACK = 3
        private const val SEAT = 4
        private const val IDLE_NOTIFIER = 5
        private const val NOTIFICATION = 6

        /**
         * The session's Wayland socket: `$WAYLAND_DISPLAY` (a name in `$XDG_RUNTIME_DIR`, or a path),
         * else the newest `wayland-*` socket there, for a service started without it.
         */
        fun socketOf(env: Map<String, String> = System.getenv()): File? {
            val runtime = File(env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() } ?: "/run/user/${uid()}")
            env["WAYLAND_DISPLAY"]?.takeIf { it.isNotBlank() }?.let { name ->
                return if (name.startsWith("/")) File(name) else File(runtime, name)
            }
            return runtime.listFiles { f -> f.name.startsWith("wayland-") && !f.name.endsWith(".lock") }
                ?.maxByOrNull { it.lastModified() }
        }

        private fun uid(): Int = runCatching { Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int }.getOrDefault(1000)
    }
}

/** The Wayland wire format, as much of it as [IdleWatch] needs: no file descriptors, no arrays. */
internal object Wayland {
    /** One event: the object it's for, its opcode, its arguments still to read. */
    class Message(val objectId: Int, val opcode: Int, val body: ByteBuffer)

    private val ORDER: ByteOrder = ByteOrder.nativeOrder() // Wayland speaks the machine's own

    /**
     * A request: an object id, then (size << 16 | opcode), then its arguments, each four bytes: an
     * Int as a uint or an object, a String as its length with the NUL, its bytes and the NUL, padded
     * to four. A bind's new id is its interface, version and id, so three arguments in a row.
     */
    fun request(objectId: Int, opcode: Int, vararg args: Any): ByteArray {
        val body = ByteArrayOutputStream()
        for (a in args) {
            when (a) {
                is Int -> body.write(int(a))
                is String -> body.write(string(a))
                else -> throw IllegalArgumentException("no Wayland argument of type ${a::class}")
            }
        }
        val size = 8 + body.size()
        return ByteBuffer.allocate(8).order(ORDER).putInt(objectId).putInt((size shl 16) or opcode).array() + body.toByteArray()
    }

    private fun int(v: Int): ByteArray = ByteBuffer.allocate(4).order(ORDER).putInt(v).array()

    fun string(s: String): ByteArray {
        val bytes = s.encodeToByteArray()
        val padded = (bytes.size + 1 + 3) and 3.inv()
        return int(bytes.size + 1) + bytes.copyOf(padded)
    }

    fun readString(body: ByteBuffer): String {
        val length = body.int
        if (length <= 0) return ""
        val bytes = ByteArray(length - 1)
        body.get(bytes)
        body.position(body.position() + ((length + 3) and 3.inv()) - (length - 1))
        return bytes.decodeToString()
    }

    /** wl_display.error(object, code, message) as a sentence. */
    fun error(m: Message): String {
        val obj = m.body.int
        val code = m.body.int
        return "Wayland error $code on object $obj: ${readString(m.body)}"
    }

    /** Takes every whole event at the front of [buf] (ready to read), leaving a part one there. */
    fun take(buf: ByteBuffer): List<Message> {
        val out = ArrayList<Message>()
        buf.order(ORDER)
        while (buf.remaining() >= 8) {
            val start = buf.position()
            val objectId = buf.getInt(start)
            val word = buf.getInt(start + 4)
            val size = word ushr 16
            if (size < 8) throw IOException("a Wayland message of $size bytes")
            if (buf.remaining() < size) break
            val body = ByteArray(size - 8)
            buf.position(start + 8)
            buf.get(body)
            out += Message(objectId, word and 0xffff, ByteBuffer.wrap(body).order(ORDER))
        }
        return out
    }

    /** Requests out, events in, over one socket. */
    class Connection(private val channel: SocketChannel) {
        private val inbox = ByteBuffer.allocate(64 * 1024)
        private val ready = ArrayDeque<Message>()

        fun send(bytes: ByteArray) {
            val out = ByteBuffer.wrap(bytes)
            while (out.hasRemaining()) channel.write(out)
        }

        /** The next event, or null once the compositor has closed the connection. */
        fun next(): Message? {
            while (ready.isEmpty()) {
                if (channel.read(inbox) < 0) return null
                inbox.flip()
                ready += take(inbox)
                inbox.compact()
                if (!inbox.hasRemaining()) throw IOException("a Wayland message larger than ${inbox.capacity()} bytes")
            }
            return ready.removeFirst()
        }
    }
}
