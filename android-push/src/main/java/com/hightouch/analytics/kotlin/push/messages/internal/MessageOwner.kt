package com.hightouch.analytics.kotlin.push.messages.internal

import com.hightouch.analytics.kotlin.core.Analytics

/**
 * The single identity messages are scoped to: `userId` when set, else `anonymousId`.
 * Mirrors iOS `MessageOwner`.
 */
internal data class MessageOwner(val kind: Kind, val id: String) {
    enum class Kind { USER_ID, ANONYMOUS_ID }

    val storageKey: String
        get() = "${if (kind == Kind.USER_ID) "u" else "a"}:$id"

    companion object {
        /** Null when both identifiers are blank. */
        fun current(analytics: Analytics): MessageOwner? {
            val userId = analytics.userId()?.trim()
            if (!userId.isNullOrEmpty()) return MessageOwner(Kind.USER_ID, userId)

            val anonymousId = analytics.anonymousId().trim()
            if (anonymousId.isNotEmpty()) return MessageOwner(Kind.ANONYMOUS_ID, anonymousId)

            return null
        }
    }
}
