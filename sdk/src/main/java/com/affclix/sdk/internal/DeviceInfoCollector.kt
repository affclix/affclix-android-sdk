package com.affclix.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.affclix.sdk.AffclixSDK
import com.affclix.sdk.model.DeviceData
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.*

/**
 * Collects the device attributes sent with each request:
 *   device_type, device_brand, device_name, os, os_version,
 *   gaid, android_id, language, timezone, isp, asn, fingerprint
 *
 * GAID collection respects the user's "Delete Advertising ID" opt-out
 * introduced in Android 12 (API 31) and controlled via [AffclixConfig.respectLAT].
 */
internal class DeviceInfoCollector(private val context: Context) {

    private val TAG = "DeviceInfoCollector"

    // Cached GAID — fetched once per session via Google Play Services
    private var cachedGaid: String? = null
    private var gaidFetched = false
    private var isLATEnabled = false

    /** Collect all device attributes. Non-blocking; GAID is fetched lazily. */
    fun collect(): DeviceData {
        val metrics = getDisplayMetrics()
        return DeviceData(
            deviceType  = getDeviceType(),
            brand       = Build.MANUFACTURER ?: "unknown",
            model       = Build.MODEL ?: "unknown",
            osVersion   = Build.VERSION.RELEASE ?: "",
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            dpi         = metrics.densityDpi,
            language    = Locale.getDefault().toLanguageTag(),
            timezone    = TimeZone.getDefault().id,
            carrier     = getCarrierName(),
            networkType = getNetworkType(),
            connectionType = getConnectionType(),
            appPackage  = context.packageName,
            appName     = getAppName(),
            appVersion  = getAppVersion(),
            appBuildNumber = getAppBuildNumber(),
            gaid        = cachedGaid,
            androidId   = getAndroidId(),
            isLATEnabled = isLATEnabled
        )
    }

    /**
     * Asynchronously fetch the advertising ID.
     *
     * Priority:
     *   1. Google GAID (Play Services) — all GMS devices
     *   2. Huawei OAID (HMS)           — GMS-less Huawei / Honor devices
     *
     * HMS dependency is soft (reflection) so the SDK compiles without HMS on the
     * classpath. Add `implementation 'com.huawei.hms:ads-identifier:3.4.56.300'`
     * to your app's build.gradle to enable OAID collection on Huawei devices.
     */
    suspend fun fetchGaid(): String? = withContext(Dispatchers.IO) {
        if (gaidFetched) return@withContext cachedGaid

        // ── 1. Google GAID ────────────────────────────────────────────────────
        try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            isLATEnabled = info.isLimitAdTrackingEnabled

            if (AffclixSDK.config.respectLAT && isLATEnabled) {
                Logger.d(TAG, "LAT enabled — skipping GAID collection")
            } else {
                val id = info.id
                if (!id.isNullOrBlank() && id != "00000000-0000-0000-0000-000000000000") {
                    cachedGaid = id
                    Logger.d(TAG, "GAID: ${id.take(8)}****")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "GAID unavailable (${e.message}) — trying OAID")
            // ── 2. Huawei OAID fallback (reflection, no hard HMS dependency) ──
            cachedGaid = fetchOaid()
        }

        gaidFetched = true
        return@withContext cachedGaid
    }

    /**
     * Fetch Huawei OAID via HMS reflection.
     * Returns null if HMS is not present or OAID is unavailable.
     */
    private fun fetchOaid(): String? = try {
        val cls  = Class.forName("com.huawei.hms.ads.identifier.AdvertisingIdClient")
        val info = cls.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
        val id   = info!!.javaClass.getMethod("getId").invoke(info) as? String
        if (!id.isNullOrBlank() && id != "00000000-0000-0000-0000-000000000000") {
            Logger.d(TAG, "OAID (Huawei): ${id.take(8)}****")
            id
        } else null
    } catch (_: Exception) { null }

    /**
     * Compute a deterministic device fingerprint for cookieless attribution.
     *
     * Combines non-PII hardware signals into a SHA-256 hash.
     * Sent as `fingerprint` / `device_fp`.
     *
     * Signals used (all non-PII):
     *   OS version, screen resolution, DPI, language, timezone,
     *   hardware model, CPU ABI, total RAM bucket, build fingerprint prefix
     */
    fun getDeviceFingerprint(): String {
        val signals = buildList {
            add(Build.VERSION.RELEASE)
            add(Build.MODEL.replace(" ", "_"))
            add(Build.MANUFACTURER)
            add(Build.CPU_ABI)
            val metrics = getDisplayMetrics()
            add("${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}")
            add(Locale.getDefault().toLanguageTag())
            add(TimeZone.getDefault().id)
            add(Build.FINGERPRINT.take(30))        // first 30 chars, no device-unique suffix
            add(Runtime.getRuntime().availableProcessors().toString())
            // RAM bucket (not exact value — avoids precise hardware identification)
            val ram = getTotalRamMb()
            val ramBucket = when {
                ram < 1500  -> "1G"
                ram < 3500  -> "2-3G"
                ram < 5500  -> "4-5G"
                ram < 9000  -> "6-8G"
                else        -> "12G+"
            }
            add(ramBucket)
        }
        return sha256(signals.joinToString("|"))
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun getDeviceType(): String {
        val metrics = getDisplayMetrics()
        val smallestWidthDp = metrics.widthPixels.coerceAtMost(metrics.heightPixels) / metrics.density
        return when {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)     -> "tv"
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)       -> "tv"
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)     -> "automotive"
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)          -> "watch"
            smallestWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
            }
        } catch (e: Exception) {
            metrics.setToDefaults()
        }
        return metrics
    }

    private fun getCarrierName(): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.networkOperatorName.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun getConnectionType(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "unknown") ?: return "unknown"

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi"

            // Cellular generation
            @Suppress("DEPRECATION")
            when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_NR                             -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE                           -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B                        -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_CDMA                          -> "2G"
                else -> "unknown"
            }
        } catch (e: Exception) { "unknown" }
    }

    private fun getAppName(): String = try {
        val info = context.applicationInfo
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) { "" }

    private fun getAppVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun getAppBuildNumber(): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toString()
        else
            @Suppress("DEPRECATION") pi.versionCode.toString()
    } catch (e: Exception) { "" }

    @SuppressLint("HardwareIds")
    private fun getAndroidId(): String? = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ).takeIf { !it.isNullOrBlank() && it != "9774d56d682e549c" }
    } catch (e: Exception) { null }

    private fun getTotalRamMb(): Long = try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        memInfo.totalMem / (1024 * 1024)
    } catch (e: Exception) { 0L }

    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback: simple hash
            input.hashCode().toString(16)
        }
    }
}
