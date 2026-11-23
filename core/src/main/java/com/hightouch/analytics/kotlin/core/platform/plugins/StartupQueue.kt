package com.hightouch.analytics.kotlin.core.platform.plugins

import com.hightouch.analytics.kotlin.core.*
import com.hightouch.analytics.kotlin.core.platform.Plugin
import com.hightouch.analytics.kotlin.core.utilities.EncodeDefaultsJson
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import sovran.kotlin.Subscriber

class StartupQueue : Plugin, Subscriber {
    override val type = Plugin.Type.Before
    override lateinit var analytics: Analytics
    @Volatile private var started = false
    private val queued = ArrayDeque<BaseEvent>()
    private val records = linkedMapOf<String, JsonElement>()
    private var restored = false
    private val replayId = ThreadLocal<String>()
    private val awaitingWrite = mutableSetOf<String>()

    override fun setup(analytics: Analytics) {
        super.setup(analytics)
        with(analytics) {
            analyticsScope.launch(analyticsDispatcher) {
                store.subscribe(
                    subscriber = this@StartupQueue,
                    stateClazz = System::class,
                    initialState = true,
                    queue = analyticsDispatcher,
                    handler = this@StartupQueue::runningUpdate
                )
            }
        }
    }

    private fun restore() {
        if (restored) return
        val saved = analytics.storage.read(Storage.Constants.StartupEvents)
        if (saved != null) {
            for (entry in Json.parseToJsonElement(saved).jsonObject) {
                records[entry.key] = entry.value
                try {
                    queued.add(EncodeDefaultsJson.decodeFromJsonElement(BaseEventSerializer, entry.value))
                } catch (e: Exception) { analytics.reportInternalError(e) }
            }
        }
        restored = true
    }

    private fun save() {
        analytics.storage.writePrefs(Storage.Constants.StartupEvents, JsonObject(records).toString())
    }

    @Synchronized internal fun capture(event: BaseEvent): Boolean {
        if (started) return false
        val info = analytics.userInfo
        event.anonymousId = info.anonymousId
        event.integrations = emptyJsonObject
        if (event.userId.isBlank()) event.userId = info.userId ?: ""
        if (event is IdentifyEvent || event is AliasEvent) info.userId = event.userId
        execute(event)
        return true
    }

    @Synchronized override fun execute(event: BaseEvent): BaseEvent? {
        if (started) return event
        try {
            restore()
            if (event.enrichment == null) {
                records[event.messageId] = EncodeDefaultsJson.encodeToJsonElement(BaseEventSerializer, event)
                save()
            } else {
                analytics.reportInternalError(IllegalStateException(
                    "Startup events with enrichment callbacks can only be replayed in this session."
                ))
            }
        } catch (e: Exception) { analytics.reportInternalError(e) }
        queued.add(event)
        return null
    }

    internal fun isReplaying(event: BaseEvent) = replayId.get()?.let { it == event.messageId } ?: false

    @Synchronized internal fun willWrite(event: BaseEvent) {
        if (isReplaying(event)) awaitingWrite.add(event.messageId)
    }

    @Synchronized internal fun didWrite(id: String) {
        if (awaitingWrite.remove(id)) acknowledge(id)
    }

    private fun acknowledge(id: String) {
        if (records.remove(id) != null) save()
    }

    @Synchronized internal fun runningUpdate(state: System) {
        started = state.running
        if (!started || !state.enabled) return
        try {
            restore()
            while (queued.isNotEmpty()) {
                val event = queued.first()
                replayId.set(event.messageId)
                analytics.timeline.process(event)
                if (event.messageId !in awaitingWrite) acknowledge(event.messageId)
                queued.removeFirst()
            }
        } catch (e: Exception) {
            analytics.reportInternalError(e)
        } finally {
            replayId.remove()
        }
    }
}
