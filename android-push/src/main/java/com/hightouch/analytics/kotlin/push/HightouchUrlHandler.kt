package com.hightouch.analytics.kotlin.push

import android.net.Uri

/**
 * Handles deep link URLs from push notification taps.
 *
 * Return true if your app handled the URL. Return false to fall back to the SDK's
 * default `Intent.ACTION_VIEW` handling, which the SDK attempts only for `https`
 * schemes or schemes listed in [HightouchPushConfig.allowedProtocols].
 *
 * This is a Kotlin SAM (functional) interface, so callers can pass a lambda directly:
 * ```
 * HightouchPushConfig.Builder("app-id")
 *     .setUrlHandler { url, _ -> deepLinkRouter.handle(url) }
 *     .build()
 * ```
 */
fun interface HightouchUrlHandler {
    fun handleUrl(url: Uri, context: HightouchActionContext): Boolean
}
