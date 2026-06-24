package com.hightouch.analytics.kotlin.push.notification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a notification rich-media attachment over HTTP.
 *
 * Synchronous and blocking by design — invoked from [HightouchNotificationWorker] on a WorkManager
 * background thread (not FCM's binder thread, whose short execution budget a slow image host could
 * blow). Returns null on any failure (timeout, non-2xx, non-image content, decode error) so the
 * notification can still be posted without the image.
 *
 * Mirrors the iOS Notification Service Extension's `retrieveAttachment` behavior in
 * `HightouchNotificationServiceExtension.swift` — best-effort fetch within a bounded budget.
 */
internal object RichMediaLoader {

    private const val TAG = "HightouchPush"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 5 * 1024 * 1024 // 5 MB

    fun load(url: String): Bitmap? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol != "https" && parsed.protocol != "http") return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Rich media fetch failed: HTTP $code for $url")
                return null
            }
            // Reject early when the advisory Content-Length already exceeds the cap. It's -1/0
            // when the server doesn't report a size (chunked transfer encoding, gzip), which is
            // common for CDNs — download those anyway, but readCapped enforces MAX_BYTES while
            // reading so an unbounded or oversized response can't exhaust memory.
            val contentLength = connection.contentLength
            if (contentLength > MAX_BYTES) {
                Log.w(TAG, "Rich media payload too large ($contentLength bytes): $url")
                return null
            }
            val bytes = connection.inputStream.use { readCapped(it, MAX_BYTES) }
            if (bytes == null) {
                Log.w(TAG, "Rich media payload exceeded $MAX_BYTES bytes: $url")
                return null
            }
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: OutOfMemoryError) {
                // decodeByteArray allocates the full decoded bitmap, which can dwarf the
                // compressed bytes; keep the fetch best-effort instead of crashing the worker.
                Log.w(TAG, "Rich media decode ran out of memory for $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rich media fetch failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads the stream into memory, returning null as soon as it would exceed [max] bytes. */
    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}
