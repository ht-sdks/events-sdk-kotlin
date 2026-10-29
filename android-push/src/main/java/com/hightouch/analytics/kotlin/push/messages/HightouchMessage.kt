package com.hightouch.analytics.kotlin.push.messages

import com.hightouch.analytics.kotlin.push.messages.internal.MessagePriority
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesISO8601
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A message fetched from the in-app gateway and cached locally. [raw] keeps the complete server
 * payload, so fields not modeled here survive a round trip. Mirrors iOS `HightouchMessage`.
 *
 * Dates are epoch milliseconds (`java.time` needs API 26).
 *
 * @property messageType `""` when the server omits it.
 * @property expiresAtMillis null means the message never expires.
 * @property priorityRank Derived locally from [priority]; lower sorts first.
 */
@Serializable(with = HightouchMessageSerializer::class)
data class HightouchMessage(
    val messageId: String,
    val messageType: String = "",
    val campaignId: String? = null,
    val createdAtMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val priority: String? = null,
    val priorityRank: Int = MessagePriority.rank(priority),
    val locationId: String? = null,
    val variantSlug: String? = null,
    val triggerType: String? = null,
    val version: String? = null,
    val customValues: JsonElement? = null,
    val content: JsonElement? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** Encodes [raw] for persistence. Keys are sorted so equal messages produce equal bytes. */
    fun payloadData(): ByteArray {
        val out = StringBuilder()
        appendSorted(raw, out)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * Unknown or wrongly-typed fields decode to null; only a missing or empty `messageId`
         * throws [SerializationException].
         */
        fun fromRaw(raw: JsonElement): HightouchMessage {
            val obj = raw as? JsonObject
            val messageId = obj.string("messageId")
            if (obj == null || messageId.isNullOrEmpty()) {
                throw SerializationException("HightouchMessage requires a non-empty \"messageId\".")
            }
            val priority = MessagePriority.payloadKeys
                .firstNotNullOfOrNull { MessagePriority.rawValue(obj[it]) }
            return HightouchMessage(
                messageId = messageId,
                messageType = obj.string("messageType") ?: "",
                campaignId = obj.string("campaignId"),
                createdAtMillis = MessagesISO8601.dateMillis(obj["createdAt"]),
                expiresAtMillis = MessagesISO8601.dateMillis(obj["expiresAt"]),
                priority = priority,
                priorityRank = MessagePriority.rank(priority),
                locationId = obj.string("locationId"),
                variantSlug = obj.string("variantSlug"),
                triggerType = obj.string("triggerType"),
                version = obj.string("version"),
                customValues = obj["customValues"],
                content = obj["content"],
                raw = obj,
            )
        }

        /** Decodes a message from bytes previously produced by [payloadData]. */
        fun decode(payload: ByteArray): HightouchMessage =
            fromRaw(Json.parseToJsonElement(payload.toString(Charsets.UTF_8)))

        private fun JsonObject?.string(key: String): String? {
            val value = this?.get(key) as? JsonPrimitive ?: return null
            return if (value.isString) value.contentOrNull else null
        }

        private fun appendSorted(element: JsonElement, out: StringBuilder) {
            when (element) {
                is JsonObject -> {
                    out.append('{')
                    var first = true
                    for (key in element.keys.sorted()) {
                        if (!first) out.append(',')
                        first = false
                        out.append(JsonPrimitive(key).toString()).append(':')
                        appendSorted(element.getValue(key), out)
                    }
                    out.append('}')
                }
                is JsonArray -> {
                    out.append('[')
                    element.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        appendSorted(item, out)
                    }
                    out.append(']')
                }
                else -> out.append(element.toString())
            }
        }
    }
}

/** Reads and writes a message as its [HightouchMessage.raw] JSON. */
object HightouchMessageSerializer : KSerializer<HightouchMessage> {
    private val delegate = JsonElement.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): HightouchMessage =
        HightouchMessage.fromRaw(decoder.decodeSerializableValue(delegate))

    override fun serialize(encoder: Encoder, value: HightouchMessage) =
        encoder.encodeSerializableValue(delegate, value.raw)
}
