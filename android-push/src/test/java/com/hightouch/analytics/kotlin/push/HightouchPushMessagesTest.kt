package com.hightouch.analytics.kotlin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.FakeTransport
import com.hightouch.analytics.kotlin.push.messages.HightouchMessages
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesConfig
import com.hightouch.analytics.kotlin.push.messages.awaitMain
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreLocation
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesTransport
import com.hightouch.analytics.kotlin.push.messages.messagesAnalytics
import com.hightouch.analytics.kotlin.push.messages.settle
import io.mockk.every
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `HightouchPush` side of messages: configuration and the identity-change sync ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchPushMessagesTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics
    private val transport = FakeTransport()

    /** Interleaves `flush` and each fetch, to pin the identity-sync ordering. */
    private val order = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        HightouchMessages.resetForTesting()
        analytics = messagesAnalytics(context, userId = null)
        every { analytics.flush() } answers { order.add("flush") }
        HightouchMessages.testStoreLocation = MessageStoreLocation.Ephemeral
        HightouchMessages.testTransport = MessagesTransport {
            order.add("fetch")
            transport.execute(it)
        }
    }

    @After
    fun tearDown() {
        HightouchMessages.resetForTesting()
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    private fun initialize(syncOnLaunch: Boolean = false) {
        val messages = HightouchMessagesConfig.Builder().setSyncOnLaunch(syncOnLaunch).build()
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").setMessages(messages).build())
    }

    @Test
    fun `messages stay off unless configured`() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("app-1").build())

        assertFalse(HightouchMessages.isInitialized)
        assertTrue(HightouchPush.isInitialized)
    }

    @Test
    fun `initialize enables messages when configured`() {
        initialize()

        assertTrue(HightouchMessages.isInitialized)
    }

    @Test
    fun `identify syncs only after the token re-registration is flushed`() {
        initialize()
        HightouchPush.register("fcm-token")
        transport.enqueueIds("m1")

        HightouchPush.identify("alice")

        assertTrue(awaitMain { transport.requestCount == 1 })
        assertEquals(listOf("flush", "fetch"), order)
        val request = transport.requests.single()
        assertEquals("alice", request.headers["x-hightouch-user-id"])
        assertEquals("fcm-token", request.headers["x-hightouch-device-token"])
    }

    @Test
    fun `the identity sync does not stamp the cooldown, so the next unforced sync still fetches`() {
        initialize()
        HightouchPush.register("fcm-token")
        HightouchPush.identify("alice")
        assertTrue(awaitMain { transport.requestCount == 1 })
        // Let that sync finish: an unforced sync that arrives while it is in flight would join it.
        settle()

        var done = false
        HightouchMessages.sync(force = false) { done = true }

        assertTrue(awaitMain { done })
        assertEquals(2, transport.requestCount)
    }

    @Test
    fun `identify without a cached token makes no network call`() {
        initialize()

        HightouchPush.identify("alice")
        settle()

        assertEquals(0, transport.requestCount)
        assertFalse("nothing was re-registered, so nothing to flush", order.contains("flush"))
    }

    @Test
    fun `a repeat identify with a fresh token syncs without flushing again`() {
        initialize()
        HightouchPush.register("fcm-token")
        HightouchPush.identify("alice")
        assertTrue(awaitMain { transport.requestCount == 1 })

        HightouchPush.identify("alice")

        assertTrue(awaitMain { transport.requestCount == 2 })
        assertEquals(listOf("flush", "fetch", "fetch"), order)
    }

    @Test
    fun `logout does not sync`() {
        initialize()
        HightouchPush.register("fcm-token")
        HightouchPush.identify("alice")
        assertTrue(awaitMain { transport.requestCount == 1 })

        HightouchPush.logout()
        settle()

        assertEquals("the new anonymous id's inbox is always empty", 1, transport.requestCount)
    }

    @Test
    fun `switching users syncs once, for the new user, after the old user's logout`() {
        initialize()
        HightouchPush.register("fcm-token")
        HightouchPush.identify("alice")
        assertTrue(awaitMain { transport.requestCount == 1 })

        HightouchPush.identify("bob")

        assertTrue(awaitMain { transport.requestCount == 2 })
        settle()
        assertEquals(2, transport.requestCount)
        assertEquals("bob", transport.requests.last().headers["x-hightouch-user-id"])
    }

    @Test
    fun `syncOnLaunch syncs at initialize once a user and token exist`() {
        PushPreferences(context).token = "cached-token"
        every { analytics.userId() } returns "alice"

        initialize(syncOnLaunch = true)

        assertTrue(awaitMain { transport.requestCount == 1 })
        assertEquals("fetch", order.single())
    }
}
