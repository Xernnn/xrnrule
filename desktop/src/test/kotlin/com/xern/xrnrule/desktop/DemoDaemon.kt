package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.AllowanceUse
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.DayWindow
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.Reward
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.SiteIcons
import com.xern.xrnrule.domain.TimeWindow
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.startOfLocalDayMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * The laptop's page on made-up data, for looking at it: blocks of every kind, a month of history
 * on both devices, site icons drawn here. Nothing is enforced (no window system, no
 * notifications), nothing syncs and nothing is fetched from the network.
 *
 *     ./gradlew --no-daemon -q :desktop:demo --args="47390 /tmp/some-empty-dir"     (or "… empty")
 *
 * With `XRNRULE_DEMO_ICONS=<a test sync server's /xrnrule URL>` and `XRNRULE_DEMO_LOGIN=user:password`
 * it trades logos with that server ([ServerIcons]), and that alone: the blocks' laptop apps' icons
 * and sites' (drawn here) go up, the other devices' come down for the page.
 *
 * Never point it at the real data dir, nor at the real server: it writes its own world there.
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 47390
    val dir = File(args.getOrNull(1) ?: error("give it an empty data dir")).apply { mkdirs() }
    val empty = args.getOrNull(2) == "empty"
    val clock = DesktopClock()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = Config(server = "", user = "", password = "", port = port, dataDir = dir)
    val world = Demo.world(clock.nowWallMs(), clock.startOfLocalDayMs(), empty)
    val telemetryFile = File(dir, "telemetry.log")
    if (!empty) telemetryFile.writeText(Demo.laptopLog(clock.nowWallMs(), clock.zone()).joinToString("") { it + "\n" })
    if (!empty) File(dir, "usage.json").writeText(Demo.usage(clock.nowWallMs(), clock.zone()))
    val store = Store(
        BlockEngine(),
        object : PersistPort {
            override fun loadWorld() = world
            override fun save(world: World) = Unit
        },
        clock,
        scope,
    )
    runBlocking { store.dispatchAndGet(BlockEvent.BootOrColdStart) }
    val foreground = Foreground(store, File(dir, "browsers.txt"))
    foreground.heardExtension(true)
    val telemetry = FileTelemetry(telemetryFile)
    val quiet = object : Notifier(config) {
        override fun notify(title: String, body: String, urgent: Boolean) = Unit
        override fun blocked(appId: String) = Unit
    }
    val enforcer = Enforcer(store, quiet, config).also { it.foreground = foreground }
    enforcer.reported()
    val shared = SharedSyncClient(config, store, telemetry, scope)
    val icons = SiteIconCache(File(dir, "site-icons"), scope, Demo::fetchIcon)
    val remote = if (empty) null else Demo.Remote(dir, clock.nowWallMs(), clock.zone(), clock.startOfLocalDayMs(), telemetry)
    val laptopIcons = LaptopAppIcons()
    val iconServer = System.getenv("XRNRULE_DEMO_ICONS")?.trimEnd('/')
    val login = System.getenv("XRNRULE_DEMO_LOGIN").orEmpty().split(":", limit = 2)
    val serverIcons = iconServer?.let { url ->
        ServerIcons(File(dir, "server-icons"), url, Http(login[0], login.getOrElse(1) { "" }), store.world, { ServerIcons.owned(it, icons, laptopIcons) }, scope)
            .also { it.start(kotlinx.coroutines.flow.MutableStateFlow(null)) }
    }
    val usage = UsageLog(File(dir, "usage.json"), clock)
    LocalServer(config, store, foreground, enforcer, shared, HabitClient(config, store, scope), icons, telemetry, remote, laptopIcons, serverIcons, usage).start()
    println("demo on http://127.0.0.1:$port/ (data in $dir)")
    // Keep the extension "seen" and the clock ticking, as the real loop does.
    while (true) {
        foreground.heardExtension(true)
        enforcer.reported()
        store.dispatch(BlockEvent.Tick)
        Thread.sleep(1_000)
    }
}

/** The made-up blocks and history. */
object Demo {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    fun world(now: Long, dayStart: Long, empty: Boolean): World {
        val every = (1..7).toSet()
        val blocks = listOf(
            Block(
                "social", "Social", targetPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
                targetSites = setOf("reddit.com", "x.com", "instagram.com"),
                trigger = Trigger(TriggerType.CHECKLIST, -1, TriggerUnit.ITEMS), reward = Reward(unlockDurationMs = 15 * MIN),
            ),
            Block(
                "youtube", "YouTube", targetPackages = setOf("com.google.android.youtube"), targetSites = setOf("youtube.com"),
                exceptSites = setOf("music.youtube.com"), mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 30 * MIN,
                trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
            ),
            Block(
                "games", "Games", targetDesktopApps = setOf("steam", "net.lutris.Lutris"), mode = BlockMode.SCHEDULE,
                schedule = Schedule(setOf(1, 2, 3, 4, 5), TimeWindow(9 * 60, 18 * 60), listOf(DayWindow(every, TimeWindow(23 * 60, 6 * 60)))),
                trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS), devices = Devices.LAPTOP,
            ),
            Block(
                "news", "News", targetSites = setOf("news.ycombinator.com", "theverge.com"),
                trigger = Trigger(TriggerType.TIMER, 20 * MIN, TriggerUnit.MS), reward = Reward(unlockDurationMs = 10 * MIN),
                dailyAllowanceMs = 15 * MIN,
            ),
            Block(
                "chat", "Chat", targetDesktopApps = setOf("discord"), targetSites = setOf("discord.com"), mode = BlockMode.LOCKDOWN,
                lockdownMs = 90 * MIN, trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
            ),
        ).let { if (empty) it.take(2) else it }
        val items = listOf(
            ChecklistItem("t1", "social", "Stretch for 5 minutes", 0, done = true),
            ChecklistItem("t2", "social", "Inbox zero", 1),
            ChecklistItem("t3", "social", "Read 10 pages", 2),
        )
        return World(
            blocks = blocks,
            checklist = mapOf("social" to items),
            deviceId = "laptop-demo",
            deviceKind = DeviceKind.LAPTOP,
            otherAllowance = if (empty) emptyMap() else mapOf(
                "youtube" to mapOf("phone-demo" to AllowanceUse(dayStartWall = dayStart, ms = 19 * MIN, atWall = now - 5 * MIN)),
                "news" to mapOf("phone-demo" to AllowanceUse(dayStartWall = dayStart, ms = 6 * MIN, atWall = now - 50 * MIN)),
            ),
        )
    }

    /** Hours people reach for their phone and laptop, weighted: evenings most, nights least. */
    private val hourWeight = doubleArrayOf(
        0.6, 0.3, 0.1, 0.05, 0.05, 0.1, 0.3, 0.8, 1.2, 1.0, 0.9, 1.1, 1.6, 1.3, 1.0, 1.1, 1.3, 1.6, 2.0, 2.4, 2.8, 2.6, 2.0, 1.2,
    )

    private fun pickHour(r: Random): Int {
        var x = r.nextDouble() * hourWeight.sum()
        for (h in hourWeight.indices) {
            x -= hourWeight[h]
            if (x <= 0) return h
        }
        return 20
    }

    /** One device's log over the last 35 days, in [FileTelemetry]'s line format, oldest first. */
    fun log(now: Long, zone: ZoneId, seed: Int, apps: List<String>, sites: List<String>): List<Pair<Long, String>> {
        val r = Random(seed)
        val out = mutableListOf<Pair<Long, String>>()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (back in 0..34) {
            val date = today.minusDays(back.toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekend = date.dayOfWeek.value >= 6
            // A good stretch two weeks ago, and a worse one lately.
            val mood = when (back) { in 9..13 -> 0.35; in 0..3 -> 1.4; else -> 1.0 } * (if (weekend) 1.5 else 1.0)
            val tries = (r.nextInt(4, 14) * mood).toInt()
            repeat(tries) {
                val at = start + pickHour(r) * HOUR + r.nextLong(HOUR)
                if (at >= now) return@repeat
                val app = r.nextInt(apps.size + sites.size)
                if (app < apps.size) out += at to "blocked;${apps[app]};$at"
                else out += at to "site;${sites[app - apps.size]};$at"
            }
            if (r.nextDouble() < 0.8) {
                val at = start + (7 + r.nextInt(5)) * HOUR + r.nextLong(HOUR)
                if (at < now) {
                    out += at to "habit;social;$at"
                    val used = (8 + r.nextInt(8)) * MIN
                    out += at + used to "unlock;social;$used;${at + used};$at"
                }
            }
            if (r.nextDouble() < 0.5) {
                val at = start + (13 + r.nextInt(8)) * HOUR
                if (at < now) {
                    out += at to "habit;news;$at"
                    out += at + 10 * MIN to "unlock;news;${10 * MIN};${at + 10 * MIN};$at"
                }
            }
            if (r.nextDouble() < 0.3) {
                val at = start + (17 + r.nextInt(5)) * HOUR
                if (at < now) out += at to "bought;youtube;${5 * MIN};$at"
            }
            if (r.nextDouble() < 0.25) {
                val at = start + (14 + r.nextInt(6)) * HOUR
                if (at < now) out += at to "break;${15 * MIN};$at"
            }
        }
        return out.filter { it.first < now }.sortedBy { it.first }
    }

    /**
     * The sync server as the demo has it: the laptop's log as sent, the phone's own tries and
     * purchases, the habits and unlocks the phone logged too (a little later), a month of limit use.
     */
    class Remote(dir: File, now: Long, zone: ZoneId, today: Long, laptop: FileTelemetry) : RemoteHistory {
        private val events: List<JsonObject>
        private val limits: JsonObject

        init {
            val names = mapOf("com.instagram.android" to "Instagram", "com.zhiliaoapp.musically" to "TikTok", "com.google.android.youtube" to "YouTube")
            val phoneFile = File(dir, "phone-telemetry.log")
            phoneFile.writeText(
                log(now, zone, 11, names.keys.toList(), listOf("reddit.com", "m.youtube.com", "x.com")).map { it.second }
                    .filterNot { it.startsWith("habit") || it.startsWith("unlock") }.joinToString("") { it + "\n" },
            )
            val mine = laptop.eventsAfter(0).map { JsonObject(it + ("d" to JsonPrimitive("laptop"))) }
            val phone = FileTelemetry(phoneFile).eventsAfter(0).map { e ->
                val target = e["target"]?.jsonPrimitive?.content
                JsonObject(e + ("d" to JsonPrimitive("phone")) + (names[target]?.let { mapOf("name" to JsonPrimitive(it)) } ?: emptyMap()))
            }
            val echoed = mine.filter { it["t"]?.jsonPrimitive?.content in setOf("habit", "unlock") }.map { e ->
                JsonObject(e + ("d" to JsonPrimitive("phone")) + ("at" to JsonPrimitive(e["at"]!!.jsonPrimitive.long + 40_000)))
            }
            events = (mine + phone + echoed).sortedBy { it["at"]!!.jsonPrimitive.long }
            val r = Random(5)
            limits = buildJsonObject {
                for ((block, daily) in listOf("youtube" to 30, "news" to 15)) {
                    put(block, buildJsonObject {
                        for (device in listOf("phone-demo", "laptop-demo")) {
                            put(device, buildJsonObject {
                                for (back in 1..30) put((today - back * DAY).toString(), ((r.nextDouble() * daily * (if (device == "phone-demo") 1.1 else 0.5)) * MIN).toLong())
                            })
                        }
                    })
                }
            }
        }

        override fun events(sinceMs: Long) = events.filter { it["at"]!!.jsonPrimitive.long >= sinceMs }
        override fun limits() = limits
    }

    /** A month of made-up screen time on this laptop, as usage.json keeps it. */
    fun usage(now: Long, zone: ZoneId): String {
        val r = Random(11)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val min = 60_000L
        val days = (0L until 30L).associate { back ->
            val browser = (60 + r.nextInt(180)) * min
            val sites = mapOf(
                "youtube.com" to browser * (20 + r.nextInt(25)) / 100,
                "reddit.com" to browser * r.nextInt(15) / 100,
                "example.org" to browser * (10 + r.nextInt(20)) / 100,
                "news.example.com" to browser * r.nextInt(10) / 100,
            ).filterValues { it >= min }
            val apps = mapOf(
                "zen" to browser,
                "org.example.Editor" to (30 + r.nextInt(150)) * min,
                "org.example.Terminal" to (10 + r.nextInt(60)) * min,
                "steam" to if (r.nextInt(3) == 0) (20 + r.nextInt(90)) * min else 0L,
            ).filterValues { it > 0 }
            (today - back) to UsageLog.Day(apps, sites)
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("days", kotlinx.serialization.json.buildJsonObject { for ((d, use) in days) put(d.toString(), json.encodeToJsonElement(UsageLog.Day.serializer(), use)) })
        }.toString()
    }

    fun laptopLog(now: Long, zone: ZoneId): List<String> =
        log(now, zone, 7, listOf("steam", "discord"), listOf("reddit.com", "www.youtube.com", "x.com", "news.ycombinator.com", "theverge.com")).map { it.second }

    /** A coloured tile with the site's first letter, as the site's own icon. */
    private val iconColours = mapOf(
        "reddit.com" to Color(0xFF4500), "youtube.com" to Color(0xE62117), "x.com" to Color(0x111111),
        "instagram.com" to Color(0xC13584), "news.ycombinator.com" to Color(0xFF6600), "theverge.com" to Color(0x5200FF),
        "discord.com" to Color(0x5865F2), "music.youtube.com" to Color(0xE62117),
    )

    fun fetchIcon(url: String, @Suppress("UNUSED_PARAMETER") maxBytes: Int): SiteIcons.Fetched? {
        val host = java.net.URI(url).host?.removePrefix("www.") ?: return null
        if (url.endsWith("/")) return SiteIcons.Fetched(url, """<html><head><link rel="icon" href="/icon.png" sizes="64x64"></head></html>""".toByteArray())
        if (!url.endsWith("/icon.png")) return null
        val colour = iconColours[host] ?: return null
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = colour
        g.fill(RoundRectangle2D.Float(0f, 0f, 64f, 64f, 18f, 18f))
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 38)
        val letter = host.first().uppercase()
        val fm = g.fontMetrics
        g.drawString(letter, (64 - fm.stringWidth(letter)) / 2, (64 - fm.height) / 2 + fm.ascent)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        return SiteIcons.Fetched(url, bytes)
    }
}
