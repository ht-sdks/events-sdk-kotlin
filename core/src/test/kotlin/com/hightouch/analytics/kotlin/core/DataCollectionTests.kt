@file:OptIn(InternalHightouchApi::class)

package com.hightouch.analytics.kotlin.core

import com.hightouch.analytics.kotlin.core.utils.TestRunPlugin
import com.hightouch.analytics.kotlin.core.utils.clearPersistentStorage
import com.hightouch.analytics.kotlin.core.utils.mockHTTPClient
import com.hightouch.analytics.kotlin.core.utils.testAnalytics
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

        assertFalse(analytics.dataCollectionEnabled)
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

    private companion object {
        const val WRITE_KEY = "data-collection-tests"
    }
}
