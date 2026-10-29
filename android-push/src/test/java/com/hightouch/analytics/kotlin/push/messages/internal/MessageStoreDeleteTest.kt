package com.hightouch.analytics.kotlin.push.messages.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The invariant under test: deleted rows never come back from a subsequent read. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreDeleteTest {
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
    fun `delete removes only the named message`() {
        store.replaceAll(listOf(makeMessage("m1"), makeMessage("m2"), makeMessage("m3")), alice)

        assertEquals(1, store.delete("m2", alice))
        assertEquals(listOf("m1", "m3"), store.ids(alice))
        assertNull(store.message("m2", alice))
    }

    @Test
    fun `delete is scoped to owner`() {
        store.replaceAll(listOf(makeMessage("shared")), alice)
        store.replaceAll(listOf(makeMessage("shared")), anon)

        assertEquals(1, store.delete("shared", alice))
        assertEquals(emptyList<String>(), store.ids(alice))
        assertEquals("another owner's identically-named row must survive", listOf("shared"), store.ids(anon))
    }

    @Test
    fun `deleting an unknown message removes nothing and does not throw`() {
        store.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(0, store.delete("nope", alice))
        assertEquals(listOf("m1"), store.ids(alice))
    }

    @Test
    fun `delete of many ids spans the chunk size`() {
        val all = (0 until 450).map { "m$it" }
        store.replaceAll(all.map { makeMessage(it) }, alice)

        assertEquals(449, store.delete(all.drop(1) + "missing", alice))
        assertEquals(listOf("m0"), store.ids(alice))
    }

    @Test
    fun `delete with an empty list removes nothing`() {
        store.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(0, store.delete(emptyList(), alice))
        assertEquals(listOf("m1"), store.ids(alice))
    }

    @Test
    fun `deleteAll removes only that owner and reports the ids`() {
        store.replaceAll(listOf(makeMessage("a1"), makeMessage("a2")), alice)
        store.replaceAll(listOf(makeMessage("b1")), bob)

        assertEquals(setOf("a1", "a2"), store.deleteAll(alice).toSet())
        assertEquals(emptyList<String>(), store.ids(alice))
        assertEquals(listOf("b1"), store.ids(bob))
        assertEquals(emptyList<String>(), store.deleteAll(alice))
    }

    @Test
    fun `deleteAllOwners clears every owner`() {
        store.replaceAll(listOf(makeMessage("a1")), alice)
        store.replaceAll(listOf(makeMessage("b1")), bob)

        assertEquals(setOf("a1", "b1"), store.deleteAllOwners().toSet())
        assertEquals(0, store.totalCount())
        assertEquals(emptyList<String>(), store.deleteAllOwners())
    }

    @Test
    fun `a message can be re-inserted after it was deleted`() {
        store.replaceAll(listOf(makeMessage("m1", campaignId = "old")), alice)
        store.delete("m1", alice)
        val outcome = store.replaceAll(listOf(makeMessage("m1", campaignId = "new")), alice)

        assertEquals(listOf("m1"), outcome.insertedIds)
        assertEquals("new", store.message("m1", alice)!!.campaignId)
    }
}
