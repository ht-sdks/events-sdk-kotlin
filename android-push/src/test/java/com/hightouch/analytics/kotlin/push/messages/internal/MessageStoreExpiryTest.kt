package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import org.junit.After
import org.junit.Assert.assertEquals
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
class MessageStoreExpiryTest {
    private lateinit var store: MessageStore

    @Before
    fun setUp() {
        store = ephemeralStore()
    }

    @After
    fun tearDown() {
        store.close()
    }

    // Query-time filtering

    @Test
    fun `expired excluded by default included with flag`() {
        store.replaceAll(
            listOf(
                makeMessage("expired", expiresAtMillis = T0 - 60_000),
                makeMessage("future", expiresAtMillis = T0 + 60_000),
            ),
            alice,
        )

        val visible = store.messages(HightouchMessageQuery(nowMillis = T0), alice)
        assertEquals(listOf("future"), visible.map { it.messageId })

        val all = store.messages(HightouchMessageQuery(includeExpired = true, nowMillis = T0), alice)
        assertEquals(setOf("expired", "future"), all.map { it.messageId }.toSet())
    }

    @Test
    fun `expiresAt null is always included`() {
        store.replaceAll(listOf(makeMessage("never_expires")), alice)
        val visible = store.messages(HightouchMessageQuery(nowMillis = T0), alice)
        assertEquals(listOf("never_expires"), visible.map { it.messageId })
    }

    @Test
    fun `boundary expiresAt equal to now is expired`() {
        store.replaceAll(listOf(makeMessage("boundary", expiresAtMillis = T0)), alice)

        assertTrue(store.messages(HightouchMessageQuery(nowMillis = T0), alice).isEmpty())
        assertEquals(0, store.count(HightouchMessageQuery(nowMillis = T0), alice))
        assertEquals(1, store.count(HightouchMessageQuery(nowMillis = T0 - 1), alice))
    }

    @Test
    fun `without nowMillis the query uses the current time`() {
        val now = System.currentTimeMillis()
        store.replaceAll(
            listOf(
                makeMessage("past", expiresAtMillis = now - 3_600_000),
                makeMessage("future", expiresAtMillis = now + 3_600_000),
            ),
            alice,
        )
        assertEquals(listOf("future"), store.messages(HightouchMessageQuery(), alice).map { it.messageId })
    }

    // pruneExpired

    @Test
    fun `prune removes only expired rows across owners and reports count`() {
        store.replaceAll(
            listOf(
                makeMessage("expired_1", expiresAtMillis = T0 - 60_000),
                makeMessage("expired_2", expiresAtMillis = T0 - 1_000),
                makeMessage("future", expiresAtMillis = T0 + 60_000),
                makeMessage("never_expires"),
            ),
            alice,
        )
        store.replaceAll(listOf(makeMessage("bob_expired", expiresAtMillis = T0 - 1_000)), bob)

        assertEquals(3, store.pruneExpired(T0))
        assertEquals(2, store.totalCount())
        assertNotNull(store.message("future", alice))
        assertNotNull(store.message("never_expires", alice))
    }

    @Test
    fun `prune boundary expiresAt equal to now is pruned`() {
        store.replaceAll(listOf(makeMessage("boundary", expiresAtMillis = T0)), alice)
        assertEquals(1, store.pruneExpired(T0))
        assertEquals(0, store.totalCount())
    }

    @Test
    fun `prune then read sees the deletion`() {
        store.replaceAll(listOf(makeMessage("expired", expiresAtMillis = T0 - 60_000)), alice)
        assertNotNull(store.message("expired", alice))

        assertEquals(1, store.pruneExpired(T0))

        assertNull(store.message("expired", alice))
        assertTrue(store.messages(HightouchMessageQuery(includeExpired = true), alice).isEmpty())
    }

    @Test
    fun `prune with nothing expired removes nothing`() {
        store.replaceAll(listOf(makeMessage("m1", expiresAtMillis = T0 + 1_000)), alice)
        assertEquals(0, store.pruneExpired(T0))
        assertEquals(1, store.totalCount())
    }
}
