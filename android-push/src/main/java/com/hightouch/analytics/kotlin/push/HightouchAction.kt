package com.hightouch.analytics.kotlin.push

/**
 * An action attached to a notification body tap or an action button.
 *
 * @param type the action type from the payload — `"openUrl"` for deep links, or any
 *   custom type your app understands.
 * @param data the URL string for `openUrl` actions, or arbitrary data for custom types.
 */
data class HightouchAction(
    val type: String,
    val data: String?,
)
