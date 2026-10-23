package com.xern.xrnrule.app.web

import android.os.SystemClock
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.domain.ExtensionApi
import com.xern.xrnrule.domain.Sites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The browser extension's door on the phone: Quetta's xrnrule extension calls
 * `http://127.0.0.1:47321` exactly as the laptop's calls the daemon ([ExtensionApi]). It says
 * which site is in front ([SiteSightings], read by the blocker service) and hears back whether
 * it's blocked and what's left. The phone's block screen stays the overlay; a blocked tab only
 * turns into the extension's own stand-in page.
 *
 * Loopback only. A write needs an `X-Xrnrule` header, which a web page can't add without a CORS
 * preflight this never approves, and the Host must be this address (DNS rebinding). Other apps
 * on the phone can still call it: they can read which sites are blocked and report a site in
 * front. Nothing they send may crash the app (see [handle]), and the extension only follows a
 * block page on this address, so an app that took the port while xrnrule was down couldn't send
 * blocked sites anywhere else.
 */
class ExtensionServer(
    private val store: WorldStore,
    private val websites: ExtensionWebsiteMonitor,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class Heartbeat(val host: String? = null, val focused: Boolean = false, val standIn: Boolean = false)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    /** Three at a time and a short queue: a flood of connections is turned away, not piled up. */
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) { r ->
        Thread(r, "extension-http").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        thread(name = "extension-server", isDaemon = true) { serve() }
        scope.launch {
            while (true) {
                SiteSightings.expire()
                delay(1_000)
            }
        }
    }

    private fun serve() {
        while (true) {
            // Someone else may hold the port for a moment (a reinstall's old process, adb reverse): retry.
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    // IPv4 on purpose: Android's getLoopbackAddress() is ::1, and the extension calls 127.0.0.1.
                    bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), PORT), 16)
                }
            } catch (_: IOException) {
                Thread.sleep(RETRY_MS)
                continue
            }
            server.use {
                while (true) {
                    val client = try {
                        it.accept()
                    } catch (_: IOException) {
                        break
                    }
                    try {
                        workers.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        client.close()
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5_000
                // Whoever calls gets one request's worth of time and headers: any app on the phone
                // can connect, and there are only three workers.
                val deadline = SystemClock.elapsedRealtime() + REQUEST_MS
                val input = BufferedInputStream(s.getInputStream())
                val request = readLine(input, deadline)?.split(' ') ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input, deadline) ?: return
                    if (line.isEmpty()) break
                    if (headers.size >= MAX_HEADERS) return
                    headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
                }
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
                val body = ByteArray(length).also { buf ->
                    var read = 0
                    while (read < length) {
                        if (SystemClock.elapsedRealtime() > deadline) return
                        val n = input.read(buf, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                }.decodeToString()
                val (code, text) = route(request.getOrNull(0).orEmpty(), request.getOrNull(1).orEmpty(), headers, body)
                val bytes = text.encodeToByteArray()
                val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                    "Content-Type: ${if (text.startsWith("{")) "application/json" else "text/plain; charset=utf-8"}\r\n" +
                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(head.encodeToByteArray())
                    write(bytes)
                    flush()
                }
            } catch (_: IOException) {
                // The page went away mid-request: nothing to answer.
            } catch (_: RuntimeException) {
                // A request we can't make sense of. Nothing may escape this thread: an uncaught
                // exception on any thread takes the whole app, and every block with it, down.
            }
        }
    }

    private fun route(method: String, target: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val host = headers["host"]
        if (host != "127.0.0.1:$PORT" && host != "localhost:$PORT") return 403 to "wrong host"
        if (method == "OPTIONS") return 403 to "no" // never approve a cross-origin preflight
        if (method == "POST" && headers["x-xrnrule"] == null) return 403 to "missing X-Xrnrule"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        SiteSightings.heard()
        websites.onHeard()
        return when {
            method == "POST" && path == "/ext/heartbeat" -> {
                val beat = runCatching { json.decodeFromString(Heartbeat.serializer(), body) }.getOrNull() ?: return 400 to "bad heartbeat"
                SiteSightings.report(beat.host?.let(Sites::normalize))
                200 to verdict(beat.host)
            }
            method == "GET" && path == "/ext/check" -> 200 to verdict(query["host"])
            method == "GET" && path == "/ext/rules" ->
                200 to json.encodeToString(ExtensionApi.SiteRules.serializer(), ExtensionApi.rules(store.world.value, null))
            else -> 404 to "not found"
        }
    }

    private fun verdict(host: String?): String = json.encodeToString(
        ExtensionApi.SiteVerdict.serializer(),
        ExtensionApi.verdict(store.engine, store.world.value, host, store.clock, page = null),
    )

    /** A query parameter as sent; a broken escape ("%" at the end, "%zz") reads as nothing. */
    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw, "UTF-8")
    } catch (_: IllegalArgumentException) {
        ""
    }

    /** One CRLF- (or LF-) terminated header line, or null at the end of the stream. */
    private fun readLine(input: InputStream, deadline: Long): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            if (out.length > 8_192) throw IOException("header line too long")
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("too slow")
            out.append(c.toChar())
        }
    }

    companion object {
        /** The laptop daemon's port too, so the extension needs no setting on either. */
        const val PORT = 47321
        private const val RETRY_MS = 30_000L
        private const val MAX_BODY = 64 * 1024
        private const val MAX_HEADERS = 64
        private const val REQUEST_MS = 10_000L
    }
}
