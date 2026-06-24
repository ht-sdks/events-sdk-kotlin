package com.hightouch.analytics.kotlin.push.internal

/**
 * String-extra keys used to carry payload data through the notification → TrampolineActivity →
 * ActionRouter hop. Centralized so the producer (HightouchNotificationPresenter) and consumer (ActionRouter)
 * never disagree.
 */
internal object IntentExtras {
    const val MESSAGE_ID = "com.hightouch.analytics.kotlin.push.MESSAGE_ID"
    /** Action-button identifier; absent for a body tap. */
    const val SOURCE_BUTTON_ID = "com.hightouch.analytics.kotlin.push.SOURCE_BUTTON_ID"
    const val ACTION_TYPE = "com.hightouch.analytics.kotlin.push.ACTION_TYPE"
    const val ACTION_DATA = "com.hightouch.analytics.kotlin.push.ACTION_DATA"
    /** Opaque messageContext JSON object, serialized as a string. */
    const val MESSAGE_CONTEXT_JSON = "com.hightouch.analytics.kotlin.push.MESSAGE_CONTEXT_JSON"
    /** Marketer-defined customData, serialized as a JSON object string. */
    const val CUSTOM_DATA_JSON = "com.hightouch.analytics.kotlin.push.CUSTOM_DATA_JSON"
    /** The notification tag the SDK posted with, or absent if posted without a tag. */
    const val NOTIFICATION_TAG = "com.hightouch.analytics.kotlin.push.NOTIFICATION_TAG"
    /** The notification id the SDK posted with, so taps dismiss the right notification. */
    const val NOTIFICATION_ID = "com.hightouch.analytics.kotlin.push.NOTIFICATION_ID"
    /** True if this intent originated from an openApp=false action button. */
    const val FROM_ACTION_RECEIVER = "com.hightouch.analytics.kotlin.push.FROM_ACTION_RECEIVER"
}
