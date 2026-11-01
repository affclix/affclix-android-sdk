# Affclix Android SDK

[![CI](https://github.com/affclix/affclix-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/affclix/affclix-android-sdk/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/affclix/affclix-android-sdk.svg)](https://jitpack.io/#affclix/affclix-android-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Install, deep-link and in-app event attribution for Android apps, for
[Affclix](https://affclix.com) — the affiliate and mobile attribution platform.

- Install attribution from the Google Play Install Referrer, App Links / deep-link `click_id`, and the advertising ID
- Deferred deep links, re-engagement and retargeting
- In-app events, purchases (validated Play purchases), ad revenue
- Events and installs saved (encrypted) before the first send and delivered by WorkManager, with retry and back-off
- Request signing (HMAC-SHA256), encrypted on-device storage
- Consent: GDPR / CCPA erasure, Google consent mode (EU DMA), IAB TCF v2
- Uninstall tracking through FCM
- Client-side fraud signals (emulator, root, proxy, device farm, CTIT)

Android 5.0+ (API 21), Kotlin, Java-friendly (`@JvmStatic` / `@JvmOverloads`).

## Install

Add JitPack to `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

and the SDK to your app module:

```groovy
dependencies {
    implementation 'com.github.affclix:affclix-android-sdk:1.0.1'
}
```

ProGuard / R8 rules ship with the library (`consumer-rules.pro`); nothing to add.

## Quick start

```kotlin
// Application.onCreate()
AffclixSDK.init(application, AffclixConfig(
    appKey         = "<app key from the Affclix dashboard>",
    appSecret      = "<SDK signing secret>",   // optional; enables sig=
    trackingDomain = "track.example.com",      // your Affclix tracking domain
    debug          = BuildConfig.DEBUG,
    callbacks = AffclixCallbacks(
        onConversionTracked = { r -> Log.d("Affclix", "conversion: ${r.transactionId}") },
        onDeepLinkResolved  = { r -> route(r.deepLinkValue) },
        onError             = { e -> Log.e("Affclix", "error: ${e.message}") }
    )
))
```

The install is reported once, on first launch, with the Play install referrer,
any deep-link `click_id` and the advertising ID (respecting the user's opt-out).

### Events

```kotlin
AffclixSDK.logPurchase(4999.0, "INR", "ORD-001")
AffclixSDK.logRegistration("google_oauth")
AffclixSDK.logEvent("product_viewed", mapOf("product_id" to "prod_123"))

// Conversions (txid makes a resend a duplicate, not a second conversion)
AffclixSDK.trackConversion(ConversionEvent(campaignId = "<campaign id>", revenue = 4999.0, currency = "INR"))

// Validated Google Play purchases (renewals and refunds follow from Play) and ad revenue
AffclixSDK.logPlayPurchase(productId = p.products[0], purchaseToken = p.purchaseToken,
    revenue = 4.99, currency = "USD", orderId = p.orderId, eventToken = "purchase")
AffclixSDK.logAdRevenue(network = "applovin", revenue = 0.0021, adFormat = "rewarded")
```

### Deep links

Pass the intent from every entry activity:

```kotlin
AffclixSDK.handleDeepLink(intent, object : DeepLinkCallback {
    override fun onDeepLinkResolved(result: DeepLinkResult) {
        // result.clickId, result.deepLinkValue, result.isDeferredDeepLink
    }
})
```

Branded links have the form `https://{your Affclix domain}/l/{applicationId}?click_id=…&deep_link_value=…`.
With the app installed and App Links verified, the app opens on the URL; otherwise
the user goes to Google Play and `onDeepLinkResolved` fires on first open with
`isDeferredDeepLink = true`. Add your signing certificate's SHA-256 in the
Affclix dashboard (so the domain's `assetlinks.json` lists it) and:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="your.affclix.domain" android:pathPrefix="/l/" />
</intent-filter>
```

### Uninstall tracking (FCM)

```kotlin
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = AffclixSDK.setPushToken(token)
    override fun onMessageReceived(msg: RemoteMessage) {
        if (AffclixSDK.isUninstallCheck(msg.data)) return
        // … your own messages
    }
}
// and once at startup:
FirebaseMessaging.getInstance().token.addOnSuccessListener { AffclixSDK.setPushToken(it) }
```

Upload the app's Firebase service-account JSON in the Affclix dashboard.

### Privacy and consent

```kotlin
AffclixSDK.grantConsent()          // deliver events held while consent was pending
AffclixSDK.revokeConsent()         // stop tracking, forget IDs on the device
AffclixSDK.requestDataDeletion()   // GDPR / CCPA erasure of this device's data

// Google consent mode (EU DMA); null = unknown
AffclixSDK.setConsentData(
    isUserSubjectToGDPR = true,
    hasConsentForDataUsage = false,          // ad_user_data
    hasConsentForAdsPersonalization = false  // ad_personalization
)
// Or read your TCF v2 CMP's IABTCF_* values: AffclixConfig(enableTCFDataCollection = true)
```

### Getters

```kotlin
AffclixSDK.getVisitorId()
AffclixSDK.getSessionId()
AffclixSDK.getClickId()
AffclixSDK.getAttribution()
AffclixSDK.getDeviceInfo()
AffclixSDK.runFraudAssessment()
```

## Integration testing

Register your test device's advertising ID in the Affclix console (app → Test
Devices), then open the app on it: installs and events appear under **Live
Test Events** within seconds, with the attribution, the decision, and anything
missing (app key, signature, install referrer, event tokens). Test events are
never reported or counted, and a test device can install again and again.

## Sample app

`sampleapp/` is a four-tab test bench (tracking, attribution, fraud signals,
live request inspector). By default it runs against an in-app mock server, so
it needs no account. To point it at your Affclix domain, set `ENV` and the IDs
in `sampleapp/src/main/java/com/affclix/sample/AppConfig.kt`.

## Building from source

JDK 21 and the Android SDK (API 35 for the library, 36 for the sample app).

```bash
./gradlew :sdk:testDebugUnitTest         # JVM unit tests (Robolectric)
./gradlew :sdk:assembleRelease           # the .aar
./gradlew :sampleapp:installDebug        # sample app on a connected device
./gradlew :sdk:connectedAndroidTest      # instrumented tests on a device
```

## Support

- Documentation: [help.affclix.com](https://help.affclix.com)
- Issues: [GitHub Issues](https://github.com/affclix/affclix-android-sdk/issues)
- Security: see [SECURITY.md](SECURITY.md)

## License

[MIT](LICENSE)
