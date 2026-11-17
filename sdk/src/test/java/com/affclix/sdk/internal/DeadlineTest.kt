package com.affclix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun aBlockingCallPastTheDeadlineIsNotWaitedFor() = runBlocking {
        val started = System.currentTimeMillis()
        // Thread.sleep, like OkHttp's execute, ignores coroutine cancellation.
        val result = awaitWithin(scope, 200) { Thread.sleep(3_000); "late" }
        val waited = System.currentTimeMillis() - started
        assertNull(result)
        assertTrue("waited $waited ms", waited < 1_500)
    }

    @Test
    fun anAnswerInTimeIsReturned() = runBlocking {
        assertEquals("ok", awaitWithin(scope, 2_000) { Thread.sleep(50); "ok" })
    }

    @Test
    fun noAnswerIsNull() = runBlocking {
        assertNull(awaitWithin<String>(scope, 2_000) { null })
    }
}
