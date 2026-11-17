package com.affclix.sdk.attribution

import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.internal.EventQueueWorker
import com.affclix.sdk.internal.OfflineEventQueue
import com.affclix.sdk.model.AttributionResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallAttributionTest {

    @Test
    fun readsAProbabilisticInstall() {
        val r = AttributionManager.installAttributionOf(JSONObject(
            """{"success":true,"event_id":"E1","attributed":true,"attribution":{"method":"probabilistic"},"click_id":"CLK-9"}"""))
        assertEquals(AttributionResult(true, "probabilistic", "CLK-9"), r)
    }

    @Test
    fun readsAnOrganicInstall() {
        val r = AttributionManager.installAttributionOf(JSONObject(
            """{"success":true,"attributed":false,"attribution":{"method":"organic"}}"""))
        assertEquals(AttributionResult(false, "organic", null), r)
    }

    @Test
    fun aRefusalIsNoAttribution() {
        assertNull(AttributionManager.installAttributionOf(JSONObject("""{"success":false,"code":"TENANT_DISABLED"}""")))
    }

    @Test
    fun refusalCodes() {
        assertEquals("TENANT_DISABLED", AffclixSDK.refusalCode(JSONObject("""{"success":false,"code":"TENANT_DISABLED"}""")))
        assertEquals("REFUSED", AffclixSDK.refusalCode(JSONObject("""{"success":false}""")))
        assertNull(AffclixSDK.refusalCode(JSONObject("""{"success":true}""")))
        // Endpoints that answer without the field (older trackers) are not refusals.
        assertNull(AffclixSDK.refusalCode(JSONObject("""{"touch_id":"T1"}""")))
    }

    @Test
    fun recognisesAQueuedInstall() {
        fun q(url: String, body: String?) = OfflineEventQueue.QueuedEvent(1, url, body, if (body != null) "POST" else "GET", 0)
        assertTrue(EventQueueWorker.isInstall(q("https://mmp.affclix.org/m/ev?ak=K", """{"event":"install"}""")))
        assertFalse(EventQueueWorker.isInstall(q("https://mmp.affclix.org/m/ev?ak=K", """{"event":"purchase"}""")))
        assertTrue(EventQueueWorker.isInstall(q("https://mmp.affclix.org/m/ev?ak=K&event=install", null)))
        assertFalse(EventQueueWorker.isInstall(q("https://mmp.affclix.org/m/ev?ak=K&event=session_start", null)))
    }
}
