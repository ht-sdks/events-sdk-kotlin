package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.messages.Completions
import com.hightouch.analytics.kotlin.push.messages.FakeTransport
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesChange
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesConfig
import com.hightouch.analytics.kotlin.push.messages.messagesAnalytics
import com.hightouch.analytics.kotlin.push.messages.messagesBody
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors iOS `MessagesSync_Tests`, with a [FakeTransport] in place of the stubbed URLProtocol. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessagesSyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var store: MessageStore
    private lateinit var preferences: MessagesPreferences
    private lateinit var transport: FakeTransport
    private val errors = Collections.synchronizedList(ArrayList<Throwable>())
    private val changes = Collections.synchronizedList(ArrayList<HightouchMessagesChange>())
    private var dismissed: Set<String> = emptySet()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = MessagesPreferences(context).also { it.clear() }
        store = ephemeralStore()
        transport = FakeTransport()
    }

    @After
    fun tearDown() {
        store.close()
        preferences.clear()
    }

    private fun analytics(): Analytics = messagesAnalytics(context, errors = errors)

    private fun coordinator(
        config: HightouchMessagesConfig = HightouchMessagesConfig.Builder().build(),
        ownerProvider: (Analytics) -> MessageOwner?,
        deviceToken: () -> String? = { DEVICE_TOKEN },
    ): MessagesSyncCoordinator {
        val analytics = analytics()
        return MessagesSyncCoordinator(
            analytics = analytics,
            config = config,
            store = store,
            client = MessagesHTTPClient(analytics, config, transport),
            preferences = preferences,
            ownerProvider = ownerProvider,
            deviceTokenProvider = deviceToken,
            dismissedIds = { dismissed },
            onChange = { changes.add(it) },
            dispatcher = Dispatchers.IO,
            // Completions run on the worker; no main-looper hop needed here.
            callbackPoster = { it() },
        )
    }

    private fun MessagesSyncCoordinator.syncAndWait(
        force: Boolean = true,
        updatesCooldown: Boolean = true,
    ): Result<HightouchMessagesChange> {
        val done = Completions()
        var outcome: Result<HightouchMessagesChange>? = null
        sync(force, updatesCooldown) {
            outcome = it
            done.done()
        }
        assertTrue("sync timed out", done.await())
        return outcome!!
    }

    private fun assertEmpty(change: HightouchMessagesChange) = assertTrue(change.isEmpty)

    @Test
    fun `a 200 with 3 messages stores the rows and reports one change`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueIds("m1", "m2", "m3")

        val change = coordinator.syncAndWait().getOrThrow()

        assertEquals(setOf("m1", "m2", "m3"), change.insertedIds.toSet())
        assertEquals(1, changes.size)
        assertEquals(1, transport.requestCount)
        assertEquals(listOf("m1", "m2", "m3"), store.ids(owner))
    }

    @Test
    fun `one malformed message among three still persists the others and reports once`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("messageId", "m1") })
                add(buildJsonObject { put("notAnId", "oops") })
                add(buildJsonObject { put("messageId", "m2") })
            })
        }.toString().toByteArray()
        transport.enqueue(200, body)

        assertTrue(coordinator.syncAndWait().isSuccess)

        assertEquals(listOf("m1", "m2"), store.ids(owner))
        assertEquals("one malformed row should report exactly once, not per row", 1, errors.size)
        assertTrue(errors.single() is MalformedMessagesError)
    }

    @Test
    fun `a bare top-level array is accepted`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueue(200, """[{"messageId":"m1"},{"messageId":"m2"}]""".toByteArray())

        val change = coordinator.syncAndWait().getOrThrow()

        assertEquals(setOf("m1", "m2"), change.insertedIds.toSet())
    }

    @Test
    fun `a 500 retries once then fails and leaves the store unchanged`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        store.replaceAll(listOf(makeMessage("existing")), owner)
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueue(500)
        transport.enqueue(500)

        val result = coordinator.syncAndWait()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MessagesHttpStatusException)
        assertEquals("exactly one retry after the initial 500", 2, transport.requestCount)
        assertEquals(listOf("existing"), store.ids(owner))
    }

    @Test
    fun `a transport failure retries once and can then succeed`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueFailure(IOException("offline"))
        transport.enqueueIds("m1")

        assertEquals(listOf("m1"), coordinator.syncAndWait().getOrThrow().insertedIds)
        assertEquals(2, transport.requestCount)
    }

    @Test
    fun `a 401 and a 400 never retry`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })

        transport.enqueue(401)
        assertTrue(coordinator.syncAndWait().isFailure)
        assertEquals("a 4xx must never be retried", 1, transport.requestCount)

        transport.enqueue(400)
        assertTrue(coordinator.syncAndWait().isFailure)
        assertEquals("a 4xx must never be retried", 2, transport.requestCount)
    }

    @Test
    fun `two back-to-back syncs share one request`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueIds("m1")

        val done = Completions(2)
        coordinator.sync(force = false) { done.done() }
        coordinator.sync(force = false) { done.done() }
        assertTrue(done.await())

        assertEquals("a request already in flight must not trigger a second one", 1, transport.requestCount)
    }

    @Test
    fun `forced syncs during an in-flight request queue one coalesced follow-up`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueIds("m1")
        transport.enqueueIds("m1", "m2")
        transport.enqueueIds("m1", "m2", "m3")

        val forcedChanges = Collections.synchronizedList(ArrayList<HightouchMessagesChange>())
        val done = Completions(3)
        coordinator.sync(force = false) { done.done() }
        coordinator.sync(force = true) { it.onSuccess { c -> forcedChanges.add(c) }; done.done() }
        coordinator.sync(force = true) { it.onSuccess { c -> forcedChanges.add(c) }; done.done() }
        assertTrue(done.await())

        assertEquals("forced syncs queue one follow-up fetch, coalesced", 2, transport.requestCount)
        assertEquals("forced callers get the follow-up's result", listOf(setOf("m2"), setOf("m2")), forcedChanges.map { it.insertedIds.toSet() })
        assertEquals(listOf("m1", "m2"), store.ids(owner))
    }

    @Test
    fun `an anonymous owner makes no request`() {
        val owner = MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "anon-1")
        val coordinator = coordinator(ownerProvider = { owner })

        assertEmpty(coordinator.syncAndWait().getOrThrow())
        assertEquals("the gateway always returns an empty inbox for anonymous ids", 0, transport.requestCount)
    }

    @Test
    fun `no identity means no request`() {
        val coordinator = coordinator(ownerProvider = { null })

        assertEmpty(coordinator.syncAndWait().getOrThrow())
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun `no device token means no request`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner }, deviceToken = { null })

        assertEmpty(coordinator.syncAndWait().getOrThrow())
        assertEquals(0, transport.requestCount)

        val blank = coordinator(ownerProvider = { owner }, deviceToken = { "" })
        assertEmpty(blank.syncAndWait().getOrThrow())
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun `a 200 without a messages array fails and keeps the cache`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueIds("m1")
        coordinator.syncAndWait()

        transport.enqueue(200, """{"error":"upstream"}""".toByteArray())
        transport.enqueue(200, """{"messages":null}""".toByteArray())
        val result = coordinator.syncAndWait()

        assertTrue("a body without a messages array must fail the sync", result.isFailure)
        assertTrue(result.exceptionOrNull() is MissingMessagesArrayError)
        assertEquals(listOf("m1"), store.ids(owner))
    }

    @Test
    fun `a sync that does not update the cooldown leaves the next unforced sync free`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val config = HightouchMessagesConfig.Builder().setForegroundSyncCooldown(3_600_000).build()
        val coordinator = coordinator(config, ownerProvider = { owner })

        coordinator.syncAndWait(force = false, updatesCooldown = false)
        assertEquals(0L, preferences.lastSyncedAtMillis)
        coordinator.syncAndWait(force = false)

        assertEquals("an unstamped sync must not start the cooldown", 2, transport.requestCount)
    }

    @Test
    fun `an owner over the cap converges and evicts only other owners`() {
        val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
        var owner = bob
        val config = HightouchMessagesConfig.Builder().setMaxStoredMessages(2).build()
        val coordinator = coordinator(config, ownerProvider = { owner })

        transport.enqueueIds("b1")
        coordinator.syncAndWait()

        owner = alice
        transport.enqueueIds("a1", "a2", "a3")
        val first = coordinator.syncAndWait().getOrThrow()
        assertEquals(2, first.insertedIds.size)
        assertEquals(2, store.ids(alice).size)
        assertTrue("other owners' rows are evicted first", store.ids(bob).isEmpty())

        transport.enqueueIds("a1", "a2", "a3")
        val second = coordinator.syncAndWait().getOrThrow()
        assertTrue("the same server set must not churn", second.insertedIds.isEmpty() && second.removedIds.isEmpty())
    }

    @Test
    fun `the cooldown blocks a second sync but force bypasses it`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val config = HightouchMessagesConfig.Builder().setForegroundSyncCooldown(3_600_000).build()
        val coordinator = coordinator(config, ownerProvider = { owner })

        transport.enqueueIds("m1")
        coordinator.syncAndWait(force = false)
        assertEquals(1, transport.requestCount)
        assertNotNull(preferences.lastSyncedOwner)

        val second = coordinator.syncAndWait(force = false)
        assertEquals("the cooldown must block a second unforced sync", 1, transport.requestCount)
        assertEmpty(second.getOrThrow())

        transport.enqueueIds("m1")
        coordinator.syncAndWait(force = true)
        assertEquals("force must bypass the cooldown", 2, transport.requestCount)
    }

    @Test
    fun `the cooldown is per owner`() {
        var owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val config = HightouchMessagesConfig.Builder().setForegroundSyncCooldown(3_600_000).build()
        val coordinator = coordinator(config, ownerProvider = { owner })

        transport.enqueueIds("m1")
        coordinator.syncAndWait(force = false)
        assertEquals(1, transport.requestCount)

        owner = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
        coordinator.syncAndWait(force = false)
        assertEquals("a new owner must sync despite the previous owner's cooldown", 2, transport.requestCount)

        coordinator.syncAndWait(force = false)
        assertEquals("the same owner stays rate-limited", 2, transport.requestCount)
    }

    @Test
    fun `a second sync omitting a stored message removes it locally`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })

        transport.enqueueIds("m1", "m2")
        coordinator.syncAndWait()
        assertEquals(listOf("m1", "m2"), store.ids(owner))

        transport.enqueueIds("m1")
        val change = coordinator.syncAndWait().getOrThrow()

        assertEquals(listOf("m2"), change.removedIds)
        assertEquals(listOf("m1"), store.ids(owner))
    }

    @Test
    fun `dismissed ids are kept out of the cache`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        dismissed = setOf("m1")
        val coordinator = coordinator(ownerProvider = { owner })
        transport.enqueueIds("m1", "m2")

        coordinator.syncAndWait()

        assertEquals(listOf("m2"), store.ids(owner))
    }

    @Test
    fun `a sync for a different owner while one is in flight runs after it`() {
        val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
        var resolutions = 0
        val coordinator = coordinator(ownerProvider = {
            resolutions++
            if (resolutions == 1) alice else bob
        })
        transport.enqueueIds("a1")
        transport.enqueueIds("b1")

        val done = Completions(2)
        coordinator.sync(force = true) { done.done() }
        coordinator.sync(force = true) { done.done() }
        assertTrue(done.await())

        assertEquals(2, transport.requestCount)
        assertEquals(listOf("b1"), store.ids(bob))
    }

    @Test
    fun `a store failure fails the sync without retrying`() {
        val owner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
        val coordinator = coordinator(ownerProvider = { owner })
        store.close()
        transport.enqueueIds("m1")

        val result = coordinator.syncAndWait()

        assertTrue(result.isFailure)
        assertEquals("a store failure is not a network failure; no retry", 1, transport.requestCount)
        assertFalse(errors.isEmpty())
    }

    // shouldSync

    private val interval = 15 * 60 * 1000L

    @Test
    fun `shouldSync when never synced`() {
        assertTrue(MessagesSyncCoordinator.shouldSync(0, System.currentTimeMillis(), interval, force = false))
    }

    @Test
    fun `shouldSync is false within the cooldown and true once it elapses`() {
        val now = interval * 10
        assertFalse(MessagesSyncCoordinator.shouldSync(now - (interval - 1), now, interval, force = false))
        assertTrue(MessagesSyncCoordinator.shouldSync(now - interval, now, interval, force = false))
    }

    @Test
    fun `force bypasses the cooldown even right after a sync`() {
        val now = interval * 10
        assertTrue(MessagesSyncCoordinator.shouldSync(now, now, interval, force = true))
    }

    @Test
    fun `a cooldown below the minimum is clamped up to it`() {
        val now = 1_000_000_000L
        val min = HightouchMessagesConfig.MIN_COOLDOWN_MILLIS
        assertFalse(MessagesSyncCoordinator.shouldSync(now - 10_000, now, 5_000, force = false))
        assertTrue(MessagesSyncCoordinator.shouldSync(now - min, now, 5_000, force = false))
    }

    @Test
    fun `a stamp in the future does not hold syncs off`() {
        val now = 1_000_000_000L
        assertTrue(MessagesSyncCoordinator.shouldSync(now + 60_000, now, interval, force = false))
    }

    private companion object {
        const val DEVICE_TOKEN = "0a1b2c3d4e5f"
    }
}
