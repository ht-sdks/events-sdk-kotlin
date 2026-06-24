package com.hightouch.analytics.kotlin.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed view of the Hightouch push payload that arrives in `RemoteMessage.data`.
 *
 * FCM `data` payloads are flat `Map<String, String>` — every value is a string. The
 * structured Hightouch payload arrives as a single JSON-encoded string under the
 * `"hightouch"` key. This type parses that wrapper.
 *
 * The payload schema is identical to the iOS APNs counterpart (see
 * `HTPushPayload.swift`). Parsing returns null if the wrapper is absent, the JSON is
 * malformed, or the required `messageId` field is missing.
 */
internal data class HTPushPayload(
    val messageId: String,
    val attachmentUrl: String?,
    val defaultAction: HightouchAction?,
    val actionButtons: List<HTActionButton>?,
    /**
     * Opaque round-trip context from `hightouch.messageContext`. The SDK merges these
     * keys into engagement events without interpreting them.
     */
    val messageContext: JsonObject?,
    val customData: Map<String, String>?,
    /**
     * Android-only extension fields. These are always present in the payload; any may be
     * null when the corresponding campaign field was left blank.
     */
    val notificationChannel: String?,
    val groupKey: String?,
    val notificationTag: String?,
    val sound: String?,
) {
    companion object {
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parses an FCM `data` map. Returns null if the payload is not a Hightouch
         * push (i.e. no `"hightouch"` key, malformed JSON, or missing `messageId`).
         */
        fun parse(data: Map<String, String>): HTPushPayload? {
            val rawJson = data[Keys.HIGHTOUCH] ?: return null
            val root = runCatching {
                lenientJson.parseToJsonElement(rawJson).jsonObject
            }.getOrNull() ?: return null

            val messageId = root[Keys.MESSAGE_ID]?.jsonPrimitive?.contentOrNull
                ?: return null

            val buttons = root[Keys.ACTION_BUTTONS]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { element ->
                    runCatching { element.jsonObject }.getOrNull()?.let(HTActionButton::parse)
                }

            return HTPushPayload(
                messageId = messageId,
                attachmentUrl = root[Keys.ATTACHMENT_URL]?.jsonPrimitive?.contentOrNull,
                defaultAction = parseAction(root[Keys.DEFAULT_ACTION]),
                actionButtons = buttons,
                messageContext = root[Keys.MESSAGE_CONTEXT]
                    ?.let { runCatching { it.jsonObject }.getOrNull() },
                customData = parseCustomData(root[Keys.CUSTOM_DATA]),
                notificationChannel = root[Keys.NOTIFICATION_CHANNEL]?.jsonPrimitive?.contentOrNull,
                groupKey = root[Keys.GROUP_KEY]?.jsonPrimitive?.contentOrNull,
                notificationTag = root[Keys.NOTIFICATION_TAG]?.jsonPrimitive?.contentOrNull,
                sound = root[Keys.SOUND]?.jsonPrimitive?.contentOrNull,
            )
        }

        /**
         * Parses `hightouch.customData` into a `Map<String, String>`.
         *
         * All-or-nothing, mirroring iOS `customData = raw[...] as? [String: String]`: the map
         * survives only if every value is a JSON string. A single non-string value (number,
         * bool, nested object/array) voids the whole map and yields null — never a partial map
         * and, importantly, never a thrown exception (an earlier version called `jsonPrimitive`,
         * which throws on objects/arrays and would crash the FCM message thread).
         */
        fun parseCustomData(element: JsonElement?): Map<String, String>? {
            val obj = element?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
            val strings = obj.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it }
            }
            return if (strings.size == obj.size) strings.toMap() else null
        }

        /** Parses a `{ type, data }` JSON object into a [HightouchAction]. */
        fun parseAction(element: JsonElement?): HightouchAction? {
            val obj = element?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
            val type = obj[Keys.TYPE]?.jsonPrimitive?.contentOrNull ?: return null
            val data = obj[Keys.DATA]?.jsonPrimitive?.contentOrNull
            return HightouchAction(type, data)
        }
    }

    /** Key names that match the iOS implementation's `PayloadKey` enum. */
    internal object Keys {
        const val HIGHTOUCH = "hightouch"
        const val MESSAGE_ID = "messageId"
        const val ATTACHMENT_URL = "attachmentUrl"
        const val DEFAULT_ACTION = "defaultAction"
        const val ACTION_BUTTONS = "actionButtons"
        const val MESSAGE_CONTEXT = "messageContext"
        const val CUSTOM_DATA = "customData"
        const val NOTIFICATION_CHANNEL = "notificationChannel"
        const val GROUP_KEY = "groupKey"
        const val NOTIFICATION_TAG = "notificationTag"
        const val SOUND = "sound"
        const val IDENTIFIER = "identifier"
        const val TITLE = "title"
        const val ACTION = "action"
        const val TYPE = "type"
        const val DATA = "data"
        const val BUTTON_TYPE = "buttonType"
        const val OPEN_APP = "openApp"
        const val REQUIRES_UNLOCK = "requiresUnlock"
        const val INPUT_TITLE = "inputTitle"
        const val INPUT_PLACEHOLDER = "inputPlaceholder"
    }
}

/**
 * One action button parsed from the `hightouch.actionButtons` array.
 *
 * Rendering fields (title, buttonType, icons) are kept on [rawJson] so the notification
 * layer (PR 6) can read them without re-parsing.
 */
internal data class HTActionButton(
    val identifier: String,
    val action: HightouchAction?,
    val title: String?,
    val buttonType: String?,
    val openApp: Boolean,
    val requiresUnlock: Boolean,
    val rawJson: JsonObject,
) {
    companion object {
        fun parse(obj: JsonObject): HTActionButton? {
            val identifier = obj[HTPushPayload.Keys.IDENTIFIER]
                ?.jsonPrimitive?.contentOrNull ?: return null
            return HTActionButton(
                identifier = identifier,
                action = HTPushPayload.parseAction(obj[HTPushPayload.Keys.ACTION]),
                title = obj[HTPushPayload.Keys.TITLE]?.jsonPrimitive?.contentOrNull,
                buttonType = obj[HTPushPayload.Keys.BUTTON_TYPE]?.jsonPrimitive?.contentOrNull,
                openApp = obj[HTPushPayload.Keys.OPEN_APP]?.jsonPrimitive?.booleanOrNull ?: true,
                requiresUnlock = obj[HTPushPayload.Keys.REQUIRES_UNLOCK]
                    ?.jsonPrimitive?.booleanOrNull ?: false,
                rawJson = obj,
            )
        }
    }
}
