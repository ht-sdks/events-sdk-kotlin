package com.hightouch.analytics.kotlin.push.messages.internal

import android.util.Base64
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesConfig
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A non-2xx response. Retryable only for 5xx. */
internal class MessagesHttpStatusException(val statusCode: Int) :
    IOException("HTTP $statusCode from the messages gateway")

/** The raw response of one request. */
internal class MessagesTransportResponse(val statusCode: Int, val body: ByteArray)

/**
 * Executes one request. Injectable so tests use a fake; the default talks to the network.
 * Throws [IOException] for transport failures.
 */
internal fun interface MessagesTransport {
    fun execute(request: HightouchMessagesRequest): MessagesTransportResponse
}

/** `HttpURLConnection`, like core's `RequestFactory`. */
internal object HttpUrlConnectionTransport : MessagesTransport {
    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 60_000

    override fun execute(request: HightouchMessagesRequest): MessagesTransportResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.doInput = true
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return MessagesTransportResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Client for the in-app gateway (`cep-inapp-gateway`). Mirrors iOS `MessagesHTTPClient`.
 *
 * Blocking: call it off the main thread.
 */
internal class MessagesHTTPClient(
    private val analytics: Analytics,
    config: HightouchMessagesConfig,
    private val transport: MessagesTransport = HttpUrlConnectionTransport,
) {
    private val host = config.messagesHost ?: HightouchMessagesConfig.DEFAULT_HOST
    private val path = config.messagesPath
    private val writeKey = analytics.configuration.writeKey
    private val requestFactory = config.requestFactory

    /**
     * [deviceToken] is the FCM token. Throws [MessagesHttpStatusException] for a non-2xx status,
     * [MissingMessagesArrayError] for a 2xx body without a `messages` array, and [IOException] for
     * transport failures.
     */
    fun fetchMessages(owner: MessageOwner, deviceToken: String): MessagesFetchResponse {
        val response = transport.execute(buildRequest(owner, deviceToken))
        if (response.statusCode !in 200..299) throw MessagesHttpStatusException(response.statusCode)
        return MessagesFetchResponse.parse(response.body)
    }

    /**
     * Gateway contract: Basic auth selects the workspace; the device token must be registered to
     * the user id or the inbox comes back empty (anonymous ids always do). Identity stays out of
     * the URL so it never reaches access logs.
     *
     * No `Accept-Encoding`: `HttpURLConnection` negotiates gzip itself and decodes it only when
     * the header was left unset.
     */
    internal fun buildRequest(owner: MessageOwner, deviceToken: String): HightouchMessagesRequest {
        // Same loopback rule as core's RequestFactory: 10.0.2.2 reaches the host from an emulator.
        val scheme = if (host.startsWith("localhost") || host.startsWith("10.")) "http" else "https"
        val credentials = Base64.encodeToString("$writeKey:".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val request = HightouchMessagesRequest(
            url = "$scheme://$host$path",
            headers = linkedMapOf(
                "Accept" to "application/json; charset=utf-8",
                "User-Agent" to "events-sdk-kotlin/${Analytics.version()}",
                "Authorization" to "Basic $credentials",
                "x-hightouch-user-id" to owner.id,
                "x-hightouch-device-token" to deviceToken,
            ),
        )
        return requestFactory?.invoke(request) ?: request
    }
}
