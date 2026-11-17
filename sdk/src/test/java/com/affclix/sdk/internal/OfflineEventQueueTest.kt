package com.affclix.sdk.internal

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineEventQueueTest {
    private val queue = OfflineEventQueue(ApplicationProvider.getApplicationContext())

    private fun urls() = queue.peek().map { it.url }

    @Test fun removeEventsDropsEventsOnTheDefaultRoute() {
        queue.enqueue("https://t.example.com/m/ev?ak=k", "{}", "POST")
        queue.enqueue("https://t.example.com/m/forget?ak=k", "{}", "POST")

        queue.removeEvents("/m/ev")

        assertEquals(listOf("https://t.example.com/m/forget?ak=k"), urls())
    }

    @Test fun removeEventsDropsEventsOnTheDedicatedServerRoute() {
        queue.enqueue("https://t.example.com/ev?ak=k", "{}", "POST")
        queue.enqueue("https://t.example.com/token?next=/ev", "{}", "POST")

        queue.removeEvents("/ev")

        assertEquals(listOf("https://t.example.com/token?next=/ev"), urls())
    }
}
