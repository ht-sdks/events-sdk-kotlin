package com.hightouch.analytics.kotlin.push.internal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hightouch.analytics.kotlin.push.CepEventTracking
import com.hightouch.analytics.kotlin.push.HightouchAction
import com.hightouch.analytics.kotlin.push.HightouchActionContext
import com.hightouch.analytics.kotlin.push.HightouchActionSource
import com.hightouch.analytics.kotlin.push.HightouchPush
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Routes a notification tap (body or action button):
 *
 *  1. Fire the `"opened"` engagement event with `action.identifier` set to `"default"` (body
 *     tap) or the button identifier.
 *  2. If the payload carries an action: when `type` is `"openUrl"`, hand the URL to
 *     [com.hightouch.analytics.kotlin.push.HightouchPushConfig.urlHandler]; if null/false, fall
 *     back to `Intent.ACTION_VIEW` for `https` and schemes in
 *     [com.hightouch.analytics.kotlin.push.HightouchPushConfig.allowedProtocols]. Other types go
 *     to the custom-action handler. Each path reports whether it actually opened something.
 *  3. If nothing was opened and this tap should open the app (body tap or an `openApp=true`
 *     button — i.e. not from the background action receiver), launch the host app, gated on
 *     [com.hightouch.analytics.kotlin.push.HightouchPushConfig.autoLaunchApp].
 *
 * Mirrors iOS `HightouchAppIntegration.userNotificationCenter(...)`.
 */
internal object ActionRouter {

    private const val TAG = "HightouchPush"
    private const val OPEN_URL = "openUrl"

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun route(context: Context, extras: Bundle) {
        val messageId = extras.getString(IntentExtras.MESSAGE_ID) ?: return
        val source = sourceFrom(extras)
        val messageContext = extras.getString(IntentExtras.MESSAGE_CONTEXT_JSON)
            ?.let { runCatching { lenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }

        fireOpenedEvent(messageId, source, messageContext)

        val customData = CustomDataJson.decode(extras.getString(IntentExtras.CUSTOM_DATA_JSON))
        val actionType = extras.getString(IntentExtras.ACTION_TYPE)
        val actionData = extras.getString(IntentExtras.ACTION_DATA)
        val handled = if (actionType != null) {
            handleAction(context, source, actionType, actionData, customData)
        } else {
            false
        }

        // openApp=false buttons route through HightouchPushActionReceiver, which sets this flag;
        // those must never launch the app. Body taps and openApp=true buttons may.
        val openApp = !extras.getBoolean(IntentExtras.FROM_ACTION_RECEIVER, false)
        if (openApp && !handled) {
            launchApp(context, customData)
        }
    }

    /**
     * Dismiss the notification a tap originated from, matching the exact `(tag, id)` the SDK
     * posted with (carried in the extras). Falls back to `messageId.hashCode()` for intents that
     * predate the id extra. Called by the trampoline activity and the action receiver.
     */
    fun dismiss(context: Context, extras: Bundle) {
        val nm = NotificationManagerCompat.from(context)
        val tag = extras.getString(IntentExtras.NOTIFICATION_TAG)
        if (extras.containsKey(IntentExtras.NOTIFICATION_ID)) {
            nm.cancel(tag, extras.getInt(IntentExtras.NOTIFICATION_ID))
        } else {
            extras.getString(IntentExtras.MESSAGE_ID)?.let { nm.cancel(it.hashCode()) }
        }
    }

    private fun sourceFrom(extras: Bundle): HightouchActionSource {
        val buttonId = extras.getString(IntentExtras.SOURCE_BUTTON_ID)
        return if (buttonId != null) HightouchActionSource.ActionButton(buttonId)
        else HightouchActionSource.Push
    }

    private fun fireOpenedEvent(
        messageId: String,
        source: HightouchActionSource,
        messageContext: JsonObject?,
    ) {
        val identifier = when (source) {
            HightouchActionSource.Push -> "default"
            is HightouchActionSource.ActionButton -> source.identifier
        }
        CepEventTracking.track(
            name = CepEventTracking.ENGAGEMENT_EVENTS,
            properties = buildJsonObject {
                put("provider_event_type", CepEventTracking.PUSH_OPENED)
                put(
                    "action",
                    buildJsonObject { put("identifier", identifier) },
                )
                put("message_id", messageId)
            },
            messageContext = messageContext,
        )
    }

    /** @return true if the action opened something (handler claimed it or a URL was launched). */
    private fun handleAction(
        context: Context,
        source: HightouchActionSource,
        type: String,
        data: String?,
        customData: Map<String, String>?,
    ): Boolean {
        val config = HightouchPush.configForRouting ?: return false
        val actionContext = HightouchActionContext(source, customData)

        return if (type == OPEN_URL) {
            val url = data?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
            if (config.urlHandler?.handleUrl(url, actionContext) == true) {
                true
            } else {
                openUrlFallback(context, url, config.allowedProtocols)
            }
        } else {
            config.customActionHandler?.handleAction(HightouchAction(type, data), actionContext) == true
        }
    }

    /** @return true if an activity was started for the URL. */
    private fun openUrlFallback(context: Context, url: Uri, allowedProtocols: List<String>): Boolean {
        val scheme = url.scheme?.lowercase() ?: return false
        val allowed = scheme == "https" || allowedProtocols.any { it.equals(scheme, ignoreCase = true) }
        if (!allowed) {
            Log.w(TAG, "Refusing to open URL with non-allowed scheme: $scheme")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrElse {
            Log.w(TAG, "No activity available to handle URL $url: ${it.message}")
            false
        }
    }

    /**
     * Launch the host app's launcher activity, attaching
     * `customData` so the opened activity can route via [HightouchPush.getCustomData]. No-op when
     * [com.hightouch.analytics.kotlin.push.HightouchPushConfig.autoLaunchApp] is false or the app
     * exposes no launcher intent.
     */
    private fun launchApp(context: Context, customData: Map<String, String>?) {
        if (!HightouchPush.cepAutoLaunchApp) return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent == null) {
            Log.w(TAG, "No launcher intent for ${context.packageName}; cannot open app.")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launchIntent.setPackage(null)
        customData?.takeIf { it.isNotEmpty() }?.let {
            launchIntent.putExtra(HightouchPush.EXTRA_CUSTOM_DATA, CustomDataJson.encode(it))
        }
        runCatching { context.startActivity(launchIntent) }.onFailure {
            Log.w(TAG, "Failed to launch app: ${it.message}")
        }
    }
}
