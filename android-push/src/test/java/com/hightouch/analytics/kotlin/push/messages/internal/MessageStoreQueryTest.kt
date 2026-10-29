package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import com.hightouch.analytics.kotlin.push.messages.HightouchMessageSort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreQueryTest {
    private lateinit var store: MessageStore

    @Before
    fun setUp() {
        store = ephemeralStore()
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun ids(query: HightouchMessageQuery, owner: MessageOwner = alice) =
        store.messages(query, owner).map { it.messageId }

    @Test
    fun `location only variant only both and neither filters`() {
        store.replaceAll(
            listOf(
                makeMessage("m_loc_a_var_x", locationId = "loc_a", variantSlug = "var_x"),
                makeMessage("m_loc_a_var_y", locationId = "loc_a", variantSlug = "var_y"),
                makeMessage("m_loc_b_var_x", locationId = "loc_b", variantSlug = "var_x"),
                makeMessage("m_no_location", variantSlug = "var_x"),
            ),
            alice,
        )

        assertEquals(4, ids(HightouchMessageQuery()).size)
        assertEquals(
            setOf("m_loc_a_var_x", "m_loc_a_var_y"),
            ids(HightouchMessageQuery(locationId = "loc_a")).toSet(),
        )
        assertEquals(
            setOf("m_loc_a_var_x", "m_loc_b_var_x", "m_no_location"),
            ids(HightouchMessageQuery(variantSlug = "var_x")).toSet(),
        )
        assertEquals(
            listOf("m_loc_a_var_x"),
            ids(HightouchMessageQuery(locationId = "loc_a", variantSlug = "var_x")),
        )
    }

    @Test
    fun `rows for another owner are never returned by any query`() {
        store.replaceAll(listOf(makeMessage("alice_msg")), alice)
        store.replaceAll(listOf(makeMessage("bob_msg")), bob)

        assertEquals(listOf("bob_msg"), ids(HightouchMessageQuery(), bob))
        assertEquals(1, store.count(HightouchMessageQuery(), bob))
        assertNull(store.message("alice_msg", bob))
        assertNotNull(store.message("alice_msg", alice))
        assertFalse(ids(HightouchMessageQuery(includeExpired = true), bob).contains("alice_msg"))
    }

    @Test
    fun `limit is honored and zero or negative are unlimited`() {
        store.replaceAll((0 until 5).map { makeMessage("m$it") }, alice)

        assertEquals(2, ids(HightouchMessageQuery(limit = 2)).size)
        assertEquals(5, ids(HightouchMessageQuery(limit = 0)).size)
        assertEquals(5, ids(HightouchMessageQuery(limit = -1)).size)
        assertEquals(5, ids(HightouchMessageQuery(limit = null)).size)
    }

    @Test
    fun `exact id sequence for mixed priority rank and createdAt`() {
        store.replaceAll(
            listOf(
                makeMessage("low_old", priority = "low", createdAtMillis = T0),
                makeMessage("high_old", priority = "high", createdAtMillis = T0),
                makeMessage("low_new", priority = "low", createdAtMillis = T0 + 5000),
                makeMessage("high_new", priority = "high", createdAtMillis = T0 + 5000),
            ),
            alice,
        )

        assertEquals(
            listOf("high_new", "high_old", "low_new", "low_old"),
            ids(HightouchMessageQuery(sort = HightouchMessageSort.PRIORITY_THEN_NEWEST)),
        )
        assertEquals(
            listOf("high_old", "high_new", "low_old", "low_new"),
            ids(HightouchMessageQuery(sort = HightouchMessageSort.PRIORITY_THEN_OLDEST)),
        )
        assertEquals(
            listOf("high_new", "low_new", "high_old", "low_old"),
            ids(HightouchMessageQuery(sort = HightouchMessageSort.NEWEST_FIRST)),
        )
        assertEquals(
            listOf("high_old", "low_old", "high_new", "low_new"),
            ids(HightouchMessageQuery(sort = HightouchMessageSort.OLDEST_FIRST)),
        )
    }

    @Test
    fun `messages without createdAt sort as oldest`() {
        store.replaceAll(
            listOf(makeMessage("dated", createdAtMillis = T0), makeMessage("undated")),
            alice,
        )
        assertEquals(listOf("dated", "undated"), ids(HightouchMessageQuery(sort = HightouchMessageSort.NEWEST_FIRST)))
        assertEquals(listOf("undated", "dated"), ids(HightouchMessageQuery(sort = HightouchMessageSort.OLDEST_FIRST)))
    }

    @Test
    fun `total order is stable across 20 repeated fetches for identical rank and date`() {
        // Inserted in scrambled order so only the messageId tiebreak can produce the expected order.
        val inserted = listOf("m7", "m2", "m9", "m0", "m5", "m1", "m8", "m3", "m6", "m4")
        store.replaceAll(inserted.map { makeMessage(it, priority = "high", createdAtMillis = T0) }, alice)
        val expected = inserted.sorted()

        for (sort in HightouchMessageSort.values()) {
            repeat(20) { assertEquals(expected, ids(HightouchMessageQuery(sort = sort))) }
        }
    }

    @Test
    fun `location id containing quote and percent matches correctly`() {
        val tricky = "it's 100%_\"banner\"; DROP TABLE messages;--"
        store.replaceAll(
            listOf(makeMessage("tricky", locationId = tricky), makeMessage("other", locationId = "home_banner")),
            alice,
        )

        assertEquals(listOf("tricky"), ids(HightouchMessageQuery(locationId = tricky)))
        assertEquals(listOf("other"), ids(HightouchMessageQuery(locationId = "home_banner")))
        assertEquals(2, store.totalCount())
    }

    @Test
    fun `message with id returns null when absent`() {
        assertNull(store.message("nope", alice))
    }

    @Test
    fun `count matches filtered messages count`() {
        store.replaceAll(
            listOf(
                makeMessage("a", locationId = "loc_a"),
                makeMessage("b", locationId = "loc_a"),
                makeMessage("c", locationId = "loc_b"),
            ),
            alice,
        )
        assertEquals(2, store.count(HightouchMessageQuery(locationId = "loc_a"), alice))
        assertEquals(3, store.count(HightouchMessageQuery(), alice))
    }

    @Test
    fun `a stored message round-trips through the payload`() {
        val message = makeMessage("m1", campaignId = "c", locationId = "l", priority = "high", createdAtMillis = T0)
        store.replaceAll(listOf(message), alice)
        assertEquals(message, store.message("m1", alice))
        assertEquals(listOf(message), store.messages(HightouchMessageQuery(), alice))
    }

    @Test
    fun `rows whose payload cannot be decoded are skipped`() {
        store.replaceAll(listOf(makeMessage("good")), alice)
        store.database.execSQL(
            "INSERT INTO messages (messageId, ownerKey, payload, insertedAt, updatedAt) VALUES ('bad', ?, x'7B', 0, 0)",
            arrayOf(alice.storageKey),
        )
        assertEquals(listOf("good"), ids(HightouchMessageQuery()))
        assertNull(store.message("bad", alice))
    }
}
