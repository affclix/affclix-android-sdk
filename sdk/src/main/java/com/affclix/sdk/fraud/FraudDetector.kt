package com.affclix.sdk.fraud

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.affclix.sdk.internal.Logger
import com.affclix.sdk.internal.StorageManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface

/**
 * Client-side fraud signals. Produces [FraudAssessment] with a score 0–100
 * and typed signal tags, sent with each request:
 *   fraud_score    0–100
 *   fraud_reasons  JSON array of signal tags
 *   is_proxy, is_emulator, is_rooted, is_farm   0 / 1
 *
 * Call only from a background coroutine — takes ~30–80 ms on a real device.
 */
internal class FraudDetector(
    private val context: Context,
    private val storage: StorageManager
) {
    companion object {
        private const val TAG                      = "FraudDetector"
        private const val BOT_THRESHOLD            = 70
        private const val VELOCITY_THRESHOLD_PER_DAY = 5
        private const val RAPID_SESSION_MS         = 2_000L
    }

    // A rapid restart is this launch coming within RAPID_SESSION_MS of the previous
    // launch's session. Both are read when the SDK starts, before this launch records
    // its own session start: comparing "now" with the stored value instead matched the
    // current session, so an install sent after the first activity started (deep link
    // wait, slow Play referrer) was flagged a device farm.
    private val launchedAt = System.currentTimeMillis()
    private val previousSessionStart = storage.getLastSessionStartTime()

    // ── Public entry point ────────────────────────────────────────────────────

    fun assess(): FraudAssessment {
        val signals = mutableListOf<FraudSignal>()
        signals += detectEmulator()
        signals += detectRoot()
        signals += detectAppCloner()
        signals += detectAutomation()
        signals += detectNetworkAnomalies()
        signals += detectVelocityAbuse()

        val score = computeScore(signals)
        return FraudAssessment(
            score      = score,
            signals    = signals,
            reasons    = signals.map { it.tag },
            isBot      = score >= BOT_THRESHOLD,
            isProxy    = signals.any { it.tag in setOf("vpn_detected", "proxy_suspected") },
            isEmulator = signals.any { it.category == SignalCategory.EMULATOR },
            isRooted   = signals.any { it.category == SignalCategory.ROOT },
            isFarm     = signals.any { it.category == SignalCategory.VELOCITY ||
                                      it.category == SignalCategory.CLONER }
        )
    }

    /** CTIT check — call after install referrer is resolved. */
    fun assessCtit(clickTs: Long?, installTs: Long?): CtitAssessment {
        if (clickTs == null || installTs == null)
            return CtitAssessment(null, CtitCategory.UNKNOWN, 0)

        val ctit = installTs - clickTs
        val (cat, score) = when {
            ctit < 0       -> CtitCategory.NEGATIVE_CTIT  to 95
            ctit < 10      -> CtitCategory.HIJACKING       to 95
            ctit < 60      -> CtitCategory.DEVICE_FARM     to 80
            ctit < 300     -> CtitCategory.SUSPICIOUS      to 40
            ctit < 86_400  -> CtitCategory.NORMAL          to 0
            ctit < 604_800 -> CtitCategory.LONG            to 5
            ctit < 2_592_000 -> CtitCategory.VERY_LONG     to 15
            else           -> CtitCategory.CLICK_FLOODING  to 70
        }
        Logger.d(TAG, "CTIT ${ctit}s → $cat (score $score)")
        return CtitAssessment(ctit, cat, score)
    }

    // ── 1. Emulator Detection ─────────────────────────────────────────────────

    private fun detectEmulator(): List<FraudSignal> = buildList {
        val fp = Build.FINGERPRINT.lowercase()
        if (fp.contains("generic") || fp.contains("unknown") ||
            fp.contains("sdk_gphone") || fp.contains("emulator") ||
            fp.contains("android_x86") || fp.contains("robolectric")) {
            add(FraudSignal(SignalCategory.EMULATOR, "emulator_build_fp", 90))
        }

        val hw = Build.HARDWARE.lowercase()
        if (hw == "goldfish" || hw == "ranchu" || hw.contains("vbox") ||
            hw.contains("genymotion") || hw.contains("nox")) {
            add(FraudSignal(SignalCategory.EMULATOR, "emulator_hardware", 90))
        }

        val model   = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val emulatorNames = listOf("sdk", "android sdk", "google_sdk", "emulator",
            "android_x86", "bluestacks", "noxplayer", "ldplayer", "memu", "phoenix")
        if (emulatorNames.any { model.contains(it) || product.contains(it) }) {
            add(FraudSignal(SignalCategory.EMULATOR, "emulator_model", 85))
        }

        val genymotionFiles = listOf("/dev/socket/genyd", "/dev/socket/baseband_genyd")
        if (genymotionFiles.any { File(it).exists() }) {
            add(FraudSignal(SignalCategory.EMULATOR, "genymotion_detected", 95))
        }

        val farmFiles = listOf(
            "/data/data/com.bluestacks",
            "/sdcard/windows/BstSharedFolder",
            "/data/app/com.ldu.ldplayerlauncher")
        if (farmFiles.any { File(it).exists() }) {
            add(FraudSignal(SignalCategory.EMULATOR, "device_farm_file", 95))
        }

        // Sensor absence — real phones always have accelerometer + gyroscope
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        if (sm.getSensorList(android.hardware.Sensor.TYPE_ALL).isEmpty()) {
            add(FraudSignal(SignalCategory.EMULATOR, "no_sensors", 70))
        }

        // Battery = null on AVD / most emulators
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        if (bm != null) {
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level == -1) add(FraudSignal(SignalCategory.EMULATOR, "no_battery", 65))
        }
    }

    // ── 2. Root Detection ─────────────────────────────────────────────────────

    private fun detectRoot(): List<FraudSignal> = buildList {
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            add(FraudSignal(SignalCategory.ROOT, "test_keys_build", 70))
        }

        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/bin/.ext/.su")
        if (suPaths.any { File(it).exists() }) {
            add(FraudSignal(SignalCategory.ROOT, "su_binary", 85))
        }

        if (File("/system/xbin/busybox").exists() || File("/system/bin/busybox").exists()) {
            add(FraudSignal(SignalCategory.ROOT, "busybox_present", 60))
        }

        val rootPkgs = listOf(
            "com.topjohnwu.magisk", "com.kingroot.kinguser",
            "com.koushikdutta.superuser", "eu.chainfire.supersu",
            "com.noshufou.android.su", "com.zachspong.temprootremovejb",
            "com.yellowes.su")
        rootPkgs.forEach { pkg ->
            if (isPackageInstalled(pkg)) {
                add(FraudSignal(SignalCategory.ROOT, "root_package", 90, pkg))
            }
        }

        if (File("/system/app/Superuser.apk").exists()) {
            add(FraudSignal(SignalCategory.ROOT, "superuser_apk", 90))
        }

        if (canExecuteSu()) {
            add(FraudSignal(SignalCategory.ROOT, "su_executable", 95))
        }
    }

    // ── 3. App Cloner / Parallel Space ────────────────────────────────────────

    private fun detectAppCloner(): List<FraudSignal> = buildList {
        val clonerPkgs = listOf(
            "com.lbe.parallel.intl", "com.excelliance.dualaid",
            "com.parallel.space.lite", "com.two.accounts",
            "me.weishu.exp", "de.robv.android.xposed.installer",
            "com.saurik.substrate", "org.meowcat.edxposed.manager",
            "io.github.lsposed.manager", "com.dimonvideo.luckypatcher")

        clonerPkgs.forEach { pkg ->
            if (isPackageInstalled(pkg)) {
                add(FraudSignal(SignalCategory.CLONER, "app_cloner_installed", 85, pkg))
            }
        }

        // Cloned app runs under a secondary user (UID ≥ 100000)
        val userId = android.os.Process.myUid() / 100_000
        if (userId > 0) {
            add(FraudSignal(SignalCategory.CLONER, "cloned_uid_space", 90, "userId=$userId"))
        }
    }

    // ── 4. Automation / Debugging ─────────────────────────────────────────────

    private fun detectAutomation(): List<FraudSignal> = buildList {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            add(FraudSignal(SignalCategory.AUTOMATION, "debugger_connected", 70))
        }

        try {
            val statusText = File("/proc/self/status").readText()
            val tracerLine = statusText.lines().firstOrNull { it.startsWith("TracerPid") }
            val tracerPid  = tracerLine?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (tracerPid != 0) {
                add(FraudSignal(SignalCategory.AUTOMATION, "ptrace_active", 80, "pid=$tracerPid"))
            }
        } catch (_: Exception) {}

        val adbEnabled = Settings.Global.getInt(context.contentResolver,
            Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled == 1) {
            add(FraudSignal(SignalCategory.AUTOMATION, "adb_enabled", 30))
        }

        // Accessibility service abuse (Appium, UI Automator)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isEnabled && am.isTouchExplorationEnabled) {
            // Touch exploration ON + ADB = very likely automation
            if (adbEnabled == 1) {
                add(FraudSignal(SignalCategory.AUTOMATION, "accessibility_adb_combo", 65))
            }
        }
    }

    // ── 5. Network Anomalies ──────────────────────────────────────────────────

    private fun detectNetworkAnomalies(): List<FraudSignal> = buildList {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return@buildList
            for (ni in ifaces) {
                val name = ni.name.lowercase()
                // tun/tap/ppp = VPN interfaces
                if (name.startsWith("tun") || name.startsWith("tap") ||
                    name.startsWith("ppp")) {
                    add(FraudSignal(SignalCategory.NETWORK, "vpn_detected", 60, "iface=$name"))
                }
            }
        } catch (_: Exception) {}

        // WiFi on cellular IP = proxy / split-tunnel
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@buildList)
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi     = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (isCellular && isWifi) {
            add(FraudSignal(SignalCategory.NETWORK, "vpn_split_tunnel", 55))
        }
    }

    // ── 6. Install Velocity ───────────────────────────────────────────────────

    private fun detectVelocityAbuse(): List<FraudSignal> = buildList {
        val dayKey = "affclix_install_count_${System.currentTimeMillis() / 86_400_000L}"
        val count  = storage.incrementDailyCounter(dayKey)
        if (count > VELOCITY_THRESHOLD_PER_DAY) {
            add(FraudSignal(SignalCategory.VELOCITY, "high_install_velocity", 85, "count=$count"))
        }

        val gap = launchedAt - previousSessionStart
        if (previousSessionStart > 0 && gap in 0 until RAPID_SESSION_MS) {
            add(FraudSignal(SignalCategory.VELOCITY, "rapid_session_restart", 60, "gap=${gap}ms"))
        }
    }

    // ── Score computation ─────────────────────────────────────────────────────

    /**
     * Highest single signal is the floor; secondary signals add diminishing returns.
     * A single 95-weight signal scores 95. Two 70-weight signals score 70 + 17 = 87.
     */
    private fun computeScore(signals: List<FraudSignal>): Int {
        if (signals.isEmpty()) return 0
        val sorted     = signals.sortedByDescending { it.weight }
        val primary    = sorted.first().weight
        val secondary  = sorted.drop(1).sumOf { it.weight / 4 }
        return minOf(primary + secondary, 100)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun canExecuteSu(): Boolean = try {
        val p   = Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "-c", "id"))
        p.waitFor()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: ""
        p.destroy()
        out.contains("uid=0")
    } catch (_: Exception) { false }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

data class FraudAssessment(
    val score:      Int,                        // → fraud_score UInt8
    val signals:    List<FraudSignal>,
    val reasons:    List<String>,               // → fraud_reasons Array(String)
    val isBot:      Boolean,
    val isProxy:    Boolean,                    // → is_proxy UInt8
    val isEmulator: Boolean,                    // → is_emulator UInt8
    val isRooted:   Boolean,                    // → is_rooted UInt8
    val isFarm:     Boolean                     // → is_farm UInt8
) {
    fun toPayloadMap(): Map<String, Any> = mapOf(
        "fraud_score"    to score,
        "is_proxy"       to if (isProxy)    1 else 0,
        "is_emulator"    to if (isEmulator) 1 else 0,
        "is_rooted"      to if (isRooted)   1 else 0,
        "is_farm"        to if (isFarm)     1 else 0,
        // JSON array string.
        "fraud_reasons"  to org.json.JSONArray(reasons).toString()
    )

    companion object {
        /** Zero-signal assessment used when fraud detection is unavailable (e.g. offline queue retry). */
        fun empty() = FraudAssessment(
            score = 0, signals = emptyList(), reasons = emptyList(),
            isBot = false, isProxy = false, isEmulator = false,
            isRooted = false, isFarm = false
        )
    }
}

data class FraudSignal(
    val category: SignalCategory,
    val tag:      String,
    val weight:   Int,
    val detail:   String = ""
)

data class CtitAssessment(
    val ctitSeconds: Long?,
    val category:    CtitCategory,
    val fraudScore:  Int
)

enum class SignalCategory { EMULATOR, ROOT, CLONER, AUTOMATION, NETWORK, VELOCITY }

enum class CtitCategory {
    UNKNOWN, NEGATIVE_CTIT, HIJACKING, DEVICE_FARM, SUSPICIOUS,
    NORMAL, LONG, VERY_LONG, CLICK_FLOODING
}
