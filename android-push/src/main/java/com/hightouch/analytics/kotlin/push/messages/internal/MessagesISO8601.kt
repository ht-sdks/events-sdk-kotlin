package com.hightouch.analytics.kotlin.push.messages.internal

import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses timestamps with or without fractional seconds into epoch milliseconds. Hand-rolled
 * because `java.time` needs API 26 (minSdk is 21) and `SimpleDateFormat`'s `X` pattern needs API 24.
 * Mirrors iOS `MessagesISO8601`.
 */
internal object MessagesISO8601 {
    private val pattern = Regex(
        """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})"""
    )

    /** Accepts an ISO8601 string or a numeric epoch in seconds. Returns epoch millis. */
    fun dateMillis(json: JsonElement?): Long? {
        if (json !is JsonPrimitive) return null
        if (json.isString) {
            return if (json.content.isEmpty()) null else parse(json.content)
        }
        val seconds = json.content.toDoubleOrNull() ?: return null
        if (seconds.isNaN() || seconds.isInfinite()) return null
        return Math.round(seconds * 1000.0)
    }

    fun parse(text: String): Long? {
        val m = pattern.matchEntire(text) ?: return null
        val g = m.groupValues
        val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').substring(0, 3).toInt()
        val offsetMinutes = if (g[8] == "Z") {
            0
        } else {
            val digits = g[8].substring(1).replace(":", "")
            val total = digits.substring(0, 2).toInt() * 60 + digits.substring(2).toInt()
            if (g[8][0] == '-') -total else total
        }
        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            calendar.set(Calendar.MILLISECOND, millis)
            calendar.timeInMillis - offsetMinutes * 60_000L
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
