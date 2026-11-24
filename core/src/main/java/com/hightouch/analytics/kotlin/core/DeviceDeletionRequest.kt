package com.hightouch.analytics.kotlin.core

import kotlinx.serialization.json.JsonPrimitive

/**
 * The one event [Analytics] still sends while data collection is off: the push module's request to
 * delete this device's record. The push module builds the event from these values, so the check
 * here and the event it sends cannot drift apart.
 */
@InternalHightouchApi
object DeviceDeletionRequest {
    const val EVENT_NAME = "CEP Push Token Events"
    const val EVENT_TYPE_PROPERTY = "provider_event_type"
    const val EVENT_TYPE = "deleted"

    internal fun matches(event: BaseEvent): Boolean {
        if (event !is TrackEvent || event.event != EVENT_NAME) return false
        val eventType = event.properties[EVENT_TYPE_PROPERTY] as? JsonPrimitive
        return eventType?.content == EVENT_TYPE
    }
}
