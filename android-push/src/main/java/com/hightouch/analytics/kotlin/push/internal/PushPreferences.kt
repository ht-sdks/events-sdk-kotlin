package com.hightouch.analytics.kotlin.push.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around the push module's [SharedPreferences] file.
 *
 * Mirrors iOS's single `UserDefaults` key `com.hightouch.push.apnsToken`.
 */
internal class PushPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The most recently registered FCM token, or null if none has been registered yet. */
    var token: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_FCM_TOKEN) else putString(KEY_FCM_TOKEN, value)
            }.apply()
        }

    /**
     * Epoch millis of the last token upload (registration event fired), or `0` if the token has
     * never been uploaded. Drives the re-upload heartbeat in [HightouchPush].
     */
    var lastUploadedAtMillis: Long
        get() = prefs.getLong(KEY_LAST_UPLOADED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_UPLOADED_AT, value).apply()
        }

    /**
     * Whether the request to delete this device's record has been sent since data collection was
     * last turned off. Keeps `HightouchPush.wipeData` from sending it again until collection is
     * turned back on.
     */
    var deletionSent: Boolean
        get() = prefs.getBoolean(KEY_DELETION_SENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DELETION_SENT, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "hightouch_push_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_LAST_UPLOADED_AT = "last_uploaded_at_millis"
        const val KEY_DELETION_SENT = "deletion_sent"
    }
}
