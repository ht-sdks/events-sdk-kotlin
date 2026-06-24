package com.hightouch.analytics.kotlin.push.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage
import com.hightouch.analytics.kotlin.push.HTActionButton
import com.hightouch.analytics.kotlin.push.HTPushPayload
import com.hightouch.analytics.kotlin.push.HightouchAction
import com.hightouch.analytics.kotlin.push.HightouchActionSource
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.internal.CustomDataJson
import com.hightouch.analytics.kotlin.push.internal.IntentExtras
import com.hightouch.analytics.kotlin.push.trampoline.HightouchPushActionReceiver
import com.hightouch.analytics.kotlin.push.trampoline.HightouchTrampolineActivity

/**
 * Presents a Hightouch push payload to the user as a system notification.
 *
 * Entry point for incoming FCM messages, called by
 * [com.hightouch.analytics.kotlin.push.fcm.HightouchFirebaseMessagingService] (and by host
 * apps that forward via its static helpers). Parses the payload, ensures the notification
 * channel exists, builds the `NotificationCompat.Builder`, fetches rich media if specified,
 * and posts via `NotificationManagerCompat`.
 *
 * Channel handling:
 *  - Resolves per-message channel → configured default → `"hightouch_default"`. A per-message
 *    channel is honored only if the host already registered it, else falls back to the default.
 *  - Auto-creates only the default channel (Android 8+) at `IMPORTANCE_DEFAULT`; host-defined
 *    channels own their own importance and sound.
 *
 * Rich media:
 *  - If [HTPushPayload.attachmentUrl] is present, [handle] defers to [HightouchNotificationWorker]
 *    so the fetch runs off FCM's binder thread; the worker calls [postNotification], which loads
 *    the image via [RichMediaLoader]. On success the notification uses `BigPictureStyle`; on
 *    failure it is posted without the image.
 *
 * Permissions:
 *  - Android 13+ requires `POST_NOTIFICATIONS`. If the user has not granted it,
 *    [NotificationManagerCompat.areNotificationsEnabled] returns false and we silently skip
 *    posting (we still claim the message). The host app is responsible for requesting the
 *    permission.
 *
 * Tap routing:
 *  - Body tap and `openApp=true` action buttons launch [HightouchTrampolineActivity], which
 *    delegates to [com.hightouch.analytics.kotlin.push.internal.ActionRouter].
 *  - `openApp=false` action buttons go to [HightouchPushActionReceiver] instead, so the host
 *    app stays in the background.
 */
internal object HightouchNotificationPresenter {

    private const val TAG = "HightouchPush"

    @VisibleForTesting internal const val DEFAULT_CHANNEL_ID = "hightouch_default"
    private const val DEFAULT_CHANNEL_NAME = "Push notifications"
    private const val MAX_ACTION_BUTTONS = 3

    /**
     * Fixed id used for every notification posted with a [HTPushPayload.notificationTag]. Android
     * dedupes on the `(tag, id)` pair, so a stable id + the marketer's tag means a later message
     * with the same tag replaces the earlier one. Untagged notifications keep their per-message
     * `messageId.hashCode()` id instead.
     */
    @VisibleForTesting internal const val TAGGED_NOTIFICATION_ID = 100

    private const val RAW_RESOURCE_TYPE = "raw"

    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * Manifest meta-data key for the notification small icon. Host apps declare it under their
     * `<application>` block — for example:
     *
     * ```xml
     * <meta-data
     *     android:name="com.hightouch.push.default_notification_icon"
     *     android:resource="@drawable/ic_notification" />
     * ```
     *
     * The declarative manifest entry sidesteps the SDK-init-ordering race where FCM could deliver before
     * [HightouchPush.initialize] has set [HightouchPush.cepSmallIconResId].
     */
    @VisibleForTesting
    internal const val META_DATA_NOTIFICATION_ICON = "com.hightouch.push.default_notification_icon"

    /**
     * Entry point from [com.hightouch.analytics.kotlin.push.fcm.HightouchFirebaseMessagingService].
     * Decides where the notification is built: a message with a rich-media image is handed to
     * [HightouchNotificationWorker] so the HTTP fetch runs off FCM's binder thread; everything else
     * is posted synchronously via [postNotification].
     *
     * @return true if the message was a Hightouch push (claimed) — including when display was
     *   suppressed (notifications disabled) or deferred to the worker — so the FMS won't fall
     *   through to another handler.
     */
    fun handle(
        context: Context,
        remoteMessage: RemoteMessage,
        imageLoader: (String) -> Bitmap? = RichMediaLoader::load,
    ): Boolean {
        val payload = HTPushPayload.parse(remoteMessage.data) ?: return false
        val data = effectiveData(remoteMessage)

        if (payload.attachmentUrl != null) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
            HightouchNotificationWorker.enqueue(context, data)
            return true
        }
        return postNotification(context, data, imageLoader)
    }

    /**
     * Build and post the notification synchronously. Called inline for image-less messages and on
     * a WorkManager background thread (via [HightouchNotificationWorker]) for messages with an
     * image. The [imageLoader] seam lets tests supply a deterministic bitmap; production and the
     * worker use [RichMediaLoader.load].
     *
     * @param data the normalized FCM data map (see [effectiveData]). Both call paths hand it the
     *   data map rather than a `RemoteMessage`, so the worker need not rebuild one off its input.
     * @return true if the message was a Hightouch push (claimed), including when display was
     *   suppressed because notifications are disabled.
     */
    // POST_NOTIFICATIONS is guarded at runtime via NotificationManagerCompat.areNotificationsEnabled()
    // below. Lint can't see across that check into the nm.notify() call site.
    @SuppressLint("MissingPermission")
    @VisibleForTesting
    internal fun postNotification(
        context: Context,
        data: Map<String, String>,
        imageLoader: (String) -> Bitmap? = RichMediaLoader::load,
    ): Boolean {
        val payload = HTPushPayload.parse(data) ?: return false

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            return true
        }

        val channelId = resolveChannelId(context, nm, payload)

        val (title, body) = extractTitleBody(data)
        val smallIcon = resolveSmallIcon(context)
        if (smallIcon == 0) {
            // No icon configured anywhere — calling setSmallIcon(0) crashes nm.notify() on
            // modern Android. Log loudly and skip the post. We still
            // return true so the FMS doesn't fall through to another handler — the message was
            // ours; it just won't display until the host fixes its config.
            Log.w(
                TAG,
                "No notification icon configured. Push notifications will not display. " +
                    "Declare <meta-data android:name=\"$META_DATA_NOTIFICATION_ICON\" " +
                    "android:resource=\"@drawable/ic_notification\" /> in your manifest, or pass " +
                    "HightouchPushConfig.Builder.setSmallIconResId(...) before HightouchPush.initialize().",
            )
            return true
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            // Display importance is owned by the channel on API 26+ (this is ignored there). On
            // pre-O it sets the heads-up behavior; DEFAULT matches the SDK default channel's
            // importance. Delivery priority (FCM android.priority) is a separate, transport-only
            // axis set by the sender.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                trampolinePendingIntent(
                    context = context,
                    payload = payload,
                    source = HightouchActionSource.Push,
                    action = payload.defaultAction,
                ),
            )

        HightouchPush.cepColorResId?.let { colorRes ->
            builder.color = ContextCompat.getColor(context, colorRes)
        }

        payload.groupKey?.takeIf { it.isNotBlank() }?.let { builder.setGroup(it) }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            applyLegacySound(context, builder, payload.sound)
        }

        payload.attachmentUrl?.let { url ->
            imageLoader(url)?.let { bitmap ->
                builder
                    .setLargeIcon(bitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?),
                    )
            }
        }

        payload.actionButtons.orEmpty().take(MAX_ACTION_BUTTONS).forEach { button ->
            builder.addAction(
                /* icon = */ 0,
                /* title = */ button.title.orEmpty(),
                buttonPendingIntent(context, payload, button),
            )
        }

        val (tag, notificationId) = notificationTargetFor(payload)
        nm.notify(tag, notificationId, builder.build())
        return true
    }

    /**
     * Resolve the small icon resource id, in priority order:
     *
     *  1. Manifest meta-data `com.hightouch.push.default_notification_icon` — recommended;
     *     declarative; immune to SDK-init ordering.
     *  2. [HightouchPush.cepSmallIconResId] — set via [HightouchPushConfig.Builder.setSmallIconResId];
     *     useful when the icon needs to be picked dynamically at runtime.
     *  3. The host app's manifest `android:icon` — only used when non-zero (a missing
     *     `android:icon` attribute reads back as 0, and `setSmallIcon(0)` then ClassCasts the
     *     framework's notification-validity check on modern Android).
     *  4. `0` — caller must check and skip posting; we log a warning at that boundary.
     */
    private fun resolveSmallIcon(context: Context): Int {
        // 1. Manifest meta-data
        try {
            val info = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            val fromMetaData = info.metaData?.getInt(META_DATA_NOTIFICATION_ICON, 0) ?: 0
            if (fromMetaData != 0) return fromMetaData
        } catch (_: PackageManager.NameNotFoundException) {
            // Defensive — getApplicationInfo for our own package should always succeed.
        }

        // 2. HightouchPushConfig.Builder.setSmallIconResId
        HightouchPush.cepSmallIconResId?.let { return it }

        // 3. android:icon from the host manifest
        val appIcon = context.applicationInfo.icon
        if (appIcon != 0) return appIcon

        // 4. Caller must handle the 0 case.
        return 0
    }

    /** Title/body from the normalized data map (the `notification` block was folded in by [effectiveData]). */
    private fun extractTitleBody(data: Map<String, String>): Pair<String?, String?> =
        data["title"] to data["body"]

    /**
     * The canonical FCM data map the renderer consumes, with the `notification` block's title/body
     * folded in so they survive both the inline render and the WorkManager round-trip (which carries
     * only the data map, not a `RemoteMessage`). The notification block supplies title/body only as
     * defaults — an existing data-map `title`/`body` overrides them. Hightouch pushes are data-only,
     * so this is normally just `remoteMessage.data`.
     */
    private fun effectiveData(remoteMessage: RemoteMessage): Map<String, String> {
        val data = remoteMessage.data
        val notification = remoteMessage.notification ?: return data
        // "notification defaults, then data overrides", built with only core Map members (put,
        // putAll). Avoids Map.putIfAbsent — a Java-8 default method that binds to
        // java.util.Map#putIfAbsent (API 24) and throws NoSuchMethodError on API 21-23, since the
        // module is minSdk 21 with no core-library desugaring. Same API-24 trap as the Map.forEach
        // fixes elsewhere in this module; note Android lint's NewApi check does not flag it.
        return buildMap {
            notification.title?.let { put("title", it) }
            notification.body?.let { put("body", it) }
            putAll(data)
        }
    }

    /**
     * Resolve the channel id to post on: per-message [HTPushPayload.notificationChannel] →
     * configured default ([HightouchPush.cepChannelId]) → [DEFAULT_CHANNEL_ID].
     *
     * A per-message channel is honored only if the host app has already registered it; posting to
     * a non-existent channel silently fails on API 26+, so an unregistered id falls back to the
     * SDK default channel (with a warning). The SDK auto-creates only the default channel — any
     * other channel must be created by the host.
     */
    private fun resolveChannelId(context: Context, nm: NotificationManagerCompat, payload: HTPushPayload): String {
        val defaultId = HightouchPush.cepChannelId ?: DEFAULT_CHANNEL_ID
        val requested = payload.notificationChannel?.takeIf { it.isNotBlank() }
        val resolved = when {
            requested == null -> defaultId
            channelExists(nm, requested) -> requested
            else -> {
                Log.w(
                    TAG,
                    "Notification channel '$requested' is not registered; falling back to " +
                        "'$defaultId'. Create the channel in your app before targeting it.",
                )
                defaultId
            }
        }
        if (resolved == defaultId) ensureDefaultChannel(context, defaultId)
        return resolved
    }

    /** Pre-O has no channels, so any id is acceptable; on O+ the channel must already exist. */
    private fun channelExists(nm: NotificationManagerCompat, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return nm.getNotificationChannel(channelId) != null
    }

    /**
     * Create the SDK default channel at [NotificationManager.IMPORTANCE_DEFAULT] if it does not
     * already exist. Idempotent: if the host already created a channel with this id (e.g. they
     * pointed the config default at their own channel), `createNotificationChannel` is a no-op and
     * the host's importance is preserved.
     */
    private fun ensureDefaultChannel(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(
            channelId,
            DEFAULT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
    }

    /**
     * Apply a per-message sound on pre-Oreo only. On API 26+ sound is a property of the channel
     * and cannot be set per notification, so the field is ignored there (the channel owns it).
     * The sound file must be bundled in the host app under `res/raw/`; an unresolvable name falls
     * through to the channel/default sound.
     */
    private fun applyLegacySound(context: Context, builder: NotificationCompat.Builder, sound: String?) {
        val name = sound?.takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: return
        val resId = context.resources.getIdentifier(name, RAW_RESOURCE_TYPE, context.packageName)
        if (resId == 0) {
            Log.w(TAG, "Notification sound '$name' not found in res/raw; using the default sound.")
            return
        }
        builder.setSound(Uri.parse("android.resource://${context.packageName}/$resId"))
    }

    private fun trampolinePendingIntent(
        context: Context,
        payload: HTPushPayload,
        source: HightouchActionSource,
        action: HightouchAction?,
    ): PendingIntent {
        val intent = Intent(context, HightouchTrampolineActivity::class.java).apply {
            attachPayloadExtras(this, payload, source, action)
            // Each PendingIntent needs a unique data Uri so PendingIntent.getActivity does not
            // collapse two intents that should be distinct (body tap vs button vs different msg).
            data = Uri.parse("hightouch://push/${payload.messageId}/${source.uniqKey()}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(payload, source),
            intent,
            PENDING_INTENT_FLAGS,
        )
    }

    private fun receiverPendingIntent(
        context: Context,
        payload: HTPushPayload,
        button: HTActionButton,
    ): PendingIntent {
        val intent = Intent(context, HightouchPushActionReceiver::class.java).apply {
            action = HightouchPushActionReceiver.ACTION
            attachPayloadExtras(
                intent = this,
                payload = payload,
                source = HightouchActionSource.ActionButton(button.identifier),
                action = button.action,
            )
            putExtra(IntentExtras.FROM_ACTION_RECEIVER, true)
            data = Uri.parse("hightouch://push/${payload.messageId}/btn-${button.identifier}")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(
                payload = payload,
                source = HightouchActionSource.ActionButton(button.identifier),
            ),
            intent,
            PENDING_INTENT_FLAGS,
        )
    }

    private fun buttonPendingIntent(
        context: Context,
        payload: HTPushPayload,
        button: HTActionButton,
    ): PendingIntent {
        return if (button.openApp) {
            trampolinePendingIntent(
                context = context,
                payload = payload,
                source = HightouchActionSource.ActionButton(button.identifier),
                action = button.action,
            )
        } else {
            receiverPendingIntent(context, payload, button)
        }
    }

    private fun attachPayloadExtras(
        intent: Intent,
        payload: HTPushPayload,
        source: HightouchActionSource,
        action: HightouchAction?,
    ) {
        intent.putExtra(IntentExtras.MESSAGE_ID, payload.messageId)
        if (source is HightouchActionSource.ActionButton) {
            intent.putExtra(IntentExtras.SOURCE_BUTTON_ID, source.identifier)
        }
        action?.let {
            intent.putExtra(IntentExtras.ACTION_TYPE, it.type)
            it.data?.let { d -> intent.putExtra(IntentExtras.ACTION_DATA, d) }
        }
        payload.messageContext?.let {
            intent.putExtra(IntentExtras.MESSAGE_CONTEXT_JSON, it.toString())
        }
        payload.customData?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(IntentExtras.CUSTOM_DATA_JSON, CustomDataJson.encode(it))
        }
        // Carry the exact (tag, id) the SDK posted with so the tap handler dismisses the right
        // notification — necessary because tagged notifications use a fixed id, not messageId's.
        val (tag, notificationId) = notificationTargetFor(payload)
        tag?.let { intent.putExtra(IntentExtras.NOTIFICATION_TAG, it) }
        intent.putExtra(IntentExtras.NOTIFICATION_ID, notificationId)
    }

    /**
     * The `(tag, id)` pair a payload posts under. A non-blank [HTPushPayload.notificationTag]
     * yields `(tag, TAGGED_NOTIFICATION_ID)` so same-tag messages replace each other; otherwise
     * `(null, messageId.hashCode())`.
     */
    private fun notificationTargetFor(payload: HTPushPayload): Pair<String?, Int> {
        val tag = payload.notificationTag?.takeIf { it.isNotBlank() }
        val id = if (tag != null) TAGGED_NOTIFICATION_ID else notificationIdFor(payload)
        return tag to id
    }

    private fun HightouchActionSource.uniqKey(): String = when (this) {
        HightouchActionSource.Push -> "body"
        is HightouchActionSource.ActionButton -> "btn-$identifier"
    }

    private fun requestCodeFor(payload: HTPushPayload, source: HightouchActionSource): Int {
        return (payload.messageId + source.uniqKey()).hashCode()
    }

    @VisibleForTesting
    internal fun notificationIdFor(payload: HTPushPayload): Int = payload.messageId.hashCode()
}
