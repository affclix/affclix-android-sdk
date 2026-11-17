package com.affclix.sample

import android.app.Application
import android.util.Log
import com.affclix.sdk.AffclixSDK
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SampleApp : Application() {

    companion object {
        lateinit var instance: SampleApp private set
        val requestLog = mutableListOf<RequestEntry>()
        val requestListeners = mutableListOf<() -> Unit>()
        fun notifyRequestReceived() = requestListeners.forEach { it() }
    }

    lateinit var mockServer: MockWebServer private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Log which environment is active
        AppConfig.logEnv()

        if (AppConfig.isDev) {
            // DEV: Start MockWebServer on background thread (required — Android 16 blocks
            // network operations on main thread including DNS lookup)
            val latch = CountDownLatch(1)
            Thread {
                try {
                    mockServer = MockWebServer()
                    mockServer.dispatcher = AffclixDispatcher()
                    mockServer.start(8080)   // fixed port 8080 matches devConfig
                    Log.d("SampleApp", "MockWebServer → http://localhost:8080")
                } catch (e: Exception) {
                    Log.e("SampleApp", "MockWebServer failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }.start()
            latch.await(3, TimeUnit.SECONDS)
        }

        // Init SDK with active config (DEV or PROD — controlled by AppConfig.ENV)
        AffclixSDK.init(application = this, config = AppConfig.active)

        Log.d("SampleApp", "SDK ready | visitor=${AffclixSDK.getVisitorId()}")
    }

    override fun onTerminate() {
        super.onTerminate()
        if (AppConfig.isDev) {
            try { mockServer.shutdown() } catch (_: Exception) {}
        }
    }

    // ── MockWebServer dispatcher — realistic responses for all endpoints ──────

    inner class AffclixDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path    = request.path ?: "/"
            val decoded = try { URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }
            val params  = parseParams(decoded)

            // SDK routes all mobile traffic through /m/* (mobileSDKMode middleware → ep=WS → JSON)
            val endpoint = when {
                path.contains("/m/c")  || path.startsWith("/c")  -> "CLICK"
                path.contains("/m/i")  || path.startsWith("/i")  -> "IMPRESSION"
                path.contains("/m/p")  || path.startsWith("/p")  -> "CONVERSION"
                path.contains("/m/ev") || path.startsWith("/ev") -> "EVENT"
                else                                             -> "OTHER"
            }

            // click_expiry in seconds (backend returns 3600 = 1 hour); SDK converts to days
            val body = when (endpoint) {
                "CLICK"      -> """{"status":true,"click_id":"clk_${randomId()}","campaign_id":1,"click_expiry":86400}"""
                "IMPRESSION" -> """{"iid":"imp_${randomId()}","status":"ok","impression_expiry":86400}"""
                "CONVERSION" -> """{"success":true,"message":"ok"}"""
                "EVENT"      -> """{"success":true,"status":"ok"}"""
                else         -> """{"status":"ok"}"""
            }

            val entry = RequestEntry(
                endpoint    = endpoint,
                method      = request.method ?: "GET",
                path        = decoded.take(300),
                params      = params,
                response    = body,
                timestampMs = System.currentTimeMillis()
            )

            synchronized(requestLog) {
                requestLog.add(0, entry)
                if (requestLog.size > 200) requestLog.removeAt(requestLog.size - 1)
            }
            notifyRequestReceived()

            return MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .addHeader("Content-Type", "application/json")
        }

        private fun parseParams(path: String): Map<String, String> {
            val qs = path.substringAfter("?", "")
            if (qs.isEmpty()) return emptyMap()
            return qs.split("&").associate { kv ->
                val idx = kv.indexOf("=")
                if (idx >= 0) kv.substring(0, idx) to kv.substring(idx + 1)
                else kv to ""
            }
        }

        private fun randomId() = (100000..999999).random().toString()
    }
}

// ── RequestEntry data class ───────────────────────────────────────────────────

data class RequestEntry(
    val endpoint:    String,
    val method:      String,
    val path:        String,
    val params:      Map<String, String>,
    val response:    String,
    val timestampMs: Long
) {
    val formattedTime: String get() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return "%02d:%02d:%02d.%03d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND)
        )
    }

    val statusColor: Int get() = when (endpoint) {
        "CLICK"      -> 0xFF4CAF50.toInt()
        "IMPRESSION" -> 0xFF2196F3.toInt()
        "CONVERSION" -> 0xFFF44336.toInt()
        "EVENT"      -> 0xFFFF9800.toInt()
        else         -> 0xFF9E9E9E.toInt()
    }
}