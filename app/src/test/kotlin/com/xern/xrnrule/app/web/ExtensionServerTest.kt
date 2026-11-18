package com.xern.xrnrule.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's door on 127.0.0.1: what a request may do, and whom it counts as. */
class ExtensionServerTest {

    private val host = "127.0.0.1:${ExtensionServer.PORT}"

    @Test
    fun onlyTheExtensionSaysTheExtensionIsThere() {
        // With the extension turned off, a page fetching this address every minute kept the
        // "sites aren't blocked" alert away: any request counted as the extension speaking.
        val page = mapOf("host" to host)
        val extension = page + ("x-xrnrule" to "1")
        assertFalse(ExtensionServer.speaksForTheExtension(page))
        assertTrue(ExtensionServer.speaksForTheExtension(extension))
        assertEquals("missing X-Xrnrule", ExtensionServer.refusal("GET", "/ext/rules", page))
        assertNull(ExtensionServer.refusal("GET", "/ext/rules", extension))
        // The block page's own files are anyone's to load.
        assertNull(ExtensionServer.refusal("GET", "/block", page))
    }

    @Test
    fun writesNeedTheHeaderAndOurOwnName() {
        assertEquals("missing X-Xrnrule", ExtensionServer.refusal("POST", "/ext/heartbeat", mapOf("host" to host)))
        assertEquals("wrong host", ExtensionServer.refusal("POST", "/ext/heartbeat", mapOf("host" to "evil.example:47321", "x-xrnrule" to "1")))
        assertEquals("no", ExtensionServer.refusal("OPTIONS", "/ext/heartbeat", mapOf("host" to host)))
        assertNull(ExtensionServer.refusal("POST", "/ext/heartbeat", mapOf("host" to "localhost:${ExtensionServer.PORT}", "x-xrnrule" to "1")))
    }
}
