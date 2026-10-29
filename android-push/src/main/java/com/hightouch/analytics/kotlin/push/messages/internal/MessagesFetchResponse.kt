package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Reported once per sync when some entries in an otherwise-successful fetch failed to decode. */
internal class MalformedMessagesError(val count: Int) :
    Exception("$count message(s) in the response could not be decoded")

/**
 * A 2xx body with no `messages` array. Failing the sync matters: parsing it as empty would make
 * `replaceAll` delete every cached message for the owner.
 */
internal class MissingMessagesArrayError :
    Exception("The response did not contain a \"messages\" array")

/**
 * The decoded result of a messages fetch. Treated as the authoritative full set for the owner:
 * `replaceAll` deletes anything it omits, so a paged response would lose data.
 * Mirrors iOS `MessagesFetchResponse`.
 */
internal class MessagesFetchResponse(
    val messages: List<HightouchMessage>,
    val syncedAtMillis: Long?,
    val malformedCount: Int,
) {
    companion object {
        /**
         * Accepts `{"messages": [...]}` or a bare array; anything else throws. A malformed entry
         * is counted and dropped; it never fails the whole sync.
         */
        fun parse(data: ByteArray): MessagesFetchResponse {
            val envelope = Json.parseToJsonElement(data.toString(Charsets.UTF_8))

            val rawMessages: JsonArray
            var syncedAtMillis: Long? = null
            when (envelope) {
                is JsonArray -> rawMessages = envelope
                is JsonObject -> {
                    rawMessages = envelope["messages"] as? JsonArray ?: throw MissingMessagesArrayError()
                    syncedAtMillis = MessagesISO8601.dateMillis(envelope["syncedAt"])
                }
                else -> throw MissingMessagesArrayError()
            }

            val messages = ArrayList<HightouchMessage>(rawMessages.size)
            var malformedCount = 0
            for (raw in rawMessages) {
                try {
                    messages.add(HightouchMessage.fromRaw(raw))
                } catch (_: Exception) {
                    malformedCount++
                }
            }
            return MessagesFetchResponse(messages, syncedAtMillis, malformedCount)
        }
    }
}
