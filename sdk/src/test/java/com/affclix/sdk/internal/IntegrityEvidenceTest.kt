package com.affclix.sdk.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityEvidenceTest {

    @Test
    fun `the token is bound to the MMP's challenge`() = runTest {
        var hashed: String? = null
        val evidence = IntegrityEvidence(
            source = { hash -> hashed = hash; "TOKEN" },
            fetchChallenge = { "CHAL" },
            timeoutMs = 1_000,
        )
        assertEquals(mapOf("integrity_challenge" to "CHAL", "integrity_token" to "TOKEN"), evidence.collect())
        assertEquals("CHAL", hashed)
    }

    @Test
    fun `no challenge means no evidence, not a failed install`() = runTest {
        val evidence = IntegrityEvidence({ "TOKEN" }, { throw java.io.IOException("offline") }, 1_000)
        assertTrue(evidence.collect().isEmpty())
    }

    @Test
    fun `a Play error or a missing library is no evidence`() = runTest {
        val playError = IntegrityEvidence({ throw IllegalStateException("API_NOT_AVAILABLE") }, { "CHAL" }, 1_000)
        assertTrue(playError.collect().isEmpty())
        val linkage = IntegrityEvidence({ throw NoClassDefFoundError("IntegrityManagerFactory") }, { "CHAL" }, 1_000)
        assertTrue(linkage.collect().isEmpty())
    }

    @Test
    fun `a slow token does not hold the install up`() = runTest {
        val slow = IntegrityEvidence({ delay(60_000); "TOKEN" }, { "CHAL" }, 5_000)
        assertTrue(slow.collect().isEmpty())
    }
}
