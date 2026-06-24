package com.hightouch.analytics.kotlin.push

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.firebase.messaging.FirebaseMessaging
import com.hightouch.analytics.kotlin.push.internal.CustomDataJson
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.android.Analytics as AndroidAnalytics
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.platform.plugins.setDeviceToken
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Main entry point for the Hightouch push SDK.
 *
 * Lifecycle (matches the iOS counterpart `HightouchPush.swift`):
 *   1. Host app calls [initialize] once at startup.
 *   2. FCM delivers a token (PR 5 wires this via [HightouchFirebaseMessagingService]). The SDK
 *      forwards it to [register], which persists the token and fires a `"CEP Push Token Events"`
 *      event with `provider_event_type = "registered"`.
 *   3. On login, host app calls [identify] with the user's id. This re-fires the registration
 *      event so the token can be associated with the new user.
 *   4. On logout, host app calls [logout]. The SDK fires `"CEP Push Token Events"` with
 *      `provider_event_type = "disabled"` and resets analytics state.
 *
 * Thread safety: state mutations are synchronized at the entry-point boundary. Identify does a
 * compound read-check-write on the current user id; the synchronization covers that sequence.
 */
object HightouchPush {

    private const val TAG = "HightouchPush"

    private var _analytics: Analytics? = null
    private var _config: HightouchPushConfig? = null
    private var _prefs: PushPreferences? = null
    private var _currentUserId: String? = null

    /** The currently identified user id, or null if none. */
    @JvmStatic
    val userId: String?
        @Synchronized get() = _currentUserId

    /** The stable anonymous id from the underlying analytics instance. */
    @JvmStatic
    val anonymousId: String
        @Synchronized get() = analyticsOrError().anonymousId()

    /**
     * The most recently registered FCM token, or null if none has been delivered. Useful for
     * sample / debug apps that want to display the token so a developer can send a test push.
     */
    @JvmStatic
    val fcmToken: String?
        @Synchronized get() = _prefs?.token

    /**
     * Intent extra (a JSON object string) carrying the push payload's `customData`, attached to
     * the launcher intent when a tap opens the host app. Read it with [getCustomData].
     */
    const val EXTRA_CUSTOM_DATA: String = "com.hightouch.analytics.kotlin.push.customData"

    /**
     * Returns the marketer-defined `customData` from a launch intent the SDK started in response
     * to a notification tap (open-app behavior), or null if the intent carries none. Call from
     * the launched activity (e.g. `getCustomData(intent)` in `onCreate`/`onNewIntent`) to route
     * based on campaign data. Mirrors iOS exposing `customData`.
     */
    @JvmStatic
    fun getCustomData(intent: Intent): Map<String, String>? =
        CustomDataJson.decode(intent.getStringExtra(EXTRA_CUSTOM_DATA))

    /**
     * Initialize with a write key. The SDK creates an internal [Analytics] instance.
     * Use this if the host app is not already using Hightouch Analytics.
     */
    @JvmStatic
    @Synchronized
    fun initialize(context: Context, writeKey: String, config: HightouchPushConfig) {
        val appContext = context.applicationContext
        val analytics = AndroidAnalytics(writeKey, appContext)
        initInternal(analytics, config, appContext)
    }

    /**
     * Initialize with an existing [Analytics] instance. Use this if the host app already uses
     * Hightouch Analytics — all push events go through the provided instance, no second
     * pipeline is created.
     *
     * Host apps using Analytics+Push should always go through [HightouchPush.identify]
     * (not `analytics.identify`) so token re-registration on login is not skipped.
     */
    @JvmStatic
    @Synchronized
    fun initialize(analytics: Analytics, config: HightouchPushConfig) {
        val appContext = (analytics.configuration.application as? Context)?.applicationContext
            ?: error(
                "[HightouchPush] Provided Analytics has no application context configured."
            )
        initInternal(analytics, config, appContext)
    }

    private fun initInternal(analytics: Analytics, config: HightouchPushConfig, appContext: Context) {
        _analytics = analytics
        _config = config
        _prefs = PushPreferences(appContext)
        _currentUserId = analytics.userId()
        // If we have a cached token from a prior process, make it visible on every event.
        _prefs?.token?.let { analytics.setDeviceToken(it) }
        // Proactively pull the current FCM token. `onNewToken` only fires on first-mint/rotation,
        // and that can happen before initialize() runs (FirebaseInitProvider starts FCM before
        // Application.onCreate; credentials may also be supplied at runtime). In those cases
        // handleTokenRefresh() already dropped the token and FCM won't call again until the next
        // rotation. getToken() recovers the already-minted token regardless of timing.
        fetchCurrentFcmToken()
    }

    /**
     * Fetch the current FCM token and [register] it if it differs from what we've persisted.
     * Complements [HightouchFirebaseMessagingService.onNewToken], which only covers
     * first-mint/rotation; this covers the steady state and late initialization.
     */
    private fun fetchCurrentFcmToken() {
        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (e: IllegalStateException) {
            // The default FirebaseApp isn't initialized — e.g. no google-services config, or
            // credentials are supplied at runtime and FirebaseApp.initializeApp hasn't run yet.
            // Don't crash the host app; onNewToken will deliver the token once Firebase is up.
            Log.w(TAG, "FirebaseApp not initialized; skipping FCM token fetch", e)
            return
        }
        messaging.token
            .addOnSuccessListener { token ->
                // Skip if unchanged so we don't re-fire the "registered" event on every launch.
                if (!token.isNullOrBlank() && token != _prefs?.token) {
                    register(token)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to fetch FCM token on init", e)
            }
    }

    /**
     * Register an FCM token. Called by [HightouchFirebaseMessagingService] when FCM delivers
     * a token via `onNewToken`, and by [identify] on user switch.
     *
     * Persists the token, attaches it to all subsequent events via `context.device.token`, and
     * fires the `"CEP Push Token Events"` event with `provider_event_type = "registered"`.
     */
    @JvmStatic
    @Synchronized
    fun register(token: String) {
        val prefs = _prefs ?: error("[HightouchPush] Call initialize() before register().")
        val analytics = analyticsOrError()
        prefs.token = token
        analytics.setDeviceToken(token)
        CepEventTracking.track(
            name = CepEventTracking.PUSH_TOKEN_EVENTS,
            properties = buildJsonObject {
                put("provider_event_type", CepEventTracking.TOKEN_REGISTERED)
                put("token", token)
                put("platform", "android")
            },
        )
    }

    /**
     * Identify the current user.
     *
     * Beyond `analytics.identify(userId)`, this:
     *  1. Re-fires the `"registered"` token event (if a token has already been delivered) so the
     *     token can be associated with the new user.
     *  2. Detects user-switch — if a different user was previously identified, calls [logout]
     *     first to cleanly disassociate the old user's token. This also resets analytics
     *     state (which generates a new anonymous id).
     */
    @JvmStatic
    @Synchronized
    fun identify(userId: String) {
        val current = _currentUserId
        if (current != null && current != userId) {
            logout()
        }
        _currentUserId = userId
        analyticsOrError().identify(userId)
        // Re-fire the token event so the new user shows up on the registration.
        _prefs?.token?.let { register(it) }
    }

    /**
     * Log out the current user.
     *
     * Fires `"CEP Push Token Events"` with `provider_event_type = "disabled"` so this token is
     * disassociated from the user, then resets analytics state. A no-op when no user
     * is identified.
     */
    @JvmStatic
    @Synchronized
    fun logout() {
        val outgoingUserId = _currentUserId ?: return
        val analytics = analyticsOrError()
        _prefs?.token?.let { token ->
            CepEventTracking.track(
                name = CepEventTracking.PUSH_TOKEN_EVENTS,
                properties = buildJsonObject {
                    put("provider_event_type", CepEventTracking.TOKEN_DISABLED)
                    put("token", token)
                    put("userId", outgoingUserId)
                },
            )
        }
        _currentUserId = null
        analytics.reset()
    }

    /** Internal accessor for [CepEventTracking] to fetch the active analytics instance. */
    internal val cepAnalytics: Analytics? @Synchronized get() = _analytics

    /** Internal accessor for [CepEventTracking] to read the configured app id. */
    internal val cepAppId: String @Synchronized get() = _config?.appId.orEmpty()

    /** Internal accessor for notification rendering to read the configured channel id. */
    internal val cepChannelId: String? @Synchronized get() = _config?.notificationChannelId

    /** Internal accessor for notification rendering to read the configured small icon. */
    internal val cepSmallIconResId: Int? @Synchronized get() = _config?.smallIconResId

    /** Internal accessor for notification rendering to read the configured accent color. */
    internal val cepColorResId: Int? @Synchronized get() = _config?.notificationColorResId

    /**
     * Internal accessor for the action router. Defaults to true when no config is set so a tap
     * arriving before (re)initialization still opens the app.
     */
    internal val cepAutoLaunchApp: Boolean @Synchronized get() = _config?.autoLaunchApp ?: true

    /** Internal accessor exposing the full config to the action router. */
    internal val configForRouting: HightouchPushConfig? @Synchronized get() = _config

    private fun analyticsOrError(): Analytics =
        _analytics ?: error("[HightouchPush] Call initialize() before using the SDK.")

    @VisibleForTesting
    @Synchronized
    internal fun resetForTesting() {
        _analytics = null
        _config = null
        _prefs = null
        _currentUserId = null
    }
}
