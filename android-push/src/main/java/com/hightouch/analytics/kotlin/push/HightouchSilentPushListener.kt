package com.hightouch.analytics.kotlin.push

/**
 * Receives the `customData` of silent (background data) pushes.
 *
 * A silent push displays no notification — the SDK suppresses display and delivers the
 * marketer-defined `customData` to this listener instead. The listener fires only when the
 * silent push carries non-empty `customData`; a silent push without it is consumed with no
 * callback (there is nothing to deliver).
 *
 * Register via [HightouchPushConfig.Builder.setSilentPushListener] and make sure
 * [HightouchPush.initialize] runs in `Application.onCreate` — FCM can cold-start the app
 * process in the background to deliver a silent push, and a listener registered later
 * (e.g. in an Activity) would miss it.
 *
 * Called on a background thread (FCM's message-handling thread). Keep work short; for
 * anything long-running, hand off to your own scheduler (e.g. WorkManager).
 *
 * This is a Kotlin SAM (functional) interface, so callers can pass a lambda directly:
 * ```
 * HightouchPushConfig.Builder("app-id")
 *     .setSilentPushListener { customData -> syncManager.apply(customData) }
 *     .build()
 * ```
 */
fun interface HightouchSilentPushListener {
    fun onSilentPush(customData: Map<String, String>)
}
