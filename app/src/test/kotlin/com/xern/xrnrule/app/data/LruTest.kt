package com.xern.xrnrule.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruTest {

    @Test
    fun eachIsLoadedOnceWhileItIsKept() {
        // Labels and icons were looked up again on every redraw: once a second behind a block screen.
        val loads = mutableListOf<String>()
        val cache = Lru<String, String>(2)
        repeat(60) { assertEquals("Videos", cache.getOrLoad("com.example.videos") { loads += it; "Videos" }) }
        assertEquals(listOf("com.example.videos"), loads)
    }

    @Test
    fun theLeastLatelyUsedGoesFirst() {
        val cache = Lru<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache["a"]
        cache.put("c", 3)
        assertNull("b was used least lately", cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(2, cache.size)
    }

    @Test
    fun whatCantBeLoadedIsTriedAgain() {
        var tries = 0
        val cache = Lru<String, String>(4)
        repeat(3) { assertNull(cache.getOrLoad("gone") { tries++; null }) }
        assertEquals(3, tries)
    }
}
