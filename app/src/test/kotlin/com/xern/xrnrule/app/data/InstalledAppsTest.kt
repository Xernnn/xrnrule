package com.xern.xrnrule.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsTest {
    private val apps = listOf(InstalledApp("com.video", "Video"), InstalledApp("com.cards", "cards"))
    private val labels = apps.associate { it.packageName to it.label }

    @Test
    fun aBlockIsDrawnWithTheAppsThisPhoneHas() {
        // Two of a block's three games were uninstalled: its card kept two blank tiles for them
        // and its form counted "3 apps".
        val held = setOf("com.video", "com.gone.one", "com.cards", "com.gone.two")
        assertEquals("by name", listOf("com.cards", "com.video"), InstalledApps.onThisPhone(held, labels))
    }

    @Test
    fun thePickerListsWhatTheBlockHoldsOfAppsThatAreGone() {
        // They were picked and counted but not in the list, so they could never be taken off.
        val held = setOf("com.video", "com.gone.two", "com.gone.one")
        assertEquals(listOf("com.gone.one", "com.gone.two"), InstalledApps.notOnThisPhone(held, apps))
        assertEquals(emptyList<String>(), InstalledApps.notOnThisPhone(setOf("com.video"), apps))
    }
}
