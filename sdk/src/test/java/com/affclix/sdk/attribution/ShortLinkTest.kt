package com.affclix.sdk.attribution

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortLinkTest {

    @Test
    fun recognisesShortLinks() {
        assertTrue(AttributionManager.isShortLink(Uri.parse("https://acme.affclix.org/l/summer-shoes")))
        assertTrue(AttributionManager.isShortLink(Uri.parse("https://go.brand.com/l/ab3x9kq?utm_source=ig")))
    }

    @Test
    fun aBrandedLinkOrACompleteOneIsNot() {
        assertFalse(AttributionManager.isShortLink(Uri.parse("https://acme.affclix.org/l/com.shoe?deep_link_value=shoes")))
        assertFalse(AttributionManager.isShortLink(Uri.parse("https://acme.affclix.org/l/summer?click_id=CLK")))
        assertFalse(AttributionManager.isShortLink(Uri.parse("https://acme.affclix.org/l/summer?afdl=abc")))
        assertFalse(AttributionManager.isShortLink(Uri.parse("https://acme.affclix.org/promo/summer")))
        assertFalse(AttributionManager.isShortLink(Uri.parse("shoeapp://l/summer-shoes")))
    }

    @Test
    fun storedParamsAreAddedUnderTheUrlsOwn() {
        val uri = Uri.parse("https://acme.affclix.org/l/summer-shoes?utm_source=email")
        val out = AttributionManager.withParams(uri, JSONObject(mapOf(
            "deep_link_value" to "shoes", "campaign_id" to "5", "utm_source" to "insta")))
        assertEquals("shoes", out.getQueryParameter("deep_link_value"))
        assertEquals("5", out.getQueryParameter("campaign_id"))
        assertEquals("email", out.getQueryParameter("utm_source"))
    }
}
