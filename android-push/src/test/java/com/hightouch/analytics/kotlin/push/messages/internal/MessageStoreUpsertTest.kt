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
        store.replaceAll(listOf(makeMessage("m1", campaignId = "camp_1")), alice, nowMillis = T0)
        val first = store.rowSnapshot("m1", alice)!!
        assertEquals(T0, first.insertedAtMillis)

        val outcome = store.replaceAll(listOf(makeMessage("m1", campaignId = "camp_2")), alice, nowMillis = T0 + 60_000)
        assertEquals(listOf("m1"), outcome.updatedIds)
        assertEquals(0, outcome.unchanged)

        val second = store.rowSnapshot("m1", alice)!!
        assertEquals("insertedAt must not move on an update", T0, second.insertedAtMillis)
        assertFalse("payload must be rewritten on real content change", second.payload.contentEquals(first.payload))
        assertEquals("camp_2", store.message("m1", alice)!!.campaignId)
    }

    @Test
    fun `byte-identical re-upsert reports unchanged and keeps the row`() {
        val message = makeMessage("m1", campaignId = "camp_1")
        store.replaceAll(listOf(message), alice, nowMillis = T0)
        val before = store.rowSnapshot("m1", alice)!!
        val outcome = store.replaceAll(listOf(message), alice, nowMillis = T0 + 60_000)

        assertEquals(1, outcome.unchanged)
        assertTrue(outcome.updatedIds.isEmpty())
        assertTrue(outcome.insertedIds.isEmpty())
        val after = store.rowSnapshot("m1", alice)!!
        assertEquals(before.insertedAtMillis, after.insertedAtMillis)
        assertArrayEquals(before.payload, after.payload)
    }

    @Test
    fun `key order differences in equivalent payloads do not count as changed`() {
        val first = HightouchMessage.fromRaw(Json.parseToJsonElement("""{"messageId":"m1","campaignId":"camp_1"}"""))
        val second = HightouchMessage.fromRaw(Json.parseToJsonElement("""{"campaignId":"camp_1","messageId":"m1"}"""))
        assertEquals(first, second)

        store.replaceAll(listOf(first), alice, nowMillis = T0)
        val before = store.rowSnapshot("m1", alice)!!
        val outcome = store.replaceAll(listOf(second), alice, nowMillis = T0 + 60_000)

        assertEquals(1, outcome.unchanged)
        assertTrue(outcome.updatedIds.isEmpty())
        assertArrayEquals(before.payload, store.rowSnapshot("m1", alice)!!.payload)
    }

    @Test
    fun `structurally equal rows stored with different bytes count as unchanged`() {
        val message = makeMessage("m1", campaignId = "camp_1")
        store.replaceAll(listOf(message), alice, nowMillis = T0)
        // Simulate a row written by an older encoding: same message, different bytes.
        val reencoded = """{ "messageId": "m1", "campaignId": "camp_1" }""".toByteArray()
        store.database.execSQL(
            "UPDATE messages SET payload = ? WHERE messageId = 'm1'",
            arrayOf<Any>(reencoded),
        )

        val outcome = store.replaceAll(listOf(message), alice, nowMillis = T0 + 1)
        assertEquals(1, outcome.unchanged)
        assertArrayEquals("an unchanged row is not rewritten", reencoded, store.rowSnapshot("m1", alice)!!.payload)
    }

    @Test
    fun `duplicate ids in one input last wins`() {
        val outcome = store.replaceAll(
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
        val outcome = store.replaceAll(messages, alice)
        assertEquals(1000, outcome.insertedIds.size)
        assertEquals(1000, store.count(HightouchMessageQuery(), alice))
        for (boundary in listOf(0, 199, 200, 201, 399, 400, 999)) {
            assertNotNull("msg_$boundary should be present", store.message("msg_$boundary", alice))
        }

        // The same set again takes the chunked existing-row path: nothing changes.
        val again = store.replaceAll(messages, alice, nowMillis = T0)
        assertEquals(1000, again.unchanged)
        assertTrue(again.removedIds.isEmpty())
    }

    @Test
    fun `replace from server deletes omitted rows for the owner and leaves other owners untouched`() {
        store.replaceAll(listOf(makeMessage("a1"), makeMessage("a2"), makeMessage("a3")), alice)
        store.replaceAll(listOf(makeMessage("b1")), bob)

        val outcome = store.replaceAll(listOf(makeMessage("a1")), alice)

        assertEquals(setOf("a2", "a3"), outcome.removedIds.toSet())
        assertEquals(listOf("a1"), store.ids(alice))
        assertNull(store.message("a2", alice))
        assertEquals(listOf("b1"), store.ids(bob))
    }

    @Test
    fun `replacing with an empty list clears the owner`() {
        store.replaceAll(listOf(makeMessage("a1")), alice)
        val outcome = store.replaceAll(emptyList(), alice)
        assertEquals(listOf("a1"), outcome.removedIds)
        assertEquals(0, store.totalCount())
    }

    @Test
    fun `same message id is allowed for two different owners`() {
        store.replaceAll(listOf(makeMessage("shared", campaignId = "for_alice")), alice)
        store.replaceAll(listOf(makeMessage("shared", campaignId = "for_bob")), bob)

        assertEquals(2, store.totalCount())
        assertEquals("for_alice", store.message("shared", alice)!!.campaignId)
        assertEquals("for_bob", store.message("shared", bob)!!.campaignId)
    }

    @Test
    fun `suppressed ids are neither stored nor kept`() {
        store.replaceAll(listOf(makeMessage("m1"), makeMessage("m2")), alice)
        val outcome = store.replaceAll(
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
        store.replaceAll(listOf(makeMessage("m1")), alice)
        try {
            store.replaceAll(listOf(makeMessage("m2")), alice, suppressing = { error("boom") })
        } catch (expected: IllegalStateException) {
        }
        assertEquals(listOf("m1"), store.ids(alice))
    }

    // Store cap: replaceAll limit

    @Test
    fun `replaceAll limit keeps the first N in priority-then-newest order`() {
        val messages = listOf(
            makeMessage("low_new", priority = "low", createdAtMillis = T0 + 2000),
            makeMessage("high_old", priority = "high", createdAtMillis = T0),
            makeMessage("high_new", priority = "high", createdAtMillis = T0 + 1000),
            makeMessage("low_old", priority = "low", createdAtMillis = T0 + 1000),
        )
        val outcome = store.replaceAll(messages, alice, limit = 2)

        assertEquals(setOf("high_new", "high_old"), outcome.insertedIds.toSet())
        assertEquals(listOf("high_new", "high_old"), store.ids(alice))
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `replaceAll limit keeps the highest priority newest messages`() {
        val outcome = store.replaceAll(
            listOf(
                makeMessage("old_default", createdAtMillis = T0),
                makeMessage("undated"),
                makeMessage("new_default", createdAtMillis = T0 + 10_000),
                makeMessage("old_high", priority = "high", createdAtMillis = T0),
            ),
            alice,
            limit = 2,
        )

        assertEquals(setOf("old_high", "new_default"), outcome.insertedIds.toSet())
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `replaceAll limit reports previously stored rows it drops as removed`() {
        val older = makeMessage("older", createdAtMillis = T0)
        val newer = makeMessage("newer", createdAtMillis = T0 + 10_000)
        store.replaceAll(listOf(older), alice)

        val outcome = store.replaceAll(listOf(older, newer), alice, limit = 1)
        assertEquals(listOf("newer"), outcome.insertedIds)
        assertEquals(listOf("older"), outcome.removedIds)

        val repeated = store.replaceAll(listOf(older, newer), alice, limit = 1)
        assertTrue("a capped set must converge, not churn", repeated.insertedIds.isEmpty() && repeated.removedIds.isEmpty())
    }

    @Test
    fun `an owner over the cap converges instead of churning on every sync`() {
        val messages = (0 until 5).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        val first = store.replaceAll(messages, alice, nowMillis = T0, limit = 3)
        assertEquals(3, first.insertedIds.size)

        val second = store.replaceAll(messages, alice, nowMillis = T0 + 1, limit = 3)
        assertTrue(second.insertedIds.isEmpty())
        assertTrue(second.removedIds.isEmpty())
        assertEquals(3, second.unchanged)
        assertEquals(listOf("m2", "m3", "m4"), store.ids(alice))
    }

    @Test
    fun `lowering the limit removes rows beyond it and null is unlimited`() {
        val messages = (0 until 4).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        store.replaceAll(messages, alice)
        assertEquals(4, store.totalCount())

        store.replaceAll(messages, alice, limit = null)
        assertEquals(4, store.totalCount())

        val outcome = store.replaceAll(messages, alice, limit = 1)
        assertEquals(setOf("m0", "m1", "m2"), outcome.removedIds.toSet())
        assertEquals(listOf("m3"), store.ids(alice))
    }

    @Test
    fun `a negative limit keeps nothing as on iOS`() {
        val messages = (0 until 3).map { makeMessage("m$it", createdAtMillis = T0 + it * 1000L) }
        store.replaceAll(messages, alice)

        val outcome = store.replaceAll(messages, alice, limit = -1)
        assertEquals(setOf("m0", "m1", "m2"), outcome.removedIds.toSet())
        assertEquals(0, store.totalCount())
    }

    // Store cap: enforceLimit

    private fun seedFourRows() {
        store.replaceAll(listOf(makeMessage("a_old")), alice, nowMillis = T0)
        store.replaceAll(listOf(makeMessage("b_old")), bob, nowMillis = T0 + 1)
        store.replaceAll(listOf(makeMessage("a_old"), makeMessage("a_new")), alice, nowMillis = T0 + 2)
        store.replaceAll(listOf(makeMessage("b_old"), makeMessage("b_new")), bob, nowMillis = T0 + 3)
    }

    @Test
    fun `enforceLimit evicts oldest by insertedAt globally across owners`() {
        seedFourRows()
        assertEquals(4, store.totalCount())

        assertEquals(2, store.enforceLimit(2))

        assertEquals(2, store.totalCount())
        assertNull(store.message("a_old", alice))
        assertNull(store.message("b_old", bob))
        assertNotNull(store.message("a_new", alice))
        assertNotNull(store.message("b_new", bob))
    }

    @Test
    fun `enforceLimit is a no-op when under the cap`() {
        store.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(0, store.enforceLimit(500))
        assertEquals(1, store.totalCount())
    }

    @Test
    fun `enforceLimit with a negative cap is a no-op`() {
        store.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(0, store.enforceLimit(-1))
        assertEquals(1, store.totalCount())
    }

    @Test
    fun `enforceLimit preserving never evicts the preserved owner`() {
        seedFourRows()

        // alice has the two oldest-inserted rows overall, but she is preserved.
        val evicted = store.enforceLimit(2, preserving = alice)

        assertEquals(2, evicted)
        assertEquals(listOf("a_new", "a_old"), store.ids(alice))
        assertEquals(0, store.ids(bob).size)
    }

    @Test
    fun `enforceLimit preserving can leave the store over the cap`() {
        seedFourRows()
        store.deleteAll(bob)

        assertEquals(0, store.enforceLimit(1, preserving = alice))
        assertEquals(2, store.totalCount())
    }

    // reconcile

    @Test
    fun `reconcile reports the owner's rows that expired as removed`() {
        val lapsing = makeMessage("lapsing", expiresAtMillis = T0 + 10_000)
        store.replaceAll(listOf(lapsing, makeMessage("kept")), alice, nowMillis = T0)

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
        store.replaceAll(
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
