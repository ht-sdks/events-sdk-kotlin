package com.hightouch.analytics.kotlin.core.platform

import com.hightouch.analytics.kotlin.core.*
import com.hightouch.analytics.kotlin.core.platform.policies.CountBasedFlushPolicy
import com.hightouch.analytics.kotlin.core.retry.*
import com.hightouch.analytics.kotlin.core.utilities.ConcreteStorageProvider
import com.hightouch.analytics.kotlin.core.utils.clearPersistentStorage
import com.hightouch.analytics.kotlin.core.utils.mockAnalytics
import io.mockk.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * Retry Chain Tests - Debug Version
 *
 * Starting with simplest possible tests to verify time manipulation works.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventPipelineRetryChainTest {

    private val writeKey = "retry-chain-${UUID.randomUUID()}"
    private lateinit var analytics: Analytics
    private lateinit var storage: Storage
    private lateinit var mockHttpClient: HTTPClient
    private lateinit var fakeTime: FakeTimeProvider
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        fakeTime = FakeTimeProvider(1000L)

        mockHttpClient = spyk(HTTPClient(writeKey, RequestFactory()))
        analytics = mockAnalytics(testScope, testDispatcher)
        every { analytics.configuration.writeKey } returns writeKey
        storage = spyk(ConcreteStorageProvider.createStorage(analytics))
        every { analytics.storage } returns storage
    }

    @AfterEach
    fun teardown() {
        clearPersistentStorage(writeKey)
    }

    private fun createTestEvent(name: String): ScreenEvent {
        return ScreenEvent(name, "", emptyJsonObject).apply {
            messageId = "msg-${UUID.randomUUID()}"
            anonymousId = "anon-123"
            integrations = emptyJsonObject
            context = emptyJsonObject
            timestamp = Date(0).toInstant().toString()
        }
    }

    private fun createPipeline(httpConfig: HttpConfig?): EventPipeline {
        return object : EventPipeline(
            analytics,
            "test-destination",
            writeKey,
            listOf(CountBasedFlushPolicy(2)),
            httpConfig = httpConfig,
            timeProvider = fakeTime
        ) {
            override val httpClient: HTTPClient
                get() = mockHttpClient
        }
    }

    @Test
    fun `SIMPLE TEST - verify FakeTimeProvider is used`() {
        val httpConfig = HttpConfig(
            rateLimitConfig = RateLimitConfig(enabled = true),
            backoffConfig = BackoffConfig(enabled = false)
        )
        val pipeline = createPipeline(httpConfig)
        pipeline.start()

        // Trigger 429 with retry-after
        val headers = mutableMapOf("retry-after" to mutableListOf("60"))
        every { mockHttpClient.upload(any()) } throws HTTPException(429, "", "", headers)

        pipeline.put(createTestEvent("event1"))
        pipeline.put(createTestEvent("event2"))
        Thread.sleep(200)

        // Verify: Rate limit state uses our fake time (1000ms + 60s)
        val state = storage.loadRetryState()
        assertEquals(PipelineState.RATE_LIMITED, state.pipelineState)
        assertEquals(1000L + 60000L, state.waitUntilTime, "Should use FakeTimeProvider's current time")

        pipeline.stop()
    }

    @Test
    fun `SIMPLE TEST - verify time advance blocks second upload`() {
        val httpConfig = HttpConfig(
            rateLimitConfig = RateLimitConfig(enabled = true),
            backoffConfig = BackoffConfig(enabled = false)
        )
        val pipeline = createPipeline(httpConfig)
        pipeline.start()

        // Track upload attempts
        var uploadAttempts = 0
        every { mockHttpClient.upload(any()) } answers {
            uploadAttempts++
            throw HTTPException(429, "", "", mutableMapOf("retry-after" to mutableListOf("60")))
        }

        // First flush: Should upload
        pipeline.put(createTestEvent("event1"))
        pipeline.put(createTestEvent("event2"))
        Thread.sleep(200)

        assertEquals(1, uploadAttempts, "First flush should attempt upload")

        // Advance time by 30 seconds (still within 60s wait)
        fakeTime.setTime(1000L + 30000L)

        // Second flush: Should be blocked
        pipeline.put(createTestEvent("event3"))
        pipeline.put(createTestEvent("event4"))
        Thread.sleep(200)

        assertEquals(1, uploadAttempts, "Second flush should NOT attempt upload (still rate limited)")

        pipeline.stop()
    }
    @Test
    fun `Retry-After holds both batch files until the source cooldown expires`() {
        val pipeline = createPipeline(HttpConfig(rateLimitConfig = RateLimitConfig(enabled = true)))
        val rateLimited = HTTPException(429, "", "", mutableMapOf("retry-after" to mutableListOf("60")))
        every { mockHttpClient.upload(any()) } throws rateLimited andThen mockk<Connection>(relaxed = true)
        pipeline.start()
        try {
            pipeline.put(createTestEvent("first"))
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            verify(exactly = 1) { mockHttpClient.upload(any()) }

            val deadline = fakeTime.currentTimeMillis() + 60_000
            fakeTime.setTime(deadline - 1)
            pipeline.put(createTestEvent("second"))
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            assertEquals(2, parseFilePaths(storage.read(Storage.Constants.Events)).size)
            verify(exactly = 1) { mockHttpClient.upload(any()) }

            fakeTime.setTime(deadline)
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            verify(exactly = 3) { mockHttpClient.upload(any()) }
            assertTrue(parseFilePaths(storage.read(Storage.Constants.Events)).isEmpty())
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `healthy batch uploads while a repeatedly failing batch waits for its deferred retry`() {
        val pipeline = createPipeline(HttpConfig(
            backoffConfig = BackoffConfig(enabled = true, maxRetryCount = 1, maxBackoffInterval = 10)
        ))
        val unavailable = HTTPException(503, "", "", mutableMapOf())
        val body = ByteArrayOutputStream()
        val success = mockk<Connection>(relaxed = true) { every { outputStream } returns body }
        every { mockHttpClient.upload(any()) } throws unavailable andThenThrows unavailable andThen success
        pipeline.start()
        try {
            val failing = createTestEvent("first")
            pipeline.put(failing)
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            val first = parseFilePaths(storage.read(Storage.Constants.Events)).single()

            fakeTime.setTime(fakeTime.currentTimeMillis() + 10_000)
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            assertEquals(2, storage.loadRetryState().batchMetadata.getValue(first).failureCount)

            // Present the waiting file first regardless of filesystem enumeration order.
            every { storage.read(Storage.Constants.Events) } answers {
                parseFilePaths(callOriginal()).sortedBy { it != first }.joinToString()
            }
            val healthy = createTestEvent("healthy")
            pipeline.put(healthy)
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            verify(exactly = 3) { mockHttpClient.upload(any()) }
            assertTrue(body.toString("UTF-8").contains(healthy.messageId))
            body.reset()
            assertEquals(listOf(first), parseFilePaths(storage.read(Storage.Constants.Events)))

            fakeTime.setTime(fakeTime.currentTimeMillis() + 10_000)
            pipeline.flush()
            testScope.testScheduler.runCurrent()
            verify(exactly = 4) { mockHttpClient.upload(any()) }
            assertTrue(body.toString("UTF-8").contains(failing.messageId))
            assertTrue(parseFilePaths(storage.read(Storage.Constants.Events)).isEmpty())
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `FULL CHAIN - 429 then 429 then 200`() {
        val httpConfig = HttpConfig(
            rateLimitConfig = RateLimitConfig(enabled = true),
            backoffConfig = BackoffConfig(enabled = false)
        )
        val pipeline = createPipeline(httpConfig)
        pipeline.start()

        var uploadAttempts = 0

        every { mockHttpClient.upload(any()) } answers {
            uploadAttempts++
            when (uploadAttempts) {
                1 -> throw HTTPException(429, "", "", mutableMapOf("retry-after" to mutableListOf("30")))
                2 -> throw HTTPException(429, "", "", mutableMapOf("retry-after" to mutableListOf("30")))
                else -> mockk {
                    every { connection } returns mockk(relaxed = true)
                    every { outputStream } returns mockk(relaxed = true)
                    every { close() } just Runs
                }
            }
        }

        // Attempt 1: 429 (rate limit for 30s)
        pipeline.put(createTestEvent("event1"))
        pipeline.put(createTestEvent("event2"))
        Thread.sleep(200)
        assertEquals(1, uploadAttempts)

        // Advance past first rate limit
        fakeTime.setTime(1000L + 31000L)

        // Attempt 2: 429 again (rate limit for another 30s)
        pipeline.put(createTestEvent("event3"))
        pipeline.put(createTestEvent("event4"))
        Thread.sleep(200)
        assertTrue(uploadAttempts >= 2, "Second attempt should happen after first rate limit expires")

        // Advance past second rate limit
        val currentTime = fakeTime.currentTimeMillis()
        fakeTime.setTime(currentTime + 31000L)

        // Attempt 3: Success
        pipeline.put(createTestEvent("event5"))
        pipeline.put(createTestEvent("event6"))
        Thread.sleep(200)
        assertTrue(uploadAttempts >= 3, "Third attempt should succeed")

        // Verify back to READY
        val state = storage.loadRetryState()
        assertEquals(PipelineState.READY, state.pipelineState)

        pipeline.stop()
    }

    @Test
    fun `FULL CHAIN - 500 with backoff then 200`() {
        val httpConfig = HttpConfig(
            rateLimitConfig = RateLimitConfig(enabled = false),
            backoffConfig = BackoffConfig(
                enabled = true,
                baseBackoffInterval = 1.0,
                maxBackoffInterval = 300
            )
        )
        val pipeline = createPipeline(httpConfig)
        pipeline.start()

        var uploadAttempts = 0

        every { mockHttpClient.upload(any()) } answers {
            uploadAttempts++
            when {
                uploadAttempts == 1 -> throw HTTPException(500, "", "", mutableMapOf())
                else -> mockk {
                    every { connection } returns mockk(relaxed = true)
                    every { outputStream } returns mockk(relaxed = true)
                    every { close() } just Runs
                }
            }
        }

        // Attempt 1: 500 error
        pipeline.put(createTestEvent("event1"))
        pipeline.put(createTestEvent("event2"))
        Thread.sleep(200)
        assertEquals(1, uploadAttempts)

        // Check backoff was set
        var state = storage.loadRetryState()
        assertEquals(1, state.batchMetadata.size, "Should have batch metadata after 500 error")
        val batchFile = state.batchMetadata.keys.first()
        val backoffTime = state.batchMetadata[batchFile]!!.nextRetryTime!!

        // Advance past backoff
        fakeTime.setTime(backoffTime + 100)

        // Attempt 2: Success
        pipeline.put(createTestEvent("event3"))
        pipeline.put(createTestEvent("event4"))
        Thread.sleep(200)
        assertTrue(uploadAttempts >= 2, "Should have at least 2 upload attempts")

        // Verify batch metadata cleared after success
        state = storage.loadRetryState()
        assertTrue(state.batchMetadata.isEmpty(), "Success should clear batch metadata")

        pipeline.stop()
    }
}
