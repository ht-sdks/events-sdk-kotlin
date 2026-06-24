package com.hightouch.analytics.kotlin.push.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hightouch.analytics.kotlin.push.sample.AppPreferences

@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    canClose: Boolean,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    var writeKey by remember { mutableStateOf(prefs.writeKey.orEmpty()) }
    var appId by remember { mutableStateOf(prefs.appId.orEmpty()) }
    var apiHost by remember { mutableStateOf(prefs.apiHost.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = if (canClose) {
                    { TextButton(onClick = onClose) { Text("Close") } }
                } else {
                    null
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Defaults come from local.properties (shown in gray inside each empty field); " +
                    "any value entered here overrides them.",
            )
            OutlinedTextField(
                value = writeKey,
                onValueChange = { writeKey = it },
                label = { Text("Write key") },
                placeholder = { DefaultPlaceholder(prefs.defaultWriteKey) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("App id") },
                placeholder = { DefaultPlaceholder(prefs.defaultAppId) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiHost,
                onValueChange = { apiHost = it },
                label = { Text("API host (optional)") },
                placeholder = { DefaultPlaceholder(prefs.defaultApiHost) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        prefs.writeKey = writeKey
                        prefs.appId = appId
                        prefs.apiHost = apiHost
                        onSaved()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save & Connect") }
                TextButton(
                    onClick = {
                        prefs.resetOverrides()
                        writeKey = ""
                        appId = ""
                        apiHost = ""
                        onSaved()
                    },
                ) { Text("Reset to defaults") }
            }
        }
    }
}

/**
 * Placeholder shown inside an empty Settings field. Surfaces the BuildConfig default (the
 * `hightouch.*` value compiled in from `local.properties`); falls back to a subdued
 * italic `(not set)` when the default itself is blank.
 */
@Composable
private fun DefaultPlaceholder(default: String) {
    if (default.isBlank()) {
        Text("(not set)", fontStyle = FontStyle.Italic)
    } else {
        Text(default)
    }
}
