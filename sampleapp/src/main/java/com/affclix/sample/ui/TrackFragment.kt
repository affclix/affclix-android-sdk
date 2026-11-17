package com.affclix.sample.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.affclix.sample.R
import com.affclix.sample.SampleApp
import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.internal.TrackingCallback
import com.affclix.sdk.model.AffclixEvent
import com.affclix.sdk.model.ClickEvent
import com.affclix.sdk.model.ConversionEvent
import com.affclix.sdk.model.ImpressionEvent
import com.affclix.sdk.model.PostbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TrackFragment : Fragment() {

    private lateinit var logView:    TextView
    private lateinit var scrollView: ScrollView
    private val log = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_track, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logView    = view.findViewById(R.id.tv_track_log)
        scrollView = view.findViewById(R.id.scroll_track)

        appendLog("SDK v${AffclixSDK.getVersion()}")
        appendLog("Visitor: ${AffclixSDK.getVisitorId()}")
        appendLog("Session: ${AffclixSDK.getSessionId()}")
        appendLog("MockServer: ${SampleApp.instance.mockServer.port}")
        appendLog("\nTap a button to test.\n")

        view.findViewById<Button>(R.id.btn_track_impression).setOnClickListener  { testImpression() }
        view.findViewById<Button>(R.id.btn_track_click).setOnClickListener       { testClick() }
        view.findViewById<Button>(R.id.btn_track_conversion).setOnClickListener  { testConversion() }
        view.findViewById<Button>(R.id.btn_track_vta).setOnClickListener         { testVta() }
        view.findViewById<Button>(R.id.btn_track_purchase).setOnClickListener    { testPurchase() }
        view.findViewById<Button>(R.id.btn_track_registration).setOnClickListener{ testRegistration() }
        view.findViewById<Button>(R.id.btn_track_custom).setOnClickListener      { testCustomEvent() }
        view.findViewById<Button>(R.id.btn_track_consent).setOnClickListener     { testConsentQueue() }
        view.findViewById<Button>(R.id.btn_track_run_all).setOnClickListener     { runAll() }
        view.findViewById<Button>(R.id.btn_track_clear).setOnClickListener       { clearLog() }
    }

    private fun testImpression() {
        section("IMPRESSION")
        AffclixSDK.trackImpression(
            ImpressionEvent(
                campaignId  = "",
                affiliateId = "",
                creativeId  = "cr_300x250",
                adType      = "banner",
                subIds      = mapOf("sub1" to "placement_header")
            ),
            object : TrackingCallback {
                override fun onSuccess(id: String) = appendLog("impression_id: $id")
                override fun onError(message: String) = appendLog("Error: $message")
            }
        )
    }

    private fun testClick() {
        section("CLICK")
        AffclixSDK.trackClick(
            ClickEvent(
                campaignId  = "",
                affiliateId = "",
                source      = "facebook_feed",
                adType      = "interstitial",
                redirectMs  = 85,
                subIds      = mapOf("sub1" to "adset_india", "sub2" to "ad_001")
            ),
            object : TrackingCallback {
                override fun onSuccess(id: String) = appendLog("click_id: $id")
                override fun onError(message: String) = appendLog("Error: $message")
            }
        )
    }

    private fun testConversion() {
        section("CONVERSION (with prior click)")
        AffclixSDK.trackClick(ClickEvent(campaignId = "", affiliateId = ""))
        lifecycleScope.launch {
            delay(600)
            AffclixSDK.trackConversion(
                ConversionEvent(
                    campaignId    = "",
                    affiliateId   = "",
                    eventName     = AffclixEvent.PURCHASE,
                    transactionId = "TXN_${System.currentTimeMillis()}",
                    revenue       = 4999.00,
                    currency      = "INR",
                    productId     = "prod_premium",
                    isNewCustomer = true,
                    couponCode    = "SUMMER20",
                    subParams     = listOf("cust_001", "premium", "Q4")
                ),
                object : TrackingCallback {
                    override fun onSuccess(id: String) = appendLog("txn_id: $id")
                    override fun onError(message: String) = appendLog("Error: $message")
                }
            )
        }
    }

    private fun testVta() {
        section("VIEW-THROUGH ATTRIBUTION")
        AffclixSDK.trackImpression(
            ImpressionEvent(
                campaignId             = "YOUR_CAMPAIGN_ID",
                affiliateId            = "YOUR_AFFILIATE_ID",
                adType                 = "rewarded_video",
                viewThroughAttribution = true
            ),
            object : TrackingCallback {
                override fun onSuccess(id: String) {
                    appendLog("imp_id: $id")
                    lifecycleScope.launch {
                        delay(500)
                        AffclixSDK.trackConversion(
                            ConversionEvent(
                                campaignId   = "YOUR_CAMPAIGN_ID",
                                eventName    = AffclixEvent.REGISTRATION,
                                postbackType = PostbackType.PIXEL
                            ),
                            object : TrackingCallback {
                                override fun onSuccess(id: String) = appendLog("VTA conv txn: $id")
                                override fun onError(message: String) = appendLog("Error: $message")
                            }
                        )
                    }
                }
                override fun onError(message: String) = appendLog("Error: $message")
            }
        )
    }

    private fun testPurchase() {
        section("PURCHASE EVENT")
        AffclixSDK.logPurchase(9999.0, "INR", "ORD-${System.currentTimeMillis()}")
        appendLog("logPurchase sent")
    }

    private fun testRegistration() {
        section("STANDARD EVENTS")
        AffclixSDK.logRegistration("google_oauth")
        appendLog("logRegistration sent")
        AffclixSDK.logLogin("google_oauth")
        appendLog("logLogin sent")
        AffclixSDK.logTutorialComplete()
        appendLog("logTutorialComplete sent")
        AffclixSDK.logSubscription("premium_monthly", 499.0, "INR")
        appendLog("logSubscription sent")
    }

    private fun testCustomEvent() {
        section("CUSTOM EVENT")
        AffclixSDK.logEvent("product_viewed", mapOf(
            "product_id" to "prod_123",
            "price"      to 4999.0,
            "currency"   to "INR",
            "source"     to "home_screen"
        ))
        appendLog("logEvent(product_viewed) sent")
    }

    private fun testConsentQueue() {
        section("GDPR CONSENT QUEUE")
        AffclixSDK.revokeConsent()
        appendLog("Consent REVOKED")
        appendLog("Sending click (will be QUEUED)...")
        AffclixSDK.trackClick(
            ClickEvent(campaignId = "camp_gdpr", affiliateId = "aff_001"),
            object : TrackingCallback {
                override fun onQueued()              = appendLog("Event QUEUED (no consent)")
                override fun onSuccess(id: String)   = appendLog("Flushed click_id: $id")
                override fun onError(message: String)= appendLog("Error: $message")
            }
        )
        lifecycleScope.launch {
            delay(1500)
            AffclixSDK.grantConsent()
            appendLog("Consent GRANTED - flushing queue...")
        }
    }

    private fun runAll() {
        section("RUNNING ALL TESTS")
        val tests = listOf(
            { testImpression() },
            { testClick() },
            { testPurchase() },
            { testRegistration() },
            { testCustomEvent() }
        )
        lifecycleScope.launch {
            tests.forEachIndexed { i, fn ->
                appendLog("\n[${i + 1}/${tests.size}]")
                fn()
                delay(800)
            }
            appendLog("\nALL TESTS COMPLETE")
            appendLog("See Inspector tab for requests")
        }
    }

    private fun section(title: String) = appendLog("\n-- $title --")

    private fun appendLog(line: String) {
        activity?.runOnUiThread {
            log.appendLine(line)
            logView.text = log.toString()
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        log.clear()
        logView.text = "Log cleared.\n"
    }
}