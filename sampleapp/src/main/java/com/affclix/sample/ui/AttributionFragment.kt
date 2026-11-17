package com.affclix.sample.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.affclix.sample.R
import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.internal.DeepLinkCallback
import com.affclix.sdk.model.DeepLinkResult

class AttributionFragment : Fragment() {

    private lateinit var logView:    TextView
    private lateinit var scrollView: ScrollView
    private val log = StringBuilder()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_attribution, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logView    = view.findViewById(R.id.tv_attr_log)
        scrollView = view.findViewById(R.id.scroll_attr)

        showCurrentAttribution()

        view.findViewById<Button>(R.id.btn_attr_show).setOnClickListener       { showCurrentAttribution() }
        view.findViewById<Button>(R.id.btn_attr_referrer).setOnClickListener   { showInstallReferrer() }
        view.findViewById<Button>(R.id.btn_attr_multitouch).setOnClickListener { testMultiTouch() }
        view.findViewById<Button>(R.id.btn_attr_deeplink).setOnClickListener   { simulateDeepLink() }
        view.findViewById<Button>(R.id.btn_attr_retarget).setOnClickListener   { showRetargeting() }
        view.findViewById<Button>(R.id.btn_attr_first_touch).setOnClickListener{ showFirstLastTouch() }
        view.findViewById<Button>(R.id.btn_attr_clear).setOnClickListener      { clearLog() }
    }

    private fun showCurrentAttribution() {
        section("CURRENT ATTRIBUTION")
        val attr = AffclixSDK.getAttribution()
        appendLog("media_source:  ${attr.mediaSource ?: "(organic)"}")
        appendLog("channel:       ${attr.channel ?: "(none)"}")
        appendLog("campaign:      ${attr.campaign ?: "(none)"}")
        appendLog("utm_source:    ${attr.utmSource ?: "(none)"}")
        appendLog("utm_medium:    ${attr.utmMedium ?: "(none)"}")
        appendLog("utm_campaign:  ${attr.utmCampaign ?: "(none)"}")
        appendLog("is_organic:    ${attr.isOrganic}")
        appendLog("")
        appendLog("visitor_id:    ${AffclixSDK.getVisitorId()}")
        appendLog("session_id:    ${AffclixSDK.getSessionId()}")
        appendLog("click_id:      ${AffclixSDK.getClickId() ?: "(none)"}")
    }

    private fun showInstallReferrer() {
        section("INSTALL REFERRER")
        val ref = AffclixSDK.getInstallReferrer()
        if (ref == null) {
            appendLog("No referrer captured yet.")
            appendLog("")
            appendLog("Simulate via ADB:")
            appendLog("adb shell am broadcast \\")
            appendLog("  -a com.android.vending.INSTALL_REFERRER \\")
            appendLog("  -n com.affclix.sample/...")
            appendLog("  --es referrer \"utm_source=google&gclid=abc\"")
        } else {
            appendLog("raw:       ${ref.rawReferrer?.take(60)}")
            appendLog("source:    ${ref.mediaSource}")
            appendLog("campaign:  ${ref.campaign}")
            appendLog("gclid:     ${ref.gclid ?: "(none)"}")
            appendLog("gbraid:    ${ref.gbraid ?: "(none)"}")
            appendLog("wbraid:    ${ref.wbraid ?: "(none)"}")
        }
    }

    private fun testMultiTouch() {
        section("MULTI-TOUCH (contributor_1/2/3)")
        AffclixSDK.clearContributorTouches()
        appendLog("Cleared. Simulating 3-touch journey:")
        AffclixSDK.recordTouchForTesting("google_organic",  "brand_search",    "impression")
        AffclixSDK.recordTouchForTesting("facebook_ads",    "retarget_q4",     "click")
        AffclixSDK.recordTouchForTesting("email",           "cart_abandonment", "click")
        appendLog("")
        val touches = AffclixSDK.getContributorTouches()
        touches.forEachIndexed { i, t ->
            appendLog("contributor_${i+1}: ${t.mediaSource} / ${t.campaign} (${t.touchType})")
        }
        appendLog("")
        appendLog("Track a conversion to send these as contributor columns")
    }

    private fun simulateDeepLink() {
        section("DEEP LINK TEST")
        val url = "affclix://open?click_id=dl_test_001" +
                  "&campaign_id=camp_dl&affiliate_id=aff_001" +
                  "&utm_source=push&utm_medium=notification" +
                  "&utm_campaign=reactivation_2024"
        appendLog("URL: $url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        AffclixSDK.handleDeepLink(intent, object : DeepLinkCallback {
            override fun onDeepLinkResolved(result: DeepLinkResult) {
                appendLog("click_id:    ${result.clickId}")
                appendLog("campaign_id: ${result.campaignId}")
                appendLog("utm_source:  ${result.utmSource}")
                appendLog("utm_medium:  ${result.utmMedium}")
                appendLog("custom:      ${result.customParams}")
            }
        })
    }

    private fun showRetargeting() {
        section("RETARGETING STATE")
        val state = AffclixSDK.getRetargetingState()
        appendLog("is_retargeting:   ${state.isRetargeting}")
        appendLog("conversion_type:  ${state.conversionType ?: "(new install)"}")
        appendLog("is_primary_attr:  ${state.isPrimaryAttribution}")
        appendLog("")
        appendLog("Logic:")
        appendLog("First install     -> is_retargeting = false")
        appendLog("Active (< 90 d)   -> re_engagement")
        appendLog("Dormant (>= 90 d) -> re_attribution")
    }

    private fun showFirstLastTouch() {
        section("FIRST / LAST TOUCH")
        val last  = AffclixSDK.getLastTouchAttribution()
        val first = AffclixSDK.getFirstTouchAttribution()
        appendLog("Last touch:")
        appendLog("  source:   ${last?.mediaSource ?: "(none)"}")
        appendLog("  campaign: ${last?.campaign ?: "(none)"}")
        appendLog("")
        appendLog("First touch:")
        appendLog("  source:   ${first?.mediaSource ?: "(none)"}")
        appendLog("  campaign: ${first?.campaign ?: "(none)"}")
    }

    private fun section(title: String) = appendLog("\n-- $title --")

    private fun appendLog(line: String) {
        activity?.runOnUiThread {
            log.appendLine(line)
            logView.text = log.toString()
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearLog() { log.clear(); logView.text = "Log cleared.\n" }
}