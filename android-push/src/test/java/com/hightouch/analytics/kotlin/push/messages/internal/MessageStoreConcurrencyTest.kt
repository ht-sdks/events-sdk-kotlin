package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.push.messages.HightouchMessageQuery
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreConcurrencyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores = mutableListOf<MessageStore>()

    private fun fileStore(name: String = "messages.db"): MessageStore =
        MessageStore(appContext(), MessageStoreLocation.Explicit(File(tmp.root, name))).also { stores.add(it) }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
    }

    /** Runs [work] for 0 until [count] on [count] threads at once and rethrows the first failure. */
    private fun runConcurrently(count: Int, work: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(count)
        for (i in 0 until count) {
            pool.execute {
                try {
                    start.await()
                    work(i)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        failure.get()?.let { throw it }
    }

    private fun checkOverlappingReplaceAll(store: MessageStore) {
        val owners = (0 until 8).map { MessageOwner(MessageOwner.Kind.USER_ID, "owner_$it") }
        val sharedIds = (0 until 20).map { "shared_$it" }

        runConcurrently(owners.size) { index ->
            val ids = sharedIds + "unique_$index"
            store.replaceAll(ids.map { makeMessage(it) }, owners[index])
        }

        val expectedPerOwner = sharedIds.size + 1
        assertEquals(owners.size * expectedPerOwner, store.totalCount())
        owners.forEachIndexed { index, owner ->
            assertEquals(expectedPerOwner, store.count(HightouchMessageQuery(), owner))
            assertNotNull(store.message("unique_$index", owner))
        }
    }

    @Test
    fun `8 concurrent replaceAll across owners with overlapping ids union with no crash`() {
        checkOverlappingReplaceAll(fileStore())
    }

    @Test
    fun `8 concurrent replaceAll works on an in-memory store too`() {
        val store = ephemeralStore().also { stores.add(it) }
        checkOverlappingReplaceAll(store)
    }

    @Test
    fun `concurrent replaceAll for the same owner ends in one of the submitted sets`() {
        val store = fileStore()
        runConcurrently(6) { index ->
            repeat(5) {
                store.replaceAll((0 until 30).map { makeMessage("w${index}_$it") }, alice)
            }
        }
        val ids = store.ids(alice)
        assertEquals(30, ids.size)
        assertEquals(1, ids.map { it.substringBefore('_') }.toSet().size)
    }

    @Test
    fun `read during a large in-flight write returns a consistent snapshot`() {
        val store = fileStore()
        val messages = (0 until 2000).map { makeMessage("m$it") }
        val writing = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                store.replaceAll(messages, alice)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                writing.set(false)
            }
        }
        writer.start()

        // A read sees the sync entirely or not at all, never part of it.
        var reads = 0
        while (writing.get() || reads < 20) {
            val count = store.count(HightouchMessageQuery(), alice)
            assertTrue("expected a consistent snapshot, got $count", count == 0 || count == 2000)
            reads++
        }
        writer.join(30_000)
        failure.get()?.let { throw it }
        assertEquals(2000, store.count(HightouchMessageQuery(), alice))
    }

    @Test
    fun `reads and deletes interleaved with writes never resurrect deleted rows`() {
        val store = fileStore()
        store.replaceAll((0 until 50).map { makeMessage("m$it") }, alice)

        runConcurrently(4) { index ->
            when (index) {
                0 -> repeat(20) { store.messages(HightouchMessageQuery(), alice) }
                1 -> repeat(20) { store.count(HightouchMessageQuery(), alice) }
                2 -> store.delete((0 until 50).map { "m$it" }, alice)
                else -> repeat(20) { store.pruneExpired() }
            }
        }
        assertEquals(emptyList<String>(), store.ids(alice))
        assertNull(store.message("m10", alice))
    }

    // Persistence (real files)

    @Test
    fun `rows survive closing and reopening a file-backed store`() {
        val file = File(tmp.root, "persist.db")
        val first = MessageStore(appContext(), MessageStoreLocation.Explicit(file))
        first.replaceAll(listOf(makeMessage("m1", campaignId = "c1")), alice, nowMillis = T0)
        first.close()

        val second = MessageStore(appContext(), MessageStoreLocation.Explicit(file)).also { stores.add(it) }
        assertEquals("c1", second.message("m1", alice)!!.campaignId)
        assertEquals(T0, second.rowSnapshot("m1", alice)!!.insertedAtMillis)
    }

    @Test
    fun `a deleted row stays deleted after reopening`() {
        val file = File(tmp.root, "persist-delete.db")
        val first = MessageStore(appContext(), MessageStoreLocation.Explicit(file))
        first.replaceAll(listOf(makeMessage("m1"), makeMessage("m2")), alice)
        first.delete("m1", alice)
        first.close()

        val second = MessageStore(appContext(), MessageStoreLocation.Explicit(file)).also { stores.add(it) }
        assertEquals(listOf("m2"), second.ids(alice))
    }

    @Test
    fun `an unreadable database file is destroyed and the store starts empty`() {
        val file = File(tmp.root, "corrupt.db")
        file.writeBytes(ByteArray(4096) { 0x42 })

        val store = MessageStore(appContext(), MessageStoreLocation.Explicit(file)).also { stores.add(it) }
        assertEquals(0, store.totalCount())
        store.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(listOf("m1"), store.ids(alice))
    }

    @Test
    fun `ephemeral stores are independent of each other`() {
        val a = ephemeralStore().also { stores.add(it) }
        val b = ephemeralStore().also { stores.add(it) }
        a.replaceAll(listOf(makeMessage("m1")), alice)
        assertEquals(0, b.totalCount())
    }
}
