package com.affclix.sdk.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // newest API level Robolectric 4.13 emulates
class ConsentManagerTest {

    private lateinit var context: Context
    private lateinit var storage: StorageManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = StorageManager(context)
    }

    private fun cmp(applies: Int, purposes: String, tcString: String = "CPtcf") =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE).edit()
            .putInt("IABTCF_gdprApplies", applies)
            .putString("IABTCF_PurposeConsents", purposes)
            .putString("IABTCF_TCString", tcString)
            .commit()

    @Test fun tcfMapsPurposesToGoogleConsentMode() {
        // Purposes 1, 3, 4 consented; 7 refused → no ad_user_data, yes personalization.
        cmp(applies = 1, purposes = "1011000000")
        val p = ConsentManager(context, storage, readTcf = true).payload()
        assertEquals(1, p["gdpr_applies"])
        assertEquals(0, p["ad_user_data"])
        assertEquals(1, p["ad_personalization"])
        assertEquals("CPtcf", p["tcf_string"])
    }

    @Test fun explicitConsentWinsOverTcf() {
        cmp(applies = 1, purposes = "1111111111")
        storage.saveConsentData(ConsentData(gdprApplies = true, adUserData = false, adPersonalization = null))
        val p = ConsentManager(context, storage, readTcf = true).payload()
        assertEquals(0, p["ad_user_data"])
        assertEquals(1, p["ad_personalization"]) // unset explicitly → from TCF
    }

    @Test fun outsideGdprSendsNoFlags() {
        cmp(applies = 0, purposes = "0000000000")
        val p = ConsentManager(context, storage, readTcf = true).payload()
        assertEquals(0, p["gdpr_applies"])
        assertNull(p["ad_user_data"])
        assertNull(p["tcf_string"])
    }

    @Test fun tcfIgnoredUnlessEnabled() {
        cmp(applies = 1, purposes = "0000000000")
        val p = ConsentManager(context, storage, readTcf = false).payload()
        assertNull(p["gdpr_applies"])
        assertNull(p["ad_user_data"])
    }
}
