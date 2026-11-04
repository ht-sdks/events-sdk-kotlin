package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessagePriority
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val stores = ArrayList<MessageStore>()

    @After
    fun tearDown() {
        stores.forEach { it.close() }
    }

    private fun parse(body: String) = MessagesFetchResponse.parse(body.toByteArray())

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

        val outcome = store.replaceAll(response.messages, alice)
        assertEquals(listOf("msg_promo_two_button_001"), outcome.insertedIds)

        val byLocation = store.messages(HightouchMessageQuery(locationId = "home_promo"), alice)
        assertEquals(listOf("msg_promo_two_button_001"), byLocation.map { it.messageId })

        val byBoth = store.messages(HightouchMessageQuery(locationId = "home_promo", variantSlug = "two-button"), alice)
        assertEquals(1, byBoth.size)

        val elements = byBoth.first().content!!.jsonObject["elements"]!!.jsonArray
        assertEquals(4, elements.size)
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
