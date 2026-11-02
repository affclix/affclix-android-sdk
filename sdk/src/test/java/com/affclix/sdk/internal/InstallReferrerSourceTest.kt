package com.affclix.sdk.internal

import androidx.test.core.app.ApplicationProvider
import com.affclix.sdk.attribution.InstallReferrerManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallReferrerSourceTest {
    private fun source(vararg params: Pair<String, String>): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val map = mapOf(*params)
        return InstallReferrerManager(context, StorageManager(context)).deriveMediaSource(
            map, map["gclid"], map["gbraid"], map["wbraid"], map["utm_source"], map["utm_medium"]
        )
    }

    @Test fun playsOwnValueIsOrganic() {
        assertEquals("organic", source("utm_source" to "google-play", "utm_medium" to "organic"))
        assertEquals("organic", source("utm_source" to "(not set)", "utm_medium" to "(not set)"))
    }

    @Test fun anEmptyReferrerIsOrganic() = assertEquals("organic", source())

    @Test fun aClickOutranksTheOrganicMedium() {
        assertEquals("google", source("utm_source" to "google-play", "utm_medium" to "organic", "gclid" to "x"))
        assertEquals("partner", source("utm_source" to "partner", "utm_medium" to "organic", "click_id" to "c1"))
    }

    @Test fun aCampaignKeepsItsSource() {
        assertEquals("google_ads", source("utm_source" to "google", "utm_medium" to "cpc"))
        assertEquals("facebook_ads", source("utm_source" to "facebook", "utm_medium" to "cpc"))
    }
}
