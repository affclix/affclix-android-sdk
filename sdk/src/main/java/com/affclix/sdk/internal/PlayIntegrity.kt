package com.affclix.sdk.internal

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mints a Play Integrity token whose request hash is [requestHash]. */
internal fun interface IntegrityTokenSource {
    suspend fun token(requestHash: String): String
}

/**
 * Google Play Integrity, standard requests: the token provider is prepared once
 * (Google's warm-up, which can take seconds) and then mints a token per request,
 * bound to the MMP's challenge through its request hash.
 *
 * The Play Integrity library is a compileOnly dependency: an app that wants
 * integrity verdicts adds `com.google.android.play:integrity` itself, and
 * [isAvailable] keeps the SDK from touching it otherwise.
 */
internal class PlayIntegritySource(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : IntegrityTokenSource {

    companion object {
        /** True when the app ships the Play Integrity library. */
        fun isAvailable(): Boolean = try {
            Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val lock = Mutex()
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    /** Prepares the token provider (once; again after it is invalidated). */
    suspend fun prepare(): StandardIntegrityManager.StandardIntegrityTokenProvider = lock.withLock {
        provider ?: IntegrityManagerFactory.createStandard(context)
            .prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
            ).await().also { provider = it }
    }

    override suspend fun token(requestHash: String): String {
        return try {
            request(prepare(), requestHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A provider kept too long is invalidated by Play: prepare a new one, once.
            provider = null
            request(prepare(), requestHash)
        }
    }

    private suspend fun request(p: StandardIntegrityManager.StandardIntegrityTokenProvider, hash: String): String =
        p.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(hash).build()
        ).await().token()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * The integrity evidence an install carries: a challenge from the MMP and a token
 * bound to it. Best effort under a hard deadline — no token, a slow Google or an
 * unreachable MMP send the install without evidence rather than hold it up.
 */
internal class IntegrityEvidence(
    private val source: IntegrityTokenSource,
    private val fetchChallenge: suspend () -> String,
    private val timeoutMs: Long,
) {
    suspend fun collect(): Map<String, String> = withTimeoutOrNull(timeoutMs) {
        try {
            val challenge = fetchChallenge()
            mapOf("integrity_challenge" to challenge, "integrity_token" to source.token(challenge))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: a missing or mismatched Play library is a LinkageError.
            Logger.w("AffclixSDK", "Play Integrity unavailable: ${e.message}")
            emptyMap()
        }
    } ?: emptyMap<String, String>().also { Logger.w("AffclixSDK", "Play Integrity: no token within ${timeoutMs}ms") }
}
