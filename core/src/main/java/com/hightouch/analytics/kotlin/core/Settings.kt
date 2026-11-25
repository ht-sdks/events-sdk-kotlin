package com.hightouch.analytics.kotlin.core

import com.hightouch.analytics.kotlin.core.platform.DestinationPlugin
import com.hightouch.analytics.kotlin.core.platform.Plugin
import com.hightouch.analytics.kotlin.core.utilities.LenientJson
import com.hightouch.analytics.kotlin.core.utilities.safeJsonObject
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

@Serializable
data class Settings(
    var integrations: JsonObject = emptyJsonObject,
    var plan: JsonObject = emptyJsonObject,
    var edgeFunction: JsonObject = emptyJsonObject,
    var middlewareSettings: JsonObject = emptyJsonObject,
    var metrics: JsonObject = emptyJsonObject,
    var consentSettings: JsonObject = emptyJsonObject,
    var autoInstrumentation: JsonObject = emptyJsonObject
) {
    inline fun <reified T : Any> destinationSettings(
        name: String,
        strategy: DeserializationStrategy<T> = Json.serializersModule.serializer(),
    ): T? {
        val integrationData = integrations[name]?.safeJsonObject ?: return null
        val typedSettings = LenientJson.decodeFromJsonElement(strategy, integrationData)
        return typedSettings
    }

    fun hasIntegrationSettings(plugin: DestinationPlugin): Boolean {
        return hasIntegrationSettings(plugin.key)
    }

    fun hasIntegrationSettings(key: String): Boolean {
        return integrations.containsKey(key)
    }
}

internal suspend fun Analytics.update(settings: Settings) {
    val systemState = store.currentState(System::class) ?: return
    val set = mutableSetOf<Int>()
    timeline.applyClosure { plugin ->
        // tell all top level plugins to update.
        // For destination plugins they auto-handle propagation to sub-plugins
        val type: Plugin.UpdateType =
            if (systemState.initializedPlugins.contains(plugin.hashCode())) {
                Plugin.UpdateType.Refresh
            } else {
                set.add(plugin.hashCode())
                Plugin.UpdateType.Initial
            }
        plugin.update(settings, type)
    }
    store.dispatch(System.AddInitializedPlugins(set), System::class)
}

/**
 * Manually enable a destination plugin.  This is useful when a given DestinationPlugin doesn't have any Segment tie-ins at all.
 * This will allow the destination to be processed in the same way within this library.
 */
fun Analytics.manuallyEnableDestination(plugin: DestinationPlugin) {
    analyticsScope.launch(analyticsDispatcher) {
        store.dispatch(
            System.AddDestinationToSettingsAction(destinationKey = plugin.key),
            System::class
        )
        // Differs from swift, bcos kotlin can store `enabled` state. ref: https://git.io/J1bhJ
        // finding it in timeline rather than using the ref that is provided to cover our bases
        find(plugin::class)?.enabled = true
    }
}


/**
 * Remote settings are no longer fetched. Propagates local settings (cached or default) to plugins
 * and releases the startup queue without making any network request.
 */
@Deprecated(
    "Remote settings are not fetched. Plugins are configured from local settings."
)
suspend fun Analytics.checkSettings() {
    store.currentState(System::class)?.settings?.let { settings ->
        update(settings)
    }
    resumeEventProcessing()
}