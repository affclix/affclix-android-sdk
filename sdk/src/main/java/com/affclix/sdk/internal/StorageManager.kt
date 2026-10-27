package com.affclix.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.affclix.sdk.model.AttributionData
import com.affclix.sdk.model.InstallReferrerData
import com.affclix.sdk.model.Session
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent storage — EncryptedSharedPreferences for sensitive attribution IDs,
 * plain SharedPreferences for non-sensitive counters and timestamps.
 *
 * Key prefix "utk_" mirrors the web SDK cookie naming for cross-platform
 * consistency in server-side reporting.
 */
internal class StorageManager(private val context: Context) {

    companion object {
        private const val PFX                  = "utk_"
        private const val KEY_VISITOR_ID       = "${PFX}vid"
        private const val KEY_CLICK_ID         = "${PFX}click_id"
        private const val KEY_FIRST_TOUCH      = "${PFX}ft"
        private const val KEY_LAST_TOUCH       = "${PFX}lt"
        private const val KEY_INSTALL_REF      = "${PFX}install_ref"
        private const val KEY_CONSENT          = "${PFX}consent"
        private const val KEY_SESSION          = "${PFX}sess"
        private const val KEY_INSTALL_TIME     = "${PFX}install_time"
        private const val KEY_LAST_SESS_TIME   = "${PFX}last_sess_ts"
        private const val KEY_TOUCH_JOURNEY    = "${PFX}touch_journey"
        private const val KEY_FIRST_LAUNCH     = "affclix_first_launch_done"
        private const val KEY_USER_ID          = "${PFX}user_id"
        private const val KEY_USER_PROPS       = "${PFX}user_props"
        private const val KEY_GDPR_APPLIES     = "${PFX}gdpr_applies"
        private const val KEY_AD_USER_DATA     = "${PFX}ad_user_data"
        private const val KEY_AD_PERSONALIZATION = "${PFX}ad_personalization"
        private const val KEY_PUSH_TOKEN       = "${PFX}push_token"
        private const val KEY_REPORTED_SESSION = "${PFX}reported_session"
        private const val CLICK_PFX            = "${PFX}click_"
        private const val CLICK_TS_PFX         = "${PFX}click_ts_"
        private const val IMP_PFX              = "${PFX}imp_"
        private const val TXN_PFX              = "${PFX}txn_"
    }

    private val secure: SharedPreferences by lazy { createSecurePrefs() }
    private val plain: SharedPreferences  by lazy {
        context.getSharedPreferences("affclix_meta", Context.MODE_PRIVATE)
    }

    // ── Visitor ID ────────────────────────────────────────────────────────────

    fun getVisitorId(): String {
        val stored = secureGet(KEY_VISITOR_ID)
        if (!stored.isNullOrBlank()) return stored
        val id = generateUUID()
        securePut(KEY_VISITOR_ID, id)
        return id
    }

    fun saveVisitorId(id: String) = securePut(KEY_VISITOR_ID, id)

    // ── Click ID ──────────────────────────────────────────────────────────────

    /**
     * The click to credit events to, null once its click expiry
     * (AffclixConfig.clickExpiryDays) has passed: the user's re-engagement
     * window. Events then fall back to the install's attribution on the MMP.
     */
    fun getClickId(campaignId: String? = null, now: Long = System.currentTimeMillis()): String? {
        if (!campaignId.isNullOrBlank() && !clickExpired("exp_$campaignId", now)) {
            val specific = secureGet("$CLICK_PFX$campaignId")
            if (!specific.isNullOrBlank()) return specific
        }
        if (clickExpired("exp_global", now)) return null
        return secureGet(KEY_CLICK_ID)?.takeIf { it.isNotBlank() }
    }

    fun storeClickId(campaignId: String, clickId: String, expiryDays: Int, now: Long = System.currentTimeMillis()) {
        securePut(KEY_CLICK_ID, clickId)
        securePut("$CLICK_PFX$campaignId", clickId)
        val expiresAt = now + expiryDays * 86_400_000L
        plain.edit()
            .putLong("${CLICK_PFX}exp_$campaignId", expiresAt)
            .putLong("${CLICK_PFX}exp_global", expiresAt)
            .apply()
    }

    // A click stored before expiries were written (no entry) never expires.
    private fun clickExpired(expKey: String, now: Long): Boolean {
        val expiresAt = plain.getLong("$CLICK_PFX$expKey", 0L)
        return expiresAt in 1 until now
    }

    // ── Click Timestamp  (for time_to_convert calculation) ───────────────────

    fun getClickTime(campaignId: String? = null): Long {
        if (!campaignId.isNullOrBlank()) {
            val t = plain.getLong("$CLICK_TS_PFX$campaignId", -1L)
            if (t > 0) return t
        }
        return plain.getLong("${CLICK_TS_PFX}global", -1L)
    }

    fun saveClickTime(campaignId: String, time: Long = System.currentTimeMillis()) {
        plain.edit()
            .putLong("$CLICK_TS_PFX$campaignId", time)
            .putLong("${CLICK_TS_PFX}global", time)
            .apply()
    }

    // ── Impression ID ─────────────────────────────────────────────────────────

    fun getImpressionId(campaignId: String): String? =
        secureGet("$IMP_PFX$campaignId")?.takeIf { it.isNotBlank() }

    fun storeImpressionId(campaignId: String, impressionId: String, expiryDays: Int) {
        securePut("$IMP_PFX$campaignId", impressionId)
        plain.edit()
            .putLong("${IMP_PFX}exp_$campaignId",
                System.currentTimeMillis() + expiryDays * 86_400_000L)
            .apply()
    }

    // ── Transaction ID ────────────────────────────────────────────────────────

    fun storeTransactionId(campaignId: String, txnId: String) =
        securePut("$TXN_PFX$campaignId", txnId)

    // ── Install Time  (for CTIT and install→conversion lag) ──────────────────

    fun getInstallTime(): Long? {
        val t = plain.getLong(KEY_INSTALL_TIME, -1L)
        return if (t > 0) t else null
    }

    fun saveInstallTime(time: Long = System.currentTimeMillis()) {
        plain.edit().putLong(KEY_INSTALL_TIME, time).apply()
    }

    // ── Last Session Start  (for retargeting detection) ───────────────────────

    fun getLastSessionStartTime(): Long = plain.getLong(KEY_LAST_SESS_TIME, -1L)

    fun saveLastSessionStartTime(time: Long = System.currentTimeMillis()) {
        plain.edit().putLong(KEY_LAST_SESS_TIME, time).apply()
    }

    // ── Daily Counter  (for FraudDetector velocity checks) ───────────────────

    fun incrementDailyCounter(key: String): Int {
        val current = plain.getInt(key, 0)
        val next    = current + 1
        plain.edit().putInt(key, next).apply()
        return next
    }

    // ── Multi-Touch Journey  (for contributor_1/2/3 columns) ─────────────────

    fun getTouchJourney(): String? = plain.getString(KEY_TOUCH_JOURNEY, null)

    fun saveTouchJourney(json: String?) {
        plain.edit().apply {
            if (json == null) remove(KEY_TOUCH_JOURNEY)
            else putString(KEY_TOUCH_JOURNEY, json)
        }.apply()
    }

    // ── Attribution ───────────────────────────────────────────────────────────

    fun getFirstTouchAttribution(): AttributionData? =
        deserializeAttribution(secureGet(KEY_FIRST_TOUCH))

    fun saveFirstTouchAttribution(data: AttributionData) =
        securePut(KEY_FIRST_TOUCH, serializeAttribution(data))

    fun getLastTouchAttribution(): AttributionData? =
        deserializeAttribution(secureGet(KEY_LAST_TOUCH))

    fun saveLastTouchAttribution(data: AttributionData) =
        securePut(KEY_LAST_TOUCH, serializeAttribution(data))

    // ── Install Referrer ──────────────────────────────────────────────────────

    fun getInstallReferrer(): InstallReferrerData? =
        deserializeInstallReferrer(plain.getString(KEY_INSTALL_REF, null))

    fun saveInstallReferrer(data: InstallReferrerData) {
        val json = JSONObject().apply {
            put("raw",           data.rawReferrer ?: "")
            put("source",        data.mediaSource ?: "")
            put("campaign",      data.campaign ?: "")
            put("adgroup",       data.adGroup ?: "")
            put("creative",      data.creative ?: "")
            put("click_ts",      data.clickTimestamp ?: 0L)
            put("install_ts",    data.installBeginTimestamp ?: 0L)
            put("gclid",         data.gclid ?: "")
            put("gbraid",        data.gbraid ?: "")
            put("wbraid",        data.wbraid ?: "")
            put("utm_source",    data.utmSource ?: "")
            put("utm_medium",    data.utmMedium ?: "")
            put("utm_campaign",  data.utmCampaign ?: "")
            put("utm_content",   data.utmContent ?: "")
            put("utm_term",      data.utmTerm ?: "")
            put("deep_link_value", data.deepLinkValue ?: "")
        }.toString()
        plain.edit().putString(KEY_INSTALL_REF, json).apply()
    }

    // ── Session ───────────────────────────────────────────────────────────────

    fun getSession(): Session? {
        val raw = plain.getString(KEY_SESSION, null) ?: return null
        return try {
            val j = JSONObject(raw)
            Session(
                id           = j.getString("id"),
                startedAt    = j.getLong("startedAt"),
                lastActivity = j.getLong("lastActivity"),
                pageviews    = j.optInt("pageviews", 1),
                isNew        = false
            )
        } catch (e: Exception) { null }
    }

    fun saveSession(session: Session) {
        val json = JSONObject().apply {
            put("id",           session.id)
            put("startedAt",    session.startedAt)
            put("lastActivity", session.lastActivity)
            put("pageviews",    session.pageviews)
        }.toString()
        plain.edit().putString(KEY_SESSION, json).apply()
    }

    // ── Consent ───────────────────────────────────────────────────────────────

    fun hasConsent(): Boolean = plain.getBoolean(KEY_CONSENT, false)

    fun saveConsent(granted: Boolean) =
        plain.edit().putBoolean(KEY_CONSENT, granted).apply()

    // ── Consent data (Google consent mode) ──────────────────────────────────
    // Stored as -1 (unset) / 0 / 1.

    fun getConsentData(): ConsentData {
        fun read(key: String) = when (plain.getInt(key, -1)) { 1 -> true; 0 -> false; else -> null }
        return ConsentData(read(KEY_GDPR_APPLIES), read(KEY_AD_USER_DATA), read(KEY_AD_PERSONALIZATION))
    }

    fun saveConsentData(c: ConsentData) {
        fun Boolean?.code() = when (this) { true -> 1; false -> 0; null -> -1 }
        plain.edit()
            .putInt(KEY_GDPR_APPLIES, c.gdprApplies.code())
            .putInt(KEY_AD_USER_DATA, c.adUserData.code())
            .putInt(KEY_AD_PERSONALIZATION, c.adPersonalization.code())
            .apply()
    }

    // ── Session reporting ─────────────────────────────────────────────────────

    fun getReportedSessionId(): String? = plain.getString(KEY_REPORTED_SESSION, null)

    fun saveReportedSessionId(id: String) = plain.edit().putString(KEY_REPORTED_SESSION, id).apply()

    // ── Push token (uninstall tracking) ───────────────────────────────────────

    /** The push token last registered with the MMP. */
    fun getPushToken(): String? = secureGet(KEY_PUSH_TOKEN)

    fun savePushToken(token: String) = securePut(KEY_PUSH_TOKEN, token)

    // ── First Launch ──────────────────────────────────────────────────────────

    fun isFirstLaunch(): Boolean = !plain.getBoolean(KEY_FIRST_LAUNCH, false)

    fun markFirstLaunchDone() =
        plain.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()

    // ── User Identity (enrichment for attribution & segmentation) ────────────

    /** Customer / user ID set by the host app after login / registration. */
    fun getUserId(): String? = secureGet(KEY_USER_ID)

    fun saveUserId(id: String?) {
        if (id.isNullOrBlank()) {
            try { secure.edit().remove(KEY_USER_ID).apply() }
            catch (_: Exception) { plain.edit().remove(KEY_USER_ID).apply() }
        } else {
            securePut(KEY_USER_ID, id)
        }
    }

    /**
     * Free-form key/value pairs sent on every subsequent event.
     * Keys are prefixed with "up_" when added to the event payload to avoid
     * collisions with reserved SDK fields.
     */
    fun getUserProperties(): Map<String, String> {
        val raw = plain.getString(KEY_USER_PROPS, null) ?: return emptyMap()
        return try {
            val j = JSONObject(raw)
            buildMap { j.keys().forEach { k -> put(k, j.optString(k)) } }
        } catch (_: Exception) { emptyMap() }
    }

    fun saveUserProperties(props: Map<String, String>) {
        val j = JSONObject()
        props.forEach { (k, v) -> j.put(k, v) }
        plain.edit().putString(KEY_USER_PROPS, j.toString()).apply()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    fun generateUUID(): String = UUID.randomUUID().toString()

    fun clearAll() {
        secure.edit().clear().apply()
        plain.edit().clear().apply()
    }

    /**
     * Forgets the user: every stored identifier and attribution touch (visitor ID,
     * click / impression IDs, user ID and properties, push token, referrer), but
     * not the install state — first-launch flag and install time — so the next
     * launch is not reported as a new install, nor the consent flag.
     */
    fun clearIdentifiers() {
        secure.edit().clear().apply()
        val keep = setOf(KEY_FIRST_LAUNCH, KEY_INSTALL_TIME, KEY_CONSENT)
        val editor = plain.edit()
        plain.all.keys.filter { it !in keep }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun secureGet(key: String): String? =
        try { secure.getString(key, null) }
        catch (e: Exception) { plain.getString(key, null) }

    private fun securePut(key: String, value: String) {
        try { secure.edit().putString(key, value).apply() }
        catch (e: Exception) { plain.edit().putString(key, value).apply() }
    }

    private fun createSecurePrefs(): SharedPreferences = try {
        val mk = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "affclix_secure", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Logger.w("StorageManager", "EncryptedSharedPreferences unavailable, using plain prefs")
        context.getSharedPreferences("affclix_fb", Context.MODE_PRIVATE)
    }

    private fun serializeAttribution(d: AttributionData): String = JSONObject().apply {
        put("media_source",  d.mediaSource ?: "")
        put("channel",       d.channel ?: "")
        put("campaign",      d.campaign ?: "")
        put("ad_group",      d.adGroup ?: "")
        put("creative",      d.creative ?: "")
        put("ad_type",       d.adType ?: "")
        put("utm_source",    d.utmSource ?: "")
        put("utm_medium",    d.utmMedium ?: "")
        put("utm_campaign",  d.utmCampaign ?: "")
        put("utm_term",      d.utmTerm ?: "")
        put("utm_content",   d.utmContent ?: "")
        put("referrer",      d.referrer ?: "")
        put("is_organic",    d.isOrganic)
        put("resolved_at",   d.resolvedAt)
    }.toString()

    private fun deserializeAttribution(raw: String?): AttributionData? {
        if (raw.isNullOrBlank()) return null
        return try {
            val j = JSONObject(raw)
            AttributionData(
                mediaSource  = j.optString("media_source").ifBlank { null },
                channel      = j.optString("channel").ifBlank { null },
                campaign     = j.optString("campaign").ifBlank { null },
                adGroup      = j.optString("ad_group").ifBlank { null },
                creative     = j.optString("creative").ifBlank { null },
                adType       = j.optString("ad_type").ifBlank { null },
                utmSource    = j.optString("utm_source").ifBlank { null },
                utmMedium    = j.optString("utm_medium").ifBlank { null },
                utmCampaign  = j.optString("utm_campaign").ifBlank { null },
                utmTerm      = j.optString("utm_term").ifBlank { null },
                utmContent   = j.optString("utm_content").ifBlank { null },
                referrer     = j.optString("referrer").ifBlank { null },
                isOrganic    = j.optBoolean("is_organic"),
                resolvedAt   = j.optLong("resolved_at")
            )
        } catch (e: Exception) { null }
    }

    private fun deserializeInstallReferrer(raw: String?): InstallReferrerData? {
        if (raw.isNullOrBlank()) return null
        return try {
            val j = JSONObject(raw)
            InstallReferrerData(
                rawReferrer           = j.optString("raw").ifBlank { null },
                mediaSource           = j.optString("source").ifBlank { null },
                campaign              = j.optString("campaign").ifBlank { null },
                adGroup               = j.optString("adgroup").ifBlank { null },
                creative              = j.optString("creative").ifBlank { null },
                clickTimestamp        = j.optLong("click_ts").takeIf { it > 0 },
                installBeginTimestamp = j.optLong("install_ts").takeIf { it > 0 },
                gclid                 = j.optString("gclid").ifBlank { null },
                gbraid                = j.optString("gbraid").ifBlank { null },
                wbraid                = j.optString("wbraid").ifBlank { null },
                utmSource             = j.optString("utm_source").ifBlank { null },
                utmMedium             = j.optString("utm_medium").ifBlank { null },
                utmCampaign           = j.optString("utm_campaign").ifBlank { null },
                utmContent            = j.optString("utm_content").ifBlank { null },
                utmTerm               = j.optString("utm_term").ifBlank { null },
                deepLinkValue         = j.optString("deep_link_value").ifBlank { null },
            )
        } catch (e: Exception) { null }
    }
}
