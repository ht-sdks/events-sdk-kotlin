package com.hightouch.analytics.kotlin.push.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hightouch.analytics.kotlin.push.HightouchPush
import com.hightouch.analytics.kotlin.push.sample.ui.HomeScreen
import com.hightouch.analytics.kotlin.push.sample.ui.LoginScreen
import com.hightouch.analytics.kotlin.push.sample.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Screen { Settings, Login, Home }

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication

    // Pick the initial screen based on configuration + identification state.
    var screen by remember {
        mutableStateOf(
            when {
                !app.sdkConfigured -> Screen.Settings
                HightouchPush.userId != null -> Screen.Home
                else -> Screen.Login
            },
        )
    }

    // Android 13+ notification permission prompt. Fire-and-forget — the SDK is happy either way.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* result not surfaced — the SDK silently no-ops without permission. */ },
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (screen) {
        Screen.Settings -> SettingsScreen(
            prefs = app.prefs,
            onSaved = {
                if (app.reinitialize()) {
                    screen = if (HightouchPush.userId != null) Screen.Home else Screen.Login
                }
            },
            onClose = { if (app.sdkConfigured) screen = Screen.Login },
        )
        Screen.Login -> LoginScreen(
            onLogin = { userId ->
                HightouchPush.identify(userId)
                screen = Screen.Home
            },
            onOpenSettings = { screen = Screen.Settings },
        )
        Screen.Home -> HomeScreen(
            onLogout = {
                HightouchPush.logout()
                screen = Screen.Login
            },
        )
    }
}
