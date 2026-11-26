package com.hightouch.analytics.kotlin.core.platform.plugins

import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.BaseEvent
import com.hightouch.analytics.kotlin.core.System
import com.hightouch.analytics.kotlin.core.platform.Plugin
import kotlinx.coroutines.launch
import com.hightouch.analytics.kotlin.core.platform.plugins.logger.*
import sovran.kotlin.Subscriber
import java.util.ArrayDeque

/**
 * Analytics plugin to manage started state of analytics client
 * All events will be held in an in-memory queue until started state is enabled, and once enabled
 * events will be replayed into the analytics timeline
 */
class StartupQueue : Plugin, Subscriber {
    override val type: Plugin.Type = Plugin.Type.Before
    override lateinit var analytics: Analytics

    private val maxSize = 1000
    private var started = false
    private val queuedEvents = ArrayDeque<BaseEvent>()

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

    override fun execute(event: BaseEvent): BaseEvent? = synchronized(queuedEvents) {
        if (started) return event
        if (queuedEvents.size >= maxSize) queuedEvents.removeFirst()
        event.startupIdentityCaptured = true
        queuedEvents.addLast(event)
        null
    }

    private fun runningUpdate(state: System) {
        val pending = synchronized(queuedEvents) {
            started = state.running
            if (!started || queuedEvents.isEmpty()) return
            queuedEvents.toList().also { queuedEvents.clear() }
        }
        for (event in pending) {
            if (analytics.enabled) analytics.timeline.process(event)
        }
    }
}
