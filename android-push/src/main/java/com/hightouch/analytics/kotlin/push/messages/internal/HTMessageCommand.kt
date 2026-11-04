package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.HTPushPayload.Keys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A ghost-push command parsed from the FCM `data` map. Read directly rather than through
 * [com.hightouch.analytics.kotlin.push.HTPushPayload], which requires `hightouch.messageId`: a
 * command-only push may omit it.
 *
 * Wire format: like every Hightouch push, the `data` map has a single `"hightouch"` entry holding
 * a JSON-encoded string, and the command is a nested object inside it:
 *
 *     {"messageId": "...", "command": {"verb": "syncMessages", "reason": "..."}, "messageContext": {...}}
 */
internal data class HTMessageCommand(
    val verb: Verb,
    /** `hightouch.messageId`, used only to attribute the `delivered` event. */
    val pushMessageId: String?,
    // Advisory only: logged on the `delivered` event, never used to decide what is stored or to
    // skip a fetch. Background pushes can be dropped, so only a full refresh converges.
    val reason: String?,
    val messageIds: List<String>?,
    val locationIds: List<String>?,
    val syncToken: String?,
    val messageContext: JsonObject?,
) {

    /**
     * Unrecognized verbs fail [parse], so older SDKs ignore verbs added later. The server sends
     * every inbox change, removals included, as [SYNC_MESSAGES].
     */
    enum class Verb(val wireValue: String) {
        SYNC_MESSAGES("syncMessages");

        companion object {
            fun fromWire(value: String): Verb? = values().firstOrNull { it.wireValue == value }
        }
    }

    companion object {
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parses an FCM `data` map. Returns null when the push carries no command: no `hightouch`
         * entry, malformed JSON, no `command` object, or a missing or unrecognized `verb`.
         */
        fun parse(data: Map<String, String>): HTMessageCommand? {
            val rawJson = data[Keys.HIGHTOUCH] ?: return null
            val root = (runCatching { lenientJson.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject)
                ?: return null
            val command = root[Keys.COMMAND] as? JsonObject ?: return null
            val verb = command[Keys.VERB].stringOrNull()?.let(Verb::fromWire) ?: return null

            return HTMessageCommand(
                verb = verb,
                pushMessageId = root[Keys.MESSAGE_ID].stringOrNull(),
                reason = command[Keys.REASON].stringOrNull(),
                messageIds = command[Keys.MESSAGE_IDS].stringListOrNull(),
                locationIds = command[Keys.LOCATION_IDS].stringListOrNull(),
                syncToken = command[Keys.SYNC_TOKEN].stringOrNull(),
                messageContext = root[Keys.MESSAGE_CONTEXT] as? JsonObject,
            )
        }

        /** A JSON string literal's content; numbers, booleans and null are not strings (iOS `as? String`). */
        private fun JsonElement?.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * All-or-nothing, like iOS `as? [String]`: one non-string element makes the whole hint
         * null, so a malformed advisory hint never sinks the parse.
         */
        private fun JsonElement?.stringListOrNull(): List<String>? {
            val array = this as? JsonArray ?: return null
            val strings = array.mapNotNull { it.stringOrNull() }
            return if (strings.size == array.size) strings else null
        }
    }
}
