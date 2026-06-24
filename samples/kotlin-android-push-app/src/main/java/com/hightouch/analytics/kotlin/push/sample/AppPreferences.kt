package com.hightouch.analytics.kotlin.push.sample

import android.content.Context

/**
 * Stores runtime overrides for the SDK credentials. Mirrors the iOS app's UserDefaults-backed
 * overrides on `SettingsView`.
 */
class AppPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var writeKey: String?
        get() = prefs.getString(KEY_WRITE_KEY, null)
        set(v) { prefs.edit().putStringOrRemove(KEY_WRITE_KEY, v).apply() }

    var appId: String?
        get() = prefs.getString(KEY_APP_ID, null)
        set(v) { prefs.edit().putStringOrRemove(KEY_APP_ID, v).apply() }

    var apiHost: String?
        get() = prefs.getString(KEY_API_HOST, null)
        set(v) { prefs.edit().putStringOrRemove(KEY_API_HOST, v).apply() }

    /** Effective write key — runtime override wins, BuildConfig is the fallback. */
    val effectiveWriteKey: String
        get() = writeKey?.takeIf { it.isNotBlank() }
            ?: BuildConfig.HIGHTOUCH_WRITE_KEY

    val effectiveAppId: String
        get() = appId?.takeIf { it.isNotBlank() }
            ?: BuildConfig.HIGHTOUCH_APP_ID

    val effectiveApiHost: String
        get() = apiHost?.takeIf { it.isNotBlank() }
            ?: BuildConfig.HIGHTOUCH_API_HOST

    /**
     * Read-only views of the BuildConfig defaults (i.e. the `hightouch.*` values baked in from
     * `local.properties` at build time). Surfaced so the Settings UI can show them as
     * placeholder hints without importing `BuildConfig` directly.
     */
    val defaultWriteKey: String get() = BuildConfig.HIGHTOUCH_WRITE_KEY
    val defaultAppId: String get() = BuildConfig.HIGHTOUCH_APP_ID
    val defaultApiHost: String get() = BuildConfig.HIGHTOUCH_API_HOST

    fun resetOverrides() {
        prefs.edit().clear().apply()
    }

    private fun android.content.SharedPreferences.Editor.putStringOrRemove(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor {
        return if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    companion object {
        private const val FILE = "hightouch_push_sample"
        private const val KEY_WRITE_KEY = "writeKey"
        private const val KEY_APP_ID = "appId"
        private const val KEY_API_HOST = "apiHost"
    }
}
