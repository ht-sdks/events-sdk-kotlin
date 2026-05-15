package com.hightouch.analytics.kotlin.destinations

import android.app.Application
import com.hightouch.analytics.kotlin.destinations.plugins.*
import com.hightouch.analytics.kotlin.android.Analytics
import com.hightouch.analytics.kotlin.core.Analytics
import com.hightouch.analytics.kotlin.core.platform.plugins.logger.*
import com.hightouch.analytics.kotlin.destinations.amplitude.AmplitudeSession
import com.hightouch.analytics.kotlin.destinations.appsflyer.AppsFlyerDestination
import com.hightouch.analytics.kotlin.destinations.comscore.ComscoreDestination
import com.hightouch.analytics.kotlin.destinations.firebase.FirebaseDestination
import com.hightouch.analytics.kotlin.destinations.intercom.IntercomDestination
import com.hightouch.analytics.kotlin.destinations.mixpanel.MixpanelDestination
import java.util.concurrent.Executors

class MainApplication : Application() {
    companion object {
        lateinit var analytics: Analytics
    }

    override fun onCreate() {
        super.onCreate()

        analytics = Analytics(BuildConfig.SEGMENT_WRITE_KEY, applicationContext) {
            this.collectDeviceId = true
            this.trackApplicationLifecycleEvents = true
            this.trackDeepLinks = true
            this.flushAt = 1
            this.flushInterval = 0
        }

        analytics.add(MixpanelDestination(applicationContext))

        // A random webhook url to view your events
        analytics.add(
            WebhookPlugin(
                "https://webhook.site/387c1740-f919-4446-a26e-a9a01ed28c8a",
                Executors.newSingleThreadExecutor()
            )
        )

        // Try out amplitude session
        analytics.add(AmplitudeSession())

        // Try out Firebase Destination
        analytics.add(FirebaseDestination(applicationContext))

        // Try out Intercom destination
        analytics.add(IntercomDestination(this))

        analytics.add(ComscoreDestination())

        val appsflyerDestination = AppsFlyerDestination(applicationContext, true)
        analytics.add(appsflyerDestination)

        appsflyerDestination.conversionListener =
            object : AppsFlyerDestination.ExternalAppsFlyerConversionListener {
                override fun onConversionDataSuccess(map: Map<String, Any>) {
                    // Process Deferred Deep Linking here
                    for (attrName in map.keys) {
                        analytics.log("Appsflyer: attribute: " + attrName + " = " + map[attrName])
                    }
                }

                override fun onConversionDataFail(s: String?) {}
                override fun onAppOpenAttribution(map: Map<String, String>) {
                    // Process Direct Deep Linking here
                    for (attrName in map.keys) {
                        analytics.log("Appsflyer: attribute: " + attrName + " = " + map[attrName])
                    }
                }

                override fun onAttributionFailure(s: String?) {}
            }

    }
}
