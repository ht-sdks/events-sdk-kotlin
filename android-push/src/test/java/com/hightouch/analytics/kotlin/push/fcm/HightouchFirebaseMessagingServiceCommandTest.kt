package com.hightouch.analytics.kotlin.push.fcm

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.HightouchSilentPushListener
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.messagesAnalytics
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Command pushes through the FCM entry point. Mirrors iOS `HightouchAppIntegrationCommand_Tests`.
 * The trigger's own behavior (deadline, exactly-once, result mapping) is in `HightouchMessagesTriggerTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchFirebaseMessagingServiceCommandTest {

    private lateinit var context: Context
    private lateinit var analytics: Analytics
    private val customDataReceived = CopyOnWriteArrayList<Map<String, String>>()
    private val fcmThread = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        analytics = messagesAnalytics(context)
    }

    @After
    fun tearDown() {
        fcmThread.shutdownNow()
        HightouchPush.resetForTesting()
        PushPreferences(context).clear()
    }

    private fun initialize() {
        HightouchPush.initialize(
            analytics,
            HightouchPushConfig.Builder("command-test-app")
                .setSilentPushListener(HightouchSilentPushListener { customDataReceived.add(it) })
                .build(),
        )
    }

    private fun message(hightouchJson: String) =
        RemoteMessage.Builder("to").setData(mapOf("hightouch" to hightouchJson)).build()

    /** Delivers on a stand-in for FCM's thread, keeping the main looper running as the real one would be. */
    private fun deliver(hightouchJson: String): Boolean {
        val result: Future<Boolean> = fcmThread.submit<Boolean> {
            HightouchFirebaseMessagingService.handleMessageReceived(context, message(hightouchJson))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!result.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return result.get()
    }

    private fun deliveredEvents(): List<JsonObject> {
        val captured = CopyOnWriteArrayList<JsonObject>()
        verify(atLeast = 0) { analytics.track("CEP Engagement Events", capture(captured), any()) }
        return captured.filter { it["provider_event_type"]?.jsonPrimitive?.content == "delivered" }
    }

    @Test
    fun `custom data push still reaches the silent push listener`() {
        initialize()

        val claimed = deliver("""{"messageId":"m1","isSilent":true,"customData":{"foo":"bar"}}""")

        assertTrue(claimed)
        assertEquals(listOf(mapOf("foo" to "bar")), customDataReceived.toList())
        assertTrue(deliveredEvents().isEmpty())
    }

    @Test
    fun `non hightouch push is not claimed`() {
        initialize()

        val claimed = HightouchFirebaseMessagingService.handleMessageReceived(
            context,
            RemoteMessage.Builder("to").setData(mapOf("title" to "hi")).build(),
        )

        assertFalse(claimed)
    }

    @Test
    fun `command push with no messageId is claimed, handled and shows no notification`() {
        initialize()

        val claimed = deliver("""{"command":{"verb":"syncMessages"}}""")

        assertTrue(claimed)
        val delivered = deliveredEvents()
        assertEquals("the command must actually be handled, not dropped", 1, delivered.size)
        assertEquals("syncMessages", delivered.single()["sync_reason"]?.jsonPrimitive?.content)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `command and customData push is handled once and still delivers custom data`() {
        initialize()

        val claimed = deliver(
            """{"messageId":"m1","command":{"verb":"syncMessages"},"customData":{"foo":"bar"}}""",
        )

        assertTrue(claimed)
        assertEquals(1, deliveredEvents().size)
        assertEquals(listOf(mapOf("foo" to "bar")), customDataReceived.toList())
    }

    @Test
    fun `command push before initialize is claimed but does no work`() {
        val claimed = deliver("""{"command":{"verb":"syncMessages"}}""")

        assertTrue(claimed)
        assertTrue(deliveredEvents().isEmpty())
    }

    @Test
    fun `unknown verb falls through to the legacy path`() {
        initialize()

        deliver("""{"messageId":"m2","isSilent":true,"command":{"verb":"someFutureVerb"}}""")

        assertTrue(deliveredEvents().isEmpty())
        assertTrue("an unknown verb is not a command", customDataReceived.isEmpty())
    }
}
