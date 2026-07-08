package com.hightouch.analytics.kotlin.push.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hightouch.analytics.kotlin.push.sample.SilentPushStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * Lists every silent push the app has received (newest first), including ones delivered while
 * the app was backgrounded — [SilentPushStore] persists them across the process wake. Polling
 * once a second keeps the list fresh when a silent push lands while this screen is open,
 * matching the HomeScreen pattern.
 */
@Composable
fun SilentPushLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val entries by produceState(initialValue = SilentPushStore.entries(context)) {
        while (true) {
            value = SilentPushStore.entries(context)
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Silent push log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "No silent pushes received yet. Send one with isSilent=true and some " +
                        "customData — it arrives with no notification and shows up here, " +
                        "even if the app was in the background.",
                    style = MaterialTheme.typography.body2,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries) { entry -> EntryCard(entry) }
                }
            }

            Button(
                onClick = { SilentPushStore.clear(context) },
                enabled = entries.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear log", color = Color.White) }
        }
    }
}

@Composable
private fun EntryCard(entry: SilentPushStore.Entry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(Date(entry.receivedAtMillis)),
                style = MaterialTheme.typography.caption,
            )
            entry.customData.forEach { (key, value) ->
                Text(
                    text = "$key = $value",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
