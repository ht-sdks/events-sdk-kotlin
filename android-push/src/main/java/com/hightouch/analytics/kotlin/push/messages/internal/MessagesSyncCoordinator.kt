package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.reportInternalError
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesChange
import com.hightouch.analytics.kotlin.push.messages.HightouchMessagesConfig
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal typealias SyncCompletion = (Result<HightouchMessagesChange>) -> Unit

/**
 * Fetches messages and reconciles them into the store. Cooldown and coalescing are per owner: a
 * sync for a different owner than the one in flight is queued behind it, not joined to it. So is
 * a forced sync for the same owner, since the in-flight response may predate whatever forced it.
 * Mirrors iOS `MessagesSyncCoordinator`.
 *
 * [sync] never blocks: the fetch and the store writes run on [dispatcher].
 *
 * @param ownerProvider resolves who the sync is for. Called once per [sync] and again when a
 *   queued follow-up starts.
 * @param deviceTokenProvider the registered FCM token, or null.
 * @param dismissedIds ids to keep out of the cache; read inside the store's write lock.
 * @param onChange told about every non-empty change.
 * @param callbackPoster runs completions; the main thread by default.
 */
internal class MessagesSyncCoordinator(
    private val analytics: Analytics,
    private val config: HightouchMessagesConfig,
    private val store: MessageStore,
    private val client: MessagesHTTPClient,
    private val preferences: MessagesPreferences,
    private val ownerProvider: (Analytics) -> MessageOwner?,
    private val deviceTokenProvider: () -> String?,
    private val dismissedIds: (MessageOwner) -> Set<String>,
    private val onChange: (HightouchMessagesChange) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackPoster: (() -> Unit) -> Unit = MessagesMainThread::post,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class FollowUp(
        val force: Boolean,
        val updatesCooldown: Boolean,
        val completions: List<SyncCompletion>,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var inFlightOwner: MessageOwner? = null
    private var inFlightCompletions: List<SyncCompletion> = emptyList()

    /** Re-resolves the owner when it starts. */
    private var queuedFollowUp: FollowUp? = null

    /**
     * [completion] always runs via [callbackPoster]. Skipped syncs report an empty success.
     * `updatesCooldown = false` leaves the cooldown unstamped, for a sync whose response may be
     * incomplete (see `HightouchMessages.identityDidChange`).
     */
    fun sync(force: Boolean, updatesCooldown: Boolean = true, completion: SyncCompletion? = null) {
        begin(force, updatesCooldown, listOfNotNull(completion))
    }

    private fun begin(force: Boolean, updatesCooldown: Boolean, completions: List<SyncCompletion>) {
        // Resolved outside the lock: both providers can take other locks (HightouchPush's).
        val owner = ownerProvider(analytics)
        val deviceToken = deviceTokenProvider()

        synchronized(lock) {
            // The gateway always answers an anonymous id with an empty inbox.
            if (owner == null || owner.kind != MessageOwner.Kind.USER_ID) {
                deliverResult(Result.success(HightouchMessagesChange()), completions)
                return
            }

            val flying = inFlightOwner
            if (flying != null) {
                if (flying == owner && !force) {
                    inFlightCompletions = inFlightCompletions + completions
                } else {
                    val pending = queuedFollowUp
                    queuedFollowUp = FollowUp(
                        force = force || (pending?.force ?: false),
                        updatesCooldown = updatesCooldown && (pending?.updatesCooldown ?: true),
                        completions = (pending?.completions ?: emptyList()) + completions,
                    )
                }
                return
            }

            val lastSyncedAt =
                if (preferences.lastSyncedOwner == owner.storageKey) preferences.lastSyncedAtMillis else 0L
            if (!shouldSync(lastSyncedAt, nowMillis(), config.foregroundSyncCooldownMillis, force)) {
                deliverResult(Result.success(HightouchMessagesChange()), completions)
                return
            }

            // The gateway requires a registered device token.
            if (deviceToken.isNullOrEmpty()) {
                deliverResult(Result.success(HightouchMessagesChange()), completions)
                return
            }

            inFlightOwner = owner
            inFlightCompletions = completions
            scope.launch { performFetch(owner, deviceToken, updatesCooldown) }
        }
    }

    private suspend fun performFetch(owner: MessageOwner, deviceToken: String, updatesCooldown: Boolean) {
        var retriesRemaining = 1
        while (true) {
            try {
                val response = client.fetchMessages(owner, deviceToken)
                finish(Result.success(handleFetchSuccess(response, owner, updatesCooldown)))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retriesRemaining > 0 && isRetryable(e)) {
                    retriesRemaining--
                    delay(Random.nextLong(RETRY_DELAY_MIN_MILLIS, RETRY_DELAY_MAX_MILLIS + 1))
                } else {
                    val error = (e as? StoreFailure)?.cause ?: e
                    analytics.reportInternalError(error)
                    finish(Result.failure(error))
                    return
                }
            }
        }
    }

    /**
     * Store failures are not retried: a store that cannot write will not succeed on repeat, and
     * the retry exists for the network.
     */
    private fun handleFetchSuccess(
        response: MessagesFetchResponse,
        owner: MessageOwner,
        updatesCooldown: Boolean,
    ): HightouchMessagesChange {
        if (response.malformedCount > 0) {
            analytics.reportInternalError(MalformedMessagesError(response.malformedCount))
        }

        // The server still returns dismissed messages; a lambda so the log is read inside the
        // write lock (see `MessageStore.replaceAll`).
        val outcome = try {
            store.replaceAll(
                response.messages,
                owner,
                suppressing = { dismissedIds(owner) },
                limit = config.maxStoredMessages,
            )
        } catch (e: Exception) {
            throw StoreFailure(e)
        }
        // Housekeeping: a failure here costs disk space, not correctness, so it never fails the sync.
        try {
            store.pruneExpired()
            // `owner` already fits under the cap (see `replaceAll`'s limit), so only other owners'
            // rows are evicted and every change to `owner` is in `outcome`.
            store.enforceLimit(config.maxStoredMessages, preserving = owner)
        } catch (e: Exception) {
            analytics.reportInternalError(e)
        }

        if (updatesCooldown) preferences.stampSync(owner, nowMillis())

        val change = HightouchMessagesChange(outcome.insertedIds, outcome.updatedIds, outcome.removedIds)
        if (!change.isEmpty) onChange(change)
        return change
    }

    /** Marks a failure that came from the local store rather than the network. */
    private class StoreFailure(cause: Exception) : Exception(cause)

    private fun finish(result: Result<HightouchMessagesChange>) {
        val (completions, followUp) = synchronized(lock) {
            val done = inFlightCompletions
            inFlightCompletions = emptyList()
            inFlightOwner = null
            val next = queuedFollowUp
            queuedFollowUp = null
            done to next
        }
        deliverResult(result, completions)
        followUp?.let { begin(it.force, it.updatesCooldown, it.completions) }
    }

    private fun deliverResult(result: Result<HightouchMessagesChange>, completions: List<SyncCompletion>) {
        if (completions.isEmpty()) return
        callbackPoster {
            for (completion in completions) completion(result)
        }
    }

    companion object {
        private const val RETRY_DELAY_MIN_MILLIS = 50L
        private const val RETRY_DELAY_MAX_MILLIS = 250L

        fun shouldSync(lastSyncedAtMillis: Long, nowMillis: Long, cooldownMillis: Long, force: Boolean): Boolean {
            if (force) return true
            // A stamp in the future (the clock moved backwards) must not hold syncs off.
            if (nowMillis < lastSyncedAtMillis) return true
            return (nowMillis - lastSyncedAtMillis) >= maxOf(cooldownMillis, HightouchMessagesConfig.MIN_COOLDOWN_MILLIS)
        }

        /** 5xx and transport failures retry once; a 4xx won't succeed on repeat. */
        private fun isRetryable(error: Throwable): Boolean {
            if (error is StoreFailure) return false
            if (error is MessagesHttpStatusException) return error.statusCode >= 500
            return true
        }
    }
}
