package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesTest {

    @Test
    fun normalizesWhatUsersTypeAndBrowsersShow() {
        assertEquals("youtube.com", Sites.normalize("https://www.YouTube.com/watch?v=x"))
        assertEquals("youtube.com", Sites.normalize("youtube.com"))
        assertEquals("m.youtube.com", Sites.normalize("m.youtube.com/feed"))
        assertEquals("reddit.com", Sites.normalize("  reddit.com/  "))
        assertEquals("news.ycombinator.com", Sites.normalize("http://news.ycombinator.com:443/item?id=1"))
        assertEquals("bbc.co.uk", Sites.normalize("bbc.co.uk#top"))
        assertEquals("192.168.1.1", Sites.normalize("192.168.1.1/admin"))
        assertEquals("xn--80ak6aa92e.xn--p1ai", Sites.normalize("xn--80ak6aa92e.xn--p1ai"))
    }

    @Test
    fun rejectsSearchQueriesAndJunk() {
        assertNull(Sites.normalize(""))
        assertNull(Sites.normalize("how to cook rice"))
        assertNull(Sites.normalize("youtube"))
        assertNull(Sites.normalize("Search or type URL"))
        assertNull(Sites.normalize("about:blank"))
        assertNull(Sites.normalize("file.txt1"))
        assertNull(Sites.normalize("-bad.com"))
        // Four labels of digits, one too long for an Int: no address, and no exception either.
        assertNull(Sites.normalize("1.1.1.99999999999"))
        assertNull(Sites.normalize("1.1.1.256"))
    }

    @Test
    fun matchesSubdomainsOnly() {
        assertTrue(Sites.matches("youtube.com", "youtube.com"))
        assertTrue(Sites.matches("m.youtube.com", "youtube.com"))
        assertFalse(Sites.matches("notyoutube.com", "youtube.com"))
        assertFalse(Sites.matches("youtube.com", "m.youtube.com"))
    }

    @Test
    fun aSiteIsShownOnceUnderItsMainName() {
        assertEquals(listOf("reddit.com"), Sites.shown(listOf("redd.it", "reddit.com")))
        assertEquals(listOf("x.com", "youtube.com"), Sites.shown(listOf("twitter.com", "x.com", "youtube.com")))
        assertEquals("an alias alone stays", listOf("redd.it"), Sites.shown(listOf("redd.it")))
    }
}
