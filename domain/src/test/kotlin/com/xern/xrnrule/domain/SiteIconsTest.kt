package com.xern.xrnrule.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SiteIconsTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private val ico = byteArrayOf(0, 0, 1, 0, 1, 0)

    @Test
    fun onlyPublicNamesAreAsked() {
        listOf("youtube.com", "m.facebook.com", "news.ycombinator.com", "xn--bcher-kva.example").forEach { assertTrue(it, SiteIcons.fetchable(it)) }
        listOf("localhost", "192.168.1.1", "1.1.1.1", "router.local1", "a.com:8080", "a.com/x", "../etc", "a..com", "-a.com", "", "com")
            .forEach { assertFalse(it, SiteIcons.fetchable(it)) }
    }

    @Test
    fun theBiggestIconComesFirstAndSvgIsLeftOut() {
        val html = """
            <html><head>
            <link rel="shortcut icon" href="/favicon.ico">
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <LINK REL='icon' sizes='32x32' href='//cdn.example.com/i32.png'>
            <link rel="apple-touch-icon" href="touch.png">
            <link rel=icon sizes="192x192" href="https://cdn.example.com/i192.png?v=1&amp;x=2">
            <link rel="mask-icon" href="/mask.svg" color="#000">
            <link rel="stylesheet" href="/a.css">
            <link rel="icon" href="http://insecure.example.com/i.png" sizes="512x512">
            </head>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.example.com/i192.png?v=1&x=2",
                "https://www.example.com/app/touch.png",
                "https://cdn.example.com/i32.png",
                "https://www.example.com/favicon.ico",
            ),
            SiteIcons.candidates(html, "https://www.example.com/app/"),
        )
    }

    @Test
    fun imagesAreKnownByTheirFirstBytes() {
        assertEquals("image/png", SiteIcons.imageType(png))
        assertEquals("image/x-icon", SiteIcons.imageType(ico))
        assertEquals("image/jpeg", SiteIcons.imageType(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertEquals("image/gif", SiteIcons.imageType("GIF89a".toByteArray()))
        assertEquals("image/webp", SiteIcons.imageType("RIFF0000WEBPVP8 ".toByteArray()))
        assertNull(SiteIcons.imageType("<svg xmlns=".toByteArray()))
        assertNull(SiteIcons.imageType("<!doctype html>".toByteArray()))
        assertNull(SiteIcons.imageType(ByteArray(0)))
    }

    @Test
    fun findFollowsTheHomePageAndFallsBackToFaviconIco() {
        val asked = mutableListOf<String>()
        val pages = mapOf(
            // youtube.com sends you to www.youtube.com, whose page names an HTML file as its icon.
            "https://youtube.com/" to SiteIcons.Fetched("https://www.youtube.com/", """<link rel="icon" href="/not-an-image">""".toByteArray()),
            "https://www.youtube.com/not-an-image" to SiteIcons.Fetched("https://www.youtube.com/not-an-image", "<html>".toByteArray()),
            "https://www.youtube.com/favicon.ico" to SiteIcons.Fetched("https://www.youtube.com/favicon.ico", ico),
        )
        val icon = SiteIcons.find("youtube.com", { url, _ -> asked += url; pages[url] })
        assertEquals("image/x-icon", icon?.type)
        assertEquals(listOf("https://youtube.com/", "https://www.youtube.com/not-an-image", "https://www.youtube.com/favicon.ico"), asked)
    }

    @Test
    fun findTakesInlineIconsAndSkipsWhatThePlatformTurnsDown() {
        val inline = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val page = SiteIcons.Fetched("https://a.example.com/", """<link rel="icon" sizes="64x64" href="$inline"><link rel="icon" href="/b.ico">""".toByteArray())
        val files = mapOf("https://a.example.com/b.ico" to SiteIcons.Fetched("https://a.example.com/b.ico", ico))
        val get = { url: String, _: Int -> if (url == "https://a.example.com/") page else files[url] }
        assertArrayEquals(png, SiteIcons.find("a.example.com", get)?.bytes)
        assertEquals("image/x-icon", SiteIcons.find("a.example.com", get, accept = { it.type != "image/png" })?.type)
    }

    @Test
    fun nothingIsFetchedForHostsThatArentNames() {
        var asked = 0
        assertNull(SiteIcons.find("192.168.1.1", { _, _ -> asked++; null }))
        assertEquals(0, asked)
    }

    @Test
    fun aSiteThatAnswersNothingHasNoIcon() {
        val asked = mutableListOf<String>()
        assertNull(SiteIcons.find("down.example", { url, _ -> asked += url; null }))
        assertEquals(listOf("https://down.example/", "https://down.example/favicon.ico"), asked)
    }
}
