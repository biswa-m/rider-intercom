package com.bmxt.riderintercom.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AudioTestScreen(
    audioRunning: Boolean,
    microphonePermission: Boolean,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    communicationDevices: List<AudioDeviceInfo>,
    currentCommunicationDevice: AudioDeviceInfo?,
    routingError: String?,
    inputDeviceName: (AudioDeviceInfo) -> String,
    outputDeviceName: (AudioDeviceInfo) -> String,
    describeDevice: (AudioDeviceInfo) -> String,
    onSelectCommunicationDevice: (AudioDeviceInfo) -> Unit,
    onClearCommunicationDevice: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Rider Intercom", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Text(if (audioRunning) "Audio: Running" else "Audio: Stopped")
            Text(
                if (microphonePermission) {
                    "Microphone: Permission granted"
                } else {
                    "Microphone: Permission required"
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Current communication device",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                currentCommunicationDevice?.let(describeDevice)
                    ?: "System default / none selected"
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onClearCommunicationDevice) {
                Text("Use System Default")
            }

            routingError?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Communication devices",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (communicationDevices.isEmpty()) {
            item {
                Text("No selectable communication devices reported by Android.")
            }
        } else {
            items(communicationDevices, key = { it.id }) { device ->
                Button(onClick = { onSelectCommunicationDevice(device) }) {
                    Text(describeDevice(device))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text("All input devices", style = MaterialTheme.typography.titleMedium)
        }

        items(inputDevices, key = { "in-${it.id}" }) { device ->
            Text("🎤 ${inputDeviceName(device)}")
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text("All output devices", style = MaterialTheme.typography.titleMedium)
        }

        items(outputDevices, key = { "out-${it.id}" }) { device ->
            Text("🔊 ${outputDeviceName(device)}")
        }

        item {
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
}
