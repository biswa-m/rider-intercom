package com.bmxt.riderintercom.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bmxt.riderintercom.intercom.audio.AudioDebugState
import com.bmxt.riderintercom.intercom.diagnostics.PingPongTestManager
import com.bmxt.riderintercom.ui.components.PingPongTestCard
import com.bmxt.riderintercom.intercom.audio.IntercomFeatureConfig
import com.bmxt.riderintercom.intercom.audio.LegacyBluetoothHeadset
import kotlin.math.roundToInt

@Composable
fun AudioTestScreen(
    audioRunning: Boolean,
    voiceDetected: Boolean,
    debugState: AudioDebugState,
    featureConfig: IntercomFeatureConfig,
    microphonePermission: Boolean,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    communicationDevices: List<AudioDeviceInfo>,
    legacyBluetoothHeadsets: List<LegacyBluetoothHeadset>,
    legacyBluetoothScoActive: Boolean,
    currentCommunicationDevice: AudioDeviceInfo?,
    routingError: String?,
    localIpv4Addresses: List<String>,
    defaultNetworkPort: Int,
    inputDeviceName: (AudioDeviceInfo) -> String,
    outputDeviceName: (AudioDeviceInfo) -> String,
    describeDevice: (AudioDeviceInfo) -> String,
    onSelectLegacyBluetoothSco: () -> Unit,
    onSelectCommunicationDevice: (AudioDeviceInfo) -> Unit,
    onClearCommunicationDevice: () -> Unit,
    onSetUseVad: (Boolean) -> Unit,
    onSetUseOpus: (Boolean) -> Unit,
    onSetUseJitterBuffer: (Boolean) -> Unit,
    onSetUseVadPreRoll: (Boolean) -> Unit,
    onSetUseTimestampLatencyTest: (Boolean) -> Unit,
    onSetLatencyClockOffsetMs: (Long) -> Unit,
    onSetLatencySampleEveryPackets: (Int) -> Unit,
    onApplyPingPongClockOffset: () -> Unit,
    onResetFeatureConfig: () -> Unit,
    pingPongState: PingPongTestManager.State,
    onStartPingPong: (String) -> Unit,
    onStopPingPong: () -> Unit,
    onStart: () -> Unit,
    onStartIntercom: (String) -> Unit,
    onStop: () -> Unit
) {
    var peerHost by remember { mutableStateOf("") }
    var latencyOffsetText by remember { mutableStateOf(featureConfig.latencyClockOffsetMs.toString()) }
    var latencySampleEveryText by remember { mutableStateOf(featureConfig.latencySampleEveryPackets.toString()) }

    LaunchedEffect(featureConfig.latencyClockOffsetMs) {
        latencyOffsetText = featureConfig.latencyClockOffsetMs.toString()
    }
    LaunchedEffect(featureConfig.latencySampleEveryPackets) {
        latencySampleEveryText = featureConfig.latencySampleEveryPackets.toString()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Rider Intercom", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(if (audioRunning) "Audio: Running" else "Audio: Stopped")
            if (audioRunning) {
                if (featureConfig.useVad) {
                    Text(
                        if (voiceDetected) "Voice activity: Speech detected"
                        else "Voice activity: Silence / listening"
                    )
                } else {
                    Text("VAD: Disabled • transmitting audio continuously")
                }
            }
            Text(if (microphonePermission) "Microphone: Permission granted" else "Microphone: Permission required")

            Spacer(modifier = Modifier.height(12.dp))
            Text("Live audio diagnostics", style = MaterialTheme.typography.titleMedium)
            DiagnosticMeter("Mic level", debugState.micLevelDb)
            DiagnosticMeter("Remote audio", debugState.remoteLevelDb)

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (debugState.transmittingAudio) "● TX AUDIO" else "○ TX AUDIO",
                    modifier = Modifier.weight(1f),
                    color = if (debugState.transmittingAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (debugState.receivingAudio) "● RX AUDIO" else "○ RX AUDIO",
                    modifier = Modifier.weight(1f),
                    color = if (debugState.receivingAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Pipeline: ${featureConfig.pipelineDescription}")
            Text("Features are applied when the intercom starts. Use the same settings on both phones.")
            Text("VAD: ${if (featureConfig.useVad) "ON" else "OFF"} | Opus: ${if (featureConfig.useOpus) "ON" else "OFF"} | Jitter: ${if (featureConfig.effectiveJitterBuffer) "ON" else "OFF"} | Pre-roll: ${if (featureConfig.effectiveVadPreRoll) "ON" else "OFF"}")

            Spacer(modifier = Modifier.height(12.dp))
            Text("Experimental network features", style = MaterialTheme.typography.titleMedium)
            FeatureSwitchRow(
                title = "VAD",
                description = "Gate transmission based on voice activity.",
                checked = featureConfig.useVad,
                enabled = !audioRunning,
                onCheckedChange = onSetUseVad
            )
            FeatureSwitchRow(
                title = "Opus",
                description = "Compress voice before UDP transmission.",
                checked = featureConfig.useOpus,
                enabled = !audioRunning,
                onCheckedChange = onSetUseOpus
            )
            FeatureSwitchRow(
                title = "Jitter buffer",
                description = if (featureConfig.useOpus) "Buffer Opus packets to smooth network timing." else "Available when Opus is enabled.",
                checked = featureConfig.useJitterBuffer,
                enabled = !audioRunning && featureConfig.useOpus,
                onCheckedChange = onSetUseJitterBuffer
            )
            FeatureSwitchRow(
                title = "VAD pre-roll",
                description = if (featureConfig.useVad) "Send a short audio lead-in when speech starts." else "Available when VAD is enabled.",
                checked = featureConfig.useVadPreRoll,
                enabled = !audioRunning && featureConfig.useVad,
                onCheckedChange = onSetUseVadPreRoll
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Timestamp latency test (testing only)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Voice packets already carry a sender timestamp. When enabled, " +
                    "the receiver measures timestamp → AudioTrack.write start. " +
                    "It does not change the audio pipeline."
            )
            FeatureSwitchRow(
                title = "Enable latency measurement",
                description = "Keep OFF for normal intercom testing.",
                checked = featureConfig.useTimestampLatencyTest,
                enabled = !audioRunning,
                onCheckedChange = onSetUseTimestampLatencyTest
            )
            OutlinedTextField(
                value = latencyOffsetText,
                onValueChange = { value ->
                    latencyOffsetText = value
                    value.toLongOrNull()?.let(onSetLatencyClockOffsetMs)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Peer clock offset (peer − this phone), ms") },
                placeholder = { Text("Example: -364") },
                singleLine = true,
                enabled = !audioRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Button(
                onClick = onApplyPingPongClockOffset,
                enabled = !audioRunning && pingPongState.clockOffsetMs != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    pingPongState.clockOffsetMs?.let {
                        "Use ping-pong offset ($it ms)"
                    } ?: "Run ping-pong test first"
                )
            }
            OutlinedTextField(
                value = latencySampleEveryText,
                onValueChange = { value ->
                    latencySampleEveryText = value
                    value.toIntOrNull()?.let(onSetLatencySampleEveryPackets)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Measure every Nth playback packet") },
                placeholder = { Text("1 = every packet") },
                singleLine = true,
                enabled = !audioRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(
                onClick = onResetFeatureConfig,
                enabled = !audioRunning
            ) {
                Text("Reset to simple PCM test")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Network: ${debugState.networkState}")
            Text("TX packets: ${debugState.packetsSent}   RX packets: ${debugState.packetsReceived}")
            Text("TX bytes: ${debugState.bytesSent}   RX bytes: ${debugState.bytesReceived}")
            if (featureConfig.useOpus) {
                Text("Encoded: ${debugState.encodedFrames}   No output: ${debugState.encodeNoOutputFrames}")
                Text("Decoded: ${debugState.decodedFrames}   No output: ${debugState.decodeNoOutputFrames}")
            } else {
                Text("Codec: PCM (no Opus encoding/decoding)")
            }
            Text("Malformed UDP: ${debugState.malformedPackets}")
            Text("Last TX payload: ${debugState.lastSentPayloadBytes} B")
            Text("Last RX payload: ${debugState.lastReceivedPayloadBytes} B, seq=${debugState.lastReceivedSequence}")
            if (debugState.latencyTestEnabled) {
                Text(
                    "Timestamp latency: samples ${debugState.latencySamples} | " +
                        "last ${debugState.latencyLastMs ?: "-"} ms | " +
                        "avg ${debugState.latencyAvgMs ?: "-"} ms"
                )
                Text(
                    "Latency min ${debugState.latencyMinMs ?: "-"} ms | " +
                        "P95 ${debugState.latencyP95Ms ?: "-"} ms | " +
                        "max ${debugState.latencyMaxMs ?: "-"} ms"
                )
                Text("Clock offset used: ${debugState.latencyClockOffsetMs} ms")
            }
            if (featureConfig.effectiveJitterBuffer) {
                Text("Jitter buffer: ${debugState.jitterBufferedPackets}/${debugState.jitterMaxPackets} (target ${debugState.jitterTargetPackets})")
                Text("Estimated lost: ${debugState.estimatedLostPackets}   Late: ${debugState.latePackets}   Overflow drop: ${debugState.overflowDroppedPackets}")
                Text("Jitter resyncs: ${debugState.jitterResyncs}   Playback underruns: ${debugState.playbackUnderruns}")
            } else {
                Text("Jitter buffer: OFF")
                Text("Direct RX queue: ${debugState.jitterBufferedPackets}/${debugState.jitterMaxPackets}   Dropped: ${debugState.overflowDroppedPackets}")
                if (featureConfig.useOpus) {
                    Text("Decoded PCM queue: ${debugState.decodedPcmQueueDepth}/6   Dropped: ${debugState.decodedPcmQueueDropped}")
                }
            }
            if (featureConfig.useOpus && featureConfig.effectiveJitterBuffer) {
                Text("Decoded PCM queue: ${debugState.decodedPcmQueueDepth}/6   Dropped: ${debugState.decodedPcmQueueDropped}")
            }
            Text("Playback samples: ${debugState.playbackSamples}")
            Text("Playback write failures: ${debugState.playbackWriteFailures}")
            Text("AudioRecord: ${debugState.audioRecordState}   AudioTrack: ${debugState.audioTrackState}")
            Text("Playback format: ${debugState.playbackSampleRate} Hz / ${debugState.playbackChannelCount} ch")
            Text("Opus encoder: ${debugState.opusEncoderName}")
            Text("Opus decoder: ${debugState.opusDecoderName}")
            debugState.lastError?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Last error: $it", color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text("Two-phone Wi-Fi test", style = MaterialTheme.typography.titleMedium)
            Text(featureConfig.pipelineDescription)
            Text("UDP port: $defaultNetworkPort")
            if (localIpv4Addresses.isEmpty()) {
                Text("Local IPv4: not available")
            } else {
                Text("This phone's IPv4: ${localIpv4Addresses.joinToString()}")
            }

            OutlinedTextField(
                value = peerHost,
                onValueChange = { peerHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Other phone IPv4 address") },
                placeholder = { Text("Example: 192.168.1.25") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            if (audioRunning) {
                Button(onClick = onStop) { Text("Stop Intercom") }
            } else {
                Button(
                    onClick = { onStartIntercom(peerHost) },
                    enabled = peerHost.isNotBlank()
                ) { Text("Start Two-Phone Intercom") }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            PingPongTestCard(
                peerHost = peerHost,
                state = pingPongState,
                onStart = { onStartPingPong(peerHost) },
                onStop = onStopPingPong
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("Current communication device", style = MaterialTheme.typography.titleMedium)
            Text(currentCommunicationDevice?.let(describeDevice) ?: "System default / none selected")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onClearCommunicationDevice) { Text("Use System Default") }
            routingError?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Communication devices", style = MaterialTheme.typography.titleMedium)

            if (legacyBluetoothHeadsets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Bluetooth headsets (legacy Android)", style = MaterialTheme.typography.titleSmall)
                Text("Android 11 uses the legacy Bluetooth SCO route.")
            }
        }

        if (legacyBluetoothHeadsets.isNotEmpty()) {
            items(legacyBluetoothHeadsets, key = { headset -> "bt-${headset.address}" }) { headset ->
                Button(onClick = onSelectLegacyBluetoothSco, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (legacyBluetoothScoActive) "${headset.name} | Bluetooth SCO (active)"
                        else "${headset.name} | Bluetooth SCO"
                    )
                }
            }
        } else if (communicationDevices.isEmpty()) {
            item { Text("No connected Bluetooth headset reported by Android.") }
        }

        if (communicationDevices.isNotEmpty()) {
            items(communicationDevices, key = { "comm-${it.id}-${it.type}" }) { device ->
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
            Text("Use this to validate the microphone and headset path.")
            if (audioRunning) {
                Button(onClick = onStop) { Text("Stop Audio Test") }
            } else {
                Button(onClick = onStart) { Text("Start Local Audio Test") }
            }
        }

        item {
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

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun DiagnosticMeter(label: String, levelDb: Float) {
    val clamped = levelDb.coerceIn(-60f, 0f)
    val progress = ((clamped + 60f) / 60f).coerceIn(0f, 1f)
    val integerDb = levelDb.roundToInt()

    Text("$label: ${integerDb} dB")
    LinearProgressIndicator(
        progress = progress,
        modifier = Modifier.fillMaxWidth()
    )
}


@Composable
private fun FeatureSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
