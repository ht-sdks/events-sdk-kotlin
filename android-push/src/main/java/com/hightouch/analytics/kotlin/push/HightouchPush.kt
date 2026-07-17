package com.hightouch.analytics.kotlin.push

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
    private var _lifecycleObserver: DefaultLifecycleObserver? = null

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
        // rotation. getToken() recovers the already-minted token regardless of timing. This is the
        // process-start (incl. background-start) leg of the upload heartbeat.
        fetchCurrentFcmToken()
        registerForegroundHeartbeat()
    }

    /**
     * Observe app foreground transitions so the token-upload heartbeat also fires when a
     * long-lived process is brought back to the foreground past the TTL — not only on cold start.
     * Without it the heartbeat cadence is bound to how often the OS creates a fresh process, so a
     * resident process could sit past the TTL without re-uploading. The re-upload stays gated by
     * [shouldUploadToken] (via [fetchCurrentFcmToken]), so foregrounds within the interval are
     * no-ops.
     *
     * Idempotent — only the first call registers. Registration is posted to the main thread
     * because [ProcessLifecycleOwner] requires it, regardless of which thread called [initialize].
     */
    private fun registerForegroundHeartbeat() {
        if (_lifecycleObserver != null) return
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                fetchCurrentFcmToken()
            }
        }
        _lifecycleObserver = observer
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    /**
     * Fetch the current FCM token and [register] it when the token has changed or the re-upload
     * heartbeat has elapsed (see [shouldUploadToken]). Complements
     * [HightouchFirebaseMessagingService.onNewToken], which only covers first-mint/rotation; this
     * covers the steady state and late initialization.
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
                if (!token.isNullOrBlank()) registerIfDue(token)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to fetch FCM token on init", e)
            }
    }

    /**
     * Atomically decide (via [shouldUploadToken]) and, if due, [register] the token. Re-uploads on
     * token change (a rotation the OS didn't surface via `onNewToken`) or once the heartbeat
     * elapses, so an unchanged token refreshes its liveness signal instead of being deduped
     * forever.
     *
     * Synchronized so the check-and-upload is a single critical section. [initialize]'s direct
     * fetch and the foreground observer can both resolve a token fetch on the same launch; without
     * atomicity both could observe a stale timestamp and upload, firing a duplicate "registered".
     */
    @Synchronized
    private fun registerIfDue(token: String) {
        val prefs = _prefs ?: return
        val intervalMillis = _config?.tokenUploadIntervalMillis
            ?: HightouchPushConfig.DEFAULT_TOKEN_UPLOAD_INTERVAL_MILLIS
        if (shouldUploadToken(
                incomingToken = token,
                lastUploadedToken = prefs.token,
                lastUploadedAtMillis = prefs.lastUploadedAtMillis,
                nowMillis = System.currentTimeMillis(),
                intervalMillis = intervalMillis,
            )
        ) {
            register(token)
        }
    }

    /**
     * Whether a cold-start token fetch should re-upload (fire a "registered" event). True when the
     * token changed since the last upload, or when the heartbeat interval has elapsed since it.
     * A [lastUploadedAtMillis] of `0` (never uploaded) always uploads. Pure so it is unit-testable
     * without the FCM/analytics machinery.
     */
    @VisibleForTesting
    internal fun shouldUploadToken(
        incomingToken: String,
        lastUploadedToken: String?,
        lastUploadedAtMillis: Long,
        nowMillis: Long,
        intervalMillis: Long,
    ): Boolean =
        incomingToken != lastUploadedToken || (nowMillis - lastUploadedAtMillis) >= intervalMillis

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
        prefs.lastUploadedAtMillis = System.currentTimeMillis()
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

    /** Internal accessor for silent-push delivery to read the configured listener. */
    internal val cepSilentPushListener: HightouchSilentPushListener?
        @Synchronized get() = _config?.silentPushListener

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

    /** True once the process-foreground heartbeat observer has been registered. */
    @VisibleForTesting
    internal val hasForegroundHeartbeatObserver: Boolean
        @Synchronized get() = _lifecycleObserver != null

    @VisibleForTesting
    @Synchronized
    internal fun resetForTesting() {
        _analytics = null
        _config = null
        _prefs = null
        _currentUserId = null
        _lifecycleObserver?.let { observer ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            } catch (_: Throwable) {
                // ProcessLifecycleOwner may be uninitialized in a bare test env; ignore.
            }
        }
        _lifecycleObserver = null
    }
}
