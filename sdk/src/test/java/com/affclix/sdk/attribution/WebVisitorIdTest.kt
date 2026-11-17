package com.affclix.sdk.attribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebVisitorIdTest {

    @Test
    fun keepsATrimmedVisitorId() {
        assertEquals("wv-123", AttributionManager.webVisitorIdOf("  wv-123 "))
    }

    @Test
    fun dropsBlankAndUnfilledMacros() {
        assertNull(AttributionManager.webVisitorIdOf(null))
        assertNull(AttributionManager.webVisitorIdOf("   "))
        assertNull(AttributionManager.webVisitorIdOf("{web_id}"))
    }

    @Test
    fun capsTheLengthAt128() {
        assertEquals(128, AttributionManager.webVisitorIdOf("x".repeat(300))!!.length)
    }
}
