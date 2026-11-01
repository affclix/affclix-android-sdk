package com.affclix.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.MainThread
import com.affclix.sdk.attribution.*
import com.affclix.sdk.fraud.FraudDetector
import com.affclix.sdk.fraud.FraudAssessment
import com.affclix.sdk.internal.*
import com.affclix.sdk.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// AffclixSDK — Android Attribution & Affiliate Tracking SDK v1.0.1
//
// Single entry-point for all tracking. Call [init] once from Application.onCreate().
// All public methods are safe to call from any thread.
//
// Routing depends on [AffclixConfig.useDedicatedMobileServer]:
//
//   false (default) — Affclix MMP, /m/ prefix: /m/ev (installs, events,
//     conversions), /m/c and /m/i (deprecated in-app touches)
//
//   true — direct paths: /ev, /c, /i
//
// Installs and events go to /m/ev; the MMP attributes them to the tracker
// click (install referrer, deep link click_id or device ID) and reports them
// to the tracker. All endpoints answer JSON.
object AffclixSDK {

    const val SDK_VERSION  = "1.0.1"
    const val SDK_PLATFORM = "android"

    // Route paths — switched by useDedicatedMobileServer.
    private val clickPath      get() = if (config.useDedicatedMobileServer) "/c"  else "/m/c"
    private val impressionPath get() = if (config.useDedicatedMobileServer) "/i"  else "/m/i"
    private val eventPath      get() = if (config.useDedicatedMobileServer) "/ev" else "/m/ev"
    private val tokenPath      get() = if (config.useDedicatedMobileServer) "/token" else "/m/token"
    private val forgetPath     get() = if (config.useDedicatedMobileServer) "/forget" else "/m/forget"

    /** How long the install waits for the advertising ID fetched alongside the referrer. */
    private const val GAID_WAIT_MS = 3_000L

    /**
     * How long the install waits for the first activity, whose launch intent may be a
     * deep link with the tracker click_id (App Link, custom scheme). The referrer
     * resolves within milliseconds of init, before any activity exists, so without
     * this wait a deep-link install would always go out organic.
     */
    private const val FIRST_ACTIVITY_WAIT_MS = 2_000L
    private val firstActivity = CompletableDeferred<Unit>()

    private val initialized  = AtomicBoolean(false)
    private val sdkScope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe: accessed from init() (main), trackXxx() (IO), flushPendingQueue() (IO)
    private val pendingQueue = CopyOnWriteArrayList<PendingEvent>()

    internal lateinit var config:          AffclixConfig
    internal lateinit var storage:         StorageManager
    internal lateinit var network:         NetworkManager
    internal lateinit var deviceInfo:      DeviceInfoCollector
    internal lateinit var attribution:     AttributionManager
    internal lateinit var sessionManager:  SessionManager
    internal lateinit var retargeting:     RetargetingManager
    internal lateinit var multiTouch:      MultiTouchAttributionManager
    internal lateinit var fraudDetector:   FraudDetector
    internal lateinit var offlineQueue:    OfflineEventQueue
    internal lateinit var consent:         ConsentManager

    private lateinit var appContext:       Context
    private lateinit var installReferrer:  InstallReferrerManager

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Convenience initialiser — uses [appKey] from the Affclix dashboard as the sole
     * authentication token. The backend's MobileAppKeyMiddleware resolves domain,
     * campaign, and affiliate automatically from the key.
     */
    @MainThread
    @JvmStatic
    fun init(application: Application, appKey: String) {
        init(application, AffclixConfig(appKey = appKey))
    }

    @MainThread
    @JvmStatic
    @JvmOverloads
    fun init(application: Application, config: AffclixConfig = AffclixConfig()) {
        if (initialized.getAndSet(true)) {
            Logger.w("AffclixSDK", "Already initialised — skipping")
            return
        }

        this.appContext = application.applicationContext
        this.config     = config
        Logger.debugMode = config.debug

        Logger.d("AffclixSDK", "Initialising v$SDK_VERSION")

        storage         = StorageManager(appContext)
        network         = NetworkManager(config)
        deviceInfo      = DeviceInfoCollector(appContext)
        sessionManager  = SessionManager(storage, config)
        attribution     = AttributionManager(appContext, storage, config)
        retargeting     = RetargetingManager(storage)
        multiTouch      = MultiTouchAttributionManager(storage)
        fraudDetector   = FraudDetector(appContext, storage)
        installReferrer = InstallReferrerManager(appContext, storage)
        offlineQueue    = OfflineEventQueue(appContext)
        consent         = ConsentManager(appContext, storage, config.enableTCFDataCollection)

        sdkScope.launch { deviceInfo.fetchGaid() }

        // A force-stop cancels the app's scheduled jobs, and Android may hold a background
        // job back for a long time, so events queued earlier are delivered on every start.
        sdkScope.launch {
            val queued = offlineQueue.size()
            if (queued > 0) {
                Logger.d("AffclixSDK", "$queued queued event(s) — delivering")
                // Drain now while the app is open; the job covers whatever still fails.
                if (!EventQueueWorker.drain(appContext)) EventQueueWorker.schedule(appContext, androidx.work.ExistingWorkPolicy.KEEP)
            }
        }

        sdkScope.launch {
            installReferrer.resolve { referrerData ->
                attribution.onInstallReferrerResolved(referrerData)
                // First open ever (install time not yet recorded) vs a later
                // launch retrying an install that was neither sent nor queued.
                val firstOpen = storage.getInstallTime() == null
                if (firstOpen) storage.saveInstallTime()
                if (storage.isFirstLaunch()) {
                    // Marked done only once the server has the install or it is
                    // in the offline queue; otherwise the next launch retries.
                    if (trackInstall(referrerData)) storage.markFirstLaunchDone()
                }
                if (firstOpen) {
                    // Deferred deep link — Play Store encoded deep_link_value= in the
                    // referrer string. Resolve and surface it to the host app on first
                    // launch, same as Branch.io / AppsFlyer deferred deep link callbacks.
                    referrerData?.deepLinkValue?.takeIf { it.isNotBlank() }?.let { dlv ->
                        try {
                            val deferredUri = android.net.Uri.parse(dlv)
                            attribution.resolveDeepLink(deferredUri) { result ->
                                val deferredResult = result.copy(
                                    isDeferredDeepLink = true,
                                    deepLinkValue      = dlv
                                )
                                config.callbacks.onDeepLinkResolved?.invoke(deferredResult)
                                Logger.d("AffclixSDK", "Deferred deep link resolved: $dlv")
                            }
                        } catch (e: Exception) {
                            Logger.w("AffclixSDK", "Deferred deep link parse error: ${e.message}")
                        }
                    }
                }
                storage.saveLastSessionStartTime()
            }
        }

        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        storage.getVisitorId()

        if (!config.consentRequired || storage.hasConsent()) {
            flushPendingQueue()
        }

        Logger.d("AffclixSDK",
            "Ready | visitor=${storage.getVisitorId()} | session=${sessionManager.currentSessionId()}")
    }

    // ── Impression Tracking ───────────────────────────────────────────────────

    @Deprecated(
        "In-app impressions are recorded as touches but not used for attribution: " +
        "installs are credited to the tracker click (install referrer, deep link " +
        "click_id or device ID). Will be removed."
    )
    @JvmStatic
    fun trackImpression(event: ImpressionEvent, callback: TrackingCallback? = null) {
        guardInit() ?: return
        if (!checkConsent(PendingEvent.Type.IMPRESSION, event, callback)) return

        sdkScope.launch {
            var url: String? = null
            try {
                val fraud   = fraudDetector.assess()
                val payload = buildImpressionPayload(event, fraud)
                url = buildGetUrl(impressionPath, payload)
                val resp    = network.get(url)

                val impressionId = resp.optString("touch_id")
                    .ifBlank { resp.optString("iid") }
                    .ifBlank { payload["iid"] as? String ?: "" }
                val expiryDays   = expiryToDays(
                    resp.optLong("impression_expiry", config.impressionExpiryDays * 86_400L)
                )

                storage.storeImpressionId(event.campaignId, impressionId, expiryDays)
                multiTouch.recordTouch(
                    mediaSource = attribution.getAttributionData().mediaSource,
                    campaign    = attribution.getAttributionData().utmCampaign,
                    touchType   = "impression"
                )
                Logger.d("AffclixSDK", "Impression tracked: $impressionId")
                config.callbacks.onImpressionTracked?.invoke(ImpressionResult(impressionId, event.campaignId))
                withContext(Dispatchers.Main) { callback?.onSuccess(impressionId) }
            } catch (e: Exception) {
                url?.let { handleDeliveryFailure(e, "impression", Prepared(it, null)) }
                config.callbacks.onError?.invoke(SdkError("impression", e.message ?: ""))
                withContext(Dispatchers.Main) { callback?.onError(e.message ?: "error") }
            }
        }
    }

    // ── Click Tracking ────────────────────────────────────────────────────────

    @Deprecated(
        "In-app clicks are recorded as touches but not used for attribution: " +
        "installs are credited to the tracker click (install referrer, deep link " +
        "click_id or device ID). Will be removed."
    )
    @JvmStatic
    fun trackClick(event: ClickEvent, callback: TrackingCallback? = null) {
        guardInit() ?: return
        if (!checkConsent(PendingEvent.Type.CLICK, event, callback)) return

        sdkScope.launch {
            var url: String? = null
            try {
                val fraud   = fraudDetector.assess()
                val payload = buildClickPayload(event, fraud)
                url = buildGetUrl(clickPath, payload)
                val resp    = network.get(url)

                // The MMP answers the recorded touch's ID. It is not a tracker
                // click ID, so it is not stored as one: sent back as click_id it
                // would credit events to a click that does not exist.
                val clickId = resp.optString("touch_id").ifBlank { resp.optString("click_id") }.ifBlank { null }
                    ?: throw SdkException("INVALID_RESPONSE", "Missing touch_id in /m/c response")

                storage.saveClickTime(event.campaignId)
                multiTouch.recordTouch(
                    mediaSource = attribution.getAttributionData().mediaSource,
                    campaign    = attribution.getAttributionData().utmCampaign,
                    touchType   = "click"
                )
                Logger.d("AffclixSDK", "Click tracked: $clickId")
                config.callbacks.onClickTracked?.invoke(ClickResult(clickId, event.campaignId))
                withContext(Dispatchers.Main) { callback?.onSuccess(clickId) }
            } catch (e: Exception) {
                url?.let { handleDeliveryFailure(e, "click", Prepared(it, null)) }
                config.callbacks.onError?.invoke(SdkError("click", e.message ?: ""))
                withContext(Dispatchers.Main) { callback?.onError(e.message ?: "error") }
            }
        }
    }

    // ── Conversion / Postback Tracking ────────────────────────────────────────

    /**
     * Track a conversion: sent to the event endpoint (/m/ev) as [ConversionEvent.eventName]
     * with e= [ConversionEvent.goalId] (the tracker event token, else the event name) and
     * txid= the transaction ID, which makes a resent conversion a duplicate.
     * [ConversionEvent.postbackType] no longer applies: every conversion is an event.
     */
    @JvmStatic
    fun trackConversion(event: ConversionEvent, callback: TrackingCallback? = null) {
        guardInit() ?: return
        if (!checkConsent(PendingEvent.Type.CONVERSION, event, callback)) return

        sdkScope.launch {
            var req: Prepared? = null
            try {
                val clickId       = storage.getClickId(event.campaignId)
                val impressionId  = storage.getImpressionId(event.campaignId)
                val transactionId = event.transactionId ?: storage.generateUUID()

                if (clickId == null && impressionId == null && event.promoCode == null) {
                    Logger.w("AffclixSDK", "No click_id/impression_id — server-side fingerprint resolution")
                }

                val fraud        = fraudDetector.assess()
                val contributors = multiTouch.getTouches()
                val payload      = buildConversionPayload(
                    event, clickId, impressionId, transactionId, fraud, contributors
                )
                val prepared = prepareEvent(payload)
                req = prepared
                sendDurably(prepared)

                storage.storeTransactionId(event.campaignId, transactionId)
                multiTouch.clearTouches()

                Logger.d("AffclixSDK", "Conversion: txn=$transactionId click=$clickId event=${event.eventName}")
                val result = ConversionResult(transactionId, clickId, "event")
                config.callbacks.onConversionTracked?.invoke(result)
                withContext(Dispatchers.Main) { callback?.onSuccess(transactionId) }
            } catch (e: Exception) {
                Logger.e("AffclixSDK", "Conversion error: ${e.message}")
                req?.let { handleDeliveryFailure(e, "conversion", it) }
                config.callbacks.onError?.invoke(SdkError("conversion", e.message ?: ""))
                withContext(Dispatchers.Main) { callback?.onError(e.message ?: "error") }
            }
        }
    }

    // ── In-App Event Logging ──────────────────────────────────────────────────

    @JvmStatic
    fun logEvent(
        eventName: String,
        params: Map<String, Any> = emptyMap(),
        callback: TrackingCallback? = null
    ) {
        guardInit() ?: return
        if (!checkConsent(PendingEvent.Type.EVENT,
                GenericEventData(eventName, params), callback)) return

        sdkScope.launch {
            var req: Prepared? = null
            try {
                val prepared = prepareEvent(buildEventPayload(eventName, params))
                req = prepared
                sendDurably(prepared)
                Logger.d("AffclixSDK", "Event: $eventName")
                withContext(Dispatchers.Main) { callback?.onSuccess(eventName) }
            } catch (e: Exception) {
                // Queue the request that was attempted, not a rebuilt one, so the
                // retry carries the same event_id (and signature) and the server
                // can drop it as a duplicate if the first attempt did land.
                req?.let { handleDeliveryFailure(e, "event", it) }
                withContext(Dispatchers.Main) { callback?.onError(e.message ?: "error") }
            }
        }
    }

    // ── Standard event shortcuts ──────────────────────────────────────────────

    @JvmStatic fun logPurchase(
        revenue: Double, currency: String,
        orderId: String? = null,
        params: Map<String, Any> = emptyMap()
    ) = logEvent(AffclixEvent.PURCHASE, buildMap {
        put("revenue", revenue); put("currency", currency)
        orderId?.let { put("order_id", it) }; putAll(params)
    })

    /**
     * Logs a Google Play purchase for server-side validation: the MMP checks the
     * purchase token with the Play Developer API (when the app's service account is
     * set in the dashboard) before the revenue counts or the purchase is reported;
     * renewals and refunds then arrive from Play's notifications. Call after the
     * purchase is acknowledged, with Purchase.getProducts()[0],
     * Purchase.getPurchaseToken() and Purchase.getOrderId().
     *
     * @param eventToken the tracker event token (e=) to report the purchase under
     */
    @JvmStatic @JvmOverloads fun logPlayPurchase(
        productId: String,
        purchaseToken: String,
        revenue: Double,
        currency: String,
        orderId: String? = null,
        isSubscription: Boolean = false,
        eventToken: String? = null,
        params: Map<String, Any> = emptyMap(),
        callback: TrackingCallback? = null
    ) = logEvent(AffclixEvent.PURCHASE, buildMap {
        putAll(params)
        put("revenue", revenue); put("currency", currency)
        put("iap_product_id", productId)
        put("iap_token", purchaseToken)
        put("iap_subscription", if (isSubscription) 1 else 0)
        // The order ID (else the token) makes a resent purchase a duplicate.
        put("txid", orderId ?: purchaseToken)
        orderId?.let { put("order_id", it) }
        eventToken?.let { put("e", it) }
    }, callback)

    /**
     * Logs impression-level ad revenue (e.g. from AppLovin MAX, ironSource, AdMob
     * paid-event callbacks), reported separately from purchase revenue.
     */
    @JvmStatic @JvmOverloads fun logAdRevenue(
        network: String,
        revenue: Double,
        currency: String = "USD",
        adUnit: String? = null,
        adFormat: String? = null,
        placement: String? = null,
        params: Map<String, Any> = emptyMap()
    ) = logEvent("ad_revenue", buildMap {
        putAll(params)
        put("revenue", revenue); put("currency", currency)
        put("ad_network", network)
        adUnit?.let { put("ad_unit", it) }
        adFormat?.let { put("ad_format", it) }
        placement?.let { put("placement", it) }
    })

    @JvmStatic fun logAddToCart(productId: String, price: Double, currency: String = "USD") =
        logEvent(AffclixEvent.ADD_TO_CART,
            mapOf("product_id" to productId, "price" to price, "currency" to currency))

    @JvmStatic fun logRegistration(method: String = "email") =
        logEvent(AffclixEvent.REGISTRATION, mapOf("method" to method))

    @JvmStatic fun logLogin(method: String = "email") =
        logEvent(AffclixEvent.LOGIN, mapOf("method" to method))

    @JvmStatic fun logTutorialComplete() = logEvent(AffclixEvent.TUTORIAL_COMPLETE)

    @JvmStatic fun logSubscription(plan: String, revenue: Double, currency: String = "USD") =
        logEvent(AffclixEvent.SUBSCRIPTION,
            mapOf("plan" to plan, "revenue" to revenue, "currency" to currency))

    /**
     * Track an app open / foreground event.
     * Call from Application.onCreate() or your deep link handler for reattribution.
     * Maps to the "app_open" event on the /m/ev endpoint.
     */
    @JvmStatic fun trackAppOpen(sourceIntent: Intent? = null) {
        guardInit() ?: return
        val params = buildMap<String, Any> {
            sourceIntent?.data?.let { put("deep_link_url", it.toString()) }
        }
        logEvent(AffclixEvent.APP_OPEN, params)
    }

    // ── Deep Link Handling ────────────────────────────────────────────────────

    @JvmStatic
    fun handleDeepLink(intent: Intent?, callback: DeepLinkCallback? = null) {
        guardInit() ?: return
        val uri = intent?.data ?: return
        attribution.resolveDeepLink(uri) { result ->
            Logger.d("AffclixSDK", "Deep link: ${result.clickId} campaign=${result.campaignId}")
            config.callbacks.onDeepLinkResolved?.invoke(result)
            callback?.onDeepLinkResolved(result)
            result.clickId?.let { storage.storeClickId("deeplink", it, config.clickExpiryDays) }
            // Log as deep_link event for reattribution tracking
            logEvent(AffclixEvent.DEEP_LINK, buildMap {
                put("deep_link_url", uri.toString())
                result.utmSource?.let { put("utm_source", it) }
                result.utmCampaign?.let { put("utm_campaign", it) }
            })
        }
    }

    // ── Uninstall tracking ────────────────────────────────────────────────────

    /**
     * Registers the FCM registration token for uninstall tracking: the MMP sends the
     * app a daily silent data message, and records an uninstall once FCM reports the
     * token unregistered. Call from FirebaseMessagingService.onNewToken, and once at
     * startup with FirebaseMessaging.getInstance().token. Sent only when the token
     * changed; queued offline when there is no connection.
     */
    @JvmStatic fun setPushToken(token: String) {
        guardInit() ?: return
        if (token.isBlank()) return
        sdkScope.launch {
            if (storage.getPushToken() == token) return@launch
            var req: Prepared? = null
            try {
                val payload = buildBasePayload()
                if (!appKeyMode) payload["d"] = config.domainId
                payload["push_token"] = token
                val body = serialisePayload(payload)
                val prepared = Prepared(buildPostUrl(tokenPath, body), body)
                req = prepared
                val resp = network.postJson(prepared.url, prepared.body!!)
                if (resp.optBoolean("success", true)) {
                    storage.savePushToken(token)
                    Logger.d("AffclixSDK", "Push token registered")
                } else {
                    Logger.e("AffclixSDK", "Push token refused: ${resp.optString("code")}")
                }
            } catch (e: Exception) {
                if (req?.let { handleDeliveryFailure(e, "push_token", it) } == true) storage.savePushToken(token)
            }
        }
    }

    /**
     * True for the MMP's silent uninstall-check message: return from
     * onMessageReceived without showing or processing anything.
     */
    @JvmStatic fun isUninstallCheck(data: Map<String, String>): Boolean =
        data["affclix_uninstall_check"] == "1"

    // ── User Identity ─────────────────────────────────────────────────────────

    /**
     * Associate a customer / user ID with all subsequent events.
     * Used for cross-device attribution matching.
     * Pass null to clear.
     */
    @JvmStatic fun setUserId(id: String?) {
        guardInit() ?: return
        storage.saveUserId(id)
        Logger.d("AffclixSDK", "User ID set: ${id?.take(8)}***")
    }

    @JvmStatic fun getUserId(): String? = if (initialized.get()) storage.getUserId() else null

    /**
     * Set persistent user properties sent on every subsequent event.
     * Keys are sent as-is in the event payload (prefix your own keys to avoid
     * collisions with reserved SDK fields).
     */
    @JvmStatic fun setUserProperties(props: Map<String, String>) {
        guardInit() ?: return
        storage.saveUserProperties(props)
    }

    // ── Consent ───────────────────────────────────────────────────────────────

    @JvmStatic fun grantConsent() {
        storage.saveConsent(true)
        Logger.d("AffclixSDK", "Consent granted")
        flushPendingQueue()
    }

    /**
     * Stops tracking and forgets the user's identifiers on the device. The install
     * state is kept, so a later grant does not report a second install. To delete
     * the data already sent, call [requestDataDeletion].
     */
    @JvmStatic fun revokeConsent() {
        storage.saveConsent(false)
        storage.clearIdentifiers()
        Logger.d("AffclixSDK", "Consent revoked")
    }

    /**
     * Right to erasure (GDPR / CCPA): asks the MMP to delete everything it holds for
     * this device (POST /m/forget with the visitor ID, GAID and Android ID), then
     * forgets the identifiers locally like [revokeConsent]. Without a connection the
     * request is queued and sent later. callback.onSuccess gets the MMP's request ID
     * (or "queued").
     */
    @JvmStatic @JvmOverloads
    fun requestDataDeletion(callback: TrackingCallback? = null) {
        guardInit() ?: return
        sdkScope.launch {
            val device = deviceInfo.collect()
            val payload = mutableMapOf<String, Any?>(
                "platform" to "android",
                "app_id"   to device.appPackage,
                "vid"      to storage.getVisitorId(),
                "gaid"     to device.gaid,
                // Sent to delete what is keyed by it, whatever collectAndroidId says.
                "android_id" to device.androidId,
            )
            if (config.appKey.isNotBlank()) payload["ak"] = config.appKey
            if (!appKeyMode) payload["d"] = config.domainId
            val body = serialisePayload(payload)
            val req = Prepared(buildPostUrl(forgetPath, body), body)
            // Sent afterwards, saved events would bring back what is being erased.
            offlineQueue.removeEvents(eventPath)
            try {
                val resp = network.postJson(req.url, body)
                if (!resp.optBoolean("success", false)) {
                    throw SdkException(resp.optString("code", "REFUSED"), "erasure refused: ${resp.optString("code")}")
                }
                storage.clearIdentifiers()
                Logger.d("AffclixSDK", "Data deletion requested: ${resp.optString("request_id")}")
                withContext(Dispatchers.Main) { callback?.onSuccess(resp.optString("request_id")) }
            } catch (e: Exception) {
                if (handleDeliveryFailure(e, "data_deletion", req)) {
                    storage.clearIdentifiers()
                    withContext(Dispatchers.Main) { callback?.onSuccess("queued") }
                } else {
                    withContext(Dispatchers.Main) { callback?.onError(e.message ?: "error") }
                }
            }
        }
    }

    @JvmStatic fun hasConsent() = storage.hasConsent()

    /**
     * Google consent mode (EU DMA), sent with every request. Pass null for "unknown".
     * Under GDPR, hasConsentForDataUsage = false makes the MMP skip device-ID
     * matching and keep the IP out of the advertiser postback. Call before init's
     * first events where possible — the install carries it too.
     *
     * @param isUserSubjectToGDPR            gdpr_applies
     * @param hasConsentForDataUsage         ad_user_data
     * @param hasConsentForAdsPersonalization ad_personalization
     */
    @JvmStatic fun setConsentData(
        isUserSubjectToGDPR: Boolean?,
        hasConsentForDataUsage: Boolean?,
        hasConsentForAdsPersonalization: Boolean?
    ) {
        guardInit() ?: return
        storage.saveConsentData(ConsentData(isUserSubjectToGDPR, hasConsentForDataUsage, hasConsentForAdsPersonalization))
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    @JvmStatic fun getClickId(campaignId: String? = null)   = storage.getClickId(campaignId)
    @JvmStatic fun getImpressionId(campaignId: String)      = storage.getImpressionId(campaignId)
    @JvmStatic fun getVisitorId()                            = storage.getVisitorId()
    @JvmStatic fun getSessionId()                            = sessionManager.currentSessionId()
    @JvmStatic fun getAttribution()                          = attribution.getAttributionData()
    @JvmStatic fun getInstallReferrer()                      = storage.getInstallReferrer()
    @JvmStatic fun getDeviceInfo()                           = deviceInfo.collect()
    @JvmStatic fun getFraudAssessment()                      = fraudDetector.assess()
    @JvmStatic fun isInitialized()                           = initialized.get()
    @JvmStatic fun getVersion()                              = SDK_VERSION
    @JvmStatic fun getOfflineQueueSize()                     = if (initialized.get()) offlineQueue.size() else 0

    // ── Payload Builders ──────────────────────────────────────────────────────

    /** True when SDK is authenticated via app_key rather than explicit d/c/a IDs. */
    private val appKeyMode get() = config.appKey.isNotBlank()

    /** Persistent device IDs may be sent only without an advertising ID and without LAT. */
    private fun mayUseDeviceIds(device: DeviceData) = device.gaid.isNullOrBlank() && !device.isLATEnabled

    internal fun buildBasePayload(): MutableMap<String, Any?> {
        val device  = deviceInfo.collect()
        val session = sessionManager.getOrCreateSession()
        val attr    = attribution.getAttributionData()
        val ref     = storage.getInstallReferrer()
        val retarg  = retargeting.getState()

        return mutableMapOf<String, Any?>(
            // SDK identity — ep=WS tells the server to return JSON (not a redirect).
            // The server's MobileSDKMode middleware also sets this; sending it from
            // the SDK ensures correct behaviour even without middleware coverage.
            "ep"          to "WS",
            "platform"    to "android",
            "sdk_version" to SDK_VERSION,
            "v"           to SDK_VERSION,
            // App
            "app_id"      to device.appPackage,
            // Session
            "vid"         to storage.getVisitorId(),
            "sid"         to session.id,
            // Mobile IDs
            "gaid"        to device.gaid,
            // Google Play policy: the advertising ID must not be linked to
            // persistent device identifiers. android_id goes only to apps that
            // opt in, and only when there is no advertising ID and the user has
            // not opted out of ad tracking.
            "android_id"  to if (mayUseDeviceIds(device) && config.collectAndroidId) device.androidId else null,
            "is_lat"      to if (device.isLATEnabled) 1 else 0,
            // Device hardware
            "device_type"  to device.deviceType,
            "device_brand" to device.brand,
            "device_name"  to device.model,
            "os"           to "android",
            "os_version"   to device.osVersion,
            // Locale
            "lang"         to device.language,
            "tz"           to device.timezone,
            // Network
            "wifi"            to if (device.networkType == "wifi") 1 else 0,
            "connection_type" to device.connectionType,
            // Attribution — sent as both AppsFlyer-style (af_pid/af_c) and standard
            // UTM fields so the server handler can read either format consistently.
            // Tracking URLs use af_pid={media_source}; SDK-direct calls use media_source.
            // Sending both ensures no silent mismatch in server-side column mapping.
            "media_source"  to attr.mediaSource,
            "af_pid"        to attr.mediaSource,      // AppsFlyer-compat alias for media_source
            "af_c"          to attr.utmCampaign,       // AppsFlyer-compat alias for utm_campaign
            "ad_type"       to attr.adType,
            "utm_source"    to (attr.utmSource  ?: ref?.utmSource),
            "utm_medium"    to (attr.utmMedium  ?: ref?.utmMedium),
            "utm_campaign"  to (attr.utmCampaign ?: ref?.utmCampaign),
            "utm_term"      to (attr.utmTerm    ?: ref?.utmTerm),
            "utm_content"   to (attr.utmContent ?: ref?.utmContent),
            // Google privacy-safe IDs
            "gbraid"        to ref?.gbraid,
            "wbraid"        to ref?.wbraid,
            // Install time
            "install_time"  to storage.getInstallTime(),
            // Retargeting
            "is_retargeting"              to if (retarg.isRetargeting) 1 else 0,
            "retargeting_conversion_type" to retarg.conversionType,
            // Fingerprint
            "device_fp"  to if (mayUseDeviceIds(device) && config.collectFingerprint) deviceInfo.getDeviceFingerprint() else null,
        ).also { payload ->
            // App-key auth — the backend resolves the tenant and app from ak=
            if (config.appKey.isNotBlank()) payload["ak"] = config.appKey
            // Consent (Google consent mode / IAB TCF)
            payload.putAll(consent.payload())
            // User identity — send all aliases so any server version picks it up
            storage.getUserId()?.let {
                payload["customer_user_id"]    = it   // server field (EventRequest.CustomerUserID)
                payload["af_customer_user_id"] = it   // AppsFlyer alias
                payload["customer_id"]         = it   // legacy backward compat
            }
            // User properties (sent as-is)
            storage.getUserProperties().forEach { (k, v) -> payload[k] = v }
        }
    }

    private fun buildImpressionPayload(
        event: ImpressionEvent,
        fraud: FraudAssessment
    ): MutableMap<String, Any?> = buildBasePayload().apply {
        // In appKey mode d/c/a are injected by MobileAppKeyMiddleware; only send
        // explicit per-event overrides so they take precedence over app defaults.
        event.domainId?.let { put("d", it) } ?: if (!appKeyMode) put("d", config.domainId) else Unit
        put("c", event.campaignId.ifBlank { if (appKeyMode) null else config.defaultCampaignId })
        put("a", event.affiliateId?.ifBlank { null } ?: if (appKeyMode) null else config.defaultAffiliateId)
        put("cr",   event.creativeId)
        put("iid",  storage.generateUUID())
        put("is_vta_eligible",       if (event.viewThroughAttribution) 1 else 0)
        put("ad_type",               event.adType)
        put("attributed_touch_type", "impression")
        putAll(fraud.toPayloadMap())
        appendSubIds(this, event.subIds)
        event.customParams.forEach { (k, v) -> put(k, v) }
    }

    private fun buildClickPayload(
        event: ClickEvent,
        fraud: FraudAssessment
    ): MutableMap<String, Any?> = buildBasePayload().apply {
        event.domainId?.let { put("d", it) } ?: if (!appKeyMode) put("d", config.domainId) else Unit
        put("c", event.campaignId.ifBlank { if (appKeyMode) null else config.defaultCampaignId })
        put("a", event.affiliateId?.ifBlank { null } ?: if (appKeyMode) null else config.defaultAffiliateId)
        put("cr",          event.creativeId)
        put("source",      event.source)
        put("redirect_ms", event.redirectMs)
        put("ad_type",     event.adType)
        put("attributed_touch_type", "click")
        storage.getImpressionId(event.campaignId)?.let { put("iid", it) }
        putAll(fraud.toPayloadMap())
        appendSubIds(this, event.subIds)
        event.customParams.forEach { (k, v) -> put(k, v) }
    }

    private fun buildConversionPayload(
        event: ConversionEvent,
        clickId: String?,
        impressionId: String?,
        transactionId: String,
        fraud: FraudAssessment,
        contributors: List<TouchPoint>
    ): MutableMap<String, Any?> {
        val clickedAt     = storage.getClickTime(event.campaignId)
        val installTime   = storage.getInstallTime()
        val timeToConvert = if (clickedAt > 0) (System.currentTimeMillis() - clickedAt) / 1000 else 0L

        return buildBasePayload().apply {
            event.domainId?.let { put("d", it) } ?: if (!appKeyMode) put("d", config.domainId) else Unit
            put("c", event.campaignId.ifBlank { if (appKeyMode) null else config.defaultCampaignId })
            put("a", event.affiliateId?.ifBlank { null } ?: if (appKeyMode) null else config.defaultAffiliateId)
            put("txid",        transactionId)
            put("event",       event.eventName)
            put("e",           event.goalId ?: event.eventName)
            put("time_to_convert", timeToConvert)
            put("install_time",    installTime)
            put("attributed_touch_type",
                if (impressionId != null && clickId == null) "impression" else "click")
            clickId?.let      { put("click_id", it) }
            impressionId?.let { put("iid", it); put("vta", 1) }
            // Revenue
            put("sale",        event.revenue)
            put("currency",    event.currency)
            put("order_id",    event.orderId)
            // Product
            put("product_id",   event.productId)
            put("product_name", event.productName)
            put("category",     event.category)
            put("quantity",     event.quantity)
            // Customer
            put("customer_id",     event.customerId ?: storage.getUserId())
            put("is_new_customer", if (event.isNewCustomer) "true" else "false")
            put("coupon",      event.couponCode)
            put("promo_code",  event.promoCode)
            if (config.testMode || event.isTest) put("t", "test")
            // sub_adv_id1..6 — server schema has 6 sub-advertiser ID columns
            event.subParams.forEachIndexed { i, v ->
                if (i < 6) put("sub_adv_id${i + 1}", v)
            }
            // Multi-touch contributors (→ contributor_1/2/3 columns)
            contributors.take(3).forEachIndexed { i, t ->
                val n = i + 1
                put("contributor_${n}_media_source", t.mediaSource)
                put("contributor_${n}_campaign",     t.campaign)
                put("contributor_${n}_touch_type",   t.touchType)
                put("contributor_${n}_touch_time",   t.touchTime / 1000)
            }
            putAll(fraud.toPayloadMap())
            appendSubIds(this, event.subIds)
            event.customParams.forEach { (k, v) -> put(k, v) }
        }
    }

    private fun buildEventPayload(
        eventName: String,
        params: Map<String, Any>
    ): MutableMap<String, Any?> = buildBasePayload().apply {
        // In appKey mode d= is resolved server-side; in classic mode send it explicitly.
        if (!appKeyMode) put("d", config.domainId)
        put("event", eventName)
        put("ts",    System.currentTimeMillis())
        // Idempotency key: the server records and reports an event once per
        // event_id, so network retries and offline-queue replays are not counted
        // twice. An event_id in params (the app's own key) overrides it.
        put("event_id", storage.generateUUID())
        storage.getClickId(null)?.let { put("click_id", it) }
        params.forEach { (k, v) -> put(k, v) }
    }

    // ── Event delivery ────────────────────────────────────────────────────────

    /**
     * A request built — and signed — once, so network retries and offline replays
     * resend exactly what was first sent. [body] is the JSON of a POST, null for GET.
     */
    private class Prepared(val url: String, val body: String?, var queueId: Long? = null) {
        val method get() = if (body != null) "POST" else "GET"
    }

    /** An event request: POST JSON (auth and signature in the URL) or GET. */
    private fun prepareEvent(payload: Map<String, Any?>): Prepared =
        if (config.usePostForEvents) {
            val body = serialisePayload(payload)
            Prepared(buildPostUrl(eventPath, body), body)
        } else {
            Prepared(buildGetUrl(eventPath, payload), null)
        }

    private suspend fun send(req: Prepared) {
        if (req.body != null) network.postJson(req.url, req.body) else network.get(req.url)
    }

    /**
     * Sends a request that must survive the app being closed: it is saved before
     * the first attempt and stays saved until the MMP has answered, so a process
     * killed during the retries loses nothing. A resend is dropped by event_id.
     */
    private suspend fun sendDurably(req: Prepared) {
        deliverDurably(
            save = { if (config.enableOfflineQueue) offlineQueue.enqueueForId(req.url, req.body, req.method) else null },
            remove = offlineQueue::remove,
            isTransient = ::isTransient,
            send = { send(req) },
            onSaved = { req.queueId = it },
        )
    }

    private fun isTransient(e: Exception): Boolean = e is java.io.IOException ||
        (e is NetworkException && (e.statusCode == 429 || e.statusCode >= 500))

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Reports the install. True once the server has it or it is in the offline
     * queue; false leaves the first launch pending, so the next launch retries.
     */
    private suspend fun trackInstall(referrer: InstallReferrerData?): Boolean {
        // Fetched in parallel with the referrer; device-ID attribution needs it on
        // the install itself, not only on later events.
        withTimeoutOrNull(GAID_WAIT_MS) { deviceInfo.fetchGaid() }

        withTimeoutOrNull(FIRST_ACTIVITY_WAIT_MS) { firstActivity.await() }

        var req: Prepared? = null
        return try {
            val payload = buildBasePayload()
            payload["event"] = "install"
            if (!appKeyMode) payload["d"] = config.domainId
            payload["install_referrer"] = referrer?.rawReferrer
            // Play's own timestamps (seconds): the store-listing click and the start
            // of the install. install_time is the install start when Play reports
            // it, else first open; the tracker compares it to the click time to
            // catch click injection.
            val clickTs        = referrer?.clickTimestamp?.takeIf { it > 0 }
            val installBeginTs = referrer?.installBeginTimestamp?.takeIf { it > 0 }
            payload["referrer_click_ts"] = clickTs
            payload["install_begin_ts"]  = installBeginTs
            payload["install_time"]      = installBeginTs?.times(1000) ?: storage.getInstallTime()
            // CTIT fraud signal
            val ctit = fraudDetector.assessCtit(clickTs, installBeginTs ?: (System.currentTimeMillis() / 1000))
            payload["ctit_seconds"]  = ctit.ctitSeconds
            payload["ctit_category"] = ctit.category.name.lowercase()
            payload.putAll(fraudDetector.assess().toPayloadMap())
            payload["click_id"] = storage.getClickId(null)

            val prepared = prepareEvent(payload)
            req = prepared
            sendDurably(prepared)
            Logger.d("AffclixSDK", "Install tracked: source=${referrer?.mediaSource} ctit=${ctit.category}")
            true
        } catch (e: Exception) {
            Logger.e("AffclixSDK", "Install tracking error: ${e.message}")
            req?.let { handleDeliveryFailure(e, "install", it) } ?: false
        }
    }

    // ── Offline queue ─────────────────────────────────────────────────────────

    /**
     * Queues a request that failed for a transient reason — no connection
     * (IOException), 429 or 5xx — for WorkManager delivery. True when queued.
     */
    private fun handleDeliveryFailure(e: Exception, type: String, req: Prepared): Boolean {
        Logger.e("AffclixSDK", "$type delivery failed: ${e.message}")
        if (!config.enableOfflineQueue || !isTransient(e)) return false
        val saved = req.queueId != null || offlineQueue.enqueue(req.url, req.body, req.method)
        if (saved) {
            EventQueueWorker.schedule(appContext)
            Logger.d("AffclixSDK", "$type queued for offline delivery")
        }
        return saved
    }

    private fun serialisePayload(payload: Map<String, Any?>): String {
        val obj = JSONObject()
        payload.forEach { (k, v) -> if (v != null) obj.put(k, v) }
        return obj.toString()
    }

    // ── Consent queue ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // replays queued trackImpression / trackClick calls
    private fun flushPendingQueue() {
        if (pendingQueue.isEmpty()) return
        val snapshot = pendingQueue.toList()
        pendingQueue.clear()
        Logger.d("AffclixSDK", "Flushing ${snapshot.size} consent-queued events")
        snapshot.forEach { pending ->
            when (pending.type) {
                PendingEvent.Type.IMPRESSION -> trackImpression(pending.event as ImpressionEvent)
                PendingEvent.Type.CLICK      -> trackClick(pending.event as ClickEvent)
                PendingEvent.Type.CONVERSION -> trackConversion(pending.event as ConversionEvent)
                PendingEvent.Type.EVENT      -> {
                    val ev = pending.event as GenericEventData
                    logEvent(ev.eventName, ev.params)
                }
            }
        }
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    private fun guardInit(): Unit? {
        if (!initialized.get()) {
            Logger.e("AffclixSDK", "SDK not initialised — call AffclixSDK.init() first")
            return null
        }
        return Unit
    }

    private fun checkConsent(type: PendingEvent.Type, event: Any, callback: TrackingCallback?): Boolean {
        if (config.consentRequired && !storage.hasConsent()) {
            pendingQueue.add(PendingEvent(type, event))
            callback?.onQueued()
            Logger.d("AffclixSDK", "Event queued (no consent): $type")
            return false
        }
        return true
    }

    // ── URL builders ──────────────────────────────────────────────────────────

    /** Base URL (no query params). */
    internal fun buildBaseUrl(path: String): String {
        val scheme = if (isLocalhost(config.trackingDomain)) "http" else "https"
        return "$scheme://${config.trackingDomain}$path"
    }

    /**
     * POST endpoint URL — the auth param (ak= or d=) in the query string so the
     * server resolves the app before reading the JSON body, and with an app secret,
     * sig= over [body] exactly as it will be sent.
     */
    internal fun buildPostUrl(path: String, body: String): String {
        val base = buildBaseUrl(path)
        val authParam = when {
            config.appKey.isNotBlank()   -> "ak=${Uri.encode(config.appKey)}"
            config.domainId.isNotBlank() -> "d=${Uri.encode(config.domainId)}"
            else                          -> return base
        }
        return "$base?$authParam" + signature(body)
    }

    /** Full URL with query params — used for GET requests; sig= covers the query string. */
    internal fun buildGetUrl(path: String, params: Map<String, Any?>): String {
        val base = buildBaseUrl(path)
        val qs   = params.entries
            .filter { it.value != null && it.value.toString().isNotBlank() }
            .joinToString("&") { (k, v) ->
                "${Uri.encode(k)}=${Uri.encode(v.toString())}"
            }
        return if (qs.isNotEmpty()) "$base?$qs" + signature(qs) else base
    }

    /** "&sig=…" over [message] when an app secret is configured, else "". */
    private fun signature(message: String): String =
        if (config.appSecret.isBlank()) ""
        else "&sig=" + RequestSigner.sign(config.appSecret, message)

    private fun isLocalhost(domain: String) =
        domain.startsWith("localhost") ||
        domain.startsWith("127.") ||
        domain.startsWith("10.0.2.2") ||
        domain.startsWith("192.168.")

    private fun appendSubIds(payload: MutableMap<String, Any?>, subIds: Map<String, String>) {
        for (i in 1..6) subIds["sub$i"]?.let { payload["sub$i"] = it }
    }

    /** Convert a server-side expiry value (seconds) to days, clamping to minimum 1. */
    private fun expiryToDays(seconds: Long): Int =
        (seconds / 86_400L).toInt().coerceAtLeast(1)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {
            // The launch intent's click_id must be stored before the first-launch install
            // is sent (trackInstall waits for this); handleDeepLink comes later from the app.
            if (!firstActivity.isCompleted) {
                a.intent?.data?.takeIf { it.isHierarchical }?.let { uri ->
                    (uri.getQueryParameter("click_id") ?: uri.getQueryParameter("clickid") ?: uri.getQueryParameter("_ac_cid"))
                        ?.takeIf { it.isNotBlank() }
                        ?.let { storage.storeClickId("deeplink", it, config.clickExpiryDays) }
                }
                firstActivity.complete(Unit)
            }
        }
        override fun onActivityStarted(a: Activity) {
            val newSession = sessionManager.onForeground()
            storage.saveLastSessionStartTime()
            // One session_start event per session: the MMP's DAU / retention reports
            // count devices with any event on a day.
            if (newSession != null && config.autoSessionTracking) {
                logEvent(AffclixEvent.SESSION_START)
            }
        }
        override fun onActivityResumed(a: Activity)  {}
        override fun onActivityPaused(a: Activity)   {}
        override fun onActivityStopped(a: Activity)  { sessionManager.onBackground() }
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    // ── Diagnostic / Testing API ──────────────────────────────────────────────

    @JvmStatic fun runFraudAssessment()       = fraudDetector.assess()
    @JvmStatic fun runCtitAssessment(clickTs: Long?, installTs: Long?) =
        fraudDetector.assessCtit(clickTs, installTs)
    @JvmStatic fun getRetargetingState()      = retargeting.getState()
    @JvmStatic fun getContributorTouches()    = multiTouch.getTouches()
    @JvmStatic fun clearContributorTouches()  = multiTouch.clearTouches()
    @JvmStatic fun getInstallTime(): Long?    = if (initialized.get()) storage.getInstallTime() else null
    @JvmStatic fun getFirstTouchAttribution() = if (initialized.get()) attribution.getFirstTouchAttribution() else null
    @JvmStatic fun getLastTouchAttribution()  = if (initialized.get()) attribution.getLastTouchAttribution()  else null

    @JvmStatic fun recordTouchForTesting(mediaSource: String, campaign: String?, touchType: String) =
        multiTouch.recordTouch(mediaSource, campaign, touchType)
}
