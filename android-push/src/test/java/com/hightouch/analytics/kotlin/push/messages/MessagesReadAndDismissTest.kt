package com.hightouch.analytics.kotlin.push.messages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.internal.PushPreferences
import com.hightouch.analytics.kotlin.push.messages.internal.MessageDismissalLog
import com.hightouch.analytics.kotlin.push.messages.internal.MessageOwner
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStore
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreLocation
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesPreferences
import com.hightouch.analytics.kotlin.push.messages.internal.ids
import com.hightouch.analytics.kotlin.push.messages.internal.makeMessage
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors iOS `MessagesReadAndDismiss_Tests`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessagesReadAndDismissTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var file: File
    private val alice = MessageOwner(MessageOwner.Kind.USER_ID, "alice")
    private val bob = MessageOwner(MessageOwner.Kind.USER_ID, "bob")
    private val opened = ArrayList<MessageStore>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PushPreferences(context).clear()
        HightouchPush.resetForTesting()
        HightouchMessages.resetForTesting()
        file = File(tmp.root, "read-dismiss.db")
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        HightouchMessages.resetForTesting()
        MessagesPreferences(context).clear()
    }

    private fun message(id: String, locationId: String? = "home") = makeMessage(id, locationId = locationId)

    /** A second connection to the file the facade uses, for seeding and inspection. */
    private fun store(): MessageStore =
        MessageStore(context, MessageStoreLocation.Explicit(file)).also { opened.add(it) }

    private fun seed(messages: List<HightouchMessage>, owner: MessageOwner = alice): MessageStore =
        store().also { it.replaceAll(messages, owner) }

    private fun initializeFacade(userId: String = "alice") {
        HightouchMessages.testStoreLocation = MessageStoreLocation.Explicit(file)
        HightouchMessages.initialize(
            messagesAnalytics(context, userId = userId),
            context,
            HightouchMessagesConfig.Builder().setSyncOnLaunch(false).build(),
        )
    }

    // Reads don't remove

    @Test
    fun `repeated reads return the same messages and are not logged`() {
        seed(listOf(message("m1"), message("m2")))
        initializeFacade()

        assertEquals(listOf("m1", "m2"), HightouchMessages.messages(locationId = "home").map { it.messageId }.sorted())
        settle()

        assertEquals(listOf("m1", "m2"), HightouchMessages.messages(locationId = "home").map { it.messageId }.sorted())
        assertEquals("a read must not be logged", emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `message by id is repeatable`() {
        seed(listOf(message("m1")))
        initializeFacade()

        assertEquals("m1", HightouchMessages.message("m1")?.messageId)
        settle()

        assertEquals("m1", HightouchMessages.message("m1")?.messageId)
        assertEquals(emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `loadMessages does not remove`() {
        seed(listOf(message("m1")))
        initializeFacade()

        var result: List<HightouchMessage>? = null
        HightouchMessages.loadMessages(HightouchMessageQuery(locationId = "home")) { result = it }
        assertTrue(awaitMain { result != null })
        settle()

        assertEquals(listOf("m1"), result!!.map { it.messageId })
        assertEquals(1, HightouchMessages.messageCount(HightouchMessageQuery(locationId = "home")))
        assertEquals(emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `a read message survives a sync`() {
        val store = seed(listOf(message("m1")))
        initializeFacade()
        HightouchMessages.messages(locationId = "home")
        settle()

        store.replaceAll(listOf(message("m1")), alice, suppressing = { HightouchMessages.dismissedIds(alice) })

        assertEquals(listOf("m1"), HightouchMessages.messages(locationId = "home").map { it.messageId })
    }

    // Dismissal

    @Test
    fun `dismiss deletes and logs, and a sync does not reinsert`() {
        val store = seed(listOf(message("m1"), message("m2")))
        initializeFacade()
        val changes = ArrayList<HightouchMessagesChange>()
        val listener = HightouchMessagesListener { changes.add(it) }
        HightouchMessages.addListener(listener)

        HightouchMessages.trackDismissed("m1")
        assertTrue(awaitMain { changes.any { it.removedIds == listOf("m1") } })

        assertEquals(null, HightouchMessages.message("m1"))
        assertEquals(setOf("m1"), HightouchMessages.dismissedIds(alice))

        store.replaceAll(listOf(message("m1"), message("m2")), alice, suppressing = { HightouchMessages.dismissedIds(alice) })

        assertEquals("m1 was dismissed and must not come back", listOf("m2"), store.ids(alice))
    }

    @Test
    fun `without suppression a sync does resurrect a deleted message`() {
        val store = seed(listOf(message("m1")))
        store.delete("m1", alice)
        assertEquals(emptyList<String>(), store.ids(alice))

        store.replaceAll(listOf(message("m1")), alice)

        assertEquals("a delete alone is undone by the next sync", listOf("m1"), store.ids(alice))
    }

    @Test
    fun `deleteMessage removes one message and reports whether a row was removed`() {
        seed(listOf(message("m1"), message("m2")))
        initializeFacade()

        val results = HashMap<String, Boolean>()
        HightouchMessages.deleteMessage("m1") { results["m1"] = it }
        HightouchMessages.deleteMessage("missing") { results["missing"] = it }
        assertTrue(awaitMain { results.size == 2 })

        assertEquals(mapOf("m1" to true, "missing" to false), results)
        assertEquals(listOf("m2"), HightouchMessages.messages(HightouchMessageQuery()).map { it.messageId })
        assertEquals("deleteMessage is not a dismissal", emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `deleteMessages removes several ids with one change notification`() {
        seed(listOf(message("m1"), message("m2"), message("m3")))
        initializeFacade()
        val changes = ArrayList<HightouchMessagesChange>()
        val listener = HightouchMessagesListener { changes.add(it) }
        HightouchMessages.addListener(listener)

        var removed = -1
        HightouchMessages.deleteMessages(listOf("m1", "m3", "missing", "m1")) { removed = it }
        assertTrue(awaitMain { removed >= 0 })

        assertEquals(2, removed)
        assertEquals(listOf("m2"), HightouchMessages.messages(HightouchMessageQuery()).map { it.messageId })
        assertEquals(listOf(listOf("m1", "m3")), changes.map { it.removedIds })
    }

    // Expiry

    @Test
    fun `an expired message is hidden from reads and pruned`() {
        val expired = HightouchMessage.fromRaw(
            buildJsonObject {
                put("messageId", "old")
                put("locationId", "home")
                put("expiresAt", "2000-01-01T00:00:00Z")
            },
        )
        val store = seed(listOf(expired, message("fresh")))
        initializeFacade()

        assertEquals(listOf("fresh"), HightouchMessages.messages(locationId = "home").map { it.messageId })
        assertEquals(1, HightouchMessages.messageCount(HightouchMessageQuery(locationId = "home")))

        store.pruneExpired()

        val all = store.messages(HightouchMessageQuery(includeExpired = true), alice).map { it.messageId }
        assertEquals(listOf("fresh"), all)
        assertEquals("expiry is not a dismissal", emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `opening the store prunes expired rows`() {
        val expired = HightouchMessage.fromRaw(
            buildJsonObject {
                put("messageId", "old")
                put("expiresAt", "2000-01-01T00:00:00Z")
            },
        )
        val store = seed(listOf(expired, message("fresh")))
        initializeFacade()
        HightouchMessages.messageCount()

        assertEquals(listOf("fresh"), store.messages(HightouchMessageQuery(includeExpired = true), alice).map { it.messageId })
    }

    // clearCachedMessages

    @Test
    fun `clear removes every cached message for the current identity`() {
        seed(listOf(message("m1"), message("m2")))
        initializeFacade()
        assertEquals(2, HightouchMessages.messageCount())

        var removed = -1
        HightouchMessages.clearCachedMessages { removed = it }
        assertTrue(awaitMain { removed >= 0 })

        assertEquals(2, removed)
        assertEquals(0, HightouchMessages.messageCount())
    }

    @Test
    fun `clear forgets dismissals so a resync repopulates`() {
        val store = seed(listOf(message("m1")))
        initializeFacade()

        HightouchMessages.removeDismissed("m1")
        assertTrue(awaitMain { HightouchMessages.messageCount() == 0 })
        assertEquals(setOf("m1"), HightouchMessages.dismissedIds(alice))

        var cleared = false
        HightouchMessages.clearCachedMessages { cleared = true }
        assertTrue(awaitMain { cleared })

        assertEquals("suppression must be forgotten too", emptySet<String>(), HightouchMessages.dismissedIds(alice))

        store.replaceAll(listOf(message("m1")), alice, suppressing = { HightouchMessages.dismissedIds(alice) })

        assertEquals("after a clear, a resync must be able to bring the message back", listOf("m1"), store.ids(alice))
    }

    @Test
    fun `clear is scoped to the current identity`() {
        seed(listOf(message("alice_msg")))
        val store = seed(listOf(message("bob_msg")), bob)
        initializeFacade()

        var cleared = false
        HightouchMessages.clearCachedMessages { cleared = true }
        assertTrue(awaitMain { cleared })

        assertEquals("another identity's cache must survive", listOf("bob_msg"), store.ids(bob))
    }

    @Test
    fun `clearAllCachedMessagesForAllUsers removes every owner and every dismissal`() {
        seed(listOf(message("alice_msg")))
        val store = seed(listOf(message("bob_msg")), bob)
        initializeFacade()
        HightouchMessages.removeDismissed("alice_msg")
        assertTrue(awaitMain { HightouchMessages.messageCount() == 0 })

        var removed = -1
        HightouchMessages.clearAllCachedMessagesForAllUsers { removed = it }
        assertTrue(awaitMain { removed >= 0 })

        assertEquals(1, removed)
        assertEquals(emptyList<String>(), store.ids(bob))
        assertEquals(emptySet<String>(), HightouchMessages.dismissedIds(alice))
    }

    @Test
    fun `clear before initialize completes with zero and does not crash`() {
        var removed = -1
        HightouchMessages.clearCachedMessages { removed = it }
        assertTrue(awaitMain { removed >= 0 })
        assertEquals(0, removed)

        removed = -1
        HightouchMessages.clearAllCachedMessagesForAllUsers { removed = it }
        assertTrue(awaitMain { removed >= 0 })
        assertEquals(0, removed)
    }

    // MessageDismissalLog

    private fun log(): MessageDismissalLog = MessageDismissalLog(MessagesPreferences(context).also { it.clear() })

    @Test
    fun `dismissal is scoped to the owner`() {
        val log = log()
        val anon = MessageOwner(MessageOwner.Kind.ANONYMOUS_ID, "anon-1")

        log.record(listOf("shared"), alice)

        assertEquals(listOf("shared"), log.dismissedIds(alice))
        assertEquals("one user's dismissal must not hide it from another", emptyList<String>(), log.dismissedIds(anon))
    }

    @Test
    fun `record is idempotent and preserves order`() {
        val log = log()

        log.record(listOf("a", "b"), alice)
        log.record(listOf("b", "c"), alice)

        assertEquals("no duplicate, order preserved", listOf("a", "b", "c"), log.dismissedIds(alice))
    }

    @Test
    fun `the log evicts the oldest past the cap`() {
        val log = log()
        val limit = MessageDismissalLog.PER_OWNER_LIMIT

        log.record((0..limit).map { "m$it" }, alice)

        val stored = log.dismissedIds(alice)
        assertEquals(limit, stored.size)
        assertTrue("oldest evicted", "m0" !in stored)
        assertTrue("newest retained", "m$limit" in stored)
    }

    @Test
    fun `clear removes only the named owner`() {
        val log = log()
        log.record(listOf("a"), alice)
        log.record(listOf("b"), bob)

        log.clear(alice)

        assertEquals(emptyList<String>(), log.dismissedIds(alice))
        assertEquals(listOf("b"), log.dismissedIds(bob))
    }

    @Test
    fun `the log survives a new instance and reads empty when corrupt`() {
        val prefs = MessagesPreferences(context).also { it.clear() }
        MessageDismissalLog(prefs).record(listOf("a"), alice)

        assertEquals(listOf("a"), MessageDismissalLog(MessagesPreferences(context)).dismissedIds(alice))

        prefs.dismissedJson = "{not json"
        assertEquals(emptyList<String>(), MessageDismissalLog(prefs).dismissedIds(alice))
    }
}
