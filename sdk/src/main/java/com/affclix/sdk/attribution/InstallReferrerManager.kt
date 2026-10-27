package com.affclix.sdk.attribution

import android.content.Context
import android.net.Uri
import com.affclix.sdk.internal.Logger
import com.affclix.sdk.internal.StorageManager
import com.affclix.sdk.model.InstallReferrerData
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves install attribution via Google Play Install Referrer API.
 *
 * The Install Referrer API is the industry-standard method (used by AppsFlyer,
 * Adjust, Branch) for capturing attribution on first install. It provides:
 *
 *   - Full UTM parameters from the Play Store click
 *   - click_timestamp & install_begin_timestamp for time-to-install metrics
 *   - Google click IDs (gclid, gbraid, wbraid) for SAN (Self-Attributing Network) matching
 *   - Deferred deep link data when the app was not installed at click time
 *
 * The resolved data populates:
 *   utm_source, utm_medium, utm_campaign, utm_term, utm_content
 *   referrer, landing_page, sub_aff_id* (via custom referrer params)
 */
internal class InstallReferrerManager(
    private val context: Context,
    private val storage: StorageManager
) {
    private val TAG = "InstallReferrerManager"

    /**
     * Timeout of each attempt. The install is reported only after resolution, so
     * a slow Play service delays it rather than losing its referrer (and so its
     * attribution); worst case about 36 s.
     */
    private val ATTEMPT_TIMEOUTS_MS = longArrayOf(5_000L, 10_000L, 15_000L)
    private val RETRY_DELAY_MS = 2_000L

    /** One attempt's result: the referrer, a final "none", or worth retrying. */
    private sealed class Fetch {
        class Ok(val data: InstallReferrerData) : Fetch()
        object Unsupported : Fetch()
        object Transient : Fetch()
    }

    /**
     * Resolves the install referrer. Safe to call every launch — returns cached
     * value on subsequent calls without hitting the Play Store API again.
     *
     * A timeout, SERVICE_UNAVAILABLE or a dropped connection is retried; only
     * after every attempt fails is the install reported without a referrer.
     */
    suspend fun resolve(callback: suspend (InstallReferrerData?) -> Unit) = withContext(Dispatchers.IO) {
        // Return cached referrer on re-launches
        val cached = storage.getInstallReferrer()
        if (cached != null) {
            Logger.d(TAG, "Using cached install referrer")
            callback(cached)
            return@withContext
        }

        var referrer: InstallReferrerData? = null
        for ((attempt, timeout) in ATTEMPT_TIMEOUTS_MS.withIndex()) {
            if (attempt > 0) delay(RETRY_DELAY_MS)
            when (val r = withTimeoutOrNull(timeout) { fetchFromPlayStore() } ?: Fetch.Transient) {
                is Fetch.Ok        -> { referrer = r.data; break }
                Fetch.Unsupported  -> break
                Fetch.Transient    -> Logger.w(TAG, "Install referrer attempt ${attempt + 1} failed")
            }
        }

        if (referrer != null) {
            storage.saveInstallReferrer(referrer)
            Logger.d(TAG, "Install referrer resolved: source=${referrer.mediaSource} campaign=${referrer.campaign}")
        } else {
            Logger.w(TAG, "Install referrer unavailable — reporting the install without it")
        }

        callback(referrer)
    }

    private suspend fun fetchFromPlayStore(): Fetch =
        suspendCancellableCoroutine { cont ->
            val client = InstallReferrerClient.newBuilder(context).build()

            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            try {
                                val details: ReferrerDetails = client.installReferrer
                                val data = parseReferrerDetails(details)
                                client.endConnection()
                                if (cont.isActive) cont.resume(Fetch.Ok(data))
                            } catch (e: Exception) {
                                Logger.e(TAG, "Referrer parse error: ${e.message}")
                                client.endConnection()
                                if (cont.isActive) cont.resume(Fetch.Transient)
                            }
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            Logger.w(TAG, "Install Referrer: feature not supported")
                            if (cont.isActive) cont.resume(Fetch.Unsupported)
                        }
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            Logger.w(TAG, "Install Referrer: service unavailable")
                            client.endConnection()
                            if (cont.isActive) cont.resume(Fetch.Transient)
                        }
                        else -> {
                            // DEVELOPER_ERROR, PERMISSION_ERROR: retrying will not help.
                            Logger.w(TAG, "Install Referrer: unknown response $responseCode")
                            if (cont.isActive) cont.resume(Fetch.Unsupported)
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Logger.w(TAG, "Install Referrer: service disconnected")
                    if (cont.isActive) cont.resume(Fetch.Transient)
                }
            })

            cont.invokeOnCancellation {
                try { client.endConnection() } catch (ignored: Exception) {}
            }
        }

    /**
     * Parse the raw referrer string from the Play Store.
     *
     * The referrer is a URL-encoded query string, e.g.:
     *   utm_source=facebook&utm_medium=cpc&utm_campaign=summer_sale&click_id=abc123
     *
     * We extract standard UTM params + Google click IDs + any custom params
     * (sub1-sub10, affiliate_id, click_id, creative_id, etc.)
     */
    private fun parseReferrerDetails(details: ReferrerDetails): InstallReferrerData {
        val raw = details.installReferrer ?: ""
        Logger.d(TAG, "Raw install referrer: $raw")

        // Parse as URI query string
        val params = parseQueryString(raw)

        // Google click IDs — present when traffic came from Google Ads
        val gclid  = params["gclid"]
        val gbraid = params["gbraid"]    // iOS-equivalent privacy-safe click ID
        val wbraid = params["wbraid"]    // web-to-app privacy-safe click ID

        val utmSource   = params["utm_source"]
        val utmMedium   = params["utm_medium"]
        val utmCampaign = params["utm_campaign"]
        val utmContent  = params["utm_content"]
        val utmTerm     = params["utm_term"]

        // Derive media_source (normalised, like AppsFlyer does)
        val mediaSource = deriveMediaSource(params, gclid, gbraid, wbraid, utmSource, utmMedium)
        val campaign    = utmCampaign ?: params["campaign"]
        val adGroup     = params["adgroup"] ?: params["ad_group"]
        val creative    = params["creative"] ?: params["creative_id"]

        // Deferred deep link — advertisers encode deep_link_value= (or deep_link=) in the
        // Play Store tracking URL. Branch.io and AppsFlyer both support this convention.
        val deepLinkValue = params["deep_link_value"]?.takeIf { it.isNotBlank() }
            ?: params["deep_link"]?.takeIf { it.isNotBlank() }

        return InstallReferrerData(
            rawReferrer              = raw,
            mediaSource              = mediaSource,
            campaign                 = campaign,
            adGroup                  = adGroup,
            creative                 = creative,
            clickTimestamp           = details.referrerClickTimestampSeconds,
            installBeginTimestamp    = details.installBeginTimestampSeconds,
            referrerClickTimestamp   = details.referrerClickTimestampServerSeconds,
            gclid                    = gclid,
            gbraid                   = gbraid,
            wbraid                   = wbraid,
            utmSource                = utmSource,
            utmMedium                = utmMedium,
            utmCampaign              = utmCampaign,
            utmContent               = utmContent,
            utmTerm                  = utmTerm,
            deepLinkValue            = deepLinkValue,
        )
    }

    /**
     * Normalise to a canonical media_source name — same logic as AppsFlyer / Singular.
     *
     * Priority: explicit utm_source → SAN (Google/Meta/TikTok click IDs) → direct
     */
    private fun deriveMediaSource(
        params: Map<String, String>,
        gclid: String?, gbraid: String?, wbraid: String?,
        utmSource: String?, utmMedium: String?
    ): String {
        // Explicit source wins
        if (!utmSource.isNullOrBlank()) {
            return normaliseSource(utmSource, utmMedium)
        }

        // Self-Attributing Networks via click IDs
        if (!gclid.isNullOrBlank() || !gbraid.isNullOrBlank() || !wbraid.isNullOrBlank()) {
            return "google_ads"
        }

        // Check Facebook / Meta (they sometimes inject fbclid into referrer)
        params["fbclid"]?.let { return "facebook_ads" }
        params["ttclid"]?.let { return "tiktok_ads" }
        params["sclid"]?.let  { return "snapchat_ads" }

        return "organic"
    }

    private fun normaliseSource(source: String, medium: String?): String {
        val s = source.lowercase()
        return when {
            s.contains("google")    -> if (medium?.lowercase() == "organic") "google" else "google_ads"
            s.contains("facebook")  -> "facebook_ads"
            s.contains("instagram") -> "instagram_ads"
            s.contains("tiktok")    -> "tiktok_ads"
            s.contains("twitter") || s.contains("x.com") -> "twitter_ads"
            s.contains("snapchat") -> "snapchat_ads"
            s.contains("pinterest") -> "pinterest_ads"
            s.contains("meta")     -> "meta_ads"
            else -> source
        }
    }

    private fun parseQueryString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            // Wrap as URI query to leverage Android's Uri parser
            Uri.parse("http://x.com?$raw").let { uri ->
                buildMap {
                    uri.queryParameterNames.forEach { key ->
                        uri.getQueryParameter(key)?.let { value ->
                            put(key, value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Query parse error: ${e.message}")
            emptyMap()
        }
    }
}
