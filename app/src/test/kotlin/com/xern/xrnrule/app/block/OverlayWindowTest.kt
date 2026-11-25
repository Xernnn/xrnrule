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

    /** A made-up window: what was done to it, in order. */
    private class Fake(val id: Int) {
        val log = mutableListOf<String>()
        var gone = false
    }

    private fun shield(made: MutableList<Fake>, mayDraw: () -> Boolean = { true }) = ShieldWindow(
        make = { Fake(made.size + 1).also { made += it } },
        add = { if (!mayDraw()) throw RuntimeException("permission denied for window type 2038") else it.log += "add" },
        remove = { it.log += "remove" },
        release = { it.log += "release" },
        reveal = { w, on -> w.log += if (on) "show" else "hide" },
        lost = { it.gone },
    )

    @Test
    fun theBlockScreenIsMadeOnceAndThenOnlyShownAndHidden() {
        // Every showing made, added and composed a new window: the blocked app showed until it drew.
        val made = mutableListOf<Fake>()
        val window = shield(made)
        assertTrue(window.prepare())
        repeat(3) {
            assertTrue(window.show())
            assertTrue("already up: nothing more", window.show())
            window.hide()
            window.hide()
        }
        assertEquals(1, made.size)
        assertEquals(listOf("add", "show", "hide", "show", "hide", "show", "hide"), made[0].log)
        assertFalse(window.shown)
    }

    @Test
    fun aBlockScreenTheSystemTookAwayIsMadeAgain() {
        val made = mutableListOf<Fake>()
        val window = shield(made)
        window.show()
        made[0].gone = true // an OEM task killer
        window.hide()
        assertTrue(window.show())
        assertEquals(2, made.size)
        assertEquals(listOf("add", "show", "hide", "remove", "release"), made[0].log)
        assertEquals(listOf("add", "show"), made[1].log)
    }

    @Test
    fun aBlockScreenThatCantGoUpIsAskedForAgain() {
        var mayDraw = false
        val made = mutableListOf<Fake>()
        val window = shield(made) { mayDraw }
        assertFalse(window.show())
        assertNull(window.window)
        assertEquals(listOf("release"), made[0].log)
        mayDraw = true
        assertTrue(window.show())
        window.dispose()
        assertNull(window.window)
        assertEquals(listOf("add", "show", "remove", "release"), made[1].log)
    }

    @Test
    fun theAppInFrontStaysWhatItWasWhileTheScreenIsOff() {
        // The screen going off read as "nothing in front", which took the block screen down; at the
        // unlock the blocked app showed until the next look.
        val front = HeldWhileAsleep<String?>(null)
        assertEquals("game", front.of(awake = true, now = "game"))
        assertEquals("game", front.of(awake = false, now = null))
        assertEquals("game", front.of(awake = false, now = null))
        assertEquals("launcher", front.of(awake = true, now = "launcher"))
    }
}
