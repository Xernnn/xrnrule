package com.xern.xrnrule.app.web

import com.xern.xrnrule.app.data.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** I49: the browsers known to have the extension. */
class ExtensionBrowsersTest {

    @Test
    fun whatWasLearnedBeforeTheBlockPageStoppedNamingBrowsersIsForgottenOnce() {
        // Saved by a build before c445ec4, when the block page opened in Chrome named Chrome.
        val prefs = MemoryPrefs(mapOf("browser" to CHROME, "browsers" to setOf(QUETTA, CHROME)))
        val browsers = ExtensionBrowsers(prefs)
        assertNull(browsers.latest)
        assertFalse("none is blocked for lacking it until one is learned again", browsers.lacksExtension(CHROME))

        browsers.learn(QUETTA) // the extension speaking from Quetta
        assertTrue(browsers.lacksExtension(CHROME))

        val later = ExtensionBrowsers(prefs) // the next start
        assertEquals("only once", QUETTA, later.latest)
        assertTrue(later.lacksExtension(CHROME))
        assertFalse(later.lacksExtension(QUETTA))
    }

    @Test
    fun aNewInstallLearnsAsBefore() {
        val prefs = MemoryPrefs()
        ExtensionBrowsers(prefs).learn(QUETTA)
        val browsers = ExtensionBrowsers(prefs)
        assertEquals(QUETTA, browsers.latest)
        assertTrue(browsers.lacksExtension(CHROME))
    }

    private companion object {
        const val CHROME = "com.android.chrome"
        const val QUETTA = "net.quetta.browser"
    }
}
