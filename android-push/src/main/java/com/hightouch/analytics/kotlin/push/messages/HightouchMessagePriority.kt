package com.hightouch.analytics.kotlin.push.messages

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A message's priority. A missing, empty, or unrecognized `priority` decodes to [DEFAULT].
 * Mirrors iOS `HightouchMessagePriority`.
 *
 * @property rawValue The payload spelling.
 */
enum class HightouchMessagePriority(val rawValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    DEFAULT("default"),
    ;

    /** Sort order, lower first. [DEFAULT] sorts after every explicitly set priority. */
    internal val rank: Int
        get() = when (this) {
            HIGH -> 0
            MEDIUM -> 1
            LOW -> 2
            DEFAULT -> 3
        }

    companion object {
        /** Matches the payload spelling exactly; anything else is [DEFAULT]. */
        fun fromPayload(payloadValue: JsonElement?): HightouchMessagePriority {
            val value = payloadValue as? JsonPrimitive
            if (value == null || !value.isString) return DEFAULT
            return entries.firstOrNull { it.rawValue == value.content } ?: DEFAULT
        }
    }
}
