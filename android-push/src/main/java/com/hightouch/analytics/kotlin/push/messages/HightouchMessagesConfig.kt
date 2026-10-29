package com.hightouch.analytics.kotlin.push.messages

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * The messages fetch request, handed to [HightouchMessagesConfig.requestFactory] last so it can
 * rewrite the URL or headers (for example to route through a proxy).
 */
data class HightouchMessagesRequest(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Configuration for embedded messages. Pass it to [com.hightouch.analytics.kotlin.push.HightouchPushConfig.Builder.setMessages]
 * to enable them. Construct via [Builder]; every field is optional.
 */
class HightouchMessagesConfig private constructor(
    listener: HightouchMessagesListener?,
    /** Bare host with no scheme. Defaults to [DEFAULT_HOST]. `https` is used except for a loopback host. */
    val messagesHost: String?,
    /** Leading slash, no trailing slash. */
    val messagesPath: String,
    /** Reconcile with the server whenever the app comes to the foreground, subject to the cooldown. */
    val syncOnForeground: Boolean,
    /**
     * Reconcile with the server on every [com.hightouch.analytics.kotlin.push.HightouchPush.initialize],
     * bypassing the cooldown. Turning this off means an app that was killed when a silent push arrived
     * waits for the next sync trigger to catch up.
     */
    val syncOnLaunch: Boolean,
    /** Minimum time between foreground syncs for the same user. Clamped to at least [MIN_COOLDOWN_MILLIS]. */
    val foregroundSyncCooldownMillis: Long,
    /**
     * Maximum messages kept locally across all users. A sync keeps the current user's
     * highest-priority, newest messages up to this cap, then evicts other users' oldest-inserted
     * messages to stay under it.
     */
    val maxStoredMessages: Int,
    /** Applied last to every fetch request, e.g. to route through a proxy. */
    val requestFactory: ((HightouchMessagesRequest) -> HightouchMessagesRequest)?,
) {
    private val listenerRef: WeakReference<HightouchMessagesListener>? = listener?.let { WeakReference(it) }

    /** The change listener, or null if none was set or it has been garbage collected. Held weakly. */
    val listener: HightouchMessagesListener? get() = listenerRef?.get()

    class Builder {
        private var listener: HightouchMessagesListener? = null
        private var messagesHost: String? = null
        private var messagesPath: String = DEFAULT_PATH
        private var syncOnForeground: Boolean = false
        private var syncOnLaunch: Boolean = true
        private var foregroundSyncCooldownMillis: Long = DEFAULT_FOREGROUND_COOLDOWN_MILLIS
        private var maxStoredMessages: Int = DEFAULT_MAX_STORED_MESSAGES
        private var requestFactory: ((HightouchMessagesRequest) -> HightouchMessagesRequest)? = null

        /** Held weakly: keep a strong reference elsewhere. See [HightouchMessagesListener]. */
        fun setListener(listener: HightouchMessagesListener?): Builder = apply { this.listener = listener }

        fun setMessagesHost(host: String?): Builder = apply { messagesHost = host }

        fun setMessagesPath(path: String): Builder = apply { messagesPath = path }

        fun setSyncOnForeground(enabled: Boolean): Builder = apply { syncOnForeground = enabled }

        fun setSyncOnLaunch(enabled: Boolean): Builder = apply { syncOnLaunch = enabled }

        fun setForegroundSyncCooldown(millis: Long): Builder =
            apply { foregroundSyncCooldownMillis = millis }

        fun setMaxStoredMessages(max: Int): Builder = apply { maxStoredMessages = max }

        fun setRequestFactory(factory: ((HightouchMessagesRequest) -> HightouchMessagesRequest)?): Builder =
            apply { requestFactory = factory }

        fun build(): HightouchMessagesConfig = HightouchMessagesConfig(
            listener = listener,
            messagesHost = messagesHost,
            messagesPath = messagesPath,
            syncOnForeground = syncOnForeground,
            syncOnLaunch = syncOnLaunch,
            foregroundSyncCooldownMillis = foregroundSyncCooldownMillis,
            maxStoredMessages = maxStoredMessages,
            requestFactory = requestFactory,
        )
    }

    companion object {
        /** Production routes the `/v1/inapp` paths on this host to the gateway. Not the events `apiHost`. */
        const val DEFAULT_HOST: String = "api.hightouch.com"
        const val DEFAULT_PATH: String = "/v1/inapp/messages"
        const val DEFAULT_MAX_STORED_MESSAGES: Int = 500

        @JvmField
        val DEFAULT_FOREGROUND_COOLDOWN_MILLIS: Long = TimeUnit.MINUTES.toMillis(15)

        @JvmField
        val MIN_COOLDOWN_MILLIS: Long = TimeUnit.MINUTES.toMillis(1)
    }
}
