package com.hightouch.analytics.kotlin.push

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared CEP event constants and `track` helper.
 *
 * Mirrors iOS `CepEventTracking.swift`. All push events the SDK emits go through here so the
 * wire-format (event names, base properties, and merge precedence) is defined in exactly one
 * place.
 */
internal object CepEventTracking {

    const val PUSH_TOKEN_EVENTS = "CEP Push Token Events"
    const val ENGAGEMENT_EVENTS = "CEP Engagement Events"

    // provider_event_type values: lowercase, single-verb. The category-vs-subtype split lives at
    // the track-event-name layer (PUSH_TOKEN_EVENTS), so these values don't repeat "Push Token".
    const val TOKEN_REGISTERED = "registered"
    const val TOKEN_DISABLED = "disabled"

    /** Canonical event type value sent on the wire. */
    const val PUSH_OPENED = "opened"

    /** Properties attached to every CEP push event. */
    fun baseProperties(): JsonObject = buildJsonObject {
        put("channel_type", "push")
        put("_ht_cep_source", "push_sdk")
        put("app_id", HightouchPush.cepAppId)
    }

    /**
     * Merge properties with the documented precedence (highest wins):
     *
     *     baseProperties  >  properties  >  messageContext
     *
     * `messageContext` is the opaque bag delivered inside the push payload; it fills in keys
     * that neither the SDK nor the caller set, and can never overwrite them.
     */
    fun mergedProperties(
        properties: JsonObject = EMPTY,
        messageContext: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        // for-loop iteration (not Map.forEach) to avoid the API-24 java.util.Map.forEach binding;
        // the minSdk is 21.
        messageContext?.let { for ((k, v) in it) put(k, v) }
        for ((k, v) in properties) put(k, v)
        for ((k, v) in baseProperties()) put(k, v)
    }

    /**
     * Track a CEP event via the underlying analytics instance. A no-op if the SDK has not been
     * initialized yet (`HightouchPush.initialize` not called) — events fired before init are
     * intentionally dropped rather than queued.
     */
    fun track(
        name: String,
        properties: JsonObject = EMPTY,
        messageContext: JsonObject? = null,
    ) {
        val analytics = HightouchPush.cepAnalytics ?: return
        analytics.track(name, mergedProperties(properties, messageContext))
    }

    private val EMPTY: JsonObject = JsonObject(emptyMap())
}
