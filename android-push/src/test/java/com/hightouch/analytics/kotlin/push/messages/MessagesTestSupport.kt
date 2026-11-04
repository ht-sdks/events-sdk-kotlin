package com.hightouch.analytics.kotlin.push.messages

import android.content.Context
import android.os.Looper
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.Configuration
import com.hightouch.analytics.kotlin.core.platform.plugins.DeviceToken
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesTransport
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesTransportResponse
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.robolectric.Shadows.shadowOf

/** A relaxed mock [Analytics] with the identity and write key the messages code reads. */
internal fun messagesAnalytics(
    context: Context,
    userId: String? = "alice",
    anonymousId: String = "anon-1",
    writeKey: String = "wk-test",
    errors: MutableList<Throwable>? = null,
): Analytics {
    val configuration = mockk<Configuration>(relaxed = true)
    every { configuration.application } returns context
    every { configuration.writeKey } returns writeKey
    if (errors != null) every { configuration.errorHandler } returns { errors.add(it) }
    return mockk<Analytics>(relaxed = true).also {
        every { it.configuration } returns configuration
        every { it.userId() } returns userId
        every { it.anonymousId() } returns anonymousId
        every { it.enabled } returns true
        every { it.find(DeviceToken::class) } returns null
    }
}

/** The transport stand-in: queued responses (the last repeats), captured requests. Thread-safe. */
internal class FakeTransport : MessagesTransport {
    private val lock = Any()
    private val queued = ArrayDeque<() -> MessagesTransportResponse>()
    private var last: () -> MessagesTransportResponse = { response(200, emptyBody()) }
    private val _requests = ArrayList<com.hightouch.analytics.kotlin.push.messages.HightouchMessagesRequest>()

    val requests: List<HightouchMessagesRequest> get() = synchronized(lock) { ArrayList(_requests) }
    val requestCount: Int get() = synchronized(lock) { _requests.size }

    fun enqueue(status: Int, body: ByteArray = ByteArray(0)) {
        synchronized(lock) { queued.addLast { MessagesTransportResponse(status, body) } }
    }

    fun enqueueIds(vararg ids: String) = enqueue(200, messagesBody(*ids))

    fun enqueueFailure(error: IOException) {
        synchronized(lock) { queued.addLast { throw error } }
    }

    override fun execute(request: HightouchMessagesRequest): MessagesTransportResponse {
        val next = synchronized(lock) {
            _requests.add(request)
            if (queued.isNotEmpty()) queued.removeFirst().also { last = it } else last
        }
        return next()
    }

    companion object {
        fun response(status: Int, body: ByteArray) = MessagesTransportResponse(status, body)
        fun emptyBody(): ByteArray = """{"messages":[]}""".toByteArray()
    }
}

internal fun messagesBody(vararg ids: String): ByteArray = buildJsonObject {
    put("messages", buildJsonArray {
        for (id in ids) add(buildJsonObject { put("messageId", id) })
    })
}.toString().toByteArray()

/** Runs [block] on a worker's behalf and waits for [count] completions. */
internal class Completions(count: Int = 1) {
    private val latch = CountDownLatch(count)
    fun done() = latch.countDown()
    fun await(timeoutSeconds: Long = 5): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS)
}

/**
 * Waits until [condition] holds, running the main looper meanwhile: under Robolectric the test
 * thread is the main thread, so work posted to it only runs when the looper is idled.
 */
internal fun awaitMain(timeoutMillis: Long = 5_000, condition: () -> Boolean = { false }): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return true
        Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
    return condition()
}

/** Lets already-queued work (and anything it posts to main) run, then returns. */
internal fun settle(millis: Long = 300) {
    val until = System.currentTimeMillis() + millis
    awaitMain(millis) { System.currentTimeMillis() >= until }
}

