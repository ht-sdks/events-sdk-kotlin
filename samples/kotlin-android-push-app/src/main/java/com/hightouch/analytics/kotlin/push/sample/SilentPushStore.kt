package com.hightouch.analytics.kotlin.push.sample

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists every silent-push delivery so entries written during a background process wake are
 * still there when the developer next opens the app. Mirrors the iOS sample's SilentPushStore.
 *
 * Writes use synchronous `commit()` deliberately: the silent-push listener runs on FCM's
 * background thread and the OS may kill the process shortly after it returns, so an async
 * `apply()` could lose the entry.
 */
object SilentPushStore {

    data class Entry(val receivedAtMillis: Long, val customData: Map<String, String>)

    private const val FILE = "hightouch_silent_push_log"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_RECEIVED_AT = "receivedAt"
    private const val KEY_CUSTOM_DATA = "customData"

    /** Keep the log bounded — this is a debug surface, not a database. */
    private const val MAX_ENTRIES = 50

    fun append(context: Context, customData: Map<String, String>) {
        val prefs = prefs(context)
        synchronized(this) {
            val entries = readArray(prefs.getString(KEY_ENTRIES, null))
            entries.put(
                JSONObject()
                    .put(KEY_RECEIVED_AT, System.currentTimeMillis())
                    .put(KEY_CUSTOM_DATA, JSONObject(customData.toMap())),
            )
            while (entries.length() > MAX_ENTRIES) {
                entries.remove(0)
            }
            prefs.edit().putString(KEY_ENTRIES, entries.toString()).commit()
        }
    }

    /** All stored entries, newest first. */
    fun entries(context: Context): List<Entry> {
        val entries = synchronized(this) {
            readArray(prefs(context).getString(KEY_ENTRIES, null))
        }
        return (0 until entries.length())
            .mapNotNull { i -> entries.optJSONObject(i)?.toEntry() }
            .sortedByDescending { it.receivedAtMillis }
    }

    fun clear(context: Context) {
        synchronized(this) {
            // apply(), not commit(): this runs on the main thread (Clear button), and losing a
            // clear to a process kill is harmless — unlike append(), which must survive one.
            prefs(context).edit().remove(KEY_ENTRIES).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readArray(json: String?): JSONArray =
        json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun JSONObject.toEntry(): Entry? {
        val receivedAt = optLong(KEY_RECEIVED_AT, -1L)
        if (receivedAt < 0) return null
        val dataJson = optJSONObject(KEY_CUSTOM_DATA) ?: JSONObject()
        val customData = buildMap {
            dataJson.keys().forEach { key -> put(key, dataJson.optString(key)) }
        }
        return Entry(receivedAtMillis = receivedAt, customData = customData)
    }
}
