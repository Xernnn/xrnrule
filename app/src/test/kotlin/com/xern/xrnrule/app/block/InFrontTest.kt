package com.xern.xrnrule.app.block

import com.xern.xrnrule.app.web.Sighting
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the engine hears is in front, and whose time the site-time log keeps (docs/laptop-screen-time.md). */
class InFrontTest {
    private val browser = "com.example.browser"
    private val isBrowser = { pkg: String -> pkg == browser }

    @Test
    fun aSiteInTheBrowserIsInFrontAndItsTimeIsItsOwn() {
        assertEquals(InFront(browser, "example.org", "example.org"), InFront.of(browser, Sighting("example.org"), awake = true, isBrowser))
    }

    @Test
    fun theBlockPageAndAPrivateTabAreInFrontForTheEngineButTheirTimeIsNoSites() {
        // The block page stands in for the site so its wait timer runs (I4); a private tab is blocked like any other.
        val untimed = Sighting("example.org", timed = false)
        assertEquals(InFront(browser, "example.org", null), InFront.of(browser, untimed, awake = true, isBrowser))
    }

    @Test
    fun noSiteOutsideABrowserNorAnythingWhileTheScreenIsOff() {
        assertEquals(InFront("com.example.chat", null, null), InFront.of("com.example.chat", Sighting("example.org"), awake = true, isBrowser))
        assertEquals(InFront(browser, null, null), InFront.of(browser, null, awake = true, isBrowser))
        assertEquals(InFront(null, null, null), InFront.of(browser, Sighting("example.org"), awake = false, isBrowser))
    }
}
