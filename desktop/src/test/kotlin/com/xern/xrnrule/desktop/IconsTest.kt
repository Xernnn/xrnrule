package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Icons
import com.xern.xrnrule.domain.SiteIcons
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** A PNG of [side] px, a smooth made-up picture; [stored]: written without compression, as big as it gets. */
fun testPng(side: Int, stored: Boolean = false, tint: Int = 0): ByteArray {
    val img = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until side) for (x in 0 until side) img.setRGB(x, y, (0xff shl 24) or ((x * 255 / side) shl 16) or ((y * 255 / side) shl 8) or tint)
    val writer = ImageIO.getImageWritersByFormatName("png").next()
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { stream ->
        writer.output = stream
        val param = writer.defaultWriteParam
        if (stored) {
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = 1.0f
        }
        writer.write(null, IIOImage(img, null, null), param)
    }
    writer.dispose()
    return out.toByteArray()
}

class IconsTest {
    private val dir = Files.createTempDirectory("xrnrule-app-icons").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun put(path: String, bytes: ByteArray) = File(dir, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun icons(names: Map<String, String?>, rsvg: String? = null, data: List<String> = listOf("user", "usr")) =
        LaptopAppIcons({ names[it] }, data.map { File(dir, it) }, rsvg, homeIcons = null)

    @Test
    fun anAppsIconIsTheOneItsEntryNamesAtTheBestSizeTheThemeHas() {
        put("usr/icons/hicolor/48x48/apps/steam.png", testPng(48))
        put("usr/icons/hicolor/256x256/apps/steam.png", testPng(256))
        put("usr/icons/hicolor/16x16/apps/steam.png", testPng(16))
        put("usr/icons/hicolor/64x64/apps/chat.png", testPng(64))
        put("user/icons/hicolor/128x128/apps/chat.png", testPng(128, tint = 9)) // the user's own, at the size wanted
        put("usr/pixmaps/old.png", testPng(32))
        val icons = icons(mapOf("steam" to "steam", "com.example.Chat" to "chat", "old" to "old.png"))

        // 256 px is drawn down to 128, never sent as it is.
        val steam = icons.icon("steam")!!
        assertEquals("image/png", steam.type)
        assertEquals(Raster.SIDE, SiteIcons.pixelSize(steam.bytes))
        assertArrayEquals(testPng(128, tint = 9), icons.icon("com.example.Chat")!!.bytes)
        assertEquals(32, SiteIcons.pixelSize(icons.icon("old")!!.bytes)) // pixmaps, an extension and all
    }

    @Test
    fun anAppWithNoEntryIsLookedForByItsId() {
        // A window with no .desktop file is blocked by its class, which apps often name their icon after.
        put("usr/icons/hicolor/96x96/apps/somegame.png", testPng(96))
        assertEquals(96, SiteIcons.pixelSize(icons(emptyMap()).icon("somegame")!!.bytes))
    }

    @Test
    fun flatpaksExportedIconsAreFound() {
        val data = LaptopAppIcons.xdgDataDirs(env = mapOf("XDG_DATA_DIRS" to "/usr/share"), home = "/home/you").map { it.path }
        assertEquals(listOf("/home/you/.local/share", "/usr/share", "/home/you/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share"), data)
        put("flatpak/icons/hicolor/128x128/apps/com.example.Flat.png", testPng(128))
        assertNotNull(icons(mapOf("com.example.Flat" to "com.example.Flat"), data = listOf("usr", "flatpak")).icon("com.example.Flat"))
    }

    @Test
    fun anIconFileTheEntryNamesIsTakenOnlyIfItIsAnImage() {
        val logo = put("opt/app/icon.png", testPng(512))
        val secret = put("home/.ssh/id_ed25519", "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray())
        val page = put("opt/app/icon.svg.png", "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".toByteArray())
        val icons = icons(mapOf("a" to logo.path, "b" to secret.path, "c" to page.path, "d" to "/dev/zero", "e" to "/nowhere.png"))
        assertEquals(Raster.SIDE, SiteIcons.pixelSize(icons.icon("a")!!.bytes))
        listOf("b", "c", "d", "e").forEach { assertNull(it, icons.icon(it)) }
    }

    @Test
    fun aNameThatWouldLeadElsewhereIsNoIcon() {
        put("usr/icons/hicolor/128x128/apps/x.png", testPng(128))
        put("usr/x.png", testPng(128))
        val icons = icons(mapOf("a" to "../../x", "b" to "../x", "c" to "x/../../x"))
        listOf("a", "b", "c").forEach { assertNull(it, icons.icon(it)) }
        assertNull(icons.icon("../x")) // nor an id that is no key
    }

    @Test
    fun anAppWithOnlyAnSvgHasItDrawnWhenTheLaptopCan() {
        put("usr/icons/hicolor/scalable/apps/vector.svg", "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10' fill='#E8590C'/></svg>".toByteArray())
        assertNull("no tool, no icon", icons(mapOf("v" to "vector"), rsvg = File(dir, "no-such-tool").path).icon("v"))
        assertNull(icons(mapOf("v" to "vector"), rsvg = null).icon("v"))
        val tool = listOf("/usr/bin/rsvg-convert", "/usr/local/bin/rsvg-convert").firstOrNull { File(it).canExecute() }
        assumeTrue("needs rsvg-convert", tool != null)
        val drawn = icons(mapOf("v" to "vector"), rsvg = tool).icon("v")!!
        assertEquals("image/png", drawn.type)
        assertEquals(Raster.SIDE, SiteIcons.pixelSize(drawn.bytes))
    }

    @Test
    fun whatGoesToTheServerFitsIt() {
        val small = testPng(64)
        assertTrue(Raster.forServer(small) === small)
        // Too big as it is: drawn down to 128 px, which fits.
        val big = testPng(300, stored = true)
        assertTrue(big.size > Icons.MAX_BYTES)
        val sent = Raster.forServer(big)!!
        assertTrue(sent.size <= Icons.MAX_BYTES)
        assertEquals(Raster.SIDE, SiteIcons.pixelSize(sent))
        // What the laptop can't open and is too big as it is goes nowhere; nor what's no image.
        assertNull(Raster.forServer(byteArrayOf(0, 0, 1, 0, 1, 0) + ByteArray(Icons.MAX_BYTES)))
        assertNull(Raster.forServer("<svg/>".toByteArray()))
        // Nor is a picture too wide to open in the daemon's small heap.
        assertNull(Raster.scaled(testPng(2000), 128))
    }
}
