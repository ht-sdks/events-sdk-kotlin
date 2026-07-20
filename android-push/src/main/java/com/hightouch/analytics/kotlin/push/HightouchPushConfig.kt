package com.hightouch.analytics.kotlin.push

import java.util.concurrent.TimeUnit

/**
 * Configuration for the Hightouch push SDK. Construct via [Builder] and pass to
 * [HightouchPush.initialize].
 *
 * @param appId the push app ID assigned by Hightouch. Included as a property on every
 *   push event the SDK emits.
 * @param urlHandler handles deep links / `openUrl` actions. If null or returns false,
 *   the SDK falls back to `Intent.ACTION_VIEW` for `https` schemes and schemes listed
 *   in [allowedProtocols].
 * @param customActionHandler handles non-URL action types from notification buttons.
 * @param silentPushListener receives the `customData` of silent (background data) pushes.
 *   Must be registered before a silent push arrives — initialize the SDK with it in
 *   `Application.onCreate`, since FCM can cold-start the app process in the background.
 *   See [HightouchSilentPushListener].
 * @param allowedProtocols additional URL schemes the SDK is allowed to open via
 *   `Intent.ACTION_VIEW`. The `https` scheme is always allowed. Add others
 *   (e.g. `"myapp"`, `"tel"`, `"sms"`) to opt them in.
 * @param notificationChannelId optional override for the default notification channel
 *   id. Defaults to `"hightouch_default"`.
 * @param smallIconResId optional drawable resource id for the small notification icon.
 *   Defaults to the application icon.
 * @param notificationColorResId optional color resource id for the notification accent.
 * @param autoLaunchApp when true (the default), tapping a notification body — or a foreground
 *   (`openApp=true`) action button — launches the host app if no handler claims the tap. Set to
 *   false if your app drives all navigation itself and you never want the SDK to start the
 *   launcher activity.
 * @param tokenUploadIntervalMillis how long a token registration stays "fresh" before the SDK
 *   re-uploads it on the next cold start even when the token is unchanged, keeping the server's
 *   `last_seen_at` a real liveness signal. Defaults to [DEFAULT_TOKEN_UPLOAD_INTERVAL_MILLIS] (24h)
 *   and is clamped to a minimum of [MIN_TOKEN_UPLOAD_INTERVAL_MILLIS] (12h); there is intentionally
 *   no way to disable it.
 */
class HightouchPushConfig private constructor(
    val appId: String,
    val urlHandler: HightouchUrlHandler?,
    val customActionHandler: HightouchCustomActionHandler?,
    val silentPushListener: HightouchSilentPushListener?,
    val allowedProtocols: List<String>,
    val notificationChannelId: String?,
    val smallIconResId: Int?,
    val notificationColorResId: Int?,
    val autoLaunchApp: Boolean,
    val tokenUploadIntervalMillis: Long,
) {
    /**
     * Fluent builder for [HightouchPushConfig]. The required [appId] is the constructor
     * argument; all other fields are optional and have sensible defaults.
     *
     * Java callers:
     * ```
     * HightouchPushConfig config = new HightouchPushConfig.Builder("app-id")
     *     .setUrlHandler((url, ctx) -> router.handle(url))
     *     .setAllowedProtocols(Arrays.asList("myapp"))
     *     .build();
     * ```
     *
     * Kotlin callers:
     * ```
     * val config = HightouchPushConfig.Builder("app-id")
     *     .setUrlHandler { url, _ -> router.handle(url) }
     *     .setAllowedProtocols(listOf("myapp"))
     *     .build()
     * ```
     */
    class Builder(private val appId: String) {
        private var urlHandler: HightouchUrlHandler? = null
        private var customActionHandler: HightouchCustomActionHandler? = null
        private var silentPushListener: HightouchSilentPushListener? = null
        private var allowedProtocols: List<String> = emptyList()
        private var notificationChannelId: String? = null
        private var smallIconResId: Int? = null
        private var notificationColorResId: Int? = null
        private var autoLaunchApp: Boolean = true
        private var tokenUploadIntervalMillis: Long = DEFAULT_TOKEN_UPLOAD_INTERVAL_MILLIS

        fun setUrlHandler(handler: HightouchUrlHandler?): Builder = apply { urlHandler = handler }

        fun setCustomActionHandler(handler: HightouchCustomActionHandler?): Builder =
            apply { customActionHandler = handler }

        fun setSilentPushListener(listener: HightouchSilentPushListener?): Builder =
            apply { silentPushListener = listener }

        fun setAllowedProtocols(protocols: List<String>): Builder =
            apply { allowedProtocols = protocols }

        fun setNotificationChannelId(id: String?): Builder = apply { notificationChannelId = id }

        fun setSmallIconResId(id: Int?): Builder = apply { smallIconResId = id }

        fun setNotificationColorResId(id: Int?): Builder = apply { notificationColorResId = id }

        fun setAutoLaunchApp(enabled: Boolean): Builder = apply { autoLaunchApp = enabled }

        /**
         * Override the token re-upload heartbeat interval. Values below
         * [MIN_TOKEN_UPLOAD_INTERVAL_MILLIS] are clamped up to it in [build]. See
         * [HightouchPushConfig.tokenUploadIntervalMillis].
         */
        fun setTokenUploadInterval(millis: Long): Builder =
            apply { tokenUploadIntervalMillis = millis }

        fun build(): HightouchPushConfig = HightouchPushConfig(
            appId = appId,
            urlHandler = urlHandler,
            customActionHandler = customActionHandler,
            silentPushListener = silentPushListener,
            allowedProtocols = allowedProtocols,
            notificationChannelId = notificationChannelId,
            smallIconResId = smallIconResId,
            notificationColorResId = notificationColorResId,
            autoLaunchApp = autoLaunchApp,
            tokenUploadIntervalMillis =
                tokenUploadIntervalMillis.coerceAtLeast(MIN_TOKEN_UPLOAD_INTERVAL_MILLIS),
        )
    }

    companion object {
        /** Default heartbeat interval (24h). */
        @JvmField
        val DEFAULT_TOKEN_UPLOAD_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

        /** Lower bound the heartbeat interval is clamped to, to avoid per-launch re-uploads. */
        @JvmField
        val MIN_TOKEN_UPLOAD_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(12)
    }
}
