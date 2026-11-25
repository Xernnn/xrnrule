package com.xern.xrnrule.app.data

import android.app.AppOpsManager
import com.xern.xrnrule.app.block.OverlayShieldPort
import com.xern.xrnrule.app.ui.leaveHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HyperOS's own switches, as xrnrule reads them, and what the block screen says about them. */
class OemSwitchesTest {

    @Test
    fun aSwitchReadsAsOnOffOrUnknown() {
        assertEquals(true, OemAutostart.opAllows(AppOpsManager.MODE_ALLOWED))
        assertEquals(false, OemAutostart.opAllows(AppOpsManager.MODE_IGNORED))
        assertEquals(false, OemAutostart.opAllows(AppOpsManager.MODE_ERRORED))
        assertNull(OemAutostart.opAllows(AppOpsManager.MODE_DEFAULT))
        assertNull(OemAutostart.opAllows(null))
    }

    @Test
    fun anAppsBlockScreenSaysHowToLeaveWhenPopUpsAreOff() {
        // With "pop-up windows while running in the background" off, Close silently did nothing.
        val app = OverlayShieldPort.OverlayUi(subject = "Videos", pkg = "com.example.videos", isSite = false, block = null, items = emptyList(), popupsOff = true)
        assertEquals("Pop-ups off · swipe home", leaveHint(app))
        assertNull("on, nothing to say", leaveHint(app.copy(popupsOff = false)))
        assertNull("a site's Close opens nothing", leaveHint(app.copy(pkg = null, isSite = true)))
    }
}
