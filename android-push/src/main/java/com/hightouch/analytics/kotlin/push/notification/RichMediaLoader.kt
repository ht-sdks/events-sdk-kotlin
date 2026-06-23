package com.hightouch.analytics.kotlin.push.notification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
            // contentLength is -1 when the server doesn't report a size (chunked transfer
            // encoding, gzip), which is common for CDNs — attempt the download anyway rather
            // than rejecting valid images. Note this means MAX_BYTES is only a best-effort
            // guard based on the advisory Content-Length header; it is not enforced while
            // reading the stream.
            val contentLength = connection.contentLength
            if (contentLength in 1..MAX_BYTES || contentLength <= 0) {
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                Log.w(TAG, "Rich media payload too large ($contentLength bytes): $url")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rich media fetch failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
