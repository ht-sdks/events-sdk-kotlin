package com.hightouch.analytics.kotlin.push.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.hightouch.analytics.kotlin.push.HightouchPush
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val context = LocalContext.current

    // Poll the SDK every second so the token field reflects any FCM refresh that lands after
    // login. Simple and good enough for a sample; a real app could expose a SharedFlow.
    val state by produceState(
        initialValue = SnapshotState.empty(),
    ) {
        while (true) {
            value = SnapshotState(
                userId = HightouchPush.userId,
                anonymousId = runCatching { HightouchPush.anonymousId }.getOrNull(),
                fcmToken = HightouchPush.fcmToken,
                notificationsEnabled = areNotificationsEnabled(context),
            )
            delay(1_000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Status") }) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(label = "User id", value = state.userId ?: "—")
            Field(label = "Anonymous id", value = state.anonymousId ?: "—")
            Field(
                label = "FCM token",
                value = state.fcmToken ?: "(waiting for FCM…)",
                mono = true,
            )
            Field(
                label = "Notifications enabled",
                value = state.notificationsEnabled.toString(),
            )

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log out", color = Color.White) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.caption)
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.body2 else MaterialTheme.typography.body1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// areNotificationsEnabled() reflects the real app-level toggle on every API level — and on
// Android 13+ it also tracks the POST_NOTIFICATIONS grant — so no version branch is needed.
private fun areNotificationsEnabled(context: android.content.Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private data class SnapshotState(
    val userId: String?,
    val anonymousId: String?,
    val fcmToken: String?,
    val notificationsEnabled: Boolean,
) {
    companion object {
        fun empty() = SnapshotState(null, null, null, false)
    }
}
