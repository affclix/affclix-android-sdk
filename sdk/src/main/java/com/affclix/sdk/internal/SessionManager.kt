package com.affclix.sdk.internal

import android.util.Log
import com.affclix.sdk.AffclixConfig
import com.affclix.sdk.model.Session
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// SessionManager
// ─────────────────────────────────────────────────────────────────────────────

internal class SessionManager(
    private val storage: StorageManager,
    private val config: AffclixConfig
) {
    private var currentSession: Session? = null
    private var backgroundedAt: Long = 0L
    private val timeoutMs get() = config.sessionTimeoutMinutes * 60_000L

    fun currentSessionId(): String = getOrCreateSession().id

    fun getOrCreateSession(): Session {
        val existing = currentSession ?: storage.getSession()
        if (existing != null) {
            val elapsed = System.currentTimeMillis() - existing.lastActivity
            if (elapsed < timeoutMs) {
                existing.lastActivity = System.currentTimeMillis()
                existing.pageviews++
                storage.saveSession(existing)
                currentSession = existing
                return existing
            }
        }
        return createNewSession()
    }

    /**
     * The app came to the foreground. Returns the session when it is one not yet
     * reported (a first open, or a return after [AffclixConfig.sessionTimeoutMinutes]),
     * so the SDK reports it once; null otherwise.
     */
    fun onForeground(): Session? {
        if (backgroundedAt > 0) Logger.d("SessionManager", "Foregrounded after ${System.currentTimeMillis() - backgroundedAt}ms")
        else Logger.d("SessionManager", "Foregrounded (first in this process)")
        val s = getOrCreateSession()
        if (storage.getReportedSessionId() == s.id) return null
        storage.saveReportedSessionId(s.id)
        return s
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
        currentSession?.let {
            it.lastActivity = backgroundedAt
            storage.saveSession(it)
        }
    }

    private fun createNewSession(): Session {
        val s = Session(
            id           = UUID.randomUUID().toString().replace("-", "").take(16),
            startedAt    = System.currentTimeMillis(),
            lastActivity = System.currentTimeMillis(),
            pageviews    = 1,
            isNew        = true
        )
        storage.saveSession(s)
        currentSession = s
        Logger.d("SessionManager", "New session: ${s.id}")
        return s
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Logger
// ─────────────────────────────────────────────────────────────────────────────

internal object Logger {
    var debugMode = false
    private const val TAG = "AffclixSDK"

    fun d(tag: String, msg: String) { if (debugMode) Log.d(TAG, "[$tag] $msg") }
    fun w(tag: String, msg: String) { if (debugMode) Log.w(TAG, "[$tag] $msg") }
    fun e(tag: String, msg: String) { Log.e(TAG, "[$tag] $msg") }
}

// ─────────────────────────────────────────────────────────────────────────────
// Exceptions
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Callback interfaces  (Java-friendly)
// ─────────────────────────────────────────────────────────────────────────────

interface TrackingCallback {
    fun onSuccess(id: String) {}
    fun onError(message: String) {}
    fun onQueued() {}
}

fun interface DeepLinkCallback {
    fun onDeepLinkResolved(result: com.affclix.sdk.model.DeepLinkResult)
}
