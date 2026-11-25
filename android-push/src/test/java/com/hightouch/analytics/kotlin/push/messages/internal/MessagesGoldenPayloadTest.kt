package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessage
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagePriority
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the expected server response shape. Same fixture as iOS `MessagesGoldenPayload_Tests`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessagesGoldenPayloadTest {

    private val goldenPayload = """
    {
      "messages": [
        {
          "messageType": "embedded",
          "messageId": "msg_promo_two_button_001",
          "campaignId": "camp_coffee_launch",
          "createdAt": "2026-08-05T12:00:00.000Z",
          "expiresAt": "2099-09-05T12:00:00.000Z",
          "priority": "high",
          "locationId": "home_promo",
          "triggerType": "immediate",
          "customValues": { "roastLevel": "medium", "featured": true },
          "version": "1",
          "variantSlug": "two-button",
          "messageContext": {
            "executionId": "exec_001",
            "sendId": "send_001",
            "campaignId": "camp_coffee_launch",
            "campaignRunId": "run_001"
          },
          "content": {
            "html": "",
            "elements": [
              {
                "id": "1",
                "tag": "background",
                "elementType": "image",
                "order": null,
                "mediaUrl": "https://hqcoffe.com/beans.jpg",
                "mediaCaption": "HQ beans",
                "text": null
              },
              {
                "id": "2",
                "tag": "title",
                "elementType": "text",
                "order": null,
                "mediaUrl": null,
                "mediaCaption": null,
                "text": "Grown in Brazil, roasted stateside."
              },
              {
                "id": "3",
                "tag": "left-button",
                "elementType": "button",
                "order": null,
                "mediaUrl": null,
                "mediaCaption": null,
                "text": "Order now"
              },
              {
                "id": "4",
                "tag": "right-button",
                "elementType": "button",
                "order": null,
                "mediaUrl": null,
                "mediaCaption": null,
                "text": "Learn more"
              }
            ]
          }
        }
      ]
    }
    """.trimIndent()

    /** Elements as the backend builds them from a template: `tag`, `elementType` and one value key. */
    private val jsonElementsPayload = """
    {
      "messages": [
        {
          "messageType": "embedded",
          "messageId": "msg_json_elements_001",
          "campaignId": "camp_coffee_launch",
          "createdAt": "2026-08-05T12:00:00.000Z",
          "expiresAt": "2099-09-05T12:00:00.000Z",
          "locationId": "home_promo",
          "variantSlug": "styled-banner",
          "content": {
            "elements": [
              { "tag": "title", "elementType": "Text", "text": "Hello world" },
              {
                "tag": "style",
                "elementType": "Json",
                "json": { "color": "#DD0031", "label": "{{ first_name }}" }
              },
              {
                "tag": "items",
                "elementType": "Json",
                "json": [{ "label": "Hi {{ first_name }}" }, 3, null, "{{ city }}"]
              }
            ]
          }
        }
      ]
    }
    """.trimIndent()

    private val stores = ArrayList<MessageStore>()

    @After
    fun tearDown() {
        stores.forEach { it.close() }
    }

    private fun parse(body: String) = MessagesFetchResponse.parse(body.toByteArray())

    /** The `content` of the first message in [body], parsed independently of the SDK's decoder. */
    private fun sentContent(body: String): JsonElement =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!

    private fun element(content: JsonElement?, tag: String): JsonObject =
        content!!.jsonObject["elements"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == tag }

    @Test
    fun `the golden payload parses with no malformed rows`() {
        val response = parse(goldenPayload)

        assertEquals(0, response.malformedCount)
        assertEquals(1, response.messages.size)

        val message = response.messages.first()
        assertEquals("msg_promo_two_button_001", message.messageId)
        assertEquals("embedded", message.messageType)
        assertEquals("camp_coffee_launch", message.campaignId)
        assertEquals("home_promo", message.locationId)
        assertEquals("two-button", message.variantSlug)
        assertEquals("immediate", message.triggerType)
        assertEquals("1", message.version)
        assertNotNull(message.createdAtMillis)
        assertNotNull(message.expiresAtMillis)
    }

    @Test
    fun `the golden payload priority decodes`() {
        val message = parse(goldenPayload).messages.first()

        assertEquals(HightouchMessagePriority.HIGH, message.priority)
    }

    @Test
    fun `the golden payload preserves content and custom values`() {
        val message = parse(goldenPayload).messages.first()

        val elements = message.content!!.jsonObject["elements"]!!.jsonArray
        assertEquals(4, elements.size)
        assertEquals(
            listOf("background", "title", "left-button", "right-button"),
            elements.map { it.jsonObject["tag"]!!.jsonPrimitive.content },
        )
        assertEquals("Order now", elements[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("", message.content!!.jsonObject["html"]!!.jsonPrimitive.content)

        assertEquals("medium", message.customValues!!.jsonObject["roastLevel"]!!.jsonPrimitive.content)
        assertTrue(message.customValues!!.jsonObject["featured"]!!.jsonPrimitive.boolean)

        assertEquals(
            "run_001",
            message.raw["messageContext"]!!.jsonObject["campaignRunId"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the golden payload stores and is queryable by location and variant`() {
        val response = parse(goldenPayload)
        val store = ephemeralStore().also { stores.add(it) }

        val outcome = store.seed(response.messages, alice)
        assertEquals(listOf("msg_promo_two_button_001"), outcome.insertedIds)

        val byLocation = store.messages(HightouchMessageQuery(locationId = "home_promo"), alice)
        assertEquals(listOf("msg_promo_two_button_001"), byLocation.map { it.messageId })

        val byBoth = store.messages(HightouchMessageQuery(locationId = "home_promo", variantSlug = "two-button"), alice)
        assertEquals(1, byBoth.size)

        val elements = byBoth.first().content!!.jsonObject["elements"]!!.jsonArray
        assertEquals(4, elements.size)
    }

    @Test
    fun `a Json element decodes as parsed JSON for an object and an array`() {
        val response = parse(jsonElementsPayload)

        assertEquals(0, response.malformedCount)
        val content = response.messages.single().content

        val style = element(content, "style")
        assertEquals("Json", style["elementType"]!!.jsonPrimitive.content)
        assertEquals("#DD0031", style["json"]!!.jsonObject["color"]!!.jsonPrimitive.content)

        val items = element(content, "items")["json"]!!.jsonArray
        assertEquals(4, items.size)
        assertEquals("Hi {{ first_name }}", items[0].jsonObject["label"]!!.jsonPrimitive.content)

        assertEquals(sentContent(jsonElementsPayload), content)
    }

    @Test
    fun `Json elements round-trip through the store unchanged`() {
        val fetched = parse(jsonElementsPayload).messages
        val store = ephemeralStore().also { stores.add(it) }

        store.seed(fetched, alice)
        val stored = store.message("msg_json_elements_001", alice)!!

        assertEquals(sentContent(jsonElementsPayload), stored.content)
        assertEquals(fetched.single().raw, stored.raw)
        assertTrue(element(stored.content, "style")["json"] is JsonObject)
        assertTrue(element(stored.content, "items")["json"] is JsonArray)

        // An unchanged payload must not be reported to the app as an update on the next sync.
        val resync = store.seed(parse(jsonElementsPayload).messages, alice)
        assertEquals(emptyList<String>(), resync.updatedIds)
    }

    @Test
    fun `Json elements near the backend size limit survive the store`() {
        // More rows than one 2 MB cursor window holds, so the read has to refill it.
        val ids = (1..6).map { "msg_large_json_$it" }
        val messages = ids.map { largeJsonMessage(it) }
        val store = ephemeralStore().also { stores.add(it) }

        val outcome = store.seed(messages, alice)
        assertEquals(ids, outcome.insertedIds)

        val single = store.message(ids.first(), alice)!!
        assertEquals(messages.first().content, single.content)

        val all = store.messages(HightouchMessageQuery(), alice).associateBy { it.messageId }
        assertEquals(ids.toSet(), all.keys)
        for (message in messages) {
            assertEquals(message.content, all.getValue(message.messageId).content)
        }
    }

    /**
     * A message whose single Json element encodes to just under 400 KB. The backend caps a whole
     * message at 400 KB, so one Json value is always smaller than that.
     */
    private fun largeJsonMessage(id: String): HightouchMessage {
        val row = "x".repeat(1000)
        val json = buildJsonObject {
            put("id", id)
            put("rows", buildJsonArray { repeat(405) { add(JsonPrimitive("$it:$row")) } })
        }
        val jsonBytes = json.toString().toByteArray().size
        assertTrue("json is $jsonBytes bytes", jsonBytes in 395 * 1024..400 * 1024)
        return HightouchMessage.fromRaw(
            buildJsonObject {
                put("messageId", id)
                put(
                    "content",
                    buildJsonObject {
                        put(
                            "elements",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("tag", "config")
                                        put("elementType", "Json")
                                        put("json", json)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    @Test
    fun `a bare object envelope throws rather than parsing as empty`() {
        val bareObject = """{"messageId":"msg_1","variantSlug":"two-button"}"""

        // An empty parse would wipe the owner's cache.
        assertThrows(MissingMessagesArrayError::class.java) { parse(bareObject) }
    }

    @Test
    fun `null or non-array messages throws`() {
        for (body in listOf("""{"messages":null}""", """{"messages":{}}""", "\"messages\"", "42")) {
            assertThrows(body, MissingMessagesArrayError::class.java) { parse(body) }
        }
    }

    @Test
    fun `a body that is not JSON throws`() {
        assertThrows(Exception::class.java) { parse("<html>bad gateway</html>") }
    }

    @Test
    fun `an empty messages array is an authoritative empty set`() {
        val response = parse("""{"messages":[]}""")

        assertEquals(0, response.messages.size)
        assertEquals(0, response.malformedCount)
    }

    @Test
    fun `an empty message id is dropped as malformed`() {
        val response = parse("""{"messages":[{"messageId":"","variantSlug":"two-button"}]}""")

        assertEquals(0, response.messages.size)
        assertEquals(1, response.malformedCount)
    }
}
