package com.hightouch.analytics.kotlin.push.trampoline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hightouch.analytics.kotlin.push.internal.ActionRouter

/**
 * Receiver for `openApp=false` action button taps. Fires the engagement event and runs the
 * configured handler (URL or custom action), then dismisses the notification, all without
 * launching the host app.
 */
class HightouchPushActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        ActionRouter.route(context, extras)
        ActionRouter.dismiss(context, extras)
    }

    companion object {
        const val ACTION = "com.hightouch.analytics.kotlin.push.ACTION_PUSH_ACTION"
    }
}
