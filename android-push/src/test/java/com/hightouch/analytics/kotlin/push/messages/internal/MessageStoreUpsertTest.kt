package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessage
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreUpsertTest {
    private lateinit var store: MessageStore

    @Before
    fun setUp() {
        store = ephemeralStore()
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `changed content rewrites payload but keeps insertedAt`() {
        store.seed(listOf(makeMessage("m1", campaignId = "camp_1")), alice, nowMillis = T0)
        val first = store.rowSnapshot("m1", alice)!!
        assertEquals(T0, first.insertedAtMillis)

        val outcome = store.seed(listOf(makeMessage("m1", campaignId = "camp_2")), alice, nowMillis = T0 + 60_000)
        assertEquals(listOf("m1"), outcome.updatedIds)

        val second = store.rowSnapshot("m1", alice)!!
        assertEquals("insertedAt must not move on an update", T0, second.insertedAtMillis)
        assertFalse("payload must be rewritten on real content change", second.payload.contentEquals(first.payload))
        assertEquals("camp_2", store.message("m1", alice)!!.campaignId)
    }

    @Test
    fun `byte-identical re-upsert reports no change and keeps the row`() {
        val message = makeMessage("m1", campaignId = "camp_1")
        store.seed(listOf(message), alice, nowMillis = T0)
        val before = store.rowSnapshot("m1", alice)!!
        val outcome = store.seed(listOf(message), alice, nowMillis = T0 + 60_000)

        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), outcome)
        val after = store.rowSnapshot("m1", alice)!!
        assertEquals(before.insertedAtMillis, after.insertedAtMillis)
        assertArrayEquals(before.payload, after.payload)
    }

    @Test
    fun `key order differences in equivalent payloads do not count as changed`() {
        val first = HightouchMessage.fromRaw(Json.parseToJsonElement("""{"messageId":"m1","campaignId":"camp_1"}"""))
        val second = HightouchMessage.fromRaw(Json.parseToJsonElement("""{"campaignId":"camp_1","messageId":"m1"}"""))
        assertEquals(first, second)

        store.seed(listOf(first), alice, nowMillis = T0)
        val before = store.rowSnapshot("m1", alice)!!
        val outcome = store.seed(listOf(second), alice, nowMillis = T0 + 60_000)

        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), outcome)
        assertArrayEquals(before.payload, store.rowSnapshot("m1", alice)!!.payload)
    }

    @Test
    fun `structurally equal rows stored with different bytes count as unchanged`() {
        val message = makeMessage("m1", campaignId = "camp_1")
        store.seed(listOf(message), alice, nowMillis = T0)
        // Simulate a row written by an older encoding: same message, different bytes.
        val reencoded = """{ "messageId": "m1", "campaignId": "camp_1" }""".toByteArray()
        store.database.execSQL(
            "UPDATE messages SET payload = ? WHERE messageId = 'm1'",
            arrayOf<Any>(reencoded),
        )

        val outcome = store.seed(listOf(message), alice, nowMillis = T0 + 1)
        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), outcome)
        assertArrayEquals("an unchanged row is not rewritten", reencoded, store.rowSnapshot("m1", alice)!!.payload)
    }

    @Test
    fun `duplicate ids in one input last wins`() {
        val outcome = store.seed(
            listOf(makeMessage("dup", campaignId = "camp_first"), makeMessage("dup", campaignId = "camp_last")),
            alice,
        )
        assertEquals(listOf("dup"), outcome.insertedIds)
        assertEquals("camp_last", store.message("dup", alice)!!.campaignId)
        assertEquals(1, store.totalCount())
    }

    @Test
    fun `1000 messages across the 200 chunk boundary all land`() {
        val messages = (0 until 1000).map { makeMessage("msg_$it") }
        val outcome = store.seed(messages, alice)
        assertEquals(1000, outcome.insertedIds.size)
        assertEquals(1000, store.count(HightouchMessageQuery(), alice))
        for (boundary in listOf(0, 199, 200, 201, 399, 400, 999)) {
            assertNotNull("msg_$boundary should be present", store.message("msg_$boundary", alice))
        }

        // The same set again takes the chunked existing-row path: nothing changes.
        val again = store.seed(messages, alice, nowMillis = T0)
        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), again)
    }

    @Test
    fun `replace from server deletes omitted rows for the owner and leaves other owners untouched`() {
        store.seed(listOf(makeMessage("a1"), makeMessage("a2"), makeMessage("a3")), alice)
        store.seed(listOf(makeMessage("b1")), bob)

        val outcome = store.seed(listOf(makeMessage("a1")), alice)

        assertEquals(setOf("a2", "a3"), outcome.removedIds.toSet())
        assertEquals(listOf("a1"), store.ids(alice))
        assertNull(store.message("a2", alice))
        assertEquals(listOf("b1"), store.ids(bob))
    }

    @Test
    fun `replacing with an empty list clears the owner`() {
        store.seed(listOf(makeMessage("a1")), alice)
        val outcome = store.seed(emptyList(), alice)
        assertEquals(listOf("a1"), outcome.removedIds)
        assertEquals(0, store.totalCount())
    }

    @Test
    fun `same message id is allowed for two different owners`() {
        store.seed(listOf(makeMessage("shared", campaignId = "for_alice")), alice)
        store.seed(listOf(makeMessage("shared", campaignId = "for_bob")), bob)

        assertEquals(2, store.totalCount())
        assertEquals("for_alice", store.message("shared", alice)!!.campaignId)
        assertEquals("for_bob", store.message("shared", bob)!!.campaignId)
    }

    @Test
    fun `suppressed ids are neither stored nor kept`() {
        store.seed(listOf(makeMessage("m1"), makeMessage("m2")), alice)
        val outcome = store.seed(
            listOf(makeMessage("m1"), makeMessage("m2"), makeMessage("m3")),
            alice,
            suppressing = { setOf("m2", "m3") },
        )
        assertEquals(listOf("m2"), outcome.removedIds)
        assertTrue(outcome.insertedIds.isEmpty())
        assertEquals(listOf("m1"), store.ids(alice))
    }

    @Test
    fun `a failing sync leaves the store unchanged`() {
        store.seed(listOf(makeMessage("m1")), alice)
        try {
            store.seed(listOf(makeMessage("m2")), alice, suppressing = { error("boom") })
        } catch (expected: IllegalStateException) {
        }
        assertEquals(listOf("m1"), store.ids(alice))
    }

    // Store cap: the syncing owner's rows

    @Test
    fun `cap keeps the first N in priority-then-newest order`() {
        val messages = listOf(
            makeMessage("low_new", priority = "low", createdAtMillis = T0 + 2000),
            makeMessage("high_old", priority = "high", createdAtMillis = T0),
            makeMessage("high_new", priority = "high", createdAtMillis = T0 + 1000),
            makeMessage("low_old", priority = "low", createdAtMillis = T0 + 1000),
        )
        val outcome = store.reconcile(messages, alice, maxStoredMessages = 2)

        assertEquals(setOf("high_new", "high_old"), outcome.insertedIds.toSet())
        assertEquals(listOf("high_new", "high_old"), store.ids(alice))
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `cap keeps the highest priority newest messages`() {
        val outcome = store.reconcile(
            listOf(
                makeMessage("old_default", createdAtMillis = T0),
                makeMessage("undated"),
                makeMessage("new_default", createdAtMillis = T0 + 10_000),
                makeMessage("old_high", priority = "high", createdAtMillis = T0),
            ),
            alice,
            maxStoredMessages = 2,
        )

        assertEquals(setOf("old_high", "new_default"), outcome.insertedIds.toSet())
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `cap reports previously stored rows it drops as removed`() {
        val older = makeMessage("older", createdAtMillis = T0)
        val newer = makeMessage("newer", createdAtMillis = T0 + 10_000)
        store.seed(listOf(older), alice)

        val outcome = store.reconcile(listOf(older, newer), alice, maxStoredMessages = 1)
        assertEquals(listOf("newer"), outcome.insertedIds)
        assertEquals(listOf("older"), outcome.removedIds)

        val repeated = store.reconcile(listOf(older, newer), alice, maxStoredMessages = 1)
        assertTrue("a capped set must converge, not churn", repeated.insertedIds.isEmpty() && repeated.removedIds.isEmpty())
    }

    @Test
    fun `an owner over the cap converges instead of churning on every sync`() {
        val messages = (0 until 5).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        val first = store.reconcile(messages, alice, maxStoredMessages = 3, nowMillis = T0)
        assertEquals(3, first.insertedIds.size)

        val second = store.reconcile(messages, alice, maxStoredMessages = 3, nowMillis = T0 + 1)
        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), second)
        assertEquals(listOf("m2", "m3", "m4"), store.ids(alice))
    }

    @Test
    fun `lowering the cap removes rows beyond it`() {
        val messages = (0 until 4).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        store.seed(messages, alice)
        assertEquals(4, store.totalCount())

        val outcome = store.reconcile(messages, alice, maxStoredMessages = 1)
        assertEquals(setOf("m0", "m1", "m2"), outcome.removedIds.toSet())
        assertEquals(listOf("m3"), store.ids(alice))
    }

    @Test
    fun `a negative cap keeps nothing as on iOS`() {
        val messages = (0 until 3).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        store.seed(messages, alice)

        val outcome = store.reconcile(messages, alice, maxStoredMessages = -1)
        assertEquals(setOf("m0", "m1", "m2"), outcome.removedIds.toSet())
        assertEquals(0, store.totalCount())
    }

    // Store cap: other owners' rows

    private fun seedFourRows() {
        store.seed(listOf(makeMessage("a_old")), alice, nowMillis = T0)
        store.seed(listOf(makeMessage("b_old")), bob, nowMillis = T0 + 1)
        store.seed(listOf(makeMessage("a_old"), makeMessage("a_new")), alice, nowMillis = T0 + 2)
        store.seed(listOf(makeMessage("b_old"), makeMessage("b_new")), bob, nowMillis = T0 + 3)
    }

    @Test
    fun `other owners are evicted oldest by insertedAt globally across owners`() {
        seedFourRows()
        assertEquals(4, store.totalCount())

        val outcome = store.reconcile(emptyList(), anon, maxStoredMessages = 2, nowMillis = T0 + 4)

        assertTrue("other owners' rows are never reported in this owner's change", outcome.removedIds.isEmpty())
        assertEquals(2, store.totalCount())
        assertNull(store.message("a_old", alice))
        assertNull(store.message("b_old", bob))
        assertNotNull(store.message("a_new", alice))
        assertNotNull(store.message("b_new", bob))
    }

    @Test
    fun `nothing is evicted when under the cap`() {
        store.seed(listOf(makeMessage("m1")), alice)
        store.reconcile(listOf(makeMessage("b1")), bob, maxStoredMessages = 500)
        assertEquals(listOf("m1"), store.ids(alice))
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `a negative cap evicts nothing from other owners`() {
        store.seed(listOf(makeMessage("m1")), alice)
        store.reconcile(listOf(makeMessage("b1")), bob, maxStoredMessages = -1)
        assertEquals(listOf("m1"), store.ids(alice))
        assertEquals(1, store.totalCount())
    }

    @Test
    fun `the syncing owner is never evicted`() {
        seedFourRows()

        // alice has the two oldest-inserted rows overall, but she is syncing.
        val outcome = store.reconcile(listOf(makeMessage("a_old"), makeMessage("a_new")), alice, maxStoredMessages = 2, nowMillis = T0 + 4)

        assertEquals(UpsertOutcome(emptyList(), emptyList(), emptyList()), outcome)
        assertEquals(listOf("a_new", "a_old"), store.ids(alice))
        assertEquals(0, store.ids(bob).size)
    }

    // reconcile expiry

    @Test
    fun `reconcile reports the owner's rows that expired as removed`() {
        val lapsing = makeMessage("lapsing", expiresAtMillis = T0 + 10_000)
        store.seed(listOf(lapsing, makeMessage("kept")), alice, nowMillis = T0)

        val outcome = store.reconcile(
            listOf(lapsing, makeMessage("kept"), makeMessage("arrived_expired", expiresAtMillis = T0)),
            alice,
            maxStoredMessages = 500,
            nowMillis = T0 + 60_000,
        )

        assertEquals(listOf("lapsing"), outcome.removedIds)
        assertTrue("a message already expired on arrival must not be inserted", outcome.insertedIds.isEmpty())
        assertEquals(
            listOf("kept"),
            store.messages(HightouchMessageQuery(includeExpired = true), alice).map { it.messageId },
        )
    }

    @Test
    fun `reconcile prunes and evicts only other owners in the same write`() {
        store.seed(
            listOf(makeMessage("bob_expired", expiresAtMillis = T0 - 1_000), makeMessage("bob_old"), makeMessage("bob_new")),
            bob,
            nowMillis = T0 - 100_000,
        )

        val outcome = store.reconcile(listOf(makeMessage("a1"), makeMessage("a2")), alice, maxStoredMessages = 3, nowMillis = T0)

        assertEquals(setOf("a1", "a2"), outcome.insertedIds.toSet())
        assertTrue("other owners' rows are never reported in this owner's change", outcome.removedIds.isEmpty())
        assertEquals(3, store.totalCount())
        assertEquals(
            "bob's expired row is pruned and one more is evicted to fit the cap",
            1,
            store.messages(HightouchMessageQuery(includeExpired = true), bob).size,
        )
    }
}
