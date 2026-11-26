package com.hightouch.analytics.kotlin.push.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onDataWiped: () -> Unit,
    onOpenSilentPushLog: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Status") }) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusPanel(onDataWiped = onDataWiped)

            Button(
                onClick = onOpenSilentPushLog,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Silent push log") }

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log out", color = Color.White) }
        }
    }
}
