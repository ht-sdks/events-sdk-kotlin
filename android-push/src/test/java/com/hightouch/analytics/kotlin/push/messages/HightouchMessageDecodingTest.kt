package com.hightouch.analytics.kotlin.push.messages

import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.messages.internal.MessageOwner
import io.mockk.every
import io.mockk.mockk
import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HightouchMessageDecodingTest {

    private val fullPayloadString =
        """{"messageType":"embedded","messageId":"msg_1","campaignId":"camp_1","createdAt":"2026-08-01T12:00:00.000Z","expiresAt":"2026-09-01T12:00:00.000Z","priority":"high","locationId":"home_banner","triggerType":"immediate","customValues":{},"version":"1","variantSlug":"two-button","content":{"elements":[{"id":"1","tag":"background","elementType":"image","order":null,"mediaUrl":"https://hqcoffe.com/beans.jpg","mediaCaption":"HQ beans","text":null}]}}"""

    private fun makeJSON(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun message(json: String) = HightouchMessage.fromRaw(makeJSON(json))

    private fun utcMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, mo - 1, d, h, mi, s)
        return c.timeInMillis
    }

    // Full payload

    @Test
    fun `full payload decodes all typed fields`() {
        val raw = makeJSON(fullPayloadString)
        val message = HightouchMessage.fromRaw(raw)

        assertEquals("msg_1", message.messageId)
        assertEquals("embedded", message.messageType)
        assertEquals("camp_1", message.campaignId)
        assertEquals(HightouchMessagePriority.HIGH, message.priority)
        assertEquals("home_banner", message.locationId)
        assertEquals("two-button", message.variantSlug)
        assertEquals("immediate", message.triggerType)
        assertEquals("1", message.version)
        assertEquals(JsonObject(emptyMap()), message.customValues)
        val element = message.content!!.jsonObject["elements"]!!.jsonArray[0].jsonObject
        assertEquals("1", element["id"]!!.jsonPrimitive.content)
        assertEquals("background", element["tag"]!!.jsonPrimitive.content)
        assertEquals("image", element["elementType"]!!.jsonPrimitive.content)
        assertEquals("https://hqcoffe.com/beans.jpg", element["mediaUrl"]!!.jsonPrimitive.content)
        assertEquals("HQ beans", element["mediaCaption"]!!.jsonPrimitive.content)
        assertEquals(raw, message.raw)

        assertEquals(utcMillis(2026, 8, 1, 12, 0, 0), message.createdAtMillis)
        assertEquals(utcMillis(2026, 9, 1, 12, 0, 0), message.expiresAtMillis)
    }

    // Round trip

    @Test
    fun `raw round trips through payloadData and decode equally`() {
        val message = message(fullPayloadString)

        val decoded = HightouchMessage.decode(message.payloadData())

        assertEquals(message, decoded)
    }

    // Unknown fields survive

    @Test
    fun `unknown top level keys survive and reencode`() {
        val raw = makeJSON("""{"messageId":"msg_1","messageType":"embedded","aBrandNewServerField":{"nested":[1,2,3]}}""")
        val message = HightouchMessage.fromRaw(raw)

        assertEquals(2, message.raw["aBrandNewServerField"]!!.jsonObject["nested"]!!.jsonArray[1].jsonPrimitive.int)

        val reencoded = Json.parseToJsonElement(Json.encodeToString(HightouchMessage.serializer(), message))

        assertEquals(raw.jsonObject["aBrandNewServerField"], reencoded.jsonObject["aBrandNewServerField"])
    }

    @Test
    fun `serializer decodes through Json`() {
        val decoded = Json.decodeFromString(HightouchMessage.serializer(), fullPayloadString)
        assertEquals(message(fullPayloadString), decoded)
    }

    @Test(expected = SerializationException::class)
    fun `missing messageId throws`() {
        message("""{"messageType":"embedded"}""")
    }

    @Test(expected = SerializationException::class)
    fun `empty messageId throws`() {
        message("""{"messageId":"","messageType":"embedded"}""")
    }

    @Test
    fun `unknown or mistyped fields do not throw`() {
        val message = message("""{"messageId":"msg_1","campaignId":42,"somethingNew":true}""")

        assertNull(message.campaignId)
        assertTrue(message.raw["somethingNew"]!!.jsonPrimitive.boolean)
    }

    // Dates

    @Test
    fun `createdAt parses both fractional and plain timestamps`() {
        val fractional = message("""{"messageId":"msg_1","createdAt":"2026-01-01T00:00:00.123Z"}""")
        assertEquals(utcMillis(2026, 1, 1, 0, 0, 0) + 123, fractional.createdAtMillis)

        val plain = message("""{"messageId":"msg_1","createdAt":"2026-01-01T00:00:00Z"}""")
        assertEquals(utcMillis(2026, 1, 1, 0, 0, 0), plain.createdAtMillis)
    }

    @Test
    fun `createdAt honors numeric offsets and rejects garbage`() {
        val offset = message("""{"messageId":"msg_1","createdAt":"2026-01-01T02:00:00+02:00"}""")
        assertEquals(utcMillis(2026, 1, 1, 0, 0, 0), offset.createdAtMillis)

        val garbage = message("""{"messageId":"msg_1","createdAt":"yesterday","expiresAt":"2026-13-45T00:00:00Z"}""")
        assertNull(garbage.createdAtMillis)
        assertNull(garbage.expiresAtMillis)
    }

    @Test
    fun `expiresAt absent is null`() {
        val message = message("""{"messageId":"msg_1","createdAt":"2026-08-01T12:00:00.000Z"}""")
        assertNull(message.expiresAtMillis)
    }

    @Test
    fun `date parsing tolerates numeric epoch seconds`() {
        val message = message("""{"messageId":"msg_1","createdAt":1735689600}""")
        assertEquals(1735689600000L, message.createdAtMillis)
    }

    // messageType

    @Test
    fun `messageType missing is null`() {
        assertNull(message("""{"messageId":"msg_1"}""").messageType)
    }

    // content.elements ordering

    @Test
    fun `content elements preserve order`() {
        val message = message("""{"messageId":"msg_1","content":{"elements":[{"id":"1"},{"id":"2"},{"id":"3"}]}}""")

        val elements = message.content!!.jsonObject["elements"]!!.jsonArray
        assertEquals(listOf("1", "2", "3"), elements.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    // customValues

    @Test
    fun `customValues with nested objects arrays numbers and null survive`() {
        val message = message("""{"messageId":"msg_1","customValues":{"a":1,"b":[1,2,3],"c":{"d":"e"},"f":null}}""")

        assertEquals(makeJSON("""{"a":1,"b":[1,2,3],"c":{"d":"e"},"f":null}"""), message.customValues)

        val values = message.customValues!!.jsonObject
        assertEquals(1, values["a"]!!.jsonPrimitive.int)
        assertEquals(3, (values["b"] as JsonArray)[2].jsonPrimitive.int)
        assertEquals("e", values["c"]!!.jsonObject["d"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, values["f"])
    }

    // priority

    @Test
    fun `priority decodes each level`() {
        for (level in HightouchMessagePriority.entries) {
            assertEquals(level, message("""{"messageId":"m","priority":"${level.rawValue}"}""").priority)
        }
    }

    @Test
    fun `priority missing empty or unrecognized is default`() {
        for (payload in listOf(
            """{"messageId":"m"}""",
            """{"messageId":"m","priority":null}""",
            """{"messageId":"m","priority":""}""",
            """{"messageId":"m","priority":"urgent"}""",
            """{"messageId":"m","priority":"HIGH"}""",
            """{"messageId":"m","priority":1}""",
        )) {
            assertEquals(payload, HightouchMessagePriority.DEFAULT, message(payload).priority)
        }
    }

    @Test
    fun `priority ranks high medium low then default`() {
        val ranks = listOf(
            HightouchMessagePriority.HIGH,
            HightouchMessagePriority.MEDIUM,
            HightouchMessagePriority.LOW,
            HightouchMessagePriority.DEFAULT,
        ).map { it.rank }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
    }

    // payloadData

    @Test
    fun `payloadData is byte stable across encodes`() {
        val message = message("""{"messageId":"m","zeta":1,"alpha":2,"middle":{"y":1,"x":2}}""")

        val first = message.payloadData()
        repeat(25) { assertEquals(first.toList(), message.payloadData().toList()) }
    }

    @Test
    fun `payloadData sorts keys at every level`() {
        val message = message("""{"messageId":"m","zeta":1,"alpha":[{"b":1,"a":2}],"middle":{"y":1,"x":"s"}}""")

        assertEquals(
            """{"alpha":[{"a":2,"b":1}],"messageId":"m","middle":{"x":"s","y":1},"zeta":1}""",
            String(message.payloadData(), Charsets.UTF_8),
        )
    }

    @Test
    fun `payloadData is independent of source key order`() {
        val a = message("""{"messageId":"m","x":1,"y":2}""")
        val b = message("""{"y":2,"x":1,"messageId":"m"}""")

        assertEquals(a.payloadData().toList(), b.payloadData().toList())
    }

    // MessageOwner.current

    private fun analytics(userId: String?, anonymousId: String): Analytics {
        val analytics = mockk<Analytics>()
        every { analytics.userId() } returns userId
        every { analytics.anonymousId() } returns anonymousId
        return analytics
    }

    @Test
    fun `MessageOwner current prefers userId`() {
        assertEquals(
            MessageOwner(MessageOwner.Kind.USER_ID, "alice"),
            MessageOwner.current(analytics("alice", "anon-1")),
        )
    }

    /** The owner id is sent to the server as-is, so it must match the `userId` events carry. */
    @Test
    fun `MessageOwner current keeps userId verbatim`() {
        assertEquals(
            MessageOwner(MessageOwner.Kind.USER_ID, " alice "),
            MessageOwner.current(analytics(" alice ", "anon-1")),
        )
    }

    @Test
    fun `MessageOwner current falls back to anonymousId`() {
        assertEquals(
            MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "anon-1"),
            MessageOwner.current(analytics(null, "anon-1")),
        )
        assertEquals(
            MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "anon-1"),
            MessageOwner.current(analytics("", "anon-1")),
        )
    }

    @Test
    fun `MessageOwner current returns null when both identifiers are empty`() {
        assertNull(MessageOwner.current(analytics(null, "")))
        assertNull(MessageOwner.current(analytics("", "")))
    }

    @Test
    fun `MessageOwner storageKey distinguishes kinds`() {
        assertEquals("u:alice", MessageOwner(MessageOwner.Kind.USER_ID, "alice").storageKey)
        assertEquals("a:alice", MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "alice").storageKey)
    }

    // Query defaults

    @Test
    fun `query defaults match all`() {
        val query = HightouchMessageQuery()

        assertNull(query.locationId)
        assertNull(query.variantSlug)
        assertNull(query.limit)
        assertEquals(false, query.includeExpired)
        assertEquals(HightouchMessageSort.PRIORITY_THEN_NEWEST, query.sort)
        assertNull(query.nowMillis)
        assertNotNull(query.copy(limit = 3))
    }
}
