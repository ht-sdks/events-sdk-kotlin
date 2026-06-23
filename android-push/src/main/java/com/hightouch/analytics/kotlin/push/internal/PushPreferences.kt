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

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "hightouch_push_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
    }
}
