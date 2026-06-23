package com.hightouch.analytics.kotlin.push.trampoline

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hightouch.analytics.kotlin.push.internal.ActionRouter

/**
 * Invisible `singleTask` activity that unifies push tap handling.
 *
 * HightouchNotificationPresenter builds two kinds of [android.app.PendingIntent]s:
 *   - Body tap and `openApp=true` action buttons route here.
 *   - `openApp=false` action buttons go to [HightouchPushActionReceiver] instead.
 *
 * On launch, the activity hands the payload extras to [ActionRouter], then finishes itself.
 */
class HightouchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val extras = intent?.extras ?: return
        ActionRouter.route(this, extras)
        // Dismiss the notification once it has been acted on.
        ActionRouter.dismiss(this, extras)
    }
}
