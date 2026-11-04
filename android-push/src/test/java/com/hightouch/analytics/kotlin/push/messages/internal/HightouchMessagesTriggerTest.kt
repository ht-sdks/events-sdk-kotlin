package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.HightouchSyncOutcome
import com.hightouch.analytics.kotlin.push.messages.messagesAnalytics
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchMessagesTriggerTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics
    private val errors = CopyOnWriteArrayList<Throwable>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = messagesAnalytics(context, errors = errors)
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("trigger-test-app").build())
    }

    @After
    fun tearDown() {
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    private fun syncCommand(extra: String = "") =
        HTMessageCommand.parse(mapOf("hightouch" to """{"messageId":"push-1","command":{"verb":"syncMessages"$extra}}"""))!!

    private fun deliveredEvents(): List<JsonObject> {
        val captured = CopyOnWriteArrayList<JsonObject>()
        verify(atLeast = 0) { analytics.track("CEP Engagement Events", capture(captured), any()) }
        return captured.filter { it["provider_event_type"]?.jsonPrimitive?.content == "delivered" }
    }

    private fun run(
        command: HTMessageCommand,
        deadlineMillis: Long = 5_000,
        isInitialized: () -> Boolean = { true },
        messagesEnabled: () -> Boolean = { true },
        sync: (HTMessageCommand, (HightouchSyncOutcome) -> Unit) -> Unit = { _, cb -> cb(HightouchSyncOutcome.Unchanged) },
    ): List<HightouchMessagesTrigger.Result> {
        val results = CopyOnWriteArrayList<HightouchMessagesTrigger.Result>()
        HightouchMessagesTrigger.handle(command, { results.add(it) }, isInitialized, messagesEnabled, deadlineMillis, sync)
        return results
    }

    @Test
    fun `completion fires exactly once for a command`() {
        assertEquals(listOf(HightouchMessagesTrigger.Result.NO_DATA), run(syncCommand()))
    }

    @Test
    fun `changed sync completes new data with its message count`() {
        val results = run(syncCommand(), sync = { _, cb -> cb(HightouchSyncOutcome.Changed(3)) })

        assertEquals(listOf(HightouchMessagesTrigger.Result.NEW_DATA), results)
        val delivered = deliveredEvents().single()
        assertEquals(3, delivered["message_count"]?.jsonPrimitive?.int)
        assertEquals("syncMessages", delivered["sync_reason"]?.jsonPrimitive?.content)
        assertEquals("push-1", delivered["push_message_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `failed sync completes failed`() {
        val results = run(syncCommand(), sync = { _, cb -> cb(HightouchSyncOutcome.Failed(IOException("timeout"))) })

        assertEquals(listOf(HightouchMessagesTrigger.Result.FAILED), results)
    }

    @Test
    fun `delivered is tracked and flushed before the completion`() {
        var flushedBeforeCompletion = false
        HightouchMessagesTrigger.handle(
            syncCommand(),
            { flushedBeforeCompletion = runCatching { verify(exactly = 1) { analytics.flush() } }.isSuccess },
            { true }, { true }, 5_000,
            { _, cb -> cb(HightouchSyncOutcome.Unchanged) },
        )

        verifyOrder {
            analytics.track("CEP Engagement Events", any<JsonObject>(), any())
            analytics.flush()
        }
        assertTrue(flushedBeforeCompletion)
    }

    @Test
    fun `delivered carries the advisory hints and omits absent ones`() {
        val command = HTMessageCommand.parse(
            mapOf(
                "hightouch" to """{"command":{"verb":"syncMessages","reason":"campaign_send",""" +
                    """"messageIds":["m1"],"locationIds":["home"],"syncToken":"t"},""" +
                    """"messageContext":{"campaignId":"camp-1"}}""",
            ),
        )!!
        run(command)

        val delivered = deliveredEvents().single()
        assertEquals("campaign_send", delivered["reason"]?.jsonPrimitive?.content)
        assertEquals("m1", delivered["message_ids"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("home", delivered["location_ids"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("t", delivered["sync_token"]?.jsonPrimitive?.content)
        assertEquals("camp-1", delivered["campaignId"]?.jsonPrimitive?.content)
        assertNull("no push messageId on the wire", delivered["push_message_id"])
    }

    @Test
    fun `completion fires exactly once when the deadline races a slow sync`() {
        val syncReturned = CountDownLatch(1)

        val results = run(
            syncCommand(),
            deadlineMillis = 50,
            sync = { _, cb ->
                thread {
                    Thread.sleep(300)
                    cb(HightouchSyncOutcome.Changed(1))
                    syncReturned.countDown()
                }
            },
        )

        assertEquals("the deadline wins, reports no data and not failed", listOf(HightouchMessagesTrigger.Result.NO_DATA), results)
        assertTrue(syncReturned.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(HightouchMessagesTrigger.Result.NO_DATA), results)
        val delivered = deliveredEvents().single()
        assertEquals(0, delivered["message_count"]?.jsonPrimitive?.int)
        verify(exactly = 1) { analytics.flush() }
    }

    @Test
    fun `not initialized ignores the command without doing any work`() {
        var syncCalled = false
        val results = run(
            syncCommand(),
            isInitialized = { false },
            sync = { _, cb -> syncCalled = true; cb(HightouchSyncOutcome.Changed(1)) },
        )

        assertEquals(listOf(HightouchMessagesTrigger.Result.NO_DATA), results)
        assertFalse("a push that arrived before initialize() must not attempt any work", syncCalled)
        assertTrue(deliveredEvents().isEmpty())
    }

    @Test
    fun `messages not enabled reports an error and does no work`() {
        var syncCalled = false
        val results = run(
            syncCommand(),
            messagesEnabled = { false },
            sync = { _, cb -> syncCalled = true; cb(HightouchSyncOutcome.Changed(1)) },
        )

        assertEquals(listOf(HightouchMessagesTrigger.Result.NO_DATA), results)
        assertFalse(syncCalled)
        assertEquals(listOf("syncMessages"), errors.mapNotNull { (it as? MessagesNotEnabledError)?.verb })
        assertTrue("a command for a feature the app hasn't enabled is not tracked", deliveredEvents().isEmpty())
    }
}
