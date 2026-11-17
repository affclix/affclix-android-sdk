package com.affclix.sdk.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's consent as sent with every request, in Google consent mode terms
 * (EU DMA): gdpr_applies, ad_user_data, ad_personalization — plus the IAB TCF v2
 * consent string.
 *
 * Values the app sets with AffclixSDK.setConsentData win. Otherwise, with
 * [readTcf], they are derived from what the app's TCF CMP stored in the default
 * SharedPreferences (IAB TCF v2 spec), using Google's mapping:
 *   ad_user_data       = purposes 1 (store / access) and 7 (measure ad performance)
 *   ad_personalization = purposes 3 and 4 (personalised ads profile / selection)
 *
 * Under GDPR an explicit ad_user_data=0 makes the MMP skip device-ID matching and
 * keep the IP out of the advertiser postback. Unknown values are not sent.
 */
internal class ConsentManager(
    private val context: Context,
    private val storage: StorageManager,
    private val readTcf: Boolean
) {
    private val tcfPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
    }

    fun payload(): Map<String, Any?> {
        val explicit = storage.getConsentData()
        val tcf = if (readTcf) readTcf() else null

        val gdprApplies = explicit.gdprApplies ?: tcf?.gdprApplies
        // Outside GDPR there is nothing to consent to: the flags would be noise.
        val inScope = gdprApplies == true
        return mapOf(
            "gdpr_applies"       to gdprApplies?.toFlag(),
            "ad_user_data"       to (if (inScope) explicit.adUserData ?: tcf?.adUserData else null)?.toFlag(),
            "ad_personalization" to (if (inScope) explicit.adPersonalization ?: tcf?.adPersonalization else null)?.toFlag(),
            "tcf_string"         to tcf?.tcString?.takeIf { inScope && it.isNotBlank() },
        )
    }

    private data class Tcf(
        val gdprApplies: Boolean?,
        val tcString: String?,
        val adUserData: Boolean?,
        val adPersonalization: Boolean?
    )

    private fun readTcf(): Tcf? = try {
        val applies = when (tcfPrefs.all["IABTCF_gdprApplies"]) {
            1, "1" -> true
            0, "0" -> false
            else   -> null
        }
        // "1010…": character n-1 is purpose n's consent.
        val purposes = tcfPrefs.getString("IABTCF_PurposeConsents", null)
        fun consented(vararg p: Int): Boolean? =
            purposes?.takeIf { it.isNotEmpty() }?.let { s -> p.all { s.getOrNull(it - 1) == '1' } }
        Tcf(
            gdprApplies       = applies,
            tcString          = tcfPrefs.getString("IABTCF_TCString", null),
            adUserData        = consented(1, 7),
            adPersonalization = consented(3, 4)
        )
    } catch (e: Exception) {
        Logger.w("ConsentManager", "TCF read failed: ${e.message}")
        null
    }

    private fun Boolean.toFlag() = if (this) 1 else 0
}

/** Consent the app set explicitly; null = not set. */
internal data class ConsentData(
    val gdprApplies: Boolean? = null,
    val adUserData: Boolean? = null,
    val adPersonalization: Boolean? = null
)
