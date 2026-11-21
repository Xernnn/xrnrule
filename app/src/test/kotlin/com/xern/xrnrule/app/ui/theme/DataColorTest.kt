package com.xern.xrnrule.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DataColorTest {
    /** The tile's background, as the launcher icon's background layer paints it. */
    private fun tileColor(): Int {
        val xml = File("src/main/res/drawable/ic_launcher_background.xml").readText()
        val hex = Regex("""android:pathData="M0,0 H108 V108 H0 Z" android:fillColor="#([0-9A-Fa-f]{6})"""").find(xml)!!.groupValues[1]
        return (0xFF000000 or hex.toLong(16)).toInt()
    }

    @Test
    fun theDataColourIsTheTilesOrange() {
        assertEquals(Integer.toHexString(tileColor()), Integer.toHexString(DataColor.toArgb()))
    }
}
