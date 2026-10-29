package com.hightouch.analytics.kotlin.push.messages

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.reportInternalError
import com.hightouch.analytics.kotlin.push.CepEventTracking
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.messages.internal.MessageDismissalLog
import com.hightouch.analytics.kotlin.push.messages.internal.MessageOwner
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStore
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreLocation
import com.hightouch.analytics.kotlin.push.messages.internal.MessageStoreSchema
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesHTTPClient
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesMainThread
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesPreferences
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesSyncCoordinator
import com.hightouch.analytics.kotlin.push.messages.internal.MessagesTransport
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public facade for embedded messages. Enable it with
 * [com.hightouch.analytics.kotlin.push.HightouchPushConfig.Builder.setMessages]. Mirrors iOS
 * `HightouchMessages`.
 *
 * Until messages are enabled every method returns an empty result and does nothing.
 *
 * Threading: [initialize] opens the SQLite store off the caller's thread, so launch never blocks
 * on disk I/O. Syncs queue behind the open. The synchronous reads ([messages], [message],
 * [messageCount]) and the cache-management calls wait for the open to finish, so right after
 * start-up they can block; on the main thread prefer [loadMessages], which never does. Callbacks
 * and listeners are delivered on the main thread.
 */
object HightouchMessages {

    // Unlike HightouchPush, state here is guarded: a silent push can sync on FCM's thread while
    // the host's main thread is querying.
    private val stateLock = Any()

    private var analytics: Analytics? = null
    private var config: HightouchMessagesConfig? = null
    private var appContext: Context? = null
    private var preferences: MessagesPreferences? = null
    private var store: MessageStore? = null
    private var storeFile: File? = null
    private var coordinator: MessagesSyncCoordinator? = null
    private var foregroundObserver: DefaultLifecycleObserver? = null
    private val changeListeners = CopyOnWriteArrayList<WeakReference<HightouchMessagesListener>>()

    /**
     * Opens the store off the caller's thread so [initialize] never blocks launch on disk I/O.
     * Anything that needs the store waits for, or queues behind, the work enqueued here.
     */
    private val setupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            onSetupThread.set(true)
            runnable.run()
        }, "hightouch-messages-setup").apply { isDaemon = true }
    }
    private val onSetupThread = ThreadLocal<Boolean>()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seam: overrides where the store is opened. `null` means [MessageStoreLocation.AppContainer]. */
    @VisibleForTesting
    @Volatile
    internal var testStoreLocation: MessageStoreLocation? = null

    /** Test seam: replaces the network layer of the sync coordinator created by [initialize]. */
    @VisibleForTesting
    @Volatile
    internal var testTransport: MessagesTransport? = null

    /** Whether messages are enabled. `true` even if the store failed to open; queries then return empty. */
    @JvmStatic
    val isInitialized: Boolean
        get() = synchronized(stateLock) { analytics != null }

    /** Called by `HightouchPush.initialize(...)` when `HightouchPushConfig.messages` is set. */
    internal fun initialize(analytics: Analytics, context: Context, config: HightouchMessagesConfig) {
        val appContext = context.applicationContext ?: context
        synchronized(stateLock) {
            this.analytics = analytics
            this.config = config
            this.appContext = appContext
            this.preferences = MessagesPreferences(appContext)
        }

        configureForegroundSync(config)
        val location = testStoreLocation ?: MessageStoreLocation.AppContainer
        setupExecutor.execute {
            val opened = openStoreIfNeeded(analytics, appContext, location)
            configureSyncCoordinator(analytics, config, opened)
        }
        if (config.syncOnLaunch) syncOnInitialize()
    }

    /**
     * Keyed on the resolved store file so repeated initialization never opens two stores over one
     * SQLite file. Runs on [setupExecutor], which serializes it; the open itself happens outside
     * [stateLock] so queries aren't held up behind it. A store that is replaced is left open: a
     * read or sync may still hold it, and re-initializing with a different location only happens
     * in tests.
     */
    private fun openStoreIfNeeded(analytics: Analytics, context: Context, location: MessageStoreLocation): MessageStore? {
        val resolvedFile = try {
            MessageStoreLocation.storeFile(location, context, MessageStoreSchema.MODEL_VERSION)
        } catch (_: Exception) {
            null
        }

        val (openStore, openFile) = synchronized(stateLock) { store to storeFile }
        if (openStore != null && resolvedFile != null && resolvedFile == openFile) return openStore

        val opened = try {
            MessageStore(context, location)
        } catch (e: Exception) {
            synchronized(stateLock) {
                store = null
                storeFile = null
            }
            analytics.reportInternalError(e)
            return null
        }
        synchronized(stateLock) {
            store = opened
            storeFile = resolvedFile
        }
        try {
            opened.pruneExpired()
        } catch (e: Exception) {
            analytics.reportInternalError(e)
        }
        return opened
    }

    /** A null `store` (failed to open) clears the coordinator, so syncs become no-ops. */
    private fun configureSyncCoordinator(analytics: Analytics, config: HightouchMessagesConfig, store: MessageStore?) {
        val prefs = synchronized(stateLock) { preferences }
        val created = if (store == null || prefs == null) {
            null
        } else {
            val transport = testTransport
            val client = if (transport != null) {
                MessagesHTTPClient(analytics, config, transport)
            } else {
                MessagesHTTPClient(analytics, config)
            }
            MessagesSyncCoordinator(
                analytics = analytics,
                config = config,
                store = store,
                client = client,
                preferences = prefs,
                ownerProvider = ::resolveOwner,
                deviceTokenProvider = { HightouchPush.fcmToken },
                dismissedIds = ::dismissedIds,
                onChange = ::notifyChange,
            )
        }
        synchronized(stateLock) { coordinator = created }
    }

    /**
     * `HightouchPush.userId` wins over `analytics.userId()`: `HightouchPush.identify` records the
     * id synchronously, while the analytics pipeline applies it asynchronously, so a sync or read
     * right after `identify` would otherwise resolve the previous owner.
     */
    private fun resolveOwner(analytics: Analytics): MessageOwner? {
        val pushUserId = HightouchPush.userId?.trim()
        if (!pushUserId.isNullOrEmpty()) return MessageOwner(MessageOwner.Kind.USER_ID, pushUserId)
        return MessageOwner.current(analytics)
    }

    /** Blocks until pending setup work has run. A no-op on the setup thread, which would deadlock. */
    private fun awaitSetup() {
        if (onSetupThread.get() == true) return
        try {
            setupExecutor.submit {}.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Blocks until a pending [initialize] has opened the store. */
    private fun currentStoreAndOwner(): Pair<MessageStore, MessageOwner>? {
        awaitSetup()
        val (analytics, store) = synchronized(stateLock) { analytics to store }
        if (analytics == null || store == null) return null
        val owner = resolveOwner(analytics) ?: return null
        return store to owner
    }

    private fun reportError(error: Exception) {
        synchronized(stateLock) { analytics }?.reportInternalError(error)
    }

    // region Queries

    /**
     * Returns cached messages matching [query] for the current user. Reading doesn't remove
     * anything; a message stays until it's dismissed, expires or the server stops returning it.
     * Right after start-up this and the other synchronous reads wait for the store to open; use
     * [loadMessages] to avoid blocking the main thread.
     */
    @JvmStatic
    fun messages(query: HightouchMessageQuery): List<HightouchMessage> {
        val (store, owner) = currentStoreAndOwner() ?: return emptyList()
        return try {
            store.messages(query, owner)
        } catch (e: Exception) {
            reportError(e)
            emptyList()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun messages(
        locationId: String? = null,
        variantSlug: String? = null,
        limit: Int? = null,
    ): List<HightouchMessage> =
        messages(HightouchMessageQuery(locationId = locationId, variantSlug = variantSlug, limit = limit))

    /** Returns the cached message with the given id for the current user. */
    @JvmStatic
    fun message(messageId: String): HightouchMessage? {
        val (store, owner) = currentStoreAndOwner() ?: return null
        return try {
            store.message(messageId, owner)
        } catch (e: Exception) {
            reportError(e)
            null
        }
    }

    /** Number of cached messages matching [query] for the current user. */
    @JvmStatic
    @JvmOverloads
    fun messageCount(query: HightouchMessageQuery = HightouchMessageQuery()): Int {
        val (store, owner) = currentStoreAndOwner() ?: return 0
        return try {
            store.count(query, owner)
        } catch (e: Exception) {
            reportError(e)
            0
        }
    }

    /**
     * Same as [messages], but fetches off the calling thread and calls [callback] on the main
     * thread.
     */
    @JvmStatic
    fun loadMessages(query: HightouchMessageQuery, callback: (List<HightouchMessage>) -> Unit) {
        ioScope.launch {
            val result = messages(query)
            MessagesMainThread.post { callback(result) }
        }
    }

    /** `suspend` counterpart to [loadMessages]; the read runs on [Dispatchers.IO]. */
    suspend fun loadMessages(query: HightouchMessageQuery): List<HightouchMessage> =
        withContext(Dispatchers.IO) { messages(query) }

    // endregion

    // region Cache management

    /**
     * Discards the current user's cached messages and forgets which were dismissed, so the next
     * sync repopulates from the server. [completion] runs on the main thread with the number
     * discarded.
     */
    @JvmStatic
    @JvmOverloads
    fun clearCachedMessages(completion: ((Int) -> Unit)? = null) {
        val (store, owner) = currentStoreAndOwner() ?: return completeOnMain(completion, 0)
        dismissalLog()?.clear(owner)
        ioScope.launch {
            val removedIds = try {
                store.deleteAll(owner)
            } catch (e: Exception) {
                reportError(e)
                emptyList()
            }
            if (removedIds.isNotEmpty()) notifyChange(HightouchMessagesChange(removedIds = removedIds))
            completeOnMain(completion, removedIds.size)
        }
    }

    /**
     * Like [clearCachedMessages], but for every user that has synced on this device. Intended for
     * debug tooling.
     */
    @JvmStatic
    @JvmOverloads
    fun clearAllCachedMessagesForAllUsers(completion: ((Int) -> Unit)? = null) {
        awaitSetup()
        val store = synchronized(stateLock) { store } ?: return completeOnMain(completion, 0)
        dismissalLog()?.clear()
        ioScope.launch {
            val removedIds = try {
                store.deleteAllOwners()
            } catch (e: Exception) {
                reportError(e)
                emptyList()
            }
            if (removedIds.isNotEmpty()) notifyChange(HightouchMessagesChange(removedIds = removedIds))
            completeOnMain(completion, removedIds.size)
        }
    }

    /**
     * Removes one cached message for the current user, without a network call. [completion] runs
     * on the main thread with whether a row was removed. Unlike [trackDismissed], a later sync can
     * bring the message back.
     */
    @JvmStatic
    @JvmOverloads
    fun deleteMessage(messageId: String, completion: ((Boolean) -> Unit)? = null) {
        deleteMessages(listOf(messageId)) { removed -> completion?.invoke(removed > 0) }
    }

    /**
     * Removes cached messages for the current user by id, with one change notification. [completion]
     * runs on the main thread with the number removed. Used by delete commands from the server.
     */
    internal fun deleteMessages(messageIds: List<String>, completion: ((Int) -> Unit)? = null) {
        val (store, owner) = currentStoreAndOwner() ?: return completeOnMain(completion, 0)
        ioScope.launch {
            val removed = try {
                // The store reports only a count, so the ids that existed are read first.
                val existing = messageIds.distinct().filter { store.message(it, owner) != null }
                if (existing.isEmpty()) {
                    0
                } else {
                    store.delete(existing, owner).also { count ->
                        if (count > 0) notifyChange(HightouchMessagesChange(removedIds = existing))
                    }
                }
            } catch (e: Exception) {
                reportError(e)
                0
            }
            completeOnMain(completion, removed)
        }
    }

    private fun <T> completeOnMain(completion: ((T) -> Unit)?, value: T) {
        if (completion != null) MessagesMainThread.post { completion(value) }
    }

    // endregion

    // region Dismissal

    private fun dismissalLog(): MessageDismissalLog? =
        synchronized(stateLock) { preferences }?.let { MessageDismissalLog(it) }

    // Records before deleting: the delete is asynchronous, and the record is what stops a racing
    // sync from re-inserting the row.
    internal fun removeDismissed(messageId: String) {
        val (store, owner) = currentStoreAndOwner() ?: return
        try {
            if (store.message(messageId, owner) == null) return
        } catch (e: Exception) {
            reportError(e)
            return
        }
        dismissalLog()?.record(listOf(messageId), owner)
        ioScope.launch {
            val removed = try {
                store.delete(messageId, owner)
            } catch (e: Exception) {
                reportError(e)
                0
            }
            if (removed > 0) notifyChange(HightouchMessagesChange(removedIds = listOf(messageId)))
        }
    }

    internal fun dismissedIds(owner: MessageOwner): Set<String> =
        dismissalLog()?.dismissedIds(owner)?.toSet() ?: emptySet()

    // endregion

    // region Engagement

    /** Records a message impression. Call when the message is actually visible, not when it's fetched. */
    @JvmStatic
    fun trackImpression(messageId: String) {
        trackLifecycleEvent(CepEventTracking.MESSAGE_DISPLAYED, messageId)
    }

    /**
     * Records a click. The host performs whatever the tap does; [identifier] names what was
     * tapped and defaults to `"default"`, as a push body tap does.
     */
    @JvmStatic
    @JvmOverloads
    fun trackClick(messageId: String, identifier: String? = null) {
        trackLifecycleEvent(
            CepEventTracking.MESSAGE_CLICKED,
            messageId,
            action = buildJsonObject { put("identifier", identifier ?: "default") },
        )
    }

    /**
     * Records that the user dismissed a message, then removes it from local storage. A later sync
     * won't bring it back.
     */
    @JvmStatic
    fun trackDismissed(messageId: String) {
        trackLifecycleEvent(CepEventTracking.MESSAGE_DISMISSED, messageId)
        removeDismissed(messageId)
    }

    internal fun messageContext(messageId: String): JsonObject? =
        message(messageId)?.raw?.get("messageContext") as? JsonObject

    private fun trackLifecycleEvent(providerEventType: String, messageId: String, action: JsonObject? = null) {
        // A message can be tapped before HightouchPush.initialize runs; nothing to send to then.
        if (HightouchPush.cepAnalytics == null) return

        CepEventTracking.track(
            name = CepEventTracking.ENGAGEMENT_EVENTS,
            properties = buildJsonObject {
                action?.let { put("action", it) }
                put("provider_event_type", providerEventType)
                put("message_id", messageId)
            },
            messageContext = messageContext(messageId),
            channel = CepEventTracking.Channel.IN_APP,
        )
    }

    // endregion

    // region Sync

    /**
     * Fetches the latest messages from the server and reconciles the local cache.
     *
     * Completes with [HightouchSyncOutcome.Unchanged] and makes no network call when there's no
     * user, an anonymous user (whose inbox is always empty), no store, or no registered FCM token.
     * [completion] runs on the main thread. `force = true` bypasses
     * [HightouchMessagesConfig.foregroundSyncCooldownMillis].
     */
    @JvmStatic
    @JvmOverloads
    fun sync(force: Boolean = false, completion: ((HightouchSyncOutcome) -> Unit)? = null) {
        syncInternal(force = force, updatesCooldown = true, completion = completion)
    }

    internal fun syncIfStale() {
        syncInternal(force = false, updatesCooldown = true, completion = null)
    }

    /**
     * Not forced: the cooldown is per user, so a new identity syncs immediately. The gateway only
     * answers once it has linked the device token to the new user, which happens asynchronously
     * after the token re-registration, so this sync doesn't stamp the cooldown: an early, empty
     * response mustn't hold off the next foreground sync.
     */
    internal fun identityDidChange() {
        syncInternal(force = false, updatesCooldown = false, completion = null)
    }

    /**
     * Forced, because launch is the only reconcile point for an app that was killed when a silent
     * push arrived.
     */
    internal fun syncOnInitialize() {
        syncInternal(force = true, updatesCooldown = true, completion = null)
    }

    /**
     * Queued behind any pending store open, so a sync requested right after [initialize] (e.g. by
     * a silent push that launched the app) isn't dropped for want of a coordinator.
     */
    private fun syncInternal(force: Boolean, updatesCooldown: Boolean, completion: ((HightouchSyncOutcome) -> Unit)?) {
        setupExecutor.execute {
            try {
                syncWithCurrentCoordinator(force, updatesCooldown, completion)
            } catch (e: Exception) {
                reportError(e)
                completeOnMain(completion, HightouchSyncOutcome.Failed(e))
            }
        }
    }

    private fun syncWithCurrentCoordinator(
        force: Boolean,
        updatesCooldown: Boolean,
        completion: ((HightouchSyncOutcome) -> Unit)?,
    ) {
        val current = synchronized(stateLock) { coordinator }
        if (current == null) {
            completeOnMain(completion, HightouchSyncOutcome.Unchanged)
            return
        }
        current.sync(force, updatesCooldown) { result ->
            val outcome = result.fold(
                onSuccess = { change ->
                    if (change.isEmpty) {
                        HightouchSyncOutcome.Unchanged
                    } else {
                        HightouchSyncOutcome.Changed(
                            change.insertedIds.size + change.updatedIds.size + change.removedIds.size,
                        )
                    }
                },
                onFailure = { HightouchSyncOutcome.Failed(it) },
            )
            completion?.invoke(outcome)
        }
    }

    /**
     * Tears down any existing observer first, so re-initializing with `syncOnForeground = false`
     * actually removes it. [ProcessLifecycleOwner] requires the main thread, so both steps are
     * posted there.
     */
    private fun configureForegroundSync(config: HightouchMessagesConfig) {
        val previous = synchronized(stateLock) { foregroundObserver.also { foregroundObserver = null } }
        previous?.let { observer ->
            MessagesMainThread.post {
                try {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
                } catch (_: Throwable) {
                    // ProcessLifecycleOwner may be uninitialized in a bare test env; ignore.
                }
            }
        }
        if (!config.syncOnForeground) return

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncIfStale()
            }
        }
        synchronized(stateLock) { foregroundObserver = observer }
        MessagesMainThread.post {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            } catch (_: Throwable) {
                // See above.
            }
        }
    }

    /** The open store, once pending setup has run. */
    @VisibleForTesting
    internal val openStoreForTesting: MessageStore?
        get() {
            awaitSetup()
            return synchronized(stateLock) { store }
        }

    /** True while the foreground-sync observer is registered. */
    @VisibleForTesting
    internal val isForegroundObserverRegistered: Boolean
        get() = synchronized(stateLock) { foregroundObserver != null }

    // endregion

    // region Change notification

    /**
     * Registers [listener] for cache changes, in addition to [HightouchMessagesConfig.listener].
     * Held weakly: keep a strong reference elsewhere. Adding the same listener twice is a no-op.
     */
    @JvmStatic
    fun addListener(listener: HightouchMessagesListener) {
        if (changeListeners.none { it.get() === listener }) {
            changeListeners.add(WeakReference(listener))
        }
    }

    @JvmStatic
    fun removeListener(listener: HightouchMessagesListener) {
        changeListeners.removeAll { ref -> ref.get().let { it == null || it === listener } }
    }

    /** Calls the configured and registered listeners on the main thread. */
    internal fun notifyChange(change: HightouchMessagesChange) {
        changeListeners.removeAll { it.get() == null }
        val targets = ArrayList<HightouchMessagesListener>()
        synchronized(stateLock) { config }?.listener?.let { targets.add(it) }
        for (ref in changeListeners) {
            ref.get()?.let { if (!targets.contains(it)) targets.add(it) }
        }
        if (targets.isEmpty()) return
        MessagesMainThread.post {
            for (target in targets) target.onMessagesChanged(change)
        }
    }

    // endregion

    /** Test seam: resets all state to "never initialized". */
    @VisibleForTesting
    internal fun resetForTesting() {
        awaitSetup()
        val (openStore, prefs, observer) = synchronized(stateLock) {
            Triple(store, preferences, foregroundObserver)
        }
        prefs?.clear()
        observer?.let {
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
            } catch (_: Throwable) {
                // See configureForegroundSync.
            }
        }
        synchronized(stateLock) {
            analytics = null
            config = null
            appContext = null
            preferences = null
            store = null
            storeFile = null
            coordinator = null
            foregroundObserver = null
        }
        changeListeners.clear()
        testStoreLocation = null
        testTransport = null
        try {
            openStore?.close()
        } catch (_: Exception) {
            // Already closed.
        }
    }
}
