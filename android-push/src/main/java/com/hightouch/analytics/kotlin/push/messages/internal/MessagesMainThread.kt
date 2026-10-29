package com.hightouch.analytics.kotlin.push.messages.internal

import android.os.Handler
import android.os.Looper

/** Posts work to the main thread, where callbacks and listeners are delivered. */
internal object MessagesMainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun post(block: () -> Unit) {
        handler.post(block)
    }
}
