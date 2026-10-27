package com.affclix.sdk.model

import android.net.Uri

// ─────────────────────────────────────────────────────────────────────────────
// EVENT MODELS  →  one per tracking call type
// ─────────────────────────────────────────────────────────────────────────────

data class ImpressionEvent(
    val campaignId: String,
    val domainId: String? = null,
    val affiliateId: String? = null,
    val creativeId: String? = null,
    val adType: String? = null,              // → ad_type: banner|interstitial|rewarded|native|video
    val viewThroughAttribution: Boolean = false,
    val subIds: Map<String, String> = emptyMap(),   // sub1–sub6 → sub_aff_id1–6
    val customParams: Map<String, Any> = emptyMap()
)

data class ClickEvent(
    val campaignId: String,
    val domainId: String? = null,
    val affiliateId: String? = null,
    val creativeId: String? = null,
    val source: String? = null,
    val adType: String? = null,              // → ad_type column
    val redirectMs: Int = 0,                 // → redirect_ms (< 5 ms = bot signal)
    val subIds: Map<String, String> = emptyMap(),
    val customParams: Map<String, Any> = emptyMap()
)

data class ConversionEvent(
    val campaignId: String,
    val domainId: String? = null,
    val affiliateId: String? = null,
    val eventName: String = AffclixEvent.PURCHASE,
    val goalId: String? = null,
    val transactionId: String? = null,
    val orderId: String? = null,
    val revenue: Double? = null,
    val currency: String = "USD",
    val productId: String? = null,
    val productName: String? = null,
    val category: String? = null,
    val quantity: Int? = null,
    val customerId: String? = null,
    val isNewCustomer: Boolean = false,
    val couponCode: String? = null,
    val promoCode: String? = null,
    val postbackType: PostbackType = PostbackType.SERVER,
    val isTest: Boolean = false,
    /** Maps positionally to sub_adv_id1..5 */
    val subParams: List<String> = emptyList(),
    val subIds: Map<String, String> = emptyMap(),
    val customParams: Map<String, Any> = emptyMap()
)

// ─────────────────────────────────────────────────────────────────────────────
// RESULT / CALLBACK MODELS
// ─────────────────────────────────────────────────────────────────────────────

data class ImpressionResult(val impressionId: String, val campaignId: String)
data class ClickResult(val clickId: String, val campaignId: String)
data class ConversionResult(val transactionId: String, val clickId: String?, val method: String)
data class SdkError(val type: String, val message: String, val code: String? = null)

data class DeepLinkResult(
    val uri: Uri,
    val clickId: String? = null,
    val campaignId: String? = null,
    val affiliateId: String? = null,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val customParams: Map<String, String> = emptyMap(),
    /** True when resolved from Play Store install referrer on first launch. */
    val isDeferredDeepLink: Boolean = false,
    /** Raw deep_link_value= from the referrer — the advertiser's routing payload. */
    val deepLinkValue: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// ATTRIBUTION DATA
// ─────────────────────────────────────────────────────────────────────────────

data class AttributionData(
    val mediaSource: String? = null,   // normalised: google_ads | facebook_ads | organic
    val channel: String? = null,       // paid_search | paid_social | affiliate | organic
    val campaign: String? = null,
    val adGroup: String? = null,
    val creative: String? = null,
    val adType: String? = null,        // → ad_type column
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val utmTerm: String? = null,
    val utmContent: String? = null,
    val referrer: String? = null,
    val isOrganic: Boolean = false,
    val resolvedAt: Long = 0L
)

// ─────────────────────────────────────────────────────────────────────────────
// INSTALL REFERRER DATA
// ─────────────────────────────────────────────────────────────────────────────

data class InstallReferrerData(
    val rawReferrer: String? = null,
    val mediaSource: String? = null,
    val campaign: String? = null,
    val adGroup: String? = null,
    val creative: String? = null,
    val clickTimestamp: Long? = null,
    val installBeginTimestamp: Long? = null,
    val referrerClickTimestamp: Long? = null,
    val gclid: String? = null,
    val gbraid: String? = null,
    val wbraid: String? = null,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val utmContent: String? = null,
    val utmTerm: String? = null,
    /** Deferred deep link value from the Play Store referrer (deep_link_value= param). */
    val deepLinkValue: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// DEVICE DATA
// ─────────────────────────────────────────────────────────────────────────────

data class DeviceData(
    val deviceType: String,          // phone | tablet | tv | watch
    val brand: String,
    val model: String,
    val osVersion: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val dpi: Int,
    val language: String,
    val timezone: String,
    val carrier: String?,
    val networkType: String,         // wifi | cellular | offline
    val connectionType: String,      // 5G | LTE | 3G | 2G | wifi
    val appPackage: String,
    val appName: String,
    val appVersion: String,
    val appBuildNumber: String,
    val gaid: String?,               // → gaid column
    val androidId: String?,          // → android_id column
    val isLATEnabled: Boolean        // → is_lat column
)

// ─────────────────────────────────────────────────────────────────────────────
// SESSION
// ─────────────────────────────────────────────────────────────────────────────

data class Session(
    val id: String,
    val startedAt: Long,
    var lastActivity: Long,
    var pageviews: Int = 1,
    val isNew: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// PENDING QUEUE  (events held before consent)
// ─────────────────────────────────────────────────────────────────────────────

data class PendingEvent(
    val type: Type,
    val event: Any,
    val queuedAt: Long = System.currentTimeMillis()
) {
    enum class Type { IMPRESSION, CLICK, CONVERSION, EVENT }
}

/** Carrier for generic events held in the pre-consent queue. */
data class GenericEventData(val eventName: String, val params: Map<String, Any> = emptyMap())

// ─────────────────────────────────────────────────────────────────────────────
// ENUMS & CONSTANTS
// ─────────────────────────────────────────────────────────────────────────────

enum class PostbackType { SERVER, PIXEL }

object AffclixEvent {
    const val INSTALL               = "install"
    const val SESSION_START         = "session_start"
    const val PURCHASE              = "purchase"
    const val ADD_TO_CART           = "add_to_cart"
    const val CHECKOUT_START        = "checkout_start"
    const val REGISTRATION          = "registration"
    const val LOGIN                 = "login"
    const val TUTORIAL_COMPLETE     = "tutorial_complete"
    const val LEVEL_ACHIEVED        = "level_achieved"
    const val SUBSCRIPTION          = "subscription"
    const val AD_CLICK              = "ad_click"
    const val CONTENT_VIEW          = "content_view"
    const val SEARCH                = "search"
    const val SHARE                 = "share"
    const val INVITE                = "invite"
    const val RATE                  = "rate_us"
    const val PUSH_OPEN             = "push_open"
    const val DEEP_LINK             = "deep_link"
    const val APP_OPEN              = "app_open"
    const val TRIAL_START           = "trial_start"
    const val LEAD                  = "lead"
    const val COMPLETE_REGISTRATION = "complete_registration"
}
