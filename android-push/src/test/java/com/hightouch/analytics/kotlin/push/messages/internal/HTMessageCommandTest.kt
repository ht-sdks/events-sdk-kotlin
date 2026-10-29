package com.hightouch.analytics.kotlin.push.messages.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HTMessageCommandTest {

    /** Wraps [hightouchJson] the way FCM delivers it: a JSON string under the `hightouch` key. */
    private fun data(hightouchJson: String) = mapOf("hightouch" to hightouchJson)

    @Test
    fun `parses without hightouch messageId`() {
        val command = HTMessageCommand.parse(data("""{"command":{"verb":"syncMessages"}}"""))

        assertNotNull(command)
        assertEquals(HTMessageCommand.Verb.SYNC_MESSAGES, command?.verb)
        assertNull(command?.pushMessageId)
    }

    @Test
    fun `parses with hightouch messageId`() {
        val command = HTMessageCommand.parse(
            data("""{"messageId":"01J8Z9K2QW3E4R5T6Y7U8I9O0P","command":{"verb":"syncMessages"}}"""),
        )

        assertNotNull(command)
        assertEquals(HTMessageCommand.Verb.SYNC_MESSAGES, command?.verb)
        assertEquals("01J8Z9K2QW3E4R5T6Y7U8I9O0P", command?.pushMessageId)
    }

    @Test
    fun `null without hightouch key`() {
        assertNull(HTMessageCommand.parse(mapOf("title" to "hi")))
    }

    @Test
    fun `null when hightouch is not valid json`() {
        assertNull(HTMessageCommand.parse(data("not json")))
        assertNull(HTMessageCommand.parse(data("[1,2]")))
    }

    @Test
    fun `null when command is absent`() {
        assertNull(HTMessageCommand.parse(data("""{"messageId":"msg-1","customData":{"foo":"bar"}}""")))
    }

    @Test
    fun `null when command is not an object`() {
        assertNull(HTMessageCommand.parse(data("""{"command":"syncMessages"}""")))
    }

    @Test
    fun `null when verb is unrecognized`() {
        assertNull(HTMessageCommand.parse(data("""{"command":{"verb":"someFutureVerb"}}""")))
    }

    @Test
    fun `null when verb key is missing entirely`() {
        assertNull(HTMessageCommand.parse(data("""{"command":{"messageId":"msg_abc"}}""")))
    }

    @Test
    fun `null when verb is not a string`() {
        assertNull(HTMessageCommand.parse(data("""{"command":{"verb":1}}""")))
    }

    @Test
    fun `deleteMessage operand is parsed`() {
        val command = HTMessageCommand.parse(
            data("""{"command":{"verb":"deleteMessage","messageId":"msg_abc"}}"""),
        )

        assertEquals(HTMessageCommand.Verb.DELETE_MESSAGE, command?.verb)
        assertEquals("msg_abc", command?.messageId)
    }

    @Test
    fun `syncMessages has no operand`() {
        val command = HTMessageCommand.parse(data("""{"command":{"verb":"syncMessages"}}"""))

        assertEquals(HTMessageCommand.Verb.SYNC_MESSAGES, command?.verb)
        assertNull(command?.messageId)
    }

    @Test
    fun `hints are parsed when well formed`() {
        val command = HTMessageCommand.parse(
            data(
                """{"command":{"verb":"syncMessages","reason":"campaign_send","messageIds":["m1","m2"],""" +
                    """"locationIds":["home_banner"],"syncToken":"tok-123"},"messageContext":{"executionId":"exec-1"}}""",
            ),
        )

        assertEquals("campaign_send", command?.reason)
        assertEquals(listOf("m1", "m2"), command?.messageIds)
        assertEquals(listOf("home_banner"), command?.locationIds)
        assertEquals("tok-123", command?.syncToken)
        assertEquals("\"exec-1\"", command?.messageContext?.get("executionId").toString())
    }

    @Test
    fun `malformed hint degrades to null without failing the whole parse`() {
        val command = HTMessageCommand.parse(
            data("""{"command":{"verb":"syncMessages","messageIds":["m1",2,"m3"]}}"""),
        )

        assertNotNull("a malformed advisory hint must not sink the whole parse", command)
        assertEquals(HTMessageCommand.Verb.SYNC_MESSAGES, command?.verb)
        assertNull(command?.messageIds)
    }

    @Test
    fun `absent hints are null not empty`() {
        val command = HTMessageCommand.parse(data("""{"command":{"verb":"syncMessages"}}"""))

        assertNull(command?.reason)
        assertNull(command?.messageIds)
        assertNull(command?.locationIds)
        assertNull(command?.syncToken)
        assertNull(command?.messageContext)
    }
}
