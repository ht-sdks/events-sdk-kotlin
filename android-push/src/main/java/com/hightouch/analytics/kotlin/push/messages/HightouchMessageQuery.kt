package com.hightouch.analytics.kotlin.push.messages

/**
 * Ordering for a message query result. In the priority cases, a lower `priorityRank` sorts
 * first. Final ties break on `messageId`, so the order is stable.
 */
enum class HightouchMessageSort {
    PRIORITY_THEN_NEWEST,
    PRIORITY_THEN_OLDEST,
    NEWEST_FIRST,
    OLDEST_FIRST,
}

/**
 * Which cached messages to return and how to order them. `HightouchMessageQuery()` matches all.
 *
 * @property locationId Restrict to messages with this `locationId`. `null` matches any.
 * @property variantSlug Restrict to messages with this `variantSlug`. `null` matches any.
 * @property limit `null`, `0`, or negative mean unlimited.
 * @property includeExpired Whether to include messages whose `expiresAt` has already passed.
 * @property nowMillis Clock used for expiry (epoch millis); `null` means the time of the query.
 */
data class HightouchMessageQuery(
    val locationId: String? = null,
    val variantSlug: String? = null,
    val limit: Int? = null,
    val includeExpired: Boolean = false,
    val sort: HightouchMessageSort = HightouchMessageSort.PRIORITY_THEN_NEWEST,
    val nowMillis: Long? = null,
)
