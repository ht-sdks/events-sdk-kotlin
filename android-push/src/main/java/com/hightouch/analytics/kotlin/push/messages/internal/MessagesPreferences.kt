package com.hightouch.analytics.kotlin.push.messages.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around the messages feature's [SharedPreferences] file. Mirrors the UserDefaults
 * keys iOS uses for the dismissal log and the sync cooldown stamp. Kept out of the SQLite store
 * because the store's recovery path deletes and refetches it.
 */
internal class MessagesPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The dismissal log as JSON (`{ownerKey: [messageId, ...]}`), or null if empty. */
    var dismissedJson: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DISMISSED) else putString(KEY_DISMISSED, value)
            }.apply()
        }

    /** Epoch millis of the last sync that stamped the cooldown, or `0` if none has. */
    val lastSyncedAtMillis: Long get() = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)

    /** [MessageOwner.storageKey] of the owner `lastSyncedAtMillis` belongs to. */
    val lastSyncedOwner: String? get() = prefs.getString(KEY_LAST_SYNCED_OWNER, null)

    fun stampSync(owner: MessageOwner, atMillis: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SYNCED_AT, atMillis)
            .putString(KEY_LAST_SYNCED_OWNER, owner.storageKey)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "hightouch_messages_prefs"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_LAST_SYNCED_AT = "last_synced_at_millis"
        const val KEY_LAST_SYNCED_OWNER = "last_synced_owner"
    }
}
