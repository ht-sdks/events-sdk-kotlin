package com.hightouch.analytics.kotlin.push.messages.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps the raw `priority` string to a sortable rank (lower first); unrecognized values sort last.
 * Mirrors iOS `MessagePriority`.
 */
internal object MessagePriority {
    /** `piority` is a misspelling from the message spec, accepted in case the server emits it. */
    val payloadKeys = listOf("priority", "piority")

    /** Rank for anything unparseable (iOS `Int32.max`). */
    const val UNRANKED = Int.MAX_VALUE

    /** A JSON string as-is, or an integral JSON number as its integer spelling; otherwise null. */
    fun rawValue(json: JsonElement?): String? {
        if (json !is JsonPrimitive) return null
        if (json.isString) return json.content
        val number = json.content.toBigDecimalOrNull() ?: return null
        // A fractional priority has no defined meaning; treat it as unrecognized.
        val integral = number.stripTrailingZeros()
        if (integral.scale() > 0) return null
        return integral.setScale(0).toPlainString()
    }

    fun rank(priority: String?): Int {
        val trimmed = priority?.trim()
        if (trimmed.isNullOrEmpty()) return UNRANKED

        trimmed.toIntOrNull()?.let { return it }

        return when (trimmed.lowercase()) {
            "urgent", "critical" -> 0
            "high" -> 100
            "normal", "medium", "default" -> 200
            "low" -> 300
            else -> UNRANKED
        }
    }
}
