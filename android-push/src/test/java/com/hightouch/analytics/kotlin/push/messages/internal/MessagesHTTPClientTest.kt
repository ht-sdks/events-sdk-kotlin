package com.hightouch.analytics.kotlin.push.messages.internal

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.messages.FakeTransport
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesConfig
import com.hightouch.analytics.kotlin.push.messages.messagesAnalytics
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors the iOS `MessagesHTTPClient` request-shape tests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessagesHTTPClientTest {

    private val transport = FakeTransport()
    private val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")

    private fun client(
        writeKey: String = "wk-test",
        config: HightouchMessagesConfig = HightouchMessagesConfig.Builder().build(),
    ): MessagesHTTPClient {
        val analytics = messagesAnalytics(ApplicationProvider.getApplicationContext(), writeKey = writeKey)
        return MessagesHTTPClient(analytics, config, transport)
    }

    private fun fetch(client: MessagesHTTPClient, owner: MessageOwner = alice, token: String = "abc123") =
        client.also { transport.enqueueIds(); it.fetchMessages(owner, token) }.let { transport.requests.last() }

    @Test
    fun `the default request is a GET to the gateway path`() {
        val request = fetch(client())

        assertEquals("https://api.hightouch.com/v1/inapp/messages", request.url)
        assertEquals("application/json; charset=utf-8", request.headers["Accept"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("events-sdk-kotlin/"))
    }

    @Test
    fun `credentials and identity travel in gateway headers, never the URL`() {
        val writeKey = "wk-secret-${UUID.randomUUID()}"
        val request = fetch(client(writeKey), MessageOwner(MessageOwner.Kind.USER_ID, "alice@example.com"), "abc123")

        val basic = Base64.encodeToString("$writeKey:".toByteArray(), Base64.NO_WRAP)
        assertEquals("Basic $basic", request.headers["Authorization"])
        assertEquals("alice@example.com", request.headers["x-hightouch-user-id"])
        assertEquals("abc123", request.headers["x-hightouch-device-token"])
        assertFalse("the write key must never appear in the URL", request.url.contains(writeKey))
        assertFalse("the user id must never appear in the URL", request.url.contains("alice"))
        assertFalse(request.url.contains("?"))
    }

    @Test
    fun `the request factory runs after the SDK headers`() {
        val config = HightouchMessagesConfig.Builder()
            .setRequestFactory { it.copy(headers = it.headers + ("x-hightouch-user-id" to "override-user")) }
            .build()

        assertEquals("override-user", fetch(client(config = config)).headers["x-hightouch-user-id"])
    }

    @Test
    fun `a custom host and path are used`() {
        val config = HightouchMessagesConfig.Builder().setMessagesHost("proxy.example.com").setMessagesPath("/inbox").build()

        assertEquals("https://proxy.example.com/inbox", fetch(client(config = config)).url)
    }

    @Test
    fun `a loopback host produces plain HTTP`() {
        for (host in listOf("localhost:8080", "10.0.2.2:8080")) {
            val config = HightouchMessagesConfig.Builder().setMessagesHost(host).build()
            assertEquals("http://$host/v1/inapp/messages", fetch(client(config = config)).url)
        }
    }

    @Test
    fun `a non-2xx status throws and a 2xx body is parsed`() {
        val client = client()
        transport.enqueue(503)
        val error = assertThrows(MessagesHttpStatusException::class.java) { client.fetchMessages(alice, "t") }
        assertEquals(503, error.statusCode)

        transport.enqueueIds("m1")
        assertEquals(listOf("m1"), client.fetchMessages(alice, "t").messages.map { it.messageId })
    }
}
