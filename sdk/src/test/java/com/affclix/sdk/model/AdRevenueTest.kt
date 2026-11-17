package com.affclix.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdRevenueTest {

    @Test
    fun `parameters are the ones the MMP reads`() {
        val p = AdRevenue(
            mediation = "applovin_max", revenue = 0.0123, network = "AdMob", adUnitId = "u1",
            adUnitName = "Level end", adFormat = "INTER", placement = "level_end", precision = "exact",
        ).toParams()
        assertEquals(
            mapOf(
                "revenue" to 0.0123, "currency" to "USD", "ad_mediation" to "applovin_max", "ad_network" to "AdMob",
                "ad_unit_id" to "u1", "ad_unit_name" to "Level end", "ad_format" to "INTER",
                "ad_placement" to "level_end", "revenue_precision" to "exact",
            ),
            p,
        )
    }

    @Test
    fun `unknown fields are left out`() {
        val p = AdRevenue(mediation = "admob", revenue = 0.5, currency = "EUR").toParams()
        assertEquals(setOf("revenue", "currency", "ad_mediation"), p.keys)
        assertFalse(p.containsKey("ad_impressions"))
        assertEquals(3, AdRevenue("levelplay", 1.0, impressions = 3).toParams()["ad_impressions"])
    }
}
