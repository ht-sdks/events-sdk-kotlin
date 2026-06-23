package com.hightouch.analytics.kotlin.push

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HTPushPayloadTest {

    @Test
    fun `parses minimal payload with only messageId`() {
        val data = fcm("""{"messageId":"msg-1"}""")

        val payload = HTPushPayload.parse(data)

        assertNotNull(payload)
        assertEquals("msg-1", payload!!.messageId)
        assertNull(payload.attachmentUrl)
        assertNull(payload.defaultAction)
        assertNull(payload.actionButtons)
        assertNull(payload.messageContext)
        assertNull(payload.customData)
        assertNull(payload.notificationChannel)
        assertNull(payload.groupKey)
        assertNull(payload.notificationTag)
        assertNull(payload.sound)
    }

    @Test
    fun `parses the Android extension fields`() {
        val data = fcm(
            """
            {"messageId":"m",
             "notificationChannel":"orders",
             "groupKey":"promos",
             "notificationTag":"order-12345",
             "sound":"chime.mp3"}
            """.trimIndent()
        )

        val payload = HTPushPayload.parse(data)
        assertNotNull(payload); payload!!

        assertEquals("orders", payload.notificationChannel)
        assertEquals("promos", payload.groupKey)
        assertEquals("order-12345", payload.notificationTag)
        assertEquals("chime.mp3", payload.sound)
    }

    @Test
    fun `parses full payload with every field`() {
        val data = fcm(
            """
            {
              "messageId": "msg-42",
              "attachmentUrl": "https://cdn.example.com/img.png",
              "defaultAction": {"type":"openUrl","data":"https://example.com/promo"},
              "actionButtons": [
                {
                  "identifier": "yes",
                  "title": "Yes",
                  "buttonType": "default",
                  "openApp": true,
                  "requiresUnlock": false,
                  "action": {"type":"openUrl","data":"https://example.com/yes"}
                },
                {
                  "identifier": "no",
                  "title": "No",
                  "buttonType": "destructive",
                  "openApp": false,
                  "requiresUnlock": true,
                  "action": {"type":"dismiss","data":null}
                }
              ],
              "messageContext": {"campaign_id":"c1","template_id":"t1"},
              "customData": {"k1":"v1","k2":"v2"}
            }
            """.trimIndent()
        )

        val payload = HTPushPayload.parse(data)
        assertNotNull(payload); payload!!

        assertEquals("msg-42", payload.messageId)
        assertEquals("https://cdn.example.com/img.png", payload.attachmentUrl)

        val defaultAction = payload.defaultAction
        assertNotNull(defaultAction)
        assertEquals("openUrl", defaultAction!!.type)
        assertEquals("https://example.com/promo", defaultAction.data)

        val buttons = payload.actionButtons
        assertNotNull(buttons)
        assertEquals(2, buttons!!.size)
        assertEquals("yes", buttons[0].identifier)
        assertEquals("Yes", buttons[0].title)
        assertEquals("default", buttons[0].buttonType)
        assertTrue(buttons[0].openApp)
        assertEquals(false, buttons[0].requiresUnlock)
        assertEquals("openUrl", buttons[0].action?.type)

        assertEquals("no", buttons[1].identifier)
        assertEquals(false, buttons[1].openApp)
        assertTrue(buttons[1].requiresUnlock)
        assertEquals("dismiss", buttons[1].action?.type)
        assertNull(buttons[1].action?.data)

        assertEquals(
            "c1",
            payload.messageContext?.get("campaign_id")?.jsonPrimitive?.content,
        )
        assertEquals(mapOf("k1" to "v1", "k2" to "v2"), payload.customData)
    }

    @Test
    fun `parses defaultAction with custom type and no data`() {
        val data = fcm(
            """{"messageId":"msg-3","defaultAction":{"type":"openLevel"}}"""
        )

        val payload = HTPushPayload.parse(data)
        assertNotNull(payload)
        assertEquals("openLevel", payload!!.defaultAction?.type)
        assertNull(payload.defaultAction?.data)
    }

    @Test
    fun `defaults openApp to true and requiresUnlock to false when omitted`() {
        val data = fcm(
            """
            {"messageId":"msg-4",
             "actionButtons":[{"identifier":"a","title":"A","action":{"type":"openUrl","data":"x"}}]}
            """.trimIndent()
        )

        val payload = HTPushPayload.parse(data)
        val btn = payload?.actionButtons?.single()
        assertNotNull(btn)
        assertTrue(btn!!.openApp)
        assertEquals(false, btn.requiresUnlock)
    }

    @Test
    fun `drops action buttons that are missing an identifier`() {
        val data = fcm(
            """
            {"messageId":"msg-5",
             "actionButtons":[
                {"title":"Bad","action":{"type":"openUrl","data":"x"}},
                {"identifier":"ok","title":"OK","action":{"type":"openUrl","data":"y"}}
             ]}
            """.trimIndent()
        )

        val payload = HTPushPayload.parse(data)
        assertEquals(1, payload?.actionButtons?.size)
        assertEquals("ok", payload?.actionButtons?.single()?.identifier)
    }

    @Test
    fun `returns null when hightouch key is missing`() {
        val data = mapOf("title" to "x", "body" to "y")
        assertNull(HTPushPayload.parse(data))
    }

    @Test
    fun `returns null when messageId is missing`() {
        val data = fcm("""{"attachmentUrl":"https://cdn/img.png"}""")
        assertNull(HTPushPayload.parse(data))
    }

    @Test
    fun `returns null when wrapper JSON is malformed`() {
        val data = mapOf("hightouch" to "not-json {")
        assertNull(HTPushPayload.parse(data))
    }

    @Test
    fun `returns null when wrapper JSON is an array, not an object`() {
        val data = mapOf("hightouch" to """["messageId"]""")
        assertNull(HTPushPayload.parse(data))
    }

    @Test
    fun `parseAction returns null for missing type`() {
        val payload = HTPushPayload.parse(fcm("""{"messageId":"m","defaultAction":{"data":"x"}}"""))
        assertNotNull(payload)
        assertNull(payload!!.defaultAction)
    }

    @Test
    fun `customData parses when every value is a string`() {
        val payload = HTPushPayload.parse(
            fcm("""{"messageId":"m","customData":{"a":"1","b":"two"}}""")
        )
        assertEquals(mapOf("a" to "1", "b" to "two"), payload?.customData)
    }

    @Test
    fun `customData is null and parse does not throw when a value is a nested object`() {
        // Regression: an earlier version called `jsonPrimitive` here, which throws on objects.
        val payload = HTPushPayload.parse(
            fcm("""{"messageId":"m","customData":{"ok":"v","bad":{"nested":"x"}}}""")
        )
        assertNotNull(payload)
        assertNull(payload!!.customData)
    }

    @Test
    fun `customData is null when a value is an array`() {
        val payload = HTPushPayload.parse(
            fcm("""{"messageId":"m","customData":{"ok":"v","bad":[1,2]}}""")
        )
        assertNotNull(payload)
        assertNull(payload!!.customData)
    }

    @Test
    fun `customData is null when a value is a number or bool (matches iOS as-cast)`() {
        // iOS `as? [String: String]` rejects the whole map if any value is non-String; a JSON
        // number/bool is not a String, so the entire map is voided rather than coerced.
        val payload = HTPushPayload.parse(
            fcm("""{"messageId":"m","customData":{"s":"v","n":5,"flag":true}}""")
        )
        assertNotNull(payload)
        assertNull(payload!!.customData)
    }

    private fun fcm(hightouchJson: String): Map<String, String> =
        mapOf("hightouch" to hightouchJson)
}
