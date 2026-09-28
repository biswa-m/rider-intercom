package com.bmxt.riderintercom.lab.ui.screens

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
import com.bmxt.riderintercom.lab.nearby.NearbyConnectionStatus
import com.bmxt.riderintercom.lab.nearby.NearbyPeer
import com.bmxt.riderintercom.lab.nearby.NearbyState

@Composable
fun NearbyLabScreen(
    state: NearbyState,
    dataTesting: Boolean,
    dataProgress: String,
    lifecycleTesting: Boolean,
    lifecycleProgress: String,
    latencyTesting: Boolean,
    latencyProgress: String,
    results: List<LabTestResult>,
    onStartAuto: () -> Unit,
    onDiscover: () -> Unit,
    onConnect: (NearbyPeer) -> Unit,
    onDisconnect: () -> Unit,
    onRunDataTest: () -> Unit,
    onRunVoiceTest: () -> Unit,
    onRunRateSweep: () -> Unit,
    onRunLatencyTest: () -> Unit,
    onRunLifecycleTest: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Rider Intercom Lab", style = MaterialTheme.typography.headlineSmall)
            Text("Nearby Connections experiment — no Wi-Fi Direct code is used here.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nearby Connections", style = MaterialTheme.typography.titleLarge)
                    Text("Status: ${state.status}")
                    Text("This device: ${state.localName}")
                    Text("Peer: ${state.connectedPeer?.name ?: "—"}")
                    Text("Event: ${state.lastEvent}")
                    state.lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartAuto, enabled = state.status != NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Auto connect") }
                        OutlinedButton(onClick = onDiscover, enabled = !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Discover") }
                        OutlinedButton(onClick = onDisconnect, enabled = state.status == NearbyConnectionStatus.CONNECTED) { Text("Disconnect") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Discovered peers", style = MaterialTheme.typography.titleMedium)
                    if (state.discoveredPeers.isEmpty()) Text("No peers discovered")
                }
            }
        }
        items(state.discoveredPeers, key = { it.endpointId }) { peer ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name)
                        Text(peer.endpointId, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onConnect(peer) }, enabled = state.status != NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting) { Text("Connect") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("10-second data test", style = MaterialTheme.typography.titleLarge)
                    Text("One phone starts a synchronized 10-second test. The other phone receives automatically. 640 bytes × 50 packets/sec.")
                    Text(dataProgress)
                    Text("TX ${state.packetsSent} / RX ${state.packetsReceived}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRunDataTest, enabled = state.status == NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("640B / 50pps") }
                        OutlinedButton(onClick = onRunVoiceTest, enabled = state.status == NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Voice size") }
                    }
                    OutlinedButton(onClick = onRunRateSweep, enabled = state.status == NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Rate sweep: 10 / 25 / 50 / 100pps") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One-way latency test", style = MaterialTheme.typography.titleLarge)
                    Text("Synchronized clock test using 80-byte payloads at 50 packets/sec for 10 seconds. Measures approximate sender-to-receiver latency.")
                    Text(latencyProgress)
                    Button(onClick = onRunLatencyTest, enabled = state.status == NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Run latency test") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic reconnect test", style = MaterialTheme.typography.titleLarge)
                    Text("Forces a connection loss and lets Nearby Connections recover automatically.")
                    Text(lifecycleProgress)
                    Button(onClick = onRunLifecycleTest, enabled = state.status == NearbyConnectionStatus.CONNECTED && !dataTesting && !latencyTesting && !lifecycleTesting) { Text("Run 3-cycle test") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unified test log", style = MaterialTheme.typography.titleLarge)
                    Text("Buffered rows: ${results.size}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExport, enabled = results.isNotEmpty()) { Text("Export Unified CSV") }
                        OutlinedButton(onClick = onReset) { Text("Reset") }
                    }
                    HorizontalDivider()
                    results.takeLast(10).forEach { r ->
                        Text("${r.testName}: ${if (r.passed) "PASS" else "FAIL"} — ${r.note}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
