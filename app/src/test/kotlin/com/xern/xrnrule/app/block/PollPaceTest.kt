package com.xern.xrnrule.app.block

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.Lockdown
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often the foreground is looked at (N7): fast whenever a switch could meet a block here. */
class PollPaceTest {

    private fun block(id: String = "b", mode: BlockMode = BlockMode.ALLOWANCE, apps: Set<String> = setOf("com.example.videos")) = Block(
        id = id,
        name = id,
        targetPackages = apps,
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = mode,
        dailyAllowanceMs = if (mode == BlockMode.ALLOWANCE) 30 * 60_000L else 0,
        lockdownMs = if (mode == BlockMode.LOCKDOWN) 60 * 60_000L else 0,
    )

    private fun phone(vararg blocks: Block) = World(blocks = blocks.toList(), deviceKind = DeviceKind.PHONE)

    @Test
    fun anythingThatCanBlockOrCountAnAppHereKeepsItFast() {
        assertTrue(phone(block()).foregroundMatters())
        assertTrue("a site", phone(block(apps = emptySet()).copy(targetSites = setOf("videos.example"))).foregroundMatters())
        val running = block(mode = BlockMode.LOCKDOWN)
        assertTrue("a lockdown running", phone(running).copy(lockdowns = mapOf("b" to Lockdown("b", 0, 0, 60_000))).foregroundMatters())
        val habitApp = block(mode = BlockMode.HABIT).copy(devices = Devices.LAPTOP, trigger = Trigger(TriggerType.TIMER, 60_000, TriggerUnit.MS, habitPackages = setOf("com.example.piano")))
        assertTrue("a timer counting in an app, from a laptop block too", phone(habitApp).foregroundMatters())
        val hardcore = block(apps = emptySet()).copy(enabled = false, enforcementTier = EnforcementTier.HARDCORE)
        assertTrue("a hardcore block guards Settings, on or off", phone(hardcore).foregroundMatters())
    }

    @Test
    fun whatCanMeetNothingHereLetsItSlowDown() {
        assertFalse(phone().foregroundMatters())
        assertFalse("off", phone(block().copy(enabled = false)).foregroundMatters())
        assertFalse("the laptop's", phone(block().copy(devices = Devices.LAPTOP)).foregroundMatters())
        assertFalse("laptop apps only", phone(block(apps = emptySet()).copy(targetDesktopApps = setOf("steam"))).foregroundMatters())
        assertFalse("a lockdown not started", phone(block(mode = BlockMode.LOCKDOWN)).foregroundMatters())
        val unlocked = block(mode = BlockMode.HABIT, apps = emptySet()).copy(enforcementTier = EnforcementTier.HARDCORE, state = BlockState.UNLOCKED)
        assertFalse("an unlocked hardcore block with nothing on the phone", phone(unlocked).foregroundMatters())
    }

    @Test
    fun aSlowWaitEndsTheMomentItMatters() = runBlocking {
        val fast = MutableStateFlow(false)
        val began = System.nanoTime()
        val wait = async { pollPause(fast, fastMs = 10, slowMs = 10_000) }
        delay(50)
        fast.value = true // a block enabled, a lockdown started
        wait.await()
        val ms = (System.nanoTime() - began) / 1_000_000
        assertTrue("woke after $ms ms", ms < 2_000)
    }
}
