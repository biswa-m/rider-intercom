package com.bmxt.riderintercom.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AudioTestScreen(
    audioRunning: Boolean,
    voiceDetected: Boolean,
    microphonePermission: Boolean,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    communicationDevices: List<AudioDeviceInfo>,
    currentCommunicationDevice: AudioDeviceInfo?,
    routingError: String?,
    localIpv4Addresses: List<String>,
    defaultNetworkPort: Int,
    inputDeviceName: (AudioDeviceInfo) -> String,
    outputDeviceName: (AudioDeviceInfo) -> String,
    describeDevice: (AudioDeviceInfo) -> String,
    onSelectCommunicationDevice: (AudioDeviceInfo) -> Unit,
    onClearCommunicationDevice: () -> Unit,
    onStart: () -> Unit,
    onStartIntercom: (String) -> Unit,
    onStop: () -> Unit
) {
    var peerHost by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Rider Intercom", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Text(if (audioRunning) "Audio: Running" else "Audio: Stopped")
            if (audioRunning) {
                Text(
                    if (voiceDetected) {
                        "Voice activity: Speech detected"
                    } else {
                        "Voice activity: Silence / listening"
                    }
                )
            }

            Text(
                if (microphonePermission) {
                    "Microphone: Permission granted"
                } else {
                    "Microphone: Permission required"
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Two-phone Wi-Fi test",
                style = MaterialTheme.typography.titleMedium
            )
            Text("This first network test sends uncompressed 16 kHz mono PCM.")
            Text("UDP port: $defaultNetworkPort")

            if (localIpv4Addresses.isEmpty()) {
                Text("Local IPv4: not available")
            } else {
                Text(
                    "This phone's IPv4: ${
                        localIpv4Addresses.joinToString()
                    }"
                )
            }

            OutlinedTextField(
                value = peerHost,
                onValueChange = { peerHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Other phone IPv4 address") },
                placeholder = { Text("Example: 192.168.1.25") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri
                )
            )

            if (audioRunning) {
                Button(onClick = onStop) {
                    Text("Stop Intercom")
                }
            } else {
                Button(
                    onClick = { onStartIntercom(peerHost) },
                    enabled = peerHost.isNotBlank()
                ) {
                    Text("Start Two-Phone Intercom")
                }
            }

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
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

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
            Text("Local audio loopback test", style = MaterialTheme.typography.titleMedium)
            Text("Use this only to validate the microphone and headset path.")
        }

        item {
            if (audioRunning) {
                Button(onClick = onStop) {
                    Text("Stop Audio Test")
                }
            } else {
                Button(onClick = onStart) {
                    Text("Start Local Audio Test")
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
        }
    }
}
