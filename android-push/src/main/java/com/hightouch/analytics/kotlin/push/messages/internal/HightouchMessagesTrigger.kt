package com.hightouch.analytics.kotlin.push.messages.internal

import android.util.Log
import com.hightouch.analytics.kotlin.core.reportInternalError
import com.hightouch.analytics.kotlin.push.CepEventTracking
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.messages.HightouchMessages
import com.hightouch.analytics.kotlin.push.messages.HightouchSyncOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A ghost-push command arrived, but `HightouchPushConfig.messages` isn't set. The server only sends
 * commands for in-app campaigns linked to this app, so this is a configuration mismatch.
 */
internal class MessagesNotEnabledError(val verb: String) : Exception(
    "'$verb' command received, but embedded messages are not enabled. Set " +
        "HightouchPushConfig.Builder.setMessages(...) before initialize().",
)

/**
 * Executes a ghost-push command inside FCM's `onMessageReceived` budget.
 *
 * Android has no `UIBackgroundFetchResult` and no background-task API to extend the budget, so
 * [handle] blocks the calling thread (FCM's message-handling thread, never the main thread)
 * until the command finishes or [SELF_IMPOSED_DEADLINE_MILLIS] passes, whichever comes first.
 * That matches how silent pushes already run (`HightouchSilentPushListener` is called inline on
 * the same thread) and keeps the process alive for the whole command, which a fire-and-forget
 * sync would not: FCM may freeze the app once `onMessageReceived` returns. WorkManager is not
 * used because it would only add a scheduling delay to a command that is meant to be prompt, and
 * the sync is a single short request.
 */
internal object HightouchMessagesTrigger {

    private const val TAG = "HightouchPush"

    /**
     * FCM allows `onMessageReceived` about 20 seconds before the process may be stopped (iOS allows
     * ~30s and the Swift SDK stops at 20). Half of FCM's budget leaves room for the `delivered`
     * event and its flush after the deadline, and for any other work the host does in the same
     * callback.
     */
    const val SELF_IMPOSED_DEADLINE_MILLIS = 10_000L

    /**
     * The outcome of a command, mirroring iOS's `UIBackgroundFetchResult`. Android has no OS-level
     * counterpart, so it is only reported through `completion` (for logging and tests).
     */
    enum class Result { NEW_DATA, NO_DATA, FAILED }

    /**
     * Runs [command], then calls [completion] exactly once, on the calling thread, with the result.
     * A `delivered` event is tracked and flushed before it, whichever path finishes first.
     *
     * `isInitialized`, `messagesEnabled`, `deadlineMillis` and `sync` are test seams; `sync` calls
     * back on any thread.
     */
    fun handle(
        command: HTMessageCommand,
        completion: (Result) -> Unit = {},
        isInitialized: () -> Boolean = { HightouchPush.isInitialized },
        messagesEnabled: () -> Boolean = { HightouchMessages.isInitialized },
        deadlineMillis: Long = SELF_IMPOSED_DEADLINE_MILLIS,
        sync: (HTMessageCommand, (HightouchSyncOutcome) -> Unit) -> Unit = ::defaultSync,
    ) {
        val verb = command.verb.wireValue

        // FCM can deliver a push into a background-started process before the host calls initialize.
        if (!isInitialized()) {
            Log.w(
                TAG,
                "Ignoring '$verb' command: HightouchPush.initialize() has not run. Call it in " +
                    "Application.onCreate - FCM can deliver a message to a freshly started process.",
            )
            completion(Result.NO_DATA)
            return
        }

        if (!messagesEnabled()) {
            HightouchPush.cepAnalytics?.reportInternalError(MessagesNotEnabledError(verb))
            completion(Result.NO_DATA)
            return
        }

        val completer = TriggerCompletion(
            completion = completion,
            emitDelivered = { emitDeliveredEvent(command, it) },
            flush = { HightouchPush.cepAnalytics?.flush() },
        )

        val outcome = AtomicReference<Pair<Result, Int>>()
        val finished = CountDownLatch(1)
        execute(command, sync) { result, messageCount ->
            outcome.set(result to messageCount)
            finished.countDown()
        }

        // NO_DATA, not FAILED: running out of time isn't a failed sync. The command keeps going;
        // its result is just not reported.
        val inTime = try {
            if (deadlineMillis > 0) finished.await(deadlineMillis, TimeUnit.MILLISECONDS) else {
                finished.await()
                true
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (inTime) {
            val (result, messageCount) = outcome.get()
            completer.complete(result, messageCount)
        } else {
            Log.w(TAG, "'$verb' command did not finish within ${deadlineMillis}ms; reporting no data.")
            completer.complete(Result.NO_DATA, 0)
        }
    }

    private fun defaultSync(command: HTMessageCommand, callback: (HightouchSyncOutcome) -> Unit) {
        HightouchMessages.sync(force = true, completion = callback)
    }

    private fun execute(
        command: HTMessageCommand,
        sync: (HTMessageCommand, (HightouchSyncOutcome) -> Unit) -> Unit,
        onResult: (Result, Int) -> Unit,
    ) {
        when (command.verb) {
            HTMessageCommand.Verb.SYNC_MESSAGES -> sync(command) { outcome ->
                when (outcome) {
                    is HightouchSyncOutcome.Changed -> onResult(Result.NEW_DATA, outcome.messageCount)
                    HightouchSyncOutcome.Unchanged -> onResult(Result.NO_DATA, 0)
                    is HightouchSyncOutcome.Failed -> onResult(Result.FAILED, 0)
                }
            }
        }
    }

    private fun emitDeliveredEvent(command: HTMessageCommand, messageCount: Int) {
        val properties = buildJsonObject {
            put("provider_event_type", CepEventTracking.PUSH_DELIVERED)
            put("sync_reason", command.verb.wireValue)
            put("message_count", messageCount)
            // Absent hints are omitted rather than sent as JSON null.
            command.pushMessageId?.let { put("push_message_id", it) }
            command.reason?.let { put("reason", it) }
            command.messageIds?.let { ids -> put("message_ids", buildJsonArray { for (id in ids) add(JsonPrimitive(id)) }) }
            command.locationIds?.let { ids -> put("location_ids", buildJsonArray { for (id in ids) add(JsonPrimitive(id)) }) }
            command.syncToken?.let { put("sync_token", it) }
        }
        CepEventTracking.track(
            name = CepEventTracking.ENGAGEMENT_EVENTS,
            properties = properties,
            messageContext = command.messageContext ?: JsonObject(emptyMap()),
            channel = DELIVERED_CHANNEL,
        )
    }

    /** Same channel as iOS, which tracks command deliveries as push events. */
    private val DELIVERED_CHANNEL = CepEventTracking.Channel.PUSH
}

/**
 * Ensures the completion runs once, whether the command finishes or the deadline fires first
 * (the late result is dropped). Whichever path wins also emits the `delivered` event and flushes
 * it before completing, so it isn't left queued in a process that may be frozen as soon as
 * `onMessageReceived` returns. iOS skips the flush in the foreground because the app-background
 * flush covers it; Android has no such hook, so it always flushes.
 */
private class TriggerCompletion(
    private val completion: (HightouchMessagesTrigger.Result) -> Unit,
    private val emitDelivered: (Int) -> Unit,
    private val flush: () -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete(result: HightouchMessagesTrigger.Result, messageCount: Int) {
        if (!completed.compareAndSet(false, true)) return
        emitDelivered(messageCount)
        flush()
        completion(result)
    }
}
