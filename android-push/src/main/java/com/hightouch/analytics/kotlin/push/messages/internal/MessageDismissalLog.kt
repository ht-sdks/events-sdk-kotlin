package com.hightouch.analytics.kotlin.push.messages.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Message ids the host dismissed, so a sync doesn't re-insert them (the server still returns
 * them). Kept in SharedPreferences rather than the SQLite store because the store's recovery
 * path destroys and refetches it. Capped per owner, so very old dismissals can eventually
 * resurface. Mirrors iOS `MessageDismissalLog`.
 */
internal class MessageDismissalLog(private val prefs: MessagesPreferences) {

    fun dismissedIds(owner: MessageOwner): List<String> = synchronized(lock) {
        readAll()[owner.storageKey].orEmpty()
    }

    /** A repeat id keeps its original position, so eviction stays by first-dismissal age. */
    fun record(messageIds: List<String>, owner: MessageOwner) {
        if (messageIds.isEmpty()) return
        synchronized(lock) {
            val all = readAll()
            val existing = ArrayList(all[owner.storageKey].orEmpty())
            val known = existing.toHashSet()
            for (id in messageIds) {
                if (known.add(id)) existing.add(id)
            }
            val overflow = existing.size - PER_OWNER_LIMIT
            val kept = if (overflow > 0) existing.subList(overflow, existing.size) else existing
            all[owner.storageKey] = ArrayList(kept)
            writeAll(all)
        }
    }

    /** Forgets [owner]'s dismissals, or everyone's when [owner] is null. */
    fun clear(owner: MessageOwner? = null) {
        synchronized(lock) {
            if (owner == null) {
                prefs.dismissedJson = null
                return
            }
            val all = readAll()
            if (all.remove(owner.storageKey) != null) writeAll(all)
        }
    }

    private fun readAll(): MutableMap<String, List<String>> {
        val text = prefs.dismissedJson ?: return LinkedHashMap()
        val result = LinkedHashMap<String, List<String>>()
        try {
            for ((key, value) in Json.parseToJsonElement(text).jsonObject) {
                val ids = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (ids != null) result[key] = ids
            }
        } catch (_: Exception) {
            // A corrupt log reads as empty: a dismissed message may resurface once, nothing worse.
        }
        return result
    }

    private fun writeAll(all: Map<String, List<String>>) {
        val json = JsonObject(all.mapValues { (_, ids) -> JsonArray(ids.map { JsonPrimitive(it) }) })
        prefs.dismissedJson = json.toString()
    }

    companion object {
        /** Matches the default `HightouchMessagesConfig.maxStoredMessages`. */
        const val PER_OWNER_LIMIT = 500

        /** Static because the log is shared state behind one SharedPreferences file. */
        private val lock = Any()
    }
}
