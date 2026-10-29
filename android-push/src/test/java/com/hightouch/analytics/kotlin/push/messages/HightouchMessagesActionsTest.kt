package com.hightouch.analytics.kotlin.push.messages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.CepEventTracking
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.internal.MessageOwner
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStore
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreLocation
import io.mockk.every
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors iOS `HightouchMessagesActions_Tests` and `MessagesTrackingStorage_Tests`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchMessagesActionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var analytics: Analytics
    private val names = ArrayList<String>()
    private val events = ArrayList<JsonObject>()
    private val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        HightouchMessages.resetForTesting()
        analytics = messagesAnalytics(context, userId = "alice")
        every { analytics.track(capture(names), capture(events), any()) } returns Unit
    }

    @After
    fun tearDown() {
        HightouchMessages.resetForTesting()
        HightouchPush.resetForTesting()
    }

    private fun initializePush() {
        HightouchPush.initialize(analytics, HightouchPushConfig.Builder("actions-test-app").build())
    }

    /** Messages on a seeded file store, plus push, with the same analytics. */
    private fun initializeBoth(vararg messages: HightouchMessage): File {
        val file = File(tmp.root, "actions.db")
        MessageStore(context, MessageStoreLocation.Explicit(file)).also {
            it.replaceAll(messages.toList(), alice)
            it.close()
        }
        HightouchMessages.testStoreLocation = MessageStoreLocation.Explicit(file)
        initializePush()
        HightouchMessages.initialize(
            analytics,
            context,
            HightouchMessagesConfig.Builder().setSyncOnLaunch(false).build(),
        )
        return file
    }

    private fun engagementEvents(): List<JsonObject> =
        events.filterIndexed { i, _ -> names[i] == CepEventTracking.ENGAGEMENT_EVENTS }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    private fun contextMessage(id: String, withContext: Boolean = true): HightouchMessage =
        HightouchMessage.fromRaw(
            buildJsonObject {
                put("messageId", id)
                if (withContext) {
                    put("messageContext", buildJsonObject {
                        put("executionId", "exec-1")
                        put("campaignId", "camp-1")
                        put("campaignRunId", "run-1")
                    })
                }
            },
        )

    // trackClick

    @Test
    fun `trackClick emits clicked on the in-app channel`() {
        initializePush()
        HightouchMessages.trackClick("msg-1")

        val props = engagementEvents().single()
        assertEquals(CepEventTracking.MESSAGE_CLICKED, props.str("provider_event_type"))
        assertEquals("msg-1", props.str("message_id"))
        assertEquals("in_app", props.str("channel_type"))
        assertEquals("in_app_sdk", props.str("_ht_cep_source"))
    }

    @Test
    fun `trackClick identifier defaults to default`() {
        initializePush()
        HightouchMessages.trackClick("msg-1")

        val action = engagementEvents().last()["action"]!!.jsonObject
        assertEquals("default", action.str("identifier"))
        assertEquals(1, action.size)
    }

    @Test
    fun `trackClick carries the passed identifier`() {
        initializePush()
        HightouchMessages.trackClick("msg-1", "left-button")

        val action = engagementEvents().last()["action"]!!.jsonObject
        assertEquals("left-button", action.str("identifier"))
        assertEquals(1, action.size)
    }

    @Test
    fun `trackClick sends no element or action type properties`() {
        initializePush()
        HightouchMessages.trackClick("msg-1", "left-button")

        val props = engagementEvents().last()
        for (key in listOf("element_id", "element_tag", "type", "url")) {
            assertNull("$key must not be sent", props[key])
        }
        val action = props["action"]!!.jsonObject
        assertNull(action["type"])
        assertNull(action["url"])
    }

    // trackImpression / trackDismissed

    @Test
    fun `trackImpression emits displayed on the in-app channel`() {
        initializePush()
        HightouchMessages.trackImpression("msg-1")

        val props = engagementEvents().last()
        assertEquals(CepEventTracking.MESSAGE_DISPLAYED, props.str("provider_event_type"))
        assertEquals("in_app", props.str("channel_type"))
        assertEquals("in_app_sdk", props.str("_ht_cep_source"))
        assertEquals("msg-1", props.str("message_id"))
        assertNull(props["action"])
    }

    @Test
    fun `trackDismissed emits dismissed on the in-app channel`() {
        initializePush()
        HightouchMessages.trackDismissed("msg-1")

        val props = engagementEvents().last()
        assertEquals(CepEventTracking.MESSAGE_DISMISSED, props.str("provider_event_type"))
        assertEquals("in_app", props.str("channel_type"))
        assertEquals("msg-1", props.str("message_id"))
        assertNull(props["action"])
    }

    @Test
    fun `tracking before HightouchPush is initialized sends nothing and does not crash`() {
        HightouchMessages.trackImpression("msg-1")
        HightouchMessages.trackClick("msg-1")
        HightouchMessages.trackClick("msg-1", "left-button")
        HightouchMessages.trackDismissed("msg-1")

        assertTrue("no engagement event should fire while push is uninitialized", engagementEvents().isEmpty())
    }

    // Message context

    @Test
    fun `a click picks up per-message context from the cached message`() {
        initializeBoth(contextMessage("msg-ctx-1"))

        HightouchMessages.trackClick("msg-ctx-1")

        val props = engagementEvents().first()
        assertEquals("exec-1", props.str("executionId"))
        assertEquals("camp-1", props.str("campaignId"))
        assertEquals("run-1", props.str("campaignRunId"))
        assertEquals("msg-ctx-1", props.str("message_id"))
    }

    @Test
    fun `a missing messageContext yields an empty bag, not a crash`() {
        initializeBoth(contextMessage("msg-no-ctx", withContext = false))

        HightouchMessages.trackClick("msg-no-ctx")

        val event = engagementEvents().firstOrNull()
        assertNotNull("the click must still be recorded with no messageContext to attach", event)
        assertEquals("msg-no-ctx", event!!.str("message_id"))
    }

    @Test
    fun `tracking after a read still carries message context, and dismissal notifies`() {
        initializeBoth(contextMessage("msg-read"))
        val changes = ArrayList<HightouchMessagesChange>()
        val listener = HightouchMessagesListener { changes.add(it) }
        HightouchMessages.addListener(listener)

        assertEquals(listOf("msg-read"), HightouchMessages.messages(HightouchMessageQuery()).map { it.messageId })
        assertNotNull(HightouchMessages.message("msg-read"))

        HightouchMessages.trackImpression("msg-read")
        HightouchMessages.trackClick("msg-read", "cta")
        HightouchMessages.trackDismissed("msg-read")
        assertTrue(awaitMain { changes.any { it.removedIds == listOf("msg-read") } })

        val events = engagementEvents()
        assertEquals(
            listOf(CepEventTracking.MESSAGE_DISPLAYED, CepEventTracking.MESSAGE_CLICKED, CepEventTracking.MESSAGE_DISMISSED),
            events.map { it.str("provider_event_type") },
        )
        for (event in events) {
            assertEquals("exec-1", event.str("executionId"))
            assertEquals("camp-1", event.str("campaignId"))
            assertEquals("run-1", event.str("campaignRunId"))
        }
    }

    // Storage effects

    @Test
    fun `trackImpression does not remove the message`() {
        initializeBoth(contextMessage("m1"))
        assertEquals(1, HightouchMessages.messageCount())

        HightouchMessages.trackImpression("m1")
        settle()

        assertEquals("an impression must not delete the message it describes", 1, HightouchMessages.messageCount())
    }

    @Test
    fun `trackDismissed removes the message and records it so a sync will not re-insert it`() {
        initializeBoth(contextMessage("m2"))

        HightouchMessages.trackDismissed("m2")
        assertTrue(awaitMain { HightouchMessages.messageCount() == 0 })

        assertTrue(HightouchMessages.dismissedIds(alice).contains("m2"))
    }

    @Test
    fun `a click still has attribution after an impression`() {
        initializeBoth(contextMessage("m3"))
        HightouchMessages.trackImpression("m3")
        settle()

        val ctx = HightouchMessages.messageContext("m3")
        assertEquals("run-1", ctx!!["campaignRunId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dismissing an unknown message records nothing`() {
        initializeBoth(contextMessage("m1"))

        HightouchMessages.trackDismissed("never-cached")
        settle()

        assertFalse(HightouchMessages.dismissedIds(alice).contains("never-cached"))
        assertEquals(1, HightouchMessages.messageCount())
    }
}
