package com.affclix.sdk.internal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DurableDeliveryTest {
    private val saved = mutableListOf<Long>()
    private var sentWhileSaved = false

    private fun deliver(send: suspend () -> Unit) = runBlocking {
        deliverDurably(
            save = { saved.add(7L); 7L },
            remove = { saved.remove(it) },
            isTransient = { it is IOException },
            send = { sentWhileSaved = saved.isNotEmpty(); send() },
        )
    }

    @Test fun `saved before the first attempt, so a killed process loses nothing`() {
        deliver { }
        assertTrue(sentWhileSaved)
    }

    @Test fun `removed once the server has answered`() {
        deliver { }
        assertTrue(saved.isEmpty())
    }

    @Test fun `a transient failure stays saved for the worker`() {
        try {
            deliver { throw IOException("offline") }
            fail("rethrown for the caller")
        } catch (e: IOException) {
            assertEquals(listOf(7L), saved)
        }
    }

    @Test fun `a refusal is not kept`() {
        try {
            deliver { throw IllegalStateException("INVALID_SIGNATURE") }
            fail("rethrown for the caller")
        } catch (e: IllegalStateException) {
            assertFalse(saved.contains(7L))
        }
    }

    @Test fun `still sends when the save failed`() = runBlocking {
        var sent = false
        val id = deliverDurably(save = { null }, remove = {}, isTransient = { true }, send = { sent = true })
        assertTrue(sent)
        assertEquals(null, id)
    }
}
