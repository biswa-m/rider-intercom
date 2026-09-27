package com.bmxt.riderintercom.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AudioTestScreen(
    audioRunning: Boolean,
    microphonePermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Rider Intercom")

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            if (audioRunning) {
                "Audio: Running"
            } else {
                "Audio: Stopped"
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            if (microphonePermission) {
                "Microphone: Permission granted"
            } else {
                "Microphone: Permission required"
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (audioRunning) {
            Button(onClick = onStop) {
                Text("Stop Audio Test")
            }
        } else {
            Button(onClick = onStart) {
                Text("Start Audio Test")
            }
        }
    }
}