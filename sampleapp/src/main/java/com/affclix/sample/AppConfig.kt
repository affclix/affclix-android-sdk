package com.affclix.sample

import android.util.Log
import com.affclix.sdk.AffclixCallbacks
import com.affclix.sdk.AffclixConfig
import com.affclix.sdk.model.ClickResult
import com.affclix.sdk.model.ConversionResult
import com.affclix.sdk.model.DeepLinkResult
import com.affclix.sdk.model.ImpressionResult
import com.affclix.sdk.model.SdkError

object AppConfig {

    // ── SWITCH HERE ───────────────────────────────────────────────────
    val ENV = Env.DEV      // change to Env.PROD before going live
    // ─────────────────────────────────────────────────────────────────

    enum class Env { DEV, PROD }

    private val devConfig = AffclixConfig(
        domainId              = "YOUR_DOMAIN_ID",
        trackingDomain        = "localhost:8080",   // the in-app MockWebServer (SampleApp.kt)
        defaultCampaignId     = "YOUR_CAMPAIGN_ID",
        defaultAffiliateId    = "YOUR_AFFILIATE_ID",
        consentRequired       = false,
        respectLAT            = false,
        collectFingerprint    = true,
        autoSessionTracking   = true,
        sessionTimeoutMinutes = 5,
        testMode              = true,
        debug                 = true,
        clickExpiryDays       = 1,
        impressionExpiryDays  = 1,
        maxRetries            = 1,
        retryBaseDelayMs      = 500L,
        retryMaxDelayMs       = 2_000L,
        requestTimeoutMs      = 5_000L,
        callbacks             = callbacks("DEV")
    )

    private val prodConfig = AffclixConfig(
        domainId              = "YOUR_DOMAIN_ID",
        trackingDomain        = "track.example.com", // your Affclix tracking domain
        defaultCampaignId     = "YOUR_CAMPAIGN_ID",
        defaultAffiliateId    = "YOUR_AFFILIATE_ID",
        consentRequired       = true,
        respectLAT            = true,
        collectFingerprint    = true,
        autoSessionTracking   = true,
        sessionTimeoutMinutes = 30,
        testMode              = false,
        debug                 = false,
        clickExpiryDays       = 30,
        impressionExpiryDays  = 30,
        maxRetries            = 3,
        retryBaseDelayMs      = 1_000L,
        retryMaxDelayMs       = 10_000L,
        requestTimeoutMs      = 10_000L,
        callbacks             = callbacks("PROD")
    )

    val active: AffclixConfig get() = when (ENV) {
        Env.DEV  -> devConfig
        Env.PROD -> prodConfig
    }

    val isDev:  Boolean get() = ENV == Env.DEV
    val isProd: Boolean get() = ENV == Env.PROD

    fun logEnv() {
        Log.i("AffclixEnv", "════════════════════════════════")
        Log.i("AffclixEnv", "  ENV:      $ENV")
        Log.i("AffclixEnv", "  SERVER:   ${active.trackingDomain}")
        Log.i("AffclixEnv", "  DOMAIN:   ${active.domainId}")
        Log.i("AffclixEnv", "  CAMPAIGN: ${active.defaultCampaignId}")
        Log.i("AffclixEnv", "  AFFILIATE:${active.defaultAffiliateId}")
        Log.i("AffclixEnv", "  TEST:     ${active.testMode}")
        Log.i("AffclixEnv", "  DEBUG:    ${active.debug}")
        Log.i("AffclixEnv", "════════════════════════════════")
    }

    private fun callbacks(env: String) = AffclixCallbacks(
        onClickTracked = { r: ClickResult ->
            Log.d("Affclix[$env]", "Click: ${r.clickId}")
        },
        onImpressionTracked = { r: ImpressionResult ->
            Log.d("Affclix[$env]", "Impression: ${r.impressionId}")
        },
        onConversionTracked = { r: ConversionResult ->
            Log.d("Affclix[$env]", "Conversion: ${r.transactionId}")
        },
        onDeepLinkResolved = { r: DeepLinkResult ->
            Log.d("Affclix[$env]", "DeepLink: click=${r.clickId} source=${r.utmSource}")
        },
        onError = { e: SdkError ->
            Log.e("Affclix[$env]", "Error [${e.type}]: ${e.message}")
        }
    )
}
