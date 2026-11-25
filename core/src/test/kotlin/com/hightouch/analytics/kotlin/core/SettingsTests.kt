package com.hightouch.analytics.kotlin.core

import com.hightouch.analytics.kotlin.core.platform.DestinationPlugin
import com.hightouch.analytics.kotlin.core.platform.EventPipeline
import com.hightouch.analytics.kotlin.core.platform.Plugin
import com.hightouch.analytics.kotlin.core.platform.plugins.SegmentDestination
import com.hightouch.analytics.kotlin.core.utilities.InMemoryStorageProvider
import com.hightouch.analytics.kotlin.core.utils.StubPlugin
import com.hightouch.analytics.kotlin.core.utils.testAnalytics
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SettingsTests {

    private lateinit var analytics: Analytics

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    init {
        Telemetry.enable = false
    }

    @BeforeEach
    fun setup() {
        analytics = testAnalytics(Configuration(
            writeKey = "123",
            application = "Test",
            storageProvider = InMemoryStorageProvider(),
            autoAddSegmentDestination = false
        ), testScope, testDispatcher)
    }

    @Test
    fun `startup does not fetch remote settings`() {
        mockkConstructor(HTTPClient::class)
        testAnalytics(
            Configuration(
                writeKey = "123",
                application = "Test",
                storageProvider = InMemoryStorageProvider(),
                autoAddSegmentDestination = false
            ),
            testScope,
            testDispatcher,
        )

        @Suppress("DEPRECATION")
        verify(exactly = 0) { anyConstructed<HTTPClient>().settings(any()) }
    }

    @Test
    fun `settings update updates plugins`() {
        val mockPlugin = spyk(StubPlugin())
        analytics.add(mockPlugin)
        verify {
            mockPlugin.update(
                Settings(
                    integrations = buildJsonObject {
                        put(
                            "Segment.io",
                            buildJsonObject {
                                put("apiKey", "123")
                                put("apiHost", Constants.DEFAULT_API_HOST)
                            })
                    },
                    plan = emptyJsonObject,
                    edgeFunction = emptyJsonObject,
                    middlewareSettings = emptyJsonObject
                ),
                Plugin.UpdateType.Initial
            )
        }
    }

    @Test
    fun `cached settings propagates to plugins`() = runTest {
        val mockPlugin = spyk(StubPlugin())

        val settings = Settings(
            integrations = buildJsonObject {
                put(
                    "cachedSettings",
                    buildJsonObject {
                        put(
                            "apiKey",
                            "1vNgUqwJeCHmqgI9S1sOm9UHCyfYqbaQ"
                        )
                    })
            },
            plan = emptyJsonObject,
            edgeFunction = emptyJsonObject,
            middlewareSettings = emptyJsonObject
        )
        analytics.store.dispatch(System.UpdateSettingsAction(settings), System::class)
        analytics.add(mockPlugin)
        verify {
            mockPlugin.update(settings, Plugin.UpdateType.Initial)
        }
    }

    @Test
    fun `local startup settings do not clobber a configured apiHost`() = runTest {
        val configured = "10.0.2.2:7777/v1"
        val a = testAnalytics(
            Configuration(
                writeKey = "123",
                application = "Test",
                storageProvider = InMemoryStorageProvider(),
                apiHost = configured
            ),
            testScope,
            testDispatcher,
        )

        val destination = a.find(SegmentDestination::class)
        assertNotNull(destination)
        val pipelineField = SegmentDestination::class.java.getDeclaredField("pipeline")
            .apply { isAccessible = true }
        val pipeline = pipelineField.get(destination) as EventPipeline

        assertEquals(configured, pipeline.apiHost)
    }

    @Test
    fun `plugin added after settings is available updates plugin correctly`() = runTest {
        analytics = testAnalytics(Configuration(
            writeKey = "123",
            application = "Test",
            storageProvider = InMemoryStorageProvider(),
            autoAddSegmentDestination = false
        ), testScope, testDispatcher)
        val mockPlugin = spyk<StubPlugin>()

        analytics.add(mockPlugin)

        // settings is already available, update with Initial
        verify(timeout = 3000, exactly = 1) {
            mockPlugin.update(any(), Plugin.UpdateType.Initial)
        }
        val system = analytics.store.currentState(System::class)
        assertTrue(system!!.initializedPlugins.contains(mockPlugin.hashCode()))

        @Suppress("DEPRECATION")
        analytics.checkSettings()
        verify(exactly = 1) {
            mockPlugin.update(any(), Plugin.UpdateType.Refresh)
        }
    }

    @Test
    fun `isDestinationEnabled returns true when present`() {
        val settings = Settings(
            integrations = buildJsonObject {
                put("Foo", buildJsonObject {
                    put("configA", true)
                })
                put("Bar", buildJsonObject {
                    put("configC", 10)
                })
            }
        )

        assertTrue(settings.hasIntegrationSettings("Foo"))
        assertTrue(settings.hasIntegrationSettings("Bar"))
    }

    @Test
    fun `isDestinationEnabled returns false when absent`() {
        val settings = Settings(
            integrations = buildJsonObject {
                put("Foo", buildJsonObject {
                    put("configA", true)
                })
            }
        )

        assertTrue(settings.hasIntegrationSettings("Foo"))
        assertFalse(settings.hasIntegrationSettings("Bar"))
    }

    @Serializable
    data class FooConfig(
        var configA: Boolean,
        var configB: Int,
        var configC: String,
    )

    @Test
    fun `can fetch typed settings correctly`() {
        val settings = Settings(
            integrations = buildJsonObject {
                put("Foo", buildJsonObject {
                    put("configA", true)
                    put("configB", 10)
                    put("configC", "something")
                })
            }
        )

        val typedSettings = settings.destinationSettings<FooConfig>("Foo")
        assertNotNull(typedSettings)
        with(typedSettings!!) {
            assertTrue(configA)
            assertEquals(10, configB)
            assertEquals("something", configC)
        }
    }

    @Disabled
    @Test
    fun `can manually enable destinations`() = runTest {
        val settings = Settings(
            integrations = buildJsonObject {
                put("Foo", buildJsonObject {
                    put("configA", true)
                    put("configB", 10)
                    put("configC", "something")
                })
            }
        )

        val eventCounter = AtomicInteger(0)
        val barDestination = object : DestinationPlugin() {
            override val key: String = "Bar"

            override fun track(payload: TrackEvent): BaseEvent? {
                eventCounter.incrementAndGet()
                return super.track(payload)
            }
        }

        analytics.add(barDestination)
        analytics.update(settings)

        analytics.track("track", buildJsonObject { put("direct", true) })
        assertEquals(0, eventCounter.get())

        analytics.manuallyEnableDestination(barDestination)
        analytics.track("track", buildJsonObject { put("direct", true) })
        assertEquals(1, eventCounter.get())
    }
}