package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class IconsTest {
    @Test
    fun keysAreTheServersOwn() {
        assertEquals("site:reddit.com", Icons.site("Reddit.com"))
        assertEquals("app:com.instagram.android", Icons.app("com.instagram.android"))
        assertEquals("desk:Some App+", Icons.desk("Some App+"))
        listOf("site:a/b", "site:", "site:a b", "app:a b", "app:a/b", "desk:a/b", "desk:", "web:x", "site:a\nb", "app:" + "a".repeat(201))
            .forEach { assertFalse(it, Icons.valid(it)) }
        assertNull(Icons.site("a/../b"))
        assertNull(Icons.desk("../../etc/passwd"))
    }

    @Test
    fun aKeyGoesInAUrlWhole() {
        for (key in listOf("site:reddit.com", "desk:Some App+", "app:com.x_y.z", "desk:a-b.c")) {
            val part = Icons.urlPart(key)
            assertFalse(part, part.contains('/') || part.contains(' ') || part.contains('+') || part.contains(':'))
            // A path's '+' is a plus: decoded as a path is, it is the key again.
            assertEquals(key, URLDecoder.decode(part.replace("+", "%2B"), "UTF-8"))
        }
        assertEquals("desk%3ASome%20App%2B", Icons.urlPart("desk:Some App+"))
    }

    @Test
    fun theHashIsTheServersFirstTwelveHexDigits() {
        // python3 -c "import hashlib; print(hashlib.sha256(b'abc').hexdigest()[:12])"
        assertEquals("ba7816bf8f01", Icons.hash("abc".toByteArray()))
        assertEquals(12, Icons.hash(ByteArray(0)).length)
    }

    @Test
    fun theServersListIsReadWithoutWhatIsntAKey() {
        assertEquals(
            mapOf("site:a.com" to "0123456789ab"),
            Icons.index("""{"site:a.com":"0123456789ab","../x":"1","app:b":3,"desk:c":null}"""),
        )
        assertNull(Icons.index("[1,2]"))
        assertNull(Icons.index("{half"))
        assertEquals(emptyMap<String, String>(), Icons.index("{}"))
    }

    @Test
    fun theSitesBlocksNameAreTheirHosts() {
        val timer = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS)
        val blocks = listOf(
            Block("a", "A", targetSites = setOf("youtube.com/shorts", "reddit.com"), exceptSites = setOf("music.youtube.com"), trigger = timer),
            Block("b", "B", targetSites = setOf("reddit.com"), trigger = timer),
        )
        assertEquals(setOf("music.youtube.com", "reddit.com", "youtube.com"), Icons.sitesNamed(blocks))
    }

    @Test
    fun anIconIsSentWhenTheServerLacksItOrHasAnotherAndThenNotAgainForADay() {
        val day = Icons.RESEND_MS
        val now = 10 * day
        assertTrue(Icons.due("h1", null, replace = false, last = null, nowWall = now))
        assertTrue(Icons.due("h1", "h0", replace = true, last = null, nowWall = now))
        assertFalse("only fills gaps", Icons.due("h1", "h0", replace = false, last = null, nowWall = now))
        assertFalse(Icons.due("h1", "h1", replace = true, last = null, nowWall = now))
        // Sent (or refused) an hour ago: not again, whatever the server has now and whatever this copy is.
        val sent = Icons.Sent("h1", now - 3_600_000)
        assertFalse(Icons.due("h1", null, replace = true, last = sent, nowWall = now))
        assertFalse(Icons.due("h2", "h1x", replace = true, last = sent, nowWall = now))
        // A day on, it goes again; so it does when the clock has gone back.
        assertTrue(Icons.due("h2", "h1x", replace = true, last = Icons.Sent("h1", now - day), nowWall = now))
        assertTrue(Icons.due("h2", null, replace = true, last = Icons.Sent("h1", now + 5_000), nowWall = now))
    }

    @Test
    fun aCopyTheServerHasAsSentNeedntBeMadeAgainForADay() {
        val now = 10 * Icons.RESEND_MS
        assertTrue(Icons.stillThere("h1", Icons.Sent("h1", now - 1000), now))
        assertFalse(Icons.stillThere("h2", Icons.Sent("h1", now - 1000), now))
        assertFalse(Icons.stillThere(null, Icons.Sent("h1", now - 1000), now))
        assertFalse(Icons.stillThere("h1", Icons.Sent("h1", now - Icons.RESEND_MS), now))
        assertFalse(Icons.stillThere("h1", null, now))
    }
}
