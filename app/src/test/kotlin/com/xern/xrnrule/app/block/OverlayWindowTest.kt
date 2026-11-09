package com.xern.xrnrule.app.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowTest {

    @Test
    fun aWindowThatCantGoUpThrowsNothingAndIsAskedForAgain() {
        var mayDraw = false // "Display over other apps", gone after an update
        var made = 0
        val added = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val window = OverlayWindow(
            make = { ++made },
            add = { if (!mayDraw) throw RuntimeException("permission denied for window type 2038") else added += it },
            remove = {},
            release = { released += it },
        )

        // Settings in front, every render: no throw out of the service's render loop.
        assertFalse(window.show())
        assertFalse(window.show())
        assertNull(window.window)
        assertEquals("each window that couldn't go up is let go", listOf(1, 2), released)

        mayDraw = true
        assertTrue(window.show())
        assertTrue("already up: no second one", window.show())
        assertEquals(listOf(3), added)
        assertEquals(3, window.window)

        window.hide()
        assertNull(window.window)
        assertEquals(listOf(1, 2, 3), released)
    }

    @Test
    fun aWindowAlreadyGoneStillHides() {
        val released = mutableListOf<String>()
        val window = OverlayWindow(make = { "gate" }, add = {}, remove = { throw IllegalArgumentException("not attached") }, release = { released += it })
        window.show()
        window.hide()
        assertNull(window.window)
        assertEquals(listOf("gate"), released)
    }
}
