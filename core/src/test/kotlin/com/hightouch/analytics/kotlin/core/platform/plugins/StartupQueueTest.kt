package com.hightouch.analytics.kotlin.core.platform.plugins

import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.TrackEvent
import com.hightouch.analytics.kotlin.core.utils.testAnalytics
import com.hightouch.analytics.kotlin.core.BaseEvent
import com.hightouch.analytics.kotlin.core.Storage
import com.hightouch.analytics.kotlin.core.System
import com.hightouch.analytics.kotlin.core.platform.Plugin
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        io.mockk.unmockkStatic(java.util.UUID::class)
        val config = Configuration(
            writeKey = java.util.UUID.randomUUID().toString(),
            application = "Test",
            autoAddSegmentDestination = false
        )
        analytics = testAnalytics(config, testScope, testDispatcher)
    }

    @Test
    fun `a restored startup event keeps its identity and callback events stay in memory`() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        analytics = testAnalytics(analytics.configuration, scope, dispatcher)
        val queue = analytics.startupQueue
        val gate = System(configuration = analytics.configuration, settings = null)
        queue.runningUpdate(gate)
        analytics.identify("original")
        analytics.track("early")
        analytics.track("callback") { it }
        val saved = analytics.storage.read(Storage.Constants.StartupEvents)!!
        val records = kotlinx.serialization.json.Json.parseToJsonElement(saved).jsonObject.values
        assertEquals(2, records.size)
        val original = records.first { it.jsonObject["event"]?.jsonPrimitive?.content == "early" }.jsonObject
        assertEquals("original", original["userId"]?.jsonPrimitive?.content)

        val next = testAnalytics(analytics.configuration, scope, dispatcher)
        val observer = mutableListOf<BaseEvent>()
        next.add(object : Plugin {
            override lateinit var analytics: Analytics
            override val type = Plugin.Type.After
            override fun execute(event: BaseEvent): BaseEvent {
                observer.add(event)
                return event
            }
        })
        next.startupQueue.runningUpdate(gate.copy(running = true))
        val early = observer.filterIsInstance<TrackEvent>().single()
        assertEquals(original["messageId"]?.jsonPrimitive?.content, early.messageId)
        assertEquals(original["timestamp"]?.jsonPrimitive?.content, early.timestamp)
        assertEquals("original", early.userId)
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