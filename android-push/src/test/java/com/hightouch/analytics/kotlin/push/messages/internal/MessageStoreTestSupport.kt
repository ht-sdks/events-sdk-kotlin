package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.messages.HightouchMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val T0 = 1_700_000_000_000L

internal val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
internal val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
internal val anon = MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "anon-1")

internal fun appContext(): Context = ApplicationProvider.getApplicationContext()

internal fun ephemeralStore(): MessageStore = MessageStore(appContext(), MessageStoreLocation.Ephemeral)

/** A message with only the given fields set in its raw payload. */
internal fun makeMessage(
    id: String,
    campaignId: String? = null,
    locationId: String? = null,
    variantSlug: String? = null,
    priority: String? = null,
    createdAtMillis: Long? = null,
    expiresAtMillis: Long? = null,
): HightouchMessage = HightouchMessage.fromRaw(
    buildJsonObject {
        put("messageId", id)
        campaignId?.let { put("campaignId", it) }
        locationId?.let { put("locationId", it) }
        variantSlug?.let { put("variantSlug", it) }
        priority?.let { put("priority", it) }
        // The decoder reads numbers as epoch seconds.
        createdAtMillis?.let { put("createdAt", JsonPrimitive(it / 1000) as JsonElement) }
        expiresAtMillis?.let { put("expiresAt", JsonPrimitive(it / 1000) as JsonElement) }
    },
)

internal fun MessageStore.ids(owner: MessageOwner): List<String> =
    messages(com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery(), owner).map { it.messageId }.sorted()
