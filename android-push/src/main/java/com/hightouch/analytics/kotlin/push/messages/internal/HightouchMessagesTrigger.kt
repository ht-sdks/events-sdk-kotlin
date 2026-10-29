package com.hightouch.analytics.kotlin.push.messages.internal

import android.util.Log
import com.hightouch.analytics.kotlin.push.CepEventTracking
import com.hightouch.analytics.kotlin.push.HTPushPayload
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
     * `isInitialized`, `deadlineMillis`, `sync` and `deleteMessage` are test seams; `sync` and
     * `deleteMessage` call back on any thread.
     *
     * @param data the FCM `data` map the command was parsed from, so a push carrying both a command
     *   and `customData` still reaches the silent-push listener.
     */
    fun handle(
        command: HTMessageCommand,
        data: Map<String, String>,
        completion: (Result) -> Unit = {},
        isInitialized: () -> Boolean = { HightouchPush.isInitialized },
        deadlineMillis: Long = SELF_IMPOSED_DEADLINE_MILLIS,
        sync: (HTMessageCommand, (HightouchSyncOutcome) -> Unit) -> Unit = ::defaultSync,
        deleteMessage: (String, (Boolean) -> Unit) -> Unit = HightouchMessages::deleteMessage,
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

        if (!HightouchMessages.isInitialized) {
            Log.w(
                TAG,
                "'$verb' command received, but embedded messages are not enabled, so it will have " +
                    "no effect. Set HightouchPushConfig.Builder.setMessages(...) before initialize().",
            )
        }

        val completer = TriggerCompletion(
            completion = completion,
            emitDelivered = { emitDeliveredEvent(command, it) },
            flush = { HightouchPush.cepAnalytics?.flush() },
        )

        val outcome = AtomicReference<Pair<Result, Int>>()
        val finished = CountDownLatch(1)
        execute(command, sync, deleteMessage) { result, messageCount ->
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
            deliverCustomData(data)
            completer.complete(result, messageCount)
        } else {
            Log.w(TAG, "'$verb' command did not finish within ${deadlineMillis}ms; reporting no data.")
            completer.complete(Result.NO_DATA, 0)
            deliverCustomData(data)
        }
    }

    private fun defaultSync(command: HTMessageCommand, callback: (HightouchSyncOutcome) -> Unit) {
        HightouchMessages.sync(force = true, completion = callback)
    }

    private fun execute(
        command: HTMessageCommand,
        sync: (HTMessageCommand, (HightouchSyncOutcome) -> Unit) -> Unit,
        deleteMessage: (String, (Boolean) -> Unit) -> Unit,
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

            HTMessageCommand.Verb.DELETE_MESSAGE -> {
                // NO_DATA for a missing operand: it isn't a failure of the command.
                val messageId = command.messageId
                if (messageId.isNullOrEmpty()) {
                    onResult(Result.NO_DATA, 0)
                } else {
                    deleteMessage(messageId) { deleted ->
                        if (deleted) onResult(Result.NEW_DATA, 1) else onResult(Result.NO_DATA, 0)
                    }
                }
            }
        }
    }

    /**
     * A push can carry both a command and `customData`; this path owns the command, so it also
     * delivers the `customData` that the silent-push path would have.
     */
    private fun deliverCustomData(data: Map<String, String>) {
        val customData = HTPushPayload.parse(data)?.customData?.takeIf { it.isNotEmpty() } ?: return
        val listener = HightouchPush.cepSilentPushListener ?: return
        try {
            listener.onSilentPush(customData)
        } catch (e: Exception) {
            Log.e(TAG, "HightouchSilentPushListener threw while handling a command push", e)
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
