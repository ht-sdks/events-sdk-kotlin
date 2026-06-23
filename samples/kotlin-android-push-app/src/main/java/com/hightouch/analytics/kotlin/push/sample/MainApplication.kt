package com.hightouch.analytics.kotlin.push.sample

import android.app.Application

class MainApplication : Application() {

    lateinit var prefs: AppPreferences
        private set

    var sdkConfigured: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        sdkConfigured = SdkInitializer.initialize(this, prefs)
    }

    fun reinitialize(): Boolean {
        sdkConfigured = SdkInitializer.initialize(this, prefs)
        return sdkConfigured
    }
}
