package com.hightouch.analytics.kotlin.push

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
 */
class HightouchPushConfig private constructor(
    val appId: String,
    val urlHandler: HightouchUrlHandler?,
    val customActionHandler: HightouchCustomActionHandler?,
    val allowedProtocols: List<String>,
    val notificationChannelId: String?,
    val smallIconResId: Int?,
    val notificationColorResId: Int?,
    val autoLaunchApp: Boolean,
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
        private var allowedProtocols: List<String> = emptyList()
        private var notificationChannelId: String? = null
        private var smallIconResId: Int? = null
        private var notificationColorResId: Int? = null
        private var autoLaunchApp: Boolean = true

        fun setUrlHandler(handler: HightouchUrlHandler?): Builder = apply { urlHandler = handler }

        fun setCustomActionHandler(handler: HightouchCustomActionHandler?): Builder =
            apply { customActionHandler = handler }

        fun setAllowedProtocols(protocols: List<String>): Builder =
            apply { allowedProtocols = protocols }

        fun setNotificationChannelId(id: String?): Builder = apply { notificationChannelId = id }

        fun setSmallIconResId(id: Int?): Builder = apply { smallIconResId = id }

        fun setNotificationColorResId(id: Int?): Builder = apply { notificationColorResId = id }

        fun setAutoLaunchApp(enabled: Boolean): Builder = apply { autoLaunchApp = enabled }

        fun build(): HightouchPushConfig = HightouchPushConfig(
            appId = appId,
            urlHandler = urlHandler,
            customActionHandler = customActionHandler,
            allowedProtocols = allowedProtocols,
            notificationChannelId = notificationChannelId,
            smallIconResId = smallIconResId,
            notificationColorResId = notificationColorResId,
            autoLaunchApp = autoLaunchApp,
        )
    }
}
