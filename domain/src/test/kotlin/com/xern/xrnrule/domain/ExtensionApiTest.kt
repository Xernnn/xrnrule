package com.xern.xrnrule.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionApiTest {

    @Test
    fun versionsCompareByNumberPartByPart() {
        assertTrue(ExtensionApi.isOlder("2.1.0", "2.1.1"))
        assertTrue(ExtensionApi.isOlder("2.9", "2.10"))
        assertTrue(ExtensionApi.isOlder("2.1", "2.1.1"))
        assertFalse(ExtensionApi.isOlder("2.1.1", "2.1.1"))
        assertFalse(ExtensionApi.isOlder("2.1.0", "2.1"))
        assertFalse("newer than the app's copy", ExtensionApi.isOlder("2.2.0", "2.1.1"))
        assertFalse("unreadable", ExtensionApi.isOlder("dev", "2.1.1"))
        assertFalse("unreadable", ExtensionApi.isOlder("2.1.1", ""))
    }
}
