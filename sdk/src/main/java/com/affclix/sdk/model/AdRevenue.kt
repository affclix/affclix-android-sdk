package com.affclix.sdk.model

/**
 * One ad impression's revenue, from the mediation SDK's paid-event callback.
 * Pass it to [com.affclix.sdk.AffclixSDK.logAdRevenue]; the MMP keeps it with the
 * device's attribution, so ad revenue reports per campaign.
 *
 * Mapping from the common mediation SDKs:
 *
 * | Field | AppLovin MAX (`MaxAd`) | LevelPlay (`ImpressionData`) | AdMob (`AdValue` + `ResponseInfo`) |
 * |---|---|---|---|
 * | [mediation] | `"applovin_max"` | `"levelplay"` | `"admob"` |
 * | [revenue] | `revenue` | `revenue` | `valueMicros / 1e6` |
 * | [currency] | `"USD"` | `"USD"` | `currencyCode` |
 * | [network] | `networkName` | `adNetwork` | `loadedAdapterResponseInfo.adSourceName` |
 * | [adUnitId] | `adUnitId` | `mediationAdUnitId` | the ad unit ID |
 * | [adFormat] | `format.label` | `adFormat` | the format you loaded |
 * | [placement] | `placement` | `placement` | — |
 * | [precision] | `revenuePrecision` | `precision` | `precisionType` (0–3) |
 */
data class AdRevenue(
    val mediation: String,
    val revenue: Double,
    val currency: String = "USD",
    val network: String? = null,
    val adUnitId: String? = null,
    val adUnitName: String? = null,
    val adFormat: String? = null,
    val placement: String? = null,
    /** exact, estimated, publisher_defined or undefined (AdMob's 3, 2, 1, 0 are read too). */
    val precision: String? = null,
    /** Impressions this revenue covers; 1 for a paid-event callback. */
    val impressions: Int = 1,
) {
    /** The `/m/ev` parameters of an `ad_revenue` event. */
    fun toParams(): Map<String, Any> = buildMap {
        put("revenue", revenue)
        put("currency", currency)
        put("ad_mediation", mediation)
        network?.let { put("ad_network", it) }
        adUnitId?.let { put("ad_unit_id", it) }
        adUnitName?.let { put("ad_unit_name", it) }
        adFormat?.let { put("ad_format", it) }
        placement?.let { put("ad_placement", it) }
        precision?.let { put("revenue_precision", it) }
        if (impressions != 1) put("ad_impressions", impressions)
    }
}
