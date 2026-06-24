package com.hightouch.analytics.kotlin.push

/**
 * Handles custom action types from push notification action buttons (any action whose
 * `type` is not `"openUrl"`).
 *
 * Return true if your app handled the action.
 *
 * This is a Kotlin SAM (functional) interface, so callers can pass a lambda directly:
 * ```
 * HightouchPushConfig.Builder("app-id")
 *     .setCustomActionHandler { action, _ -> customRouter.route(action) }
 *     .build()
 * ```
 */
fun interface HightouchCustomActionHandler {
    fun handleAction(action: HightouchAction, context: HightouchActionContext): Boolean
}
