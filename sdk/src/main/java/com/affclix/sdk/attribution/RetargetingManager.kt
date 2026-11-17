package com.affclix.sdk.attribution

import com.affclix.sdk.internal.Logger
import com.affclix.sdk.internal.StorageManager
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// RetargetingManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Detects whether the current session is a re-engagement or re-attribution.
 *
 *   First install            → is_retargeting = 0
 *   Re-engagement (< 90 d)  → is_retargeting = 1, type = "re_engagement"
 *   Re-attribution (≥ 90 d) → is_retargeting = 1, type = "re_attribution"
 *
 * Maps to:  is_retargeting             UInt8
 *           retargeting_conversion_type LowCardinality(String)
 */
internal class RetargetingManager(private val storage: StorageManager) {

    companion object {
        private const val TAG                     = "RetargetingManager"
        private const val RE_ATTRIBUTION_DAYS     = 90L
    }

    fun getState(): RetargetingState {
        if (storage.isFirstLaunch()) {
            return RetargetingState(false, null, false)
        }

        val lastSessionMs = storage.getLastSessionStartTime()
        if (lastSessionMs <= 0) return RetargetingState(false, null, true)

        val inactiveDays = (System.currentTimeMillis() - lastSessionMs) / 86_400_000L
        val convType = when {
            inactiveDays >= RE_ATTRIBUTION_DAYS -> "re_attribution"
            inactiveDays > 0                   -> "re_engagement"
            else                               -> null
        }
        val isRetargeting = convType != null

        Logger.d(TAG, "Retargeting: $isRetargeting ($convType) inactiveDays=$inactiveDays")
        return RetargetingState(isRetargeting, convType, !isRetargeting)
    }
}

data class RetargetingState(
    val isRetargeting:        Boolean,
    val conversionType:       String?,    // re_engagement | re_attribution | null
    val isPrimaryAttribution: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// MultiTouchAttributionManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Records up to 3 prior marketing touches before a conversion.
 * Populates contributor_1/2/3_media_source, _campaign, _touch_type, _touch_time
 * columns in the conversions table.
 *
 * Touch recording: every trackClick / trackImpression call records a touch.
 * On conversion: the last 3 touches are emitted as contributors, then cleared.
 */
internal class MultiTouchAttributionManager(private val storage: StorageManager) {

    private val TAG             = "MultiTouch"
    private val MAX_CONTRIBUTORS = 3

    fun recordTouch(
        mediaSource: String?,
        campaign:    String?,
        touchType:   String,
        touchTime:   Long = System.currentTimeMillis()
    ) {
        val touches = getTouches().toMutableList()
        touches.add(TouchPoint(
            mediaSource = mediaSource ?: "unknown",
            campaign    = campaign,
            touchType   = touchType,
            touchTime   = touchTime
        ))
        while (touches.size > MAX_CONTRIBUTORS) touches.removeAt(0)
        saveTouches(touches)
        Logger.d(TAG, "Touch recorded: $mediaSource ($touchType) total=${touches.size}")
    }

    fun getTouches(): List<TouchPoint> {
        val raw = storage.getTouchJourney() ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TouchPoint(
                    mediaSource = o.optString("ms", "unknown"),
                    campaign    = o.optString("c").ifBlank { null },
                    touchType   = o.optString("tt", "click"),
                    touchTime   = o.optLong("ts", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clearTouches() = storage.saveTouchJourney(null)

    private fun saveTouches(touches: List<TouchPoint>) {
        val arr = JSONArray()
        touches.forEach { t ->
            arr.put(JSONObject().apply {
                put("ms", t.mediaSource)
                put("c",  t.campaign ?: "")
                put("tt", t.touchType)
                put("ts", t.touchTime)
            })
        }
        storage.saveTouchJourney(arr.toString())
    }
}

data class TouchPoint(
    val mediaSource: String,     // → contributor_N_media_source
    val campaign:    String?,    // → contributor_N_campaign
    val touchType:   String,     // → contributor_N_touch_type (click|impression)
    val touchTime:   Long        // → contributor_N_touch_time
)
