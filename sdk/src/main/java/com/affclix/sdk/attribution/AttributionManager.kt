package com.affclix.sdk.attribution

import android.content.Context
import android.net.Uri
import com.affclix.sdk.AffclixConfig
import com.affclix.sdk.internal.Logger
import com.affclix.sdk.internal.StorageManager
import com.affclix.sdk.model.AttributionData
import com.affclix.sdk.model.DeepLinkResult
import com.affclix.sdk.model.InstallReferrerData

/**
 * Manages attribution data lifecycle:
 *   - First-touch & last-touch attribution models
 *   - UTM parameter persistence across sessions
 *   - Deep link parameter extraction (standard and deferred)
 *   - Install referrer → attribution resolution
 *
 * Attribution model support:
 *   - last_click  (default): last marketing touch gets credit
 *   - first_click: very first install referrer gets credit
 *   - linear:      credit split across all touches (server-side)
 */
internal class AttributionManager(
    private val context: Context,
    private val storage: StorageManager,
    private val config: AffclixConfig
) {
    private val TAG = "AttributionManager"

    // ── Install Referrer ──────────────────────────────────────────────────────

    fun onInstallReferrerResolved(referrer: InstallReferrerData?) {
        if (referrer == null) return

        val data = AttributionData(
            mediaSource   = referrer.mediaSource,
            channel       = deriveChannel(referrer.utmMedium),
            campaign      = referrer.campaign,
            adGroup       = referrer.adGroup,
            creative      = referrer.creative,
            utmSource     = referrer.utmSource,
            utmMedium     = referrer.utmMedium,
            utmCampaign   = referrer.utmCampaign,
            utmTerm       = referrer.utmTerm,
            utmContent    = referrer.utmContent,
            referrer      = referrer.rawReferrer,
            isOrganic     = referrer.mediaSource == "organic",
            resolvedAt    = System.currentTimeMillis()
        )

        // Always store first-touch (never overwritten)
        if (storage.getFirstTouchAttribution() == null) {
            storage.saveFirstTouchAttribution(data)
            Logger.d(TAG, "First-touch attribution saved: ${data.mediaSource}")
        }

        // Update last-touch on every meaningful touch
        if (!data.isOrganic) {
            storage.saveLastTouchAttribution(data)
            Logger.d(TAG, "Last-touch attribution updated: ${data.mediaSource}")
        }
    }

    // ── Deep Link Handling ────────────────────────────────────────────────────

    /**
     * Extracts attribution and routing parameters from an Android intent URI.
     *
     * Handles:
     *   1. Standard deep links:     yourapp://open?utm_source=fb&click_id=abc
     *   2. App Links (HTTP/HTTPS):  https://yourapp.com/promo?ref=aff&c=123, including
     *      branded links https://{tenant domain}/l/{bundle_id}?click_id=…&deep_link_value=…
     *   3. Deferred deep links:     resolved from install referrer on first launch
     *
     * deep_link_value (or deep_link) is the advertiser's routing payload, surfaced as
     * [DeepLinkResult.deepLinkValue] — the same field a deferred deep link fills.
     */
    fun resolveDeepLink(uri: Uri, callback: (DeepLinkResult) -> Unit) {
        Logger.d(TAG, "Resolving deep link: $uri")

        val params     = extractUriParams(uri)
        val clickId    = params["click_id"] ?: params["clickid"] ?: params["_ac_cid"]
        val campaignId = params["campaign_id"] ?: params["c"]
        val affiliateId = params["affiliate_id"] ?: params["a"] ?: params["aff_id"]
        val visitorId  = params["_ac_vid"]

        // Persist UTMs from deep link
        val utmSource   = params["utm_source"]
        val utmMedium   = params["utm_medium"]
        val utmCampaign = params["utm_campaign"]
        val utmTerm     = params["utm_term"]
        val utmContent  = params["utm_content"]

        if (!utmSource.isNullOrBlank()) {
            val deepLinkAttribution = AttributionData(
                mediaSource  = utmSource,
                channel      = deriveChannel(utmMedium),
                campaign     = utmCampaign,
                utmSource    = utmSource,
                utmMedium    = utmMedium,
                utmCampaign  = utmCampaign,
                utmTerm      = utmTerm,
                utmContent   = utmContent,
                resolvedAt   = System.currentTimeMillis()
            )
            storage.saveLastTouchAttribution(deepLinkAttribution)
        }

        // Cross-domain visitor ID sync
        if (!visitorId.isNullOrBlank()) {
            storage.saveVisitorId(visitorId)
        }

        val customParams = params.filterKeys { key ->
            key !in setOf("click_id", "clickid", "_ac_cid", "_ac_vid",
                "campaign_id", "c", "affiliate_id", "a", "aff_id",
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")
        }

        val result = DeepLinkResult(
            uri          = uri,
            clickId      = clickId,
            campaignId   = campaignId,
            affiliateId  = affiliateId,
            utmSource    = utmSource,
            utmMedium    = utmMedium,
            utmCampaign  = utmCampaign,
            customParams = customParams,
            isDeferredDeepLink = false,
            deepLinkValue = params["deep_link_value"]?.takeIf { it.isNotBlank() }
                ?: params["deep_link"]?.takeIf { it.isNotBlank() }
        )

        callback(result)
    }

    // ── Attribution Getters ───────────────────────────────────────────────────

    fun getAttributionData(): AttributionData {
        return storage.getLastTouchAttribution()
            ?: storage.getFirstTouchAttribution()
            ?: AttributionData(isOrganic = true)
    }

    fun getFirstTouchAttribution(): AttributionData? = storage.getFirstTouchAttribution()
    fun getLastTouchAttribution(): AttributionData?  = storage.getLastTouchAttribution()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractUriParams(uri: Uri): Map<String, String> = buildMap {
        try {
            uri.queryParameterNames?.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    put(key, value)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "URI param extraction error: ${e.message}")
        }
    }

    /**
     * Derive channel from utm_medium — maps to the `channel` concept in
     * industry-standard attribution (used by Singular, AppsFlyer, Adjust).
     */
    private fun deriveChannel(medium: String?): String? {
        return when (medium?.lowercase()) {
            "cpc", "ppc", "paid", "paidsearch"              -> "paid_search"
            "cpm", "display", "banner"                      -> "display"
            "social", "paidsocial", "paid_social"           -> "paid_social"
            "email"                                         -> "email"
            "sms"                                           -> "sms"
            "push"                                          -> "push"
            "affiliate", "referral"                         -> "affiliate"
            "organic", null, ""                             -> "organic"
            else                                            -> medium
        }
    }
}
