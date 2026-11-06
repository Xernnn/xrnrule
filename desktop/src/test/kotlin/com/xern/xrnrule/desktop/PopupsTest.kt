package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupsTest {
    private val laptop = World(deviceKind = DeviceKind.LAPTOP)

    private fun block(apps: Set<String> = emptySet(), sites: Set<String> = emptySet(), desktop: Set<String> = emptySet()) = Block(
        id = "b",
        name = "B",
        targetPackages = apps,
        targetSites = sites,
        targetDesktopApps = desktop,
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
    )

    @Test
    fun aWarningOnEveryTickIsOnePopup() {
        val limit = PopupLimit()
        val shown = (0 until 30).count { limit.allows("B locks again soon", it * 1_000L) }
        assertEquals(1, shown)
    }

    @Test
    fun theSamePopupCanComeBackLater() {
        val limit = PopupLimit(everyMs = 1_000)
        assertTrue(limit.allows("a", 0))
        assertFalse(limit.allows("a", 999))
        assertTrue(limit.allows("a", 1_000))
    }

    @Test
    fun differentPopupsDontHoldEachOtherUp() {
        val limit = PopupLimit()
        assertTrue(limit.allows("a", 0))
        assertTrue(limit.allows("b", 0))
    }

    @Test
    fun aBlockOfPhoneAppsHasNothingToLockHere() {
        assertFalse(laptop.locksOnLaptop(block(apps = setOf("com.game"))))
        assertTrue(laptop.locksOnLaptop(block(apps = setOf("com.game"), sites = setOf("example.com"))))
        assertTrue(laptop.locksOnLaptop(block(desktop = setOf("steam.desktop"))))
        assertFalse(laptop.locksOnLaptop(block(sites = setOf("example.com")).copy(devices = Devices.PHONE)))
    }
}
