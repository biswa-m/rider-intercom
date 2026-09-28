package com.bmxt.riderintercom.lab.ui.screens

import android.net.wifi.p2p.WifiP2pDevice
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.wifidirect.WifiDirectState
import com.bmxt.riderintercom.lab.tests.WifiDirectLifecycleResult

@Composable
fun WifiDirectLabScreen(
    state: WifiDirectState,
    devices: List<WifiP2pDevice>,
    lastResult: LabTestResult?,
    results: List<LabTestResult>,
    testing: Boolean,
    progressText: String,
    tx: Long,
    rx: Long,
    onDiscover: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRunTest: () -> Unit,
    lifecycleTesting: Boolean,
    lifecycleProgress: String,
    lifecycleResults: List<WifiDirectLifecycleResult>,
    onRunLifecycleTest: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Rider Intercom Lab", style = MaterialTheme.typography.headlineSmall)
            Text("Step 1 — Wi-Fi Direct + UDP network validation")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Wi-Fi Direct", style = MaterialTheme.typography.titleLarge)
                    Text("Status: ${state.status}")
                    if (state.reconnecting) {
                        Text("Automatic recovery is active — keep both phones within Wi-Fi Direct range.")
                    }
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    Text("Role: ${if (state.isGroupOwner) "Group Owner" else "Client"}")
                    Text("Group owner: ${state.groupOwnerAddress ?: "—"}")
                    Text("Peer: ${state.peerName ?: "—"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDiscover, enabled = !testing) { Text("Discover") }
                        OutlinedButton(
                            onClick = onDisconnect,
                            enabled = (state.connected || state.reconnecting) && !testing
                        ) {
                            Text(if (state.reconnecting) "Cancel reconnect" else "Disconnect")
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Discovered devices", style = MaterialTheme.typography.titleMedium)
                    if (devices.isEmpty()) Text("No devices discovered")
                }
            }
        }
        items(devices, key = { it.deviceAddress }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(device.deviceName.ifBlank { "Unnamed device" })
                        Text(device.deviceAddress, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onConnect(device) }, enabled = !testing) { Text("Connect") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Automated UDP test", style = MaterialTheme.typography.titleLarge)
                    Text("Runs for 10 seconds at 50 packets/sec. No audio, Opus, decoder, or jitter buffer is involved.")
                    Text("TX: $tx    RX: $rx")
                    Text(progressText)
                    Button(onClick = onRunTest, enabled = state.connected && !testing) { Text("Run 10-second test") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Connection lifecycle test", style = MaterialTheme.typography.titleLarge)
                    Text("Runs 3 disconnect → automatic reconnect cycles. Both phones should have this app open; the peer also participates in discovery automatically.")
                    Text(lifecycleProgress)
                    lifecycleResults.forEach { r ->
                        Text("Cycle ${r.cycle}: ${if (r.passed) "PASS" else "FAIL"} — disconnect ${r.disconnectMs} ms, reconnect ${r.reconnectMs} ms")
                    }
                    Button(
                        onClick = onRunLifecycleTest,
                        enabled = state.connected && !testing && !lifecycleTesting
                    ) { Text("Run 3-cycle test") }
                }
            }
        }
        item {
            lastResult?.let { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Result: ${if (result.passed) "PASS" else "FAIL"}", style = MaterialTheme.typography.titleLarge)
                        Text("Combined results: ${results.joinToString { "${it.testName}=${if (it.passed) "PASS" else "FAIL"}" }}")
                        Text("TX ${result.txPackets}  RX ${result.uniqueRxPackets}")
                        Text("Loss estimate: ${"%.2f".format((1.0 - result.uniqueRxPackets.toDouble() / result.txPackets.coerceAtLeast(1)) * 100)}%")
                        Text("Avg interval: ${"%.2f".format(result.averageInterArrivalMs)} ms")
                        Text("P95 interval: ${"%.2f".format(result.p95InterArrivalMs)} ms")
                        Text("Max interval: ${"%.2f".format(result.maxInterArrivalMs)} ms")
                        Text("Gaps: ${result.sequenceGaps}, duplicates: ${result.duplicatePackets}, out-of-order: ${result.outOfOrderPackets}")
                        HorizontalDivider()
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Test log", style = MaterialTheme.typography.titleLarge)
                    Text("Buffered test runs: ${results.size}")
                    Text("All completed test results are kept in one in-memory CSV buffer until Reset.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExport, enabled = results.isNotEmpty()) {
                            Text("Export Unified CSV")
                        }
                        OutlinedButton(onClick = onReset, enabled = results.isNotEmpty()) {
                            Text("Reset")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
