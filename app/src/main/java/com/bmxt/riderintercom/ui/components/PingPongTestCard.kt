package com.bmxt.riderintercom.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bmxt.riderintercom.intercom.diagnostics.PingPongTestManager

@Composable
fun PingPongTestCard(
    peerHost: String,
    state: PingPongTestManager.State,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Ping-pong clock test", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "A standalone network test. Start it on both phones. Each phone sends " +
                "30 timestamped requests and replies. It stops automatically when 30 " +
                "successful samples are collected."
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("Diagnostic UDP port: ${PingPongTestManager.DEFAULT_PORT}")
        Text("Status: ${state.status}")
        Text("Local samples: ${state.localSamples}/${PingPongTestManager.REQUIRED_SAMPLES}   Peer replies: ${state.peerSamples}")

        state.clockOffsetMs?.let {
            Text("Clock offset (peer − this phone): ${it} ms")
        }
        state.offsetJitterMs?.let {
            Text("Offset jitter: ${it} ms")
        }
        if (state.rttAvgMs != null) {
            Text(
                "RTT: min ${state.rttMinMs} ms • avg ${state.rttAvgMs} ms • " +
                    "P95 ${state.rttP95Ms} ms • max ${state.rttMaxMs} ms"
            )
        }
        state.error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = if (state.running) onStop else onStart,
            enabled = state.running || peerHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.running) "Stop Ping-Pong Test" else "Start Ping-Pong Test")
        }
    }
}
