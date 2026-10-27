# ─── Affclix Android SDK — consumer ProGuard / R8 rules ─────────────────────
# These rules are merged into the host app's ProGuard config automatically
# when the SDK is consumed as an AAR dependency.

# ── Public SDK API ────────────────────────────────────────────────────────────
-keep class com.affclix.sdk.AffclixSDK { *; }
-keep class com.affclix.sdk.AffclixConfig { *; }
-keep class com.affclix.sdk.AffclixConfig$Builder { *; }
-keep class com.affclix.sdk.AffclixCallbacks { *; }

# ── Event / result model classes (passed back to host app) ────────────────────
-keep class com.affclix.sdk.model.** { *; }

# ── Callback interfaces (implemented by host app) ─────────────────────────────
-keep interface com.affclix.sdk.internal.TrackingCallback { *; }
-keep interface com.affclix.sdk.internal.DeepLinkCallback { *; }

# ── Fraud / attribution data classes returned through public API ──────────────
-keep class com.affclix.sdk.fraud.FraudAssessment { *; }
-keep class com.affclix.sdk.fraud.CtitAssessment { *; }
-keep class com.affclix.sdk.fraud.FraudSignal { *; }
-keep enum  com.affclix.sdk.fraud.CtitCategory { *; }
-keep enum  com.affclix.sdk.fraud.SignalCategory { *; }
-keep class com.affclix.sdk.attribution.RetargetingState { *; }
-keep class com.affclix.sdk.attribution.TouchPoint { *; }

# ── WorkManager worker (referenced by class name at runtime) ─────────────────
-keep class com.affclix.sdk.internal.EventQueueWorker { *; }

# WorkManager 2.9 builds its Room database (WorkDatabase_Impl) by reflection. Room 2.5's
# own rule keeps the class but not its constructor under R8 full mode (AGP 8 default),
# so a minified host app crashed at startup in androidx.startup.InitializationProvider:
# "Failed to create an instance of class androidx.work.impl.WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── OkHttp / Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }

# ── Google Play Services (GAID, Install Referrer) ────────────────────────────
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.ads.identifier.** { *; }
-keep class com.android.installreferrer.** { *; }

# ── Kotlin metadata (needed for reflection in some integrations) ──────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
