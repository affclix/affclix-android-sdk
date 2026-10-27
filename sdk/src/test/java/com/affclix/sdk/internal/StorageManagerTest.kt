package com.affclix.sdk.internal

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageManagerTest {
    @Test fun clearIdentifiersKeepsInstallState() {
        val s = StorageManager(ApplicationProvider.getApplicationContext())
        s.markFirstLaunchDone()
        s.saveInstallTime(1_700_000_000_000L)
        s.saveClickTime("c1")
        s.saveConsentData(ConsentData(gdprApplies = true))

        s.clearIdentifiers()

        assertFalse("must not look like a first launch again", s.isFirstLaunch())
        assertNotNull(s.getInstallTime())
        assertNull(s.getConsentData().gdprApplies)
        assertTrue(s.getClickTime("c1") < 0)
    }

    @Test fun clickIdExpiresAfterClickExpiryDays() {
        val s = StorageManager(ApplicationProvider.getApplicationContext())
        val day = 86_400_000L
        val t0 = 1_700_000_000_000L
        s.storeClickId("deeplink", "click-a", expiryDays = 30, now = t0)

        assertEquals("click-a", s.getClickId(null, now = t0 + 29 * day))
        assertEquals("click-a", s.getClickId("deeplink", now = t0 + 29 * day))
        assertNull("re-engagement window over", s.getClickId(null, now = t0 + 31 * day))
        assertNull(s.getClickId("deeplink", now = t0 + 31 * day))

        // A new deep link (retargeting) starts a new window and wins.
        s.storeClickId("deeplink", "click-b", expiryDays = 30, now = t0 + 40 * day)
        assertEquals("click-b", s.getClickId(null, now = t0 + 41 * day))
    }
}
