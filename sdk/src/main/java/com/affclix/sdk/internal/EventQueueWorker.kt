package com.affclix.sdk.internal

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that drains [OfflineEventQueue].
 *
 * Runs only when CONNECTED; retries with exponential back-off on failure.
 * Intentionally has no dependency on [com.affclix.sdk.AffclixSDK] — it
 * operates on pre-formed URLs/bodies so it works even when the SDK is not
 * initialized in the host process.
 *
 * Delivery semantics:
 *   - HTTP 2xx → remove from queue (success)
 *   - HTTP 408 / 429 / 5xx / IOException → increment retry counter; prune after MAX_RETRIES
 *   - any other HTTP 4xx → remove from queue (bad request, no point retrying)
 */
internal class EventQueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (drain(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val WORK_NAME = "affclix_event_queue_flush"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        private val JSON_MT = "application/json; charset=utf-8".toMediaType()

        /** The worker and the SDK's start-up drain share one queue; never send a batch twice at once. */
        private val draining = Mutex()

        /**
         * Sends up to 50 queued events. True when nothing is left to retry.
         * Called by the worker and, on start-up, by the SDK itself: Android 14+ may hold a
         * background job back for a long time (flexible scheduling), even with a connection.
         */
        suspend fun drain(context: Context): Boolean = withContext(Dispatchers.IO) {
            draining.withLock {
                val queue = OfflineEventQueue(context)
                queue.pruneExpired()

                val events = queue.peek(50)
                if (events.isEmpty()) return@withLock true

                var hadTransientFailure = false

                for (event in events) {
                    try {
                        val response = client.newCall(buildRequest(event)).execute()
                        val code = response.code
                        response.close()

                        when {
                            code in 200..299 -> {
                                queue.remove(event.id)
                                Logger.d("EventQueueWorker", "Delivered queued event #${event.id}")
                            }
                            // Timeout and rate limit are worth another try; any other 4xx is not.
                            code == 408 || code == 429 || code >= 500 -> {
                                queue.incrementRetry(event.id)
                                hadTransientFailure = true
                                Logger.w("EventQueueWorker", "Queued event #${event.id} got HTTP $code — will retry")
                            }
                            else -> {
                                queue.remove(event.id)
                                Logger.e("EventQueueWorker", "Dropped queued event #${event.id}: HTTP $code")
                            }
                        }
                    } catch (e: Exception) {
                        queue.incrementRetry(event.id)
                        hadTransientFailure = true
                        Logger.w("EventQueueWorker", "Queued event #${event.id} failed: ${e.message} — will retry")
                    }
                }
                !hadTransientFailure
            }
        }

        private fun buildRequest(event: OfflineEventQueue.QueuedEvent): Request {
            val builder = Request.Builder()
                .url(event.url)
                // Same headers as a live request (NetworkManager): the MMP records the User-Agent.
                .header("User-Agent", "AffclixSDK/${com.affclix.sdk.AffclixSDK.SDK_VERSION} (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE})")
                .header("X-SDK-Version", com.affclix.sdk.AffclixSDK.SDK_VERSION)
                .header("X-SDK-Platform", "android")
                .header("X-Queue-Retry", event.retries.toString())

            return if (event.method == "POST" && event.body != null) {
                builder.post(event.body.toRequestBody(JSON_MT)).build()
            } else {
                builder.get().build()
            }
        }

        /** [policy] KEEP leaves an already scheduled or running drain alone. */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<EventQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
