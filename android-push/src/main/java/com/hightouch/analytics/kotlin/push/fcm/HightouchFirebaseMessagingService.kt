package com.hightouch.analytics.kotlin.push.fcm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.notification.HightouchNotificationPresenter

/**
 * The SDK's default [FirebaseMessagingService] subclass.
 *
 * This service is registered automatically by the `android-push` module's manifest at
 * `priority="-1"` so that a host app's own [FirebaseMessagingService] (if any) wins. Two
 * integration patterns are supported:
 *
 *  - **No host-app FMS** — the host app does nothing. Our service receives FCM callbacks and
 *    forwards to [HightouchPush.register] / [HightouchNotificationPresenter.handle].
 *
 *  - **Host-app FMS** — the host app's service receives the callbacks first. The host calls
 *    [handleTokenRefresh] and [handleMessageReceived] to forward to us. [handleMessageReceived]
 *    returns `false` if the message is not a Hightouch push, so the host can continue handling
 *    it.
 */
class HightouchFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        handleTokenRefresh(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        handleMessageReceived(this, remoteMessage)
    }

    companion object {

        /**
         * Forward an FCM token refresh to the Hightouch SDK. No-op if the SDK has not been
         * initialized yet (the next refresh after initialization will deliver the token).
         */
        @JvmStatic
        fun handleTokenRefresh(token: String) {
            try {
                HightouchPush.register(token)
            } catch (_: IllegalStateException) {
                // SDK not initialized yet — FCM will call us again on the next refresh.
            }
        }

        /**
         * Forward an FCM message to the Hightouch SDK.
         *
         * @return `true` if the message was a Hightouch push (the SDK claimed it); `false` if
         *   not — in which case the calling [FirebaseMessagingService] is expected to keep
         *   handling the message itself (display its own notification, etc.).
         */
        @JvmStatic
        fun handleMessageReceived(context: Context, remoteMessage: RemoteMessage): Boolean {
            return HightouchNotificationPresenter.handle(context, remoteMessage)
        }
    }
}
