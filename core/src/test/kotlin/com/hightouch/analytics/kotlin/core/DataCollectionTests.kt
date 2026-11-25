@file:OptIn(InternalHightouchApi::class)

package com.hightouch.analytics.kotlin.core

import com.hightouch.analytics.kotlin.core.utils.TestRunPlugin
import com.hightouch.analytics.kotlin.core.utils.clearPersistentStorage
import com.hightouch.analytics.kotlin.core.utils.mockHTTPClient
import com.hightouch.analytics.kotlin.core.utils.testAnalytics
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataCollectionTests {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val processedEvents = mutableListOf<BaseEvent>()

    init {
        Telemetry.enable = false
    }

    @BeforeEach
    fun setup() {
        clearPersistentStorage(WRITE_KEY)
        mockHTTPClient()
        processedEvents.clear()
    }

    private fun newAnalytics(dataCollectionEnabledByDefault: Boolean = true): Analytics {
        val configuration = Configuration(
            writeKey = WRITE_KEY,
            application = "Test",
            autoAddSegmentDestination = false,
            dataCollectionEnabledByDefault = dataCollectionEnabledByDefault,
        )
        return testAnalytics(configuration, testScope, testDispatcher).also { analytics ->
            analytics.add(TestRunPlugin { event -> event?.let(processedEvents::add) })
        }
    }

    private fun processedTrackNames(): List<String> =
        processedEvents.filterIsInstance<TrackEvent>().map { it.event }

    @Test
    fun `data collection is on by default and events are processed`() {
        val analytics = newAnalytics()

        analytics.track("collected")

        assertTrue(analytics.dataCollectionEnabled)
        assertEquals(listOf("collected"), processedTrackNames())
    }

    @Test
    fun `a default of off drops every event type from construction`() {
        val analytics = newAnalytics(dataCollectionEnabledByDefault = false)

        analytics.track("dropped")
        analytics.identify("user-1")
        analytics.screen("Home")
        analytics.group("group-1")
        analytics.alias("user-2")

        assertFalse(analytics.dataCollectionEnabled)
        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `identify with traits only while off records traits locally without sending an event`() {
        val analytics = newAnalytics(dataCollectionEnabledByDefault = false)
        val traits = buildJsonObject { put("plan", "pro") }

        analytics.identify(traits)

        assertEquals("pro", analytics.traits()?.get("plan")?.jsonPrimitive?.content)
        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `the decision is made when the event is tracked`() {
        val analytics = newAnalytics()

        analytics.track("before off")
        analytics.dataCollectionEnabled = false
        analytics.track("while off")
        analytics.dataCollectionEnabled = true
        analytics.track("after on")

        assertEquals(listOf("before off", "after on"), processedTrackNames())
    }

    @Test
    fun `identify while off still records the user locally`() {
        val analytics = newAnalytics(dataCollectionEnabledByDefault = false)

        analytics.identify("user-1")

        assertEquals("user-1", analytics.userId())
        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `a user identified while off is the one on events after turning on`() {
        val analytics = newAnalytics()
        analytics.identify("user-1")

        analytics.dataCollectionEnabled = false
        analytics.identify("user-2")
        analytics.dataCollectionEnabled = true
        analytics.track("after on")

        val tracked = processedEvents.filterIsInstance<TrackEvent>().single()
        assertEquals("user-2", tracked.userId)
    }

    @Test
    fun `a stored off choice wins over a default of on after a restart`() {
        newAnalytics().dataCollectionEnabled = false

        val restarted = newAnalytics(dataCollectionEnabledByDefault = true)
        restarted.track("dropped")

        assertFalse(restarted.dataCollectionEnabled)
        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `a stored on choice wins over a default of off after a restart`() {
        newAnalytics(dataCollectionEnabledByDefault = false).dataCollectionEnabled = true

        val restarted = newAnalytics(dataCollectionEnabledByDefault = false)
        restarted.track("collected")

        assertTrue(restarted.dataCollectionEnabled)
        assertEquals(listOf("collected"), processedTrackNames())
    }

    @Test
    fun `the kill switch still drops events while data collection is on`() {
        val analytics = newAnalytics()

        analytics.enabled = false
        analytics.track("dropped")

        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `a pre-switch event left in tmp rolls over after restart while collection is off`() = runTest(testDispatcher) {
        val first = newAnalytics()
        first.storage.write(Storage.Constants.Events, """{"type":"track","event":"before-restart"}""")
        first.dataCollectionEnabled = false
        first.storage.close()

        val restarted = newAnalytics(dataCollectionEnabledByDefault = true)
        assertFalse(restarted.dataCollectionEnabled)
        assertTrue(restarted.pendingUploads().isEmpty())

        restarted.storage.rollover()

        val uploads = restarted.pendingUploads()
        assertEquals(1, uploads.size)
        val contents = restarted.storage.readAsStream(uploads.single())!!.bufferedReader().use { it.readText() }
        assertTrue(contents.contains("before-restart"))
    }

    @Test
    fun `a pre-off identify replayed after turning collection off does not overwrite the newer user`() {
        val analytics = newAnalytics()
        val staleIdentify = IdentifyEvent(userId = "user-1", traits = emptyJsonObject).apply {
            anonymousId = analytics.anonymousId()
        }

        analytics.dataCollectionEnabled = false
        analytics.identify("user-2")
        analytics.find(com.hightouch.analytics.kotlin.core.platform.plugins.UserInfoPlugin::class)!!
            .execute(staleIdentify)

        assertEquals("user-2", analytics.userId())
    }

    private fun Analytics.trackTokenEvent(type: String) {
        track(
            DeviceDeletionRequest.EVENT_NAME,
            buildJsonObject { put(DeviceDeletionRequest.EVENT_TYPE_PROPERTY, type) },
        )
    }

    private fun processedTokenEventTypes(): List<String?> =
        processedEvents.filterIsInstance<TrackEvent>().map {
            it.properties[DeviceDeletionRequest.EVENT_TYPE_PROPERTY]?.jsonPrimitive?.content
        }

    @Test
    fun `a device deletion request is the only event delivered while collection is off`() {
        val analytics = newAnalytics(dataCollectionEnabledByDefault = false)

        analytics.trackTokenEvent("registered")
        analytics.trackTokenEvent("disabled")
        analytics.track("deleted")
        analytics.trackTokenEvent(DeviceDeletionRequest.EVENT_TYPE)

        assertEquals(listOf<String?>("deleted"), processedTokenEventTypes())
        assertEquals(1, processedEvents.size)
    }

    @Test
    fun `the kill switch still drops a device deletion request`() {
        val analytics = newAnalytics()

        analytics.enabled = false
        analytics.trackTokenEvent(DeviceDeletionRequest.EVENT_TYPE)

        assertTrue(processedEvents.isEmpty())
    }

    @Test
    fun `a device deletion request held at startup is delivered with collection off`() =
        runTest(testDispatcher) {
            val analytics = newAnalytics(dataCollectionEnabledByDefault = false)
            analytics.pauseEventProcessing()

            analytics.trackTokenEvent(DeviceDeletionRequest.EVENT_TYPE)
            analytics.resumeEventProcessing()

            assertEquals(listOf<String?>("deleted"), processedTokenEventTypes())
        }

    @Test
    fun `an event held at startup is dropped when collection goes off before startup finishes`() =
        runTest(testDispatcher) {
            val analytics = newAnalytics()
            analytics.pauseEventProcessing()

            analytics.track("held at startup")
            analytics.dataCollectionEnabled = false
            analytics.resumeEventProcessing()

            assertTrue(processedEvents.isEmpty())
        }

    @Test
    fun `startup replay preserves original timestamp and messageId`() =
        runTest(testDispatcher) {
            val analytics = newAnalytics(dataCollectionEnabledByDefault = false)
            analytics.pauseEventProcessing()

            val originalTimestamp = "2026-01-01T00:00:00.000Z"
            val originalMessageId = "orig-msg-id"
            val event = TrackEvent(
                event = DeviceDeletionRequest.EVENT_NAME,
                properties = buildJsonObject {
                    put(DeviceDeletionRequest.EVENT_TYPE_PROPERTY, DeviceDeletionRequest.EVENT_TYPE)
                },
            ).apply {
                timestamp = originalTimestamp
                messageId = originalMessageId
            }

            analytics.process(event)
            analytics.resumeEventProcessing()

            val delivered = processedEvents.single()
            assertEquals(originalTimestamp, delivered.timestamp)
            assertEquals(originalMessageId, delivered.messageId)
        }

    @Test
    fun `a deleted event left in tmp rolls over after restart while collection stays off`() =
        runTest(testDispatcher) {
            val first = newAnalytics(dataCollectionEnabledByDefault = false)
            first.storage.write(
                Storage.Constants.Events,
                """{"type":"track","event":"${DeviceDeletionRequest.EVENT_NAME}","properties":{"${DeviceDeletionRequest.EVENT_TYPE_PROPERTY}":"${DeviceDeletionRequest.EVENT_TYPE}"}}""",
            )
            first.storage.close()

            val restarted = newAnalytics(dataCollectionEnabledByDefault = false)
            restarted.storage.rollover()

            val uploads = restarted.pendingUploads()
            assertEquals(1, uploads.size)
            val contents = restarted.storage.readAsStream(uploads.single())!!.bufferedReader().use { it.readText() }
            assertTrue(contents.contains(DeviceDeletionRequest.EVENT_TYPE))
        }

    private companion object {
        const val WRITE_KEY = "data-collection-tests"
    }
}
