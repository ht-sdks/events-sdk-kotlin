package com.hightouch.analytics.kotlin.push.sample.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.hightouch.analytics.kotlin.push.HightouchPush
import kotlinx.coroutines.delay
import java.io.File

/**
 * Live SDK status plus the data collection controls. Shown on both the login and home screens so
 * [HightouchPush.wipeData] can be exercised with and without an identified user.
 *
 * @param onDataWiped called after [HightouchPush.wipeData], which logs the user out locally.
 */
@Composable
fun StatusPanel(onDataWiped: () -> Unit = {}) {
    val context = LocalContext.current
    val deviceId = remember { readDeviceId(context) }

    // Bumped after each button tap so the status refreshes immediately instead of on the next poll.
    var refreshKey by remember { mutableStateOf(0) }

    // Poll the SDK every second so the token field reflects any FCM refresh that lands after
    // login. Simple and good enough for a sample; a real app could expose a SharedFlow.
    val state by produceState(initialValue = SnapshotState.read(context), refreshKey) {
        while (true) {
            value = SnapshotState.read(context)
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Field(
            label = "Data collection enabled",
            value = state.dataCollectionEnabled?.toString() ?: "—",
            color = when (state.dataCollectionEnabled) {
                true -> Color(0xFF2E7D32)
                false -> Color(0xFFD32F2F)
                null -> Color.Unspecified
            },
        )
        Field(label = "User id", value = state.userId ?: "—")
        Field(label = "Anonymous id", value = state.anonymousId ?: "—")
        Field(label = "Device id", value = deviceId ?: "—", mono = true)
        Field(
            label = "FCM token",
            value = state.fcmToken ?: "(waiting for FCM…)",
            mono = true,
        )
        Field(
            label = "Notifications enabled",
            value = state.notificationsEnabled.toString(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    HightouchPush.setDataCollectionEnabled(false)
                    refreshKey++
                },
                modifier = Modifier.weight(1f),
            ) { Text("Stop Data Collection") }
            Button(
                onClick = {
                    HightouchPush.setDataCollectionEnabled(true)
                    refreshKey++
                },
                modifier = Modifier.weight(1f),
            ) { Text("Start Data Collection") }
        }
        OutlinedButton(
            onClick = {
                HightouchPush.wipeData()
                refreshKey++
                onDataWiped()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Wipe Data") }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    mono: Boolean = false,
    color: Color = Color.Unspecified,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.caption)
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.body2 else MaterialTheme.typography.body1,
            color = color,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// HightouchPush has no public device id accessor; this is the file its DeviceIdStore writes.
private fun readDeviceId(context: Context): String? =
    runCatching { File(context.noBackupFilesDir, "hightouch_push_device_id").readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

private data class SnapshotState(
    val dataCollectionEnabled: Boolean?,
    val userId: String?,
    val anonymousId: String?,
    val fcmToken: String?,
    val notificationsEnabled: Boolean,
) {
    companion object {
        // areNotificationsEnabled() reflects the real app-level toggle on every API level — and
        // on Android 13+ it also tracks the POST_NOTIFICATIONS grant — so no version branch is
        // needed.
        fun read(context: Context) = SnapshotState(
            dataCollectionEnabled = runCatching { HightouchPush.isDataCollectionEnabled }.getOrNull(),
            userId = HightouchPush.userId,
            anonymousId = runCatching { HightouchPush.anonymousId }.getOrNull(),
            fcmToken = HightouchPush.fcmToken,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }
}
