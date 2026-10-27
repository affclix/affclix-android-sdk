package com.affclix.sdk.internal

import com.affclix.sdk.AffclixConfig
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * HTTP client for the Affclix SDK.
 *
 * - Persistent connection pool (reused across all SDK requests)
 * - Exponential back-off + jitter retry (matching web SDK policy)
 * - GET for /m/c, /m/i        (query-param endpoints)
 * - POST JSON for /m/ev        (event endpoint; supports both GET and POST)
 * - Fire-and-forget pixel for impression view pixels
 * - Debug logging via OkHttp interceptor (stripped in release by ProGuard)
 */
internal class NetworkManager(private val config: AffclixConfig) {

    private val TAG  = "NetworkManager"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = buildClient()

    // ── GET ───────────────────────────────────────────────────────────────────

    suspend fun get(url: String): JSONObject = retry(config.maxRetries) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        Logger.d(TAG, "GET $url")
        execute(request)
    }

    // ── POST (JSON body) ──────────────────────────────────────────────────────

    /**
     * POST to [url] with [payload] serialised as a JSON body.
     * Used for /m/ev — the event endpoint accepts both GET params and JSON body.
     * Prefer POST: avoids URL-length limits when payloads carry many custom params.
     */
    suspend fun post(url: String, payload: Map<String, Any?>): JSONObject = retry(config.maxRetries) {
        val json = serialisePayload(payload)
        val body = json.toRequestBody(JSON)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        Logger.d(TAG, "POST $url body=${json.take(300)}")
        execute(request)
    }

    /**
     * POST a body that was already serialised (and signed): the bytes sent must be
     * exactly the ones the signature covers.
     */
    suspend fun postJson(url: String, json: String): JSONObject = retry(config.maxRetries) {
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
            .build()

        Logger.d(TAG, "POST $url body=${json.take(300)}")
        execute(request)
    }

    // ── Pixel (fire-and-forget) ───────────────────────────────────────────────

    fun pixel(url: String) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Logger.w(TAG, "Pixel failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    Logger.d(TAG, "Pixel fired: $url")
                }
            })
        } catch (e: Exception) {
            Logger.e(TAG, "Pixel error: ${e.message}")
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    private suspend fun <T> retry(maxRetries: Int, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= maxRetries) throw e
                val backoff = computeBackoff(attempt)
                Logger.w(TAG, "Retry ${attempt + 1}/$maxRetries in ${backoff}ms — ${e.message}")
                delay(backoff)
                attempt++
            }
        }
    }

    /**
     * Exponential back-off with full jitter:
     *   delay = min(base * 2^attempt + jitter(0..500ms), maxDelay)
     */
    private fun computeBackoff(attempt: Int): Long {
        val base   = config.retryBaseDelayMs * 2.0.pow(attempt.toDouble()).toLong()
        val jitter = Random.nextLong(500)
        return min(base + jitter, config.retryMaxDelayMs)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun execute(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw NetworkException("HTTP $code", code)
        }
        val body = response.body?.string() ?: "{}"
        Logger.d(TAG, "Response: ${body.take(300)}")
        return parseJsonSafe(body)
    }

    private fun parseJsonSafe(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (_: Exception) {
        Logger.w(TAG, "JSON parse failed — returning {}")
        JSONObject()
    }

    private fun serialisePayload(payload: Map<String, Any?>): String {
        val obj = JSONObject()
        payload.forEach { (k, v) ->
            if (v != null) obj.put(k, v)
        }
        return obj.toString()
    }

    private fun buildUserAgent(): String {
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val os     = "Android ${android.os.Build.VERSION.RELEASE}"
        return "AffclixSDK/${com.affclix.sdk.AffclixSDK.SDK_VERSION} ($device; $os)"
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", buildUserAgent())
                    .header("X-SDK-Version", com.affclix.sdk.AffclixSDK.SDK_VERSION)
                    .header("X-SDK-Platform", "android")
                    .build()
                chain.proceed(req)
            }

        if (config.debug) {
            val logging = HttpLoggingInterceptor { msg -> Logger.d(TAG, msg) }
            logging.level = HttpLoggingInterceptor.Level.BASIC
            builder.addInterceptor(logging)
        }

        return builder.build()
    }
}
