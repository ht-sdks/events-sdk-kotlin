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
        /**
         * Null when both identifiers are empty. Ids are not trimmed: the owner id is sent to the
         * server as-is, so it must match the `userId` events carry.
         */
        fun current(analytics: Analytics): MessageOwner? {
            val userId = analytics.userId()
            if (!userId.isNullOrEmpty()) return MessageOwner(Kind.USER_ID, userId)

            val anonymousId = analytics.anonymousId()
            if (anonymousId.isNotEmpty()) return MessageOwner(Kind.ANONYMOUS_ID, anonymousId)

            return null
        }
    }
}
