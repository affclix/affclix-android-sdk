package com.affclix.sample

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affclix.sdk.AffclixSDK
import org.junit.*
import org.junit.runner.RunWith

/**
 * Sample app UI tests — use ONLY public SDK API.
 * Internal SDK tests live in sdk/src/androidTest/ (same module).
 *
 * Run: ./gradlew :sampleapp:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedSdkTest {

    @Test fun main_activity_launches_without_crash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Assert.assertNotNull(activity)
            }
        }
    }

    @Test fun sdk_is_initialised_after_activity_launch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1_000)
            Assert.assertTrue("SDK must be initialised after MainActivity.onCreate()",
                AffclixSDK.isInitialized())
        }
    }

    @Test fun visitor_id_is_created_on_first_launch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1_000)
            val visitorId = AffclixSDK.getVisitorId()
            Assert.assertNotNull(visitorId)
            Assert.assertTrue("Visitor ID must be non-blank", visitorId.isNotBlank())
        }
    }

    @Test fun session_id_is_created_after_launch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            val sessionId = AffclixSDK.getSessionId()
            Assert.assertNotNull(sessionId)
            Assert.assertEquals("Session ID must be 16 chars", 16, sessionId.length)
        }
    }

    @Test fun all_test_buttons_are_displayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            listOf(R.id.btn_click, R.id.btn_impression, R.id.btn_conversion,
                   R.id.btn_vta, R.id.btn_fraud, R.id.btn_run_all)
                .forEach { id ->
                    onView(withId(id)).check(matches(isDisplayed()))
                }
        }
    }

    @Test fun run_all_tests_button_is_enabled() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            onView(withId(R.id.btn_run_all)).check(matches(isEnabled()))
        }
    }

    @Test fun log_view_is_visible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tv_log)).check(matches(isDisplayed()))
        }
    }

    @Test fun tapping_fraud_button_does_not_crash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            onView(withId(R.id.btn_fraud)).perform(click())
            Thread.sleep(500) // Let fraud detection complete
            // If we reach here, no crash occurred
        }
    }

    @Test fun tapping_deep_link_button_does_not_crash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            onView(withId(R.id.btn_deep_link)).perform(click())
            Thread.sleep(300)
        }
    }

    @Test fun deep_link_intent_opens_activity_without_crash() {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("yourapp://open?click_id=ui_test_dl&campaign_id=c_ui"))
        intent.setPackage("com.affclix.sample.debug")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(500)
            scenario.onActivity { Assert.assertNotNull(it) }
        }
    }

    @Test fun app_version_is_not_blank() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            val device = AffclixSDK.getDeviceInfo()
            Assert.assertFalse("app_package must not be blank", device.appPackage.isBlank())
        }
    }
}
