package com.hightouch.analytics.kotlin.push

/**
 * Context passed to handlers describing how the action was triggered.
 *
 * @param source whether the tap came from the notification body or a named action button.
 * @param customData the marketer-defined key/value pairs from the push payload's `customData`,
 *   or null if the message carried none. Lets a handler branch on campaign-specific data.
 */
data class HightouchActionContext(
    val source: HightouchActionSource,
    val customData: Map<String, String>? = null,
)
