package com.hightouch.analytics.kotlin.core.platform.plugins

import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.TrackEvent
import com.hightouch.analytics.kotlin.core.utils.testAnalytics
import com.hightouch.analytics.kotlin.core.BaseEvent
import com.hightouch.analytics.kotlin.core.System
import kotlinx.coroutines.runBlocking
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

import org.junit.jupiter.api.Test

internal class StartupQueueTest {

    private lateinit var analytics: Analytics

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    internal fun setUp() {
        val config = Configuration(
            writeKey = "123",
            application = "Test",
            autoAddSegmentDestination = false
        )
        analytics = testAnalytics(config, testScope, testDispatcher)
    }

    @Test
    fun `startup replay keeps the captured event across reset`() = runBlocking {
        analytics.store.dispatch(System.ToggleRunningAction(false), System::class)
        val delivered = mutableListOf<BaseEvent>()
        val event = TrackEvent(event = "early", properties = buildJsonObject {})
        analytics.process(event) { it?.also(delivered::add) }
        val originalId = event.messageId
        val originalTime = event.timestamp
        val originalAnonymousId = event.anonymousId
        assertTrue(delivered.isEmpty())

        analytics.reset()
        analytics.store.dispatch(System.ToggleRunningAction(true), System::class)
        val replay = delivered.single()
        assertEquals(originalId, replay.messageId)
        assertEquals(originalTime, replay.timestamp)
        assertEquals(originalAnonymousId, replay.anonymousId)
    }

    @Test
    fun `execute when startup queue not ready`() {
        val startupQueue = spyk(StartupQueue())
        every { startupQueue.setup(any()) } answers { startupQueue.analytics = analytics }

        analytics.add(startupQueue)
        val trackEvent = TrackEvent(
            event = "clicked",
            properties = buildJsonObject { put("behaviour", "good") })
        assertNull(startupQueue.execute(trackEvent))
    }
}