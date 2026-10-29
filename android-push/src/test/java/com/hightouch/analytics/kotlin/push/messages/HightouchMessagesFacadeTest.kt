package com.hightouch.analytics.kotlin.push.messages

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.internal.MessageOwner
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStore
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreLocation
import com.hightouch.analytics.kotlin.push.messages.internal.makeMessage
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors iOS `HightouchMessages_Facade_Tests` and `HightouchMessagesForegroundSync_Tests`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HightouchMessagesFacadeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        HightouchMessages.resetForTesting()
    }

    @After
    fun tearDown() {
        HightouchMessages.resetForTesting()
        HightouchPush.resetForTesting()
    }

    private fun storeFile() = File(tmp.root, "messages-test.db")

    private fun seed(messages: List<HightouchMessage>, owner: MessageOwner, file: File) {
        MessageStore(context, MessageStoreLocation.Explicit(file)).use { it.replaceAll(messages, owner) }
    }

    private fun MessageStore.use(block: (MessageStore) -> Unit) = try {
        block(this)
    } finally {
        close()
    }

    private fun initializeFacade(
        file: File?,
        userId: String? = "alice",
        config: HightouchMessagesConfig = HightouchMessagesConfig.Builder().setSyncOnLaunch(false).build(),
        analytics: Analytics = messagesAnalytics(context, userId = userId),
    ): Analytics {
        HightouchMessages.testStoreLocation =
            if (file != null) MessageStoreLocation.Explicit(file) else MessageStoreLocation.Ephemeral
        HightouchMessages.initialize(analytics, context, config)
        return analytics
    }

    private val aliceOwner = MessageOwner(MessageOwner.Kind.USER_ID, "alice")

    // Before initialize

    @Test
    fun `every API before initialize returns empty and does not crash`() {
        assertFalse(HightouchMessages.isInitialized)
        assertEquals(emptyList<HightouchMessage>(), HightouchMessages.messages(HightouchMessageQuery()))
        assertEquals(emptyList<HightouchMessage>(), HightouchMessages.messages("loc", "var", 3))
        assertNull(HightouchMessages.message("anything"))
        assertEquals(0, HightouchMessages.messageCount())

        var loaded: List<HightouchMessage>? = null
        HightouchMessages.loadMessages(HightouchMessageQuery()) { loaded = it }
        assertTrue(awaitMain { loaded != null })
        assertEquals(emptyList<HightouchMessage>(), loaded)

        assertEquals(emptyList<HightouchMessage>(), runBlocking { HightouchMessages.loadMessages(HightouchMessageQuery()) })

        var syncOutcome: HightouchSyncOutcome? = null
        HightouchMessages.sync(force = true) { syncOutcome = it }
        assertTrue(awaitMain { syncOutcome != null })
        assertEquals(HightouchSyncOutcome.Unchanged, syncOutcome)
    }

    // loadMessages threading

    @Test
    fun `loadMessages completes later and on the main thread`() {
        val file = storeFile()
        seed(listOf(makeMessage("m1", locationId = "home")), aliceOwner, file)
        initializeFacade(file)

        var didReturn = false
        var completedOnMain = false
        var firedBeforeReturn = false
        var result: List<HightouchMessage>? = null
        HightouchMessages.loadMessages(HightouchMessageQuery(locationId = "home")) {
            firedBeforeReturn = !didReturn
            completedOnMain = Looper.myLooper() == Looper.getMainLooper()
            result = it
        }
        didReturn = true

        assertTrue(awaitMain { result != null })
        assertFalse("completion fired synchronously: the fetch ran on the calling thread", firedBeforeReturn)
        assertTrue("completion must arrive on the main thread", completedOnMain)
        assertEquals(listOf("m1"), result!!.map { it.messageId })
    }

    @Test
    fun `suspend loadMessages returns seeded rows`() {
        val file = storeFile()
        seed(listOf(makeMessage("m1", locationId = "home")), aliceOwner, file)
        initializeFacade(file)

        val messages = runBlocking { HightouchMessages.loadMessages(HightouchMessageQuery(locationId = "home")) }

        assertEquals(listOf("m1"), messages.map { it.messageId })
    }

    // initialize idempotency

    @Test
    fun `initializing twice at the same resolved file reuses the same store`() {
        val file = storeFile()
        initializeFacade(file)
        val first = HightouchMessages.openStoreForTesting
        assertNotNull(first)

        initializeFacade(file, analytics = messagesAnalytics(context, writeKey = "other-key"))

        assertSame(
            "a second initialize at the same store file must reuse the open store",
            first,
            HightouchMessages.openStoreForTesting,
        )
    }

    @Test
    fun `different write keys share the app's one store`() {
        HightouchMessages.testStoreLocation = null
        HightouchMessages.initialize(messagesAnalytics(context, writeKey = "k1"), context, HightouchMessagesConfig.Builder().setSyncOnLaunch(false).build())
        val first = HightouchMessages.openStoreForTesting
        assertNotNull(first)

        HightouchMessages.initialize(messagesAnalytics(context, writeKey = "k2"), context, HightouchMessagesConfig.Builder().setSyncOnLaunch(false).build())

        assertSame("the store is per app, not per write key", first, HightouchMessages.openStoreForTesting)
    }

    // Owner scoping / privacy

    @Test
    fun `queries only return rows for the currently identified owner`() {
        val file = storeFile()
        val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
        seed(listOf(makeMessage("alice_msg")), aliceOwner, file)
        seed(listOf(makeMessage("bob_msg")), bob, file)
        initializeFacade(file, userId = "alice")

        assertEquals(1, HightouchMessages.messageCount())
        assertNull("bob's row must be invisible while alice is the identified owner", HightouchMessages.message("bob_msg"))
        assertEquals(listOf("alice_msg"), HightouchMessages.messages(HightouchMessageQuery()).map { it.messageId })
    }

    @Test
    fun `the owner follows HightouchPush userId before the analytics pipeline catches up`() {
        val file = storeFile()
        val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
        seed(listOf(makeMessage("alice_msg")), aliceOwner, file)
        seed(listOf(makeMessage("bob_msg")), bob, file)
        // Analytics still reports alice (its identify applies asynchronously).
        val analytics = initializeFacade(file, userId = "alice")
        HightouchPush.initialize(analytics, com.hightouch.analytics.kotlin.push.HightouchPushConfig.Builder("app").build())
        HightouchPush.identify("bob")

        assertEquals(listOf("bob_msg"), HightouchMessages.messages(HightouchMessageQuery()).map { it.messageId })
    }

    // Filters

    @Test
    fun `location variant and limit filter correctly through the facade`() {
        val file = storeFile()
        seed(
            listOf(
                makeMessage("m1", locationId = "loc_a", variantSlug = "var_x"),
                makeMessage("m2", locationId = "loc_a", variantSlug = "var_y"),
                makeMessage("m3", locationId = "loc_b", variantSlug = "var_x"),
                makeMessage("m4", locationId = "loc_a", variantSlug = "var_x"),
            ),
            aliceOwner,
            file,
        )
        initializeFacade(file)

        assertEquals(4, HightouchMessages.messageCount())
        assertEquals(3, HightouchMessages.messageCount(HightouchMessageQuery(locationId = "loc_a")))
        assertEquals(2, HightouchMessages.messageCount(HightouchMessageQuery(locationId = "loc_a", variantSlug = "var_x")))

        val filtered = HightouchMessages.messages(locationId = "loc_a", variantSlug = "var_x")
        assertEquals(setOf("m1", "m4"), filtered.map { it.messageId }.toSet())

        val limited = HightouchMessages.messages(locationId = "loc_a", limit = 1)
        assertEquals(1, limited.size)
        assertTrue(limited[0].messageId in listOf("m1", "m2", "m4"))

        assertEquals("reads must not remove rows", 4, HightouchMessages.messageCount())
    }

    @Test
    fun `messageCount agrees with messages`() {
        val file = storeFile()
        seed((0 until 7).map { makeMessage("m$it", locationId = if (it % 2 == 0) "loc_even" else "loc_odd") }, aliceOwner, file)
        initializeFacade(file)

        val even = HightouchMessageQuery(locationId = "loc_even")
        assertEquals(HightouchMessages.messages(even).size, HightouchMessages.messageCount(even))
        assertEquals(4, HightouchMessages.messageCount(even))
        assertEquals(7, HightouchMessages.messageCount())
    }

    // Change notification

    private class RecordingListener : HightouchMessagesListener {
        val changes = ArrayList<HightouchMessagesChange>()
        var onMain = false
        override fun onMessagesChanged(change: HightouchMessagesChange) {
            changes.add(change)
            onMain = Looper.myLooper() == Looper.getMainLooper()
        }
    }

    @Test
    fun `notifyChange calls the config listener and added listeners on the main thread`() {
        val configured = RecordingListener()
        val added = RecordingListener()
        initializeFacade(
            null,
            config = HightouchMessagesConfig.Builder().setSyncOnLaunch(false).setListener(configured).build(),
        )
        HightouchMessages.addListener(added)
        HightouchMessages.addListener(added)

        val change = HightouchMessagesChange(insertedIds = listOf("m1"), updatedIds = listOf("m2"), removedIds = listOf("m3"))
        HightouchMessages.notifyChange(change)
        assertTrue(awaitMain { configured.changes.isNotEmpty() && added.changes.isNotEmpty() })

        assertEquals(listOf(change), configured.changes)
        assertEquals("a listener added twice is called once", listOf(change), added.changes)
        assertTrue(configured.onMain && added.onMain)
    }

    @Test
    fun `a removed listener is no longer called`() {
        val listener = RecordingListener()
        initializeFacade(null)
        HightouchMessages.addListener(listener)
        HightouchMessages.removeListener(listener)

        HightouchMessages.notifyChange(HightouchMessagesChange(insertedIds = listOf("m1")))
        settle()

        assertTrue(listener.changes.isEmpty())
    }

    @Test
    fun `listeners are held weakly`() {
        var listener: RecordingListener? = RecordingListener()
        val ref = WeakReference(listener)
        val config = HightouchMessagesConfig.Builder().setListener(listener).build()
        HightouchMessages.addListener(listener!!)
        listener = null

        var collected = false
        for (i in 0 until 20) {
            System.gc()
            if (ref.get() == null) { collected = true; break }
            Thread.sleep(25)
        }
        assumeTrue("GC did not run in time", collected)

        assertNull(config.listener)
        // Notifying with only dead listeners neither crashes nor leaks.
        initializeFacade(null, config = config)
        HightouchMessages.notifyChange(HightouchMessagesChange(insertedIds = listOf("m1")))
        settle()
    }

    // Foreground sync observer

    private fun foregroundConfig(on: Boolean) =
        HightouchMessagesConfig.Builder().setSyncOnLaunch(false).setSyncOnForeground(on).build()

    @Test
    fun `initialize with syncOnForeground true registers the observer`() {
        initializeFacade(null, config = foregroundConfig(true))
        assertTrue(HightouchMessages.isForegroundObserverRegistered)
    }

    @Test
    fun `initialize with syncOnForeground false does not register the observer`() {
        initializeFacade(null, config = foregroundConfig(false))
        assertFalse(HightouchMessages.isForegroundObserverRegistered)
    }

    @Test
    fun `reinitializing with the flag off removes the prior observer`() {
        initializeFacade(null, config = foregroundConfig(true))
        assertTrue(HightouchMessages.isForegroundObserverRegistered)

        initializeFacade(null, config = foregroundConfig(false))

        assertFalse(HightouchMessages.isForegroundObserverRegistered)
    }

    @Test
    fun `reinitializing with the flag on keeps an observer registered`() {
        initializeFacade(null, config = foregroundConfig(true))
        initializeFacade(null, config = foregroundConfig(true))
        assertTrue(HightouchMessages.isForegroundObserverRegistered)
    }
}
