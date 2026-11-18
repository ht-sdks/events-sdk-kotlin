package com.hightouch.analytics.kotlin.push.messages

/**
 * Notifies the host when the local message cache changes. Delivered on the main thread.
 *
 * Held weakly, both by [HightouchMessagesConfig] and by [HightouchMessages.addListener]: keep a
 * strong reference elsewhere (for example in a ViewModel or the Application), or the listener
 * stops being called once it is garbage collected.
 *
 * This is a Kotlin SAM (functional) interface, so callers can pass a lambda, but a lambda has no
 * other owner and would be collected. Prefer a class that is kept alive.
 */
fun interface HightouchMessagesListener {
    fun onMessagesChanged(change: HightouchMessagesChange)
}

/** Describes what changed in the local message cache. */
data class HightouchMessagesChange(
    val insertedIds: List<String> = emptyList(),
    val updatedIds: List<String> = emptyList(),
    val removedIds: List<String> = emptyList(),
) {
    /** True when nothing was inserted, updated or removed. */
    val isEmpty: Boolean
        get() = insertedIds.isEmpty() && updatedIds.isEmpty() && removedIds.isEmpty()
}

/** The result of a [HightouchMessages.sync] call. */
sealed class HightouchSyncOutcome {
    /** The cache changed. [messageCount] is inserted + updated + removed. */
    data class Changed(val messageCount: Int) : HightouchSyncOutcome()

    /** Nothing changed, or the sync was skipped (no user, no FCM token, cooldown, no store). */
    object Unchanged : HightouchSyncOutcome() {
        override fun toString(): String = "Unchanged"
    }

    data class Failed(val error: Throwable) : HightouchSyncOutcome()
}
