package com.affclix.sdk

import com.affclix.sdk.model.ClickResult
import com.affclix.sdk.model.ConversionResult
import com.affclix.sdk.model.DeepLinkResult
import com.affclix.sdk.model.ImpressionResult
import com.affclix.sdk.model.SdkError

/**
 * SDK configuration. Pass to [AffclixSDK.init] once from Application.onCreate().
 *
 * Defaults are production-safe. Override only what you need.
 */
data class AffclixConfig(
    // ── Required ──────────────────────────────────────────────────────────────
    /**
     * UUID app key from the Affclix dashboard (preferred auth mechanism for mobile SDK).
     * When set, the backend resolves the tenant and app from it — you do not need to
     * set domainId. Required by the MMP's event endpoint.
     */
    val appKey: String = "",
    /**
     * SDK signing secret from the Affclix dashboard. When set, every request carries
     * sig= (HMAC-SHA256 of the request under this secret), so the server can tell
     * requests from your app apart from forged ones. Keep it out of source control
     * (e.g. inject via BuildConfig); like any embedded secret it raises the bar
     * rather than making extraction impossible.
     */
    val appSecret: String = "",
    /** Numeric domain ID assigned in the Affclix dashboard (d= param). Used only when appKey is blank. */
    val domainId: String = "",
    /** Hostname of your Affclix tracking server, e.g. "track.example.com". */
    val trackingDomain: String = "affclix.org",

    // ── Optional routing defaults ─────────────────────────────────────────────
    /** Default affiliate ID sent when an event doesn't specify one. */
    val defaultAffiliateId: String = "",
    /** Default campaign ID sent when an event doesn't specify one. */
    val defaultCampaignId: String = "",

    // ── Privacy / consent ─────────────────────────────────────────────────────
    /**
     * If true, all tracking is paused until [AffclixSDK.grantConsent] is called.
     * Events fired before consent are queued in-memory and replayed on grant.
     * Required for GDPR/CCPA compliance in markets where consent is mandatory.
     */
    val consentRequired: Boolean = false,
    /**
     * If true and the user has enabled Limit Ad Tracking (LAT) / opted out of
     * personalized ads, the GAID is not collected.
     */
    val respectLAT: Boolean = true,
    /**
     * Send a device fingerprint for cookieless attribution. Sent only when there is no
     * advertising ID and LAT is off: Google Play policy forbids linking the advertising
     * ID to persistent device identifiers.
     */
    val collectFingerprint: Boolean = false,
    /**
     * Send Settings.Secure.ANDROID_ID. Same conditions as [collectFingerprint]; enable
     * only with the user's consent where your privacy policy requires it.
     */
    val collectAndroidId: Boolean = false,
    /**
     * Read the IAB TCF v2 consent your CMP stores (IABTCF_* in the default
     * SharedPreferences) and send it with every request as gdpr_applies /
     * ad_user_data / ad_personalization / tcf_string. Values set with
     * [AffclixSDK.setConsentData] take precedence.
     */
    val enableTCFDataCollection: Boolean = false,

    // ── Session ───────────────────────────────────────────────────────────────
    val autoSessionTracking: Boolean = true,
    /** Minutes of background inactivity before the session is considered ended. */
    val sessionTimeoutMinutes: Int = 30,

    // ── Event delivery ────────────────────────────────────────────────────────
    /**
     * Use HTTP POST (JSON body) for /m/ev event requests.
     * Recommended: true. Large payloads with many custom params exceed GET URL limits.
     */
    val usePostForEvents: Boolean = true,
    /**
     * Persist failed events to SQLite and retry via WorkManager (CONNECTED constraint).
     * Mirrors AppsFlyer / Adjust offline queue behaviour.
     */
    val enableOfflineQueue: Boolean = true,

    // ── Testing / debug ───────────────────────────────────────────────────────
    /**
     * Marks all conversions with t=test so the backend flags them as test conversions.
     * Never set to true in a production build.
     */
    val testMode: Boolean = false,
    /** Enables verbose LogCat output. Strip from release builds via BuildConfig.DEBUG. */
    val debug: Boolean = false,

    // ── Attribution windows ───────────────────────────────────────────────────
    /** Days to retain a click ID for attribution look-back. */
    val clickExpiryDays: Int = 30,
    /** Days to retain an impression ID for view-through attribution look-back. */
    val impressionExpiryDays: Int = 30,

    // ── Server routing ────────────────────────────────────────────────────────
    /**
     * Set to true when [trackingDomain] serves the SDK routes without the /m prefix.
     * Routes become /ev, /c, /i instead of /m/ev, /m/c, /m/i.
     *
     * - false (default): web server, /m/ route prefix (backward-compatible)
     * - true: dedicated mobile server, no prefix (lower latency, fewer hops)
     */
    val useDedicatedMobileServer: Boolean = false,

    // ── Network ───────────────────────────────────────────────────────────────
    val maxRetries: Int = 3,
    val retryBaseDelayMs: Long = 1_000L,
    val retryMaxDelayMs: Long = 30_000L,
    val requestTimeoutMs: Long = 10_000L,

    // ── Callbacks ─────────────────────────────────────────────────────────────
    val callbacks: AffclixCallbacks = AffclixCallbacks()
)

data class AffclixCallbacks(
    val onImpressionTracked: ((ImpressionResult) -> Unit)?  = null,
    val onClickTracked:      ((ClickResult) -> Unit)?       = null,
    val onConversionTracked: ((ConversionResult) -> Unit)?  = null,
    val onDeepLinkResolved:  ((DeepLinkResult) -> Unit)?    = null,
    val onError:             ((SdkError) -> Unit)?          = null
)
