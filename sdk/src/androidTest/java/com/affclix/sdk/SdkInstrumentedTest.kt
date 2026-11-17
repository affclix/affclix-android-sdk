package com.affclix.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affclix.sdk.attribution.MultiTouchAttributionManager
import com.affclix.sdk.attribution.RetargetingManager
import com.affclix.sdk.fraud.CtitCategory
import com.affclix.sdk.fraud.FraudDetector
import com.affclix.sdk.internal.*
import com.affclix.sdk.model.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SDK module instrumented tests — run on a real device or emulator.
 *
 * Lives inside the SDK module so it can access `internal` members
 * (storage, deviceInfo, fraudDetector, etc.) — exactly as AppsFlyer
 * and Branch structure their own instrumented test suites.
 *
 * Run command (device/emulator must be connected):
 *   ./gradlew :sdk:connectedAndroidTest
 *
 * Or via ADB after installing the test APK:
 *   adb shell am instrument -w \
 *     com.affclix.sdk.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SdkInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        ctx    = ApplicationProvider.getApplicationContext()
        app    = ctx as Application
        server = MockWebServer()
        server.start()
        resetSdk()
    }

    @After fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
        resetSdk()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. INIT — real Android environment
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun init_creates_stable_visitor_id_on_real_device() {
        AffclixSDK.init(app, testConfig())
        Thread.sleep(500)

        val id1 = AffclixSDK.getVisitorId()
        val id2 = AffclixSDK.getVisitorId()
        Assert.assertNotNull(id1)
        Assert.assertTrue("Visitor ID must be non-blank", id1.isNotBlank())
        Assert.assertEquals("Visitor ID must be stable", id1, id2)
        Assert.assertTrue("Visitor ID must look like a UUID (≥32 chars)", id1.length >= 32)
    }

    @Test fun visitor_id_persists_across_sdk_reinit() {
        AffclixSDK.init(app, testConfig())
        val id1 = AffclixSDK.getVisitorId()
        resetSdk()
        AffclixSDK.init(app, testConfig())
        val id2 = AffclixSDK.getVisitorId()
        Assert.assertEquals("Visitor ID must survive re-init (EncryptedSharedPreferences)", id1, id2)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. ENCRYPTED STORAGE — real Android keystore
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun storage_click_id_roundtrip_on_real_keystore() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.storage.storeClickId("real_camp", "real_clk_001", 30)
        val retrieved = AffclixSDK.storage.getClickId("real_camp")
        Assert.assertEquals("Click ID must survive EncryptedSharedPreferences roundtrip",
            "real_clk_001", retrieved)
    }

    @Test fun storage_click_time_persists_correctly() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis()
        AffclixSDK.storage.saveClickTime("camp_ts", now)
        val retrieved = AffclixSDK.storage.getClickTime("camp_ts")
        Assert.assertTrue("Click time must round-trip within 1ms", retrieved >= now - 1)
    }

    @Test fun storage_install_time_persists_correctly() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis()
        AffclixSDK.storage.saveInstallTime(now)
        Assert.assertEquals("Install time must be persisted exactly", now,
            AffclixSDK.storage.getInstallTime())
    }

    @Test fun storage_touch_journey_survives_serialize_deserialize() {
        AffclixSDK.init(app, testConfig())
        val json = """[{"ms":"google_ads","c":"summer","tt":"click","ts":1700000000}]"""
        AffclixSDK.storage.saveTouchJourney(json)
        Assert.assertEquals(json, AffclixSDK.storage.getTouchJourney())
        AffclixSDK.storage.saveTouchJourney(null)
        Assert.assertNull(AffclixSDK.storage.getTouchJourney())
    }

    @Test fun storage_clear_all_wipes_every_key() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.storage.storeClickId("c", "clk", 30)
        AffclixSDK.storage.saveConsent(true)
        AffclixSDK.storage.clearAll()
        Assert.assertNull(AffclixSDK.storage.getClickId("c"))
        Assert.assertFalse(AffclixSDK.storage.hasConsent())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. DEVICE INFO — real hardware signals
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun device_fingerprint_is_64_char_sha256_hex() {
        AffclixSDK.init(app, testConfig())
        val fp = AffclixSDK.deviceInfo.getDeviceFingerprint()
        Assert.assertTrue("Fingerprint must be 64 hex chars (SHA-256)",
            fp.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun device_fingerprint_is_deterministic_on_same_device() {
        AffclixSDK.init(app, testConfig())
        val fp1 = AffclixSDK.deviceInfo.getDeviceFingerprint()
        val fp2 = AffclixSDK.deviceInfo.getDeviceFingerprint()
        Assert.assertEquals("Fingerprint must be same on consecutive calls", fp1, fp2)
    }

    @Test fun device_info_collect_returns_all_required_fields() {
        AffclixSDK.init(app, testConfig())
        val device = AffclixSDK.deviceInfo.collect()
        Assert.assertFalse("device_type must not be blank", device.deviceType.isBlank())
        Assert.assertFalse("brand must not be blank",       device.brand.isBlank())
        Assert.assertFalse("model must not be blank",       device.model.isBlank())
        Assert.assertFalse("os_version must not be blank",  device.osVersion.isBlank())
        Assert.assertFalse("language must not be blank",    device.language.isBlank())
        Assert.assertFalse("timezone must not be blank",    device.timezone.isBlank())
        Assert.assertFalse("app_package must not be blank", device.appPackage.isBlank())
        Assert.assertTrue("screen width must be > 0",       device.screenWidth > 0)
        Assert.assertTrue("screen height must be > 0",      device.screenHeight > 0)
    }

    @Test fun device_type_is_valid_enum_value() {
        AffclixSDK.init(app, testConfig())
        val type = AffclixSDK.deviceInfo.collect().deviceType
        Assert.assertTrue("device_type must be one of phone|tablet|tv|watch|automotive",
            type in listOf("phone", "tablet", "tv", "watch", "automotive"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. FRAUD DETECTION — real Android APIs
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun fraud_assessment_completes_within_200ms() {
        AffclixSDK.init(app, testConfig())
        val start = System.currentTimeMillis()
        val assessment = AffclixSDK.fraudDetector.assess()
        val elapsed = System.currentTimeMillis() - start
        Assert.assertNotNull(assessment)
        Assert.assertTrue("Fraud assessment must complete < 200ms", elapsed < 200)
    }

    @Test fun fraud_score_is_in_valid_range() {
        AffclixSDK.init(app, testConfig())
        val score = AffclixSDK.fraudDetector.assess().score
        Assert.assertTrue("Fraud score must be 0–100, got $score", score in 0..100)
    }

    @Test fun fraud_payload_map_has_all_required_keys() {
        AffclixSDK.init(app, testConfig())
        val map = AffclixSDK.fraudDetector.assess().toPayloadMap()
        listOf("fraud_score", "is_proxy", "is_emulator", "is_rooted", "is_farm", "fraud_reasons")
            .forEach { key ->
                Assert.assertTrue("Missing fraud payload key: $key", map.containsKey(key))
            }
    }

    @Test fun ctit_hijacking_detected_under_10_seconds() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis() / 1000
        val r = AffclixSDK.fraudDetector.assessCtit(now - 5, now)
        Assert.assertEquals(CtitCategory.HIJACKING, r.category)
        Assert.assertEquals(95, r.fraudScore)
    }

    @Test fun ctit_device_farm_detected_at_40_seconds() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis() / 1000
        val r = AffclixSDK.fraudDetector.assessCtit(now - 40, now)
        Assert.assertEquals(CtitCategory.DEVICE_FARM, r.category)
        Assert.assertTrue(r.fraudScore >= 80)
    }

    @Test fun ctit_normal_at_10_minutes() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis() / 1000
        val r = AffclixSDK.fraudDetector.assessCtit(now - 600, now)
        Assert.assertEquals(CtitCategory.NORMAL, r.category)
        Assert.assertEquals(0, r.fraudScore)
    }

    @Test fun ctit_click_flooding_at_45_days() {
        AffclixSDK.init(app, testConfig())
        val now = System.currentTimeMillis() / 1000
        val r = AffclixSDK.fraudDetector.assessCtit(now - 45 * 86400, now)
        Assert.assertEquals(CtitCategory.CLICK_FLOODING, r.category)
        Assert.assertTrue(r.fraudScore >= 70)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. NETWORK — real HTTP via MockWebServer
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun click_tracking_fires_real_http_request() {
        AffclixSDK.init(app, testConfig())
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"click_id":"real_device_clk_001"}"""))

        var receivedId: String? = null
        AffclixSDK.trackClick(ClickEvent(campaignId = "", affiliateId = ""),
            object : TrackingCallback {
                override fun onSuccess(id: String) { receivedId = id }
            })
        Thread.sleep(2000) // Allow background coroutine + real network

        val req = server.takeRequest(3, TimeUnit.SECONDS)
        Assert.assertNotNull("HTTP request must reach MockWebServer", req)
        val path = URLDecoder.decode(req!!.path ?: "", "UTF-8")
        Assert.assertTrue("platform=android must be in URL",    path.contains("platform=android"))
        Assert.assertTrue("c= must be in URL",         path.contains("c=camp_real"))
        Assert.assertTrue("fraud_score= must be in URL",        path.contains("fraud_score="))
        Assert.assertTrue("device_type= must be in URL",        path.contains("device_type="))
        Assert.assertEquals("Callback must receive click_id", "real_device_clk_001", receivedId)
    }

    @Test fun impression_tracking_sends_ad_type() {
        AffclixSDK.init(app, testConfig())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"iid":"imp_real_001"}"""))
        AffclixSDK.trackImpression(ImpressionEvent(
            campaignId = "camp_imp", affiliateId = "aff_001",
            adType = "rewarded_video"))
        Thread.sleep(2000)
        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue("ad_type=rewarded_video must be in URL",
            path.contains("ad_type=rewarded_video"))
    }

    @Test fun conversion_carries_click_id_from_prior_click() {
        AffclixSDK.init(app, testConfig())

        // Step 1: click
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"click_id":"prior_clk_001"}"""))
        AffclixSDK.trackClick(ClickEvent(campaignId = "", affiliateId = ""))
        Thread.sleep(2000)
        server.takeRequest(3, TimeUnit.SECONDS) // consume click

        // Step 2: conversion
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        AffclixSDK.trackConversion(ConversionEvent(
            campaignId = "", revenue = 9999.0, currency = "INR"))
        Thread.sleep(2000)

        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue("click_id must be passed to conversion",
            path.contains("click_id=prior_clk_001"))
    }

    @Test fun conversion_sends_contributor_from_prior_touch() {
        AffclixSDK.init(app, testConfig())
        // Record a touch manually (multiTouch is internal — accessible here)
        AffclixSDK.multiTouch.recordTouch("google_ads", "brand_campaign", "click")

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        AffclixSDK.trackConversion(ConversionEvent(campaignId = "camp_mt", revenue = 1.0))
        Thread.sleep(2000)

        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue("contributor_1_touch_type=click must be in URL",
            path.contains("contributor_1_touch_type=click"))
        Assert.assertTrue("contributor_1_media_source=google_ads must be in URL",
            path.contains("contributor_1_media_source=google_ads"))
    }

    @Test fun conversion_sends_sub_adv_ids_from_sub_params() {
        AffclixSDK.init(app, testConfig())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        AffclixSDK.trackConversion(ConversionEvent(
            campaignId = "camp_sub",
            subParams  = listOf("cust_xyz", "plan_pro", "cohort_Q4")))
        Thread.sleep(2000)
        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue(path.contains("sub_adv_id1=cust_xyz"))
        Assert.assertTrue(path.contains("sub_adv_id2=plan_pro"))
        Assert.assertTrue(path.contains("sub_adv_id3=cohort_Q4"))
    }

    @Test fun consent_queue_holds_events_until_granted() {
        AffclixSDK.init(app, AffclixConfig(
            domainId       = "dom_consent",
            trackingDomain = "${server.hostName}:${server.port}",
            consentRequired = true))

        var queued = false
        AffclixSDK.trackClick(ClickEvent(campaignId = "c", affiliateId = "a"),
            object : TrackingCallback { override fun onQueued() { queued = true } })
        Thread.sleep(500)
        Assert.assertEquals("No requests before consent", 0, server.requestCount)
        Assert.assertTrue("onQueued must fire", queued)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"click_id":"flushed"}"""))
        AffclixSDK.grantConsent()
        Thread.sleep(2000)
        Assert.assertTrue("Event must fire after consent", server.requestCount >= 1)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. SESSION MANAGEMENT — real Activity lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun session_id_is_16_chars() {
        AffclixSDK.init(app, testConfig())
        Assert.assertEquals(16, AffclixSDK.getSessionId().length)
    }

    @Test fun session_is_preserved_within_timeout() {
        AffclixSDK.init(app, testConfig())
        val id1 = AffclixSDK.getSessionId()
        val id2 = AffclixSDK.getSessionId()
        Assert.assertEquals("Session must be same within timeout", id1, id2)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. RETARGETING — day-boundary logic on real clock
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun first_launch_is_not_retargeting() {
        AffclixSDK.init(app, testConfig())
        val state = AffclixSDK.retargeting.getState()
        Assert.assertFalse("First launch must not be retargeting", state.isRetargeting)
        Assert.assertNull("No conversion type on first launch", state.conversionType)
    }

    @Test fun active_user_is_re_engagement() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.storage.markFirstLaunchDone()
        AffclixSDK.storage.saveLastSessionStartTime(
            System.currentTimeMillis() - 3 * 86_400_000L)  // 3 days ago
        val state = AffclixSDK.retargeting.getState()
        Assert.assertTrue("3-day inactive user must be retargeting", state.isRetargeting)
        Assert.assertEquals("re_engagement", state.conversionType)
    }

    @Test fun dormant_90d_user_is_re_attribution() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.storage.markFirstLaunchDone()
        AffclixSDK.storage.saveLastSessionStartTime(
            System.currentTimeMillis() - 95 * 86_400_000L)  // 95 days ago
        val state = AffclixSDK.retargeting.getState()
        Assert.assertTrue(state.isRetargeting)
        Assert.assertEquals("re_attribution", state.conversionType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. MULTI-TOUCH — real persistence
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun multi_touch_records_and_retrieves_three_touches() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.multiTouch.clearTouches()
        AffclixSDK.multiTouch.recordTouch("facebook", "summer", "click")
        AffclixSDK.multiTouch.recordTouch("google",   "brand",  "click")
        AffclixSDK.multiTouch.recordTouch("email",    "promo",  "click")
        val touches = AffclixSDK.multiTouch.getTouches()
        Assert.assertEquals(3, touches.size)
        Assert.assertEquals("email",    touches[2].mediaSource)
        Assert.assertEquals("facebook", touches[0].mediaSource)
    }

    @Test fun multi_touch_evicts_oldest_when_full() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.multiTouch.clearTouches()
        repeat(5) { i -> AffclixSDK.multiTouch.recordTouch("source_$i", null, "click") }
        val touches = AffclixSDK.multiTouch.getTouches()
        Assert.assertEquals("Max 3 contributors", 3, touches.size)
        Assert.assertEquals("Oldest must be evicted", "source_2", touches[0].mediaSource)
    }

    @Test fun multi_touch_clear_removes_all_touches() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.multiTouch.recordTouch("fb", null, "click")
        AffclixSDK.multiTouch.clearTouches()
        Assert.assertTrue(AffclixSDK.multiTouch.getTouches().isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. DEEP LINK — Intent parsing
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun deep_link_extracts_all_attribution_params() {
        AffclixSDK.init(app, testConfig())
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("yourapp://open?click_id=dl_001&campaign_id=c_test&" +
                      "affiliate_id=aff_dl&utm_source=push&utm_medium=notification&" +
                      "utm_campaign=reactivation_q4"))

        var result: DeepLinkResult? = null
        AffclixSDK.handleDeepLink(intent) { result = it }
        Thread.sleep(200)

        Assert.assertEquals("dl_001",            result?.clickId)
        Assert.assertEquals("c_test",            result?.campaignId)
        Assert.assertEquals("aff_dl",            result?.affiliateId)
        Assert.assertEquals("push",              result?.utmSource)
        Assert.assertEquals("notification",      result?.utmMedium)
        Assert.assertEquals("reactivation_q4",   result?.utmCampaign)
    }

    @Test fun deep_link_null_intent_does_not_crash() {
        AffclixSDK.init(app, testConfig())
        AffclixSDK.handleDeepLink(null) {}  // must not throw
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. STANDARD EVENTS — fire-and-forget
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun standard_event_logPurchase_sends_revenue_and_currency() {
        AffclixSDK.init(app, testConfig())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        AffclixSDK.logPurchase(4999.0, "INR", "ORD-001")
        Thread.sleep(2000)
        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue("event=purchase must be in URL", path.contains("event=purchase"))
        Assert.assertTrue("revenue=4999 must be in URL",  path.contains("revenue=4999"))
        Assert.assertTrue("currency=INR must be in URL",   path.contains("currency=INR"))
    }

    @Test fun custom_event_sends_ev_prefixed_params() {
        AffclixSDK.init(app, testConfig())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        AffclixSDK.logEvent("tutorial_step", mapOf("step" to 3, "topic" to "attribution"))
        Thread.sleep(2000)
        val req = server.takeRequest(3, TimeUnit.SECONDS)
        val path = URLDecoder.decode(req?.path ?: "", "UTF-8")
        Assert.assertTrue("event=tutorial_step must be in URL",
            path.contains("event=tutorial_step"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun testConfig() = AffclixConfig(
        domainId       = "dom_instrumented",
        trackingDomain = "${server.hostName}:${server.port}",
        debug          = true,
        testMode       = true
    )

    private fun resetSdk() {
        try {
            val f = AffclixSDK::class.java.getDeclaredField("initialized")
            f.isAccessible = true
            (f.get(AffclixSDK) as AtomicBoolean).set(false)
        } catch (_: Exception) {}
    }
}
