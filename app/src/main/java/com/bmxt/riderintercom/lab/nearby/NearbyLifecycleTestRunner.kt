package com.bmxt.riderintercom.lab.nearby

import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class NearbyLifecycleTestRunner {
    private var job: Job? = null

    fun run(manager: NearbyConnectionManager, cycles: Int = 3, onUpdate: (String) -> Unit, onComplete: (List<LabTestResult>) -> Unit) {
        cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            val results = mutableListOf<LabTestResult>()
            repeat(cycles) { cycle ->
                val start = System.currentTimeMillis()
                onUpdate("Cycle ${cycle + 1}: forcing disconnect")
                manager.forceUnexpectedDisconnectForTest()
                val reconnectStart = System.currentTimeMillis()
                val state = withTimeoutOrNull(30_000L) {
                    manager.state.filter { it.status == NearbyConnectionStatus.CONNECTED || it.status == NearbyConnectionStatus.ERROR }.first()
                } ?: manager.state.value
                val reconnectMs = System.currentTimeMillis() - reconnectStart
                val passed = state.status == NearbyConnectionStatus.CONNECTED
                results += LabTestResult(
                    testName = "NEARBY_LIFECYCLE_CYCLE_${cycle + 1}",
                    startEpochMs = start,
                    durationMs = System.currentTimeMillis() - start,
                    txPackets = 0, rxPackets = 0, uniqueRxPackets = 0,
                    duplicatePackets = 0, outOfOrderPackets = 0, sequenceGaps = 0,
                    txBytes = 0, rxBytes = 0,
                    averageInterArrivalMs = 0.0, p95InterArrivalMs = 0.0, maxInterArrivalMs = 0.0,
                    passed = passed,
                    note = "automatic reconnect=${reconnectMs}ms; state=${state.status}; ${state.lastEvent}"
                )
                onUpdate("Cycle ${cycle + 1}: ${if (passed) "PASS" else "FAIL"} — reconnect ${reconnectMs} ms")
                if (!passed) return@repeat
                delay(1000)
            }
            onComplete(results)
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
