package com.affclix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [block]'s result, or null when it has not answered within [timeoutMs] — even when
 * it is stuck in a blocking call (an OkHttp execute), which withTimeoutOrNull alone
 * cannot interrupt: the block runs in [scope] and is only awaited here. A late
 * result is dropped; the block is cancelled so its retries stop.
 */
internal suspend fun <T : Any> awaitWithin(scope: CoroutineScope, timeoutMs: Long, block: suspend () -> T?): T? {
    val job = scope.async { block() }
    val result = withTimeoutOrNull(timeoutMs) { job.await() }
    if (result == null && job.isActive) job.cancel()
    return result
}
