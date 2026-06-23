package com.hightouch.analytics.kotlin.push.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Serializes the push payload's `customData` map to/from the JSON string carried in intent
 * extras. Centralized so the producer (notification presenter, app-launch intent) and the
 * consumers (ActionRouter, the public [com.hightouch.analytics.kotlin.push.HightouchPush]
 * accessor) agree on the wire shape.
 */
internal object CustomDataJson {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(customData: Map<String, String>): String =
        // for-loop iteration (not Map.forEach) to avoid the API-24 java.util.Map.forEach binding;
        // the minSdk is 21.
        buildJsonObject { for ((k, v) in customData) put(k, v) }.toString()

    /**
     * Decodes a JSON object string back into a `Map<String, String>`. Returns null on malformed
     * input or a non-object root; non-string values are dropped (all-or-nothing would discard the
     * whole map, but a host-facing accessor is more useful returning the string-typed subset).
     */
    fun decode(json: String?): Map<String, String>? {
        val obj: JsonObject = json
            ?.let { runCatching { lenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return null
        return obj.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it }
        }.toMap()
    }
}
