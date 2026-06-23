package com.hightouch.analytics.kotlin.push.sample

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hightouch.analytics.kotlin.push.HightouchAction
import com.hightouch.analytics.kotlin.push.HightouchActionContext
import com.hightouch.analytics.kotlin.push.HightouchCustomActionHandler
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.HightouchPushConfig
import com.hightouch.analytics.kotlin.push.HightouchUrlHandler
import com.hightouch.analytics.kotlin.android.Analytics

/**
 * Single place that decides how to call [HightouchPush.initialize]. Called from
 * [MainApplication.onCreate] on launch and from [ui.SettingsScreen] when the developer enters
 * fresh credentials.
 */
internal object SdkInitializer {

    private const val TAG = "HightouchPushSample"

    /**
     * @return true if the SDK was (re-)initialized; false if credentials are missing.
     */
    fun initialize(context: Context, prefs: AppPreferences): Boolean {
        val writeKey = prefs.effectiveWriteKey
        val appId = prefs.effectiveAppId
        val apiHost = prefs.effectiveApiHost.takeIf { it.isNotBlank() }

        if (writeKey.isBlank() || appId.isBlank()) {
            Log.w(TAG, "writeKey and appId required; falling back to placeholder screen.")
            return false
        }

        val config = HightouchPushConfig.Builder(appId)
            .setUrlHandler(SampleUrlHandler)
            .setCustomActionHandler(SampleCustomActionHandler)
            .setAllowedProtocols(listOf("hightouchsample"))
            // Small icon is declared in AndroidManifest.xml via the meta-data
            // `com.hightouch.push.default_notification_icon`. That's the recommended pattern;
            // setSmallIconResId(...) on the Builder is available as a programmatic alternative.
            .build()

        if (apiHost != null) {
            // Override the apiHost on the underlying Analytics instance.
            val analytics = Analytics(writeKey, context.applicationContext) {
                this.apiHost = apiHost
            }
            HightouchPush.initialize(analytics, config)
        } else {
            HightouchPush.initialize(
                context = context.applicationContext,
                writeKey = writeKey,
                config = config,
            )
        }
        Log.i(TAG, "SDK initialized writeKey=${writeKey.take(6)}* appId=$appId")
        return true
    }

    private object SampleUrlHandler : HightouchUrlHandler {
        override fun handleUrl(url: Uri, context: HightouchActionContext): Boolean {
            Log.i(TAG, "urlHandler: url=$url source=${context.source}")
            return false // let the SDK do default Intent.ACTION_VIEW
        }
    }

    private object SampleCustomActionHandler : HightouchCustomActionHandler {
        override fun handleAction(action: HightouchAction, context: HightouchActionContext): Boolean {
            Log.i(
                TAG,
                "customActionHandler: type=${action.type} data=${action.data} source=${context.source}",
            )
            return true
        }
    }
}
