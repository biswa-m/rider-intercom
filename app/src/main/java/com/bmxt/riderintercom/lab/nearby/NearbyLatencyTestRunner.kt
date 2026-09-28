package com.bmxt.riderintercom.lab.nearby

import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NearbyLatencyTestRunner {
    private var job: Job? = null

    fun run(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) {
        cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            onComplete(execute(manager, onUpdate))
        }
    }

    private suspend fun execute(manager: NearbyConnectionManager, onUpdate: (String) -> Unit): LabTestResult {
        val testId = "NEARBY_LATENCY_10S_80B_50PPS-${System.currentTimeMillis()}"
        val started = System.currentTimeMillis()
        val config = NearbyDataTestConfig(testId, 80, 50)

        onUpdate("Preparing synchronized latency test…")
        manager.resetLatencyTest()
        val ready = manager.prepareTestSignal("LAT_READY|$testId")
        if (!manager.sendTestControl("LAT_PREPARE|$testId|${config.startDelayMs}")) {
            return failure(started, "Could not send latency PREPARE")
        }
        if (withTimeoutOrNull(5_000L) { ready.await() } == null) {
            return failure(started, "Peer did not become ready within 5 seconds")
        }

        onUpdate("Synchronizing clocks…")
        val offsets = mutableListOf<Double>()
        val syncRtts = mutableListOf<Double>()
        repeat(SYNC_SAMPLES) { index ->
            val id = "$testId-$index"
            val response = manager.prepareTestSignal("LAT_SYNC_RESP|$id")
            val t1 = System.currentTimeMillis()
            if (!manager.sendTestControl("LAT_SYNC_REQ|$id|$t1")) {
                return failure(started, "Could not send clock sync request $index")
            }
            val message = withTimeoutOrNull(SYNC_TIMEOUT_MS) { response.await() }
                ?: return failure(started, "Clock sync timed out at sample $index")
            val parts = message.split('|')
            val t1Echo = parts.getOrNull(2)?.toLongOrNull() ?: t1
            val t2 = parts.getOrNull(3)?.toLongOrNull() ?: return failure(started, "Invalid sync response t2")
            val t3 = parts.getOrNull(4)?.toLongOrNull() ?: return failure(started, "Invalid sync response t3")
            val t4 = System.currentTimeMillis()
            val offset = ((t2 - t1Echo) + (t3 - t4)) / 2.0
            val rtt = (t4 - t1Echo) - (t3 - t2)
            offsets += offset
            syncRtts += rtt.toDouble()
            onUpdate("Clock sync ${index + 1}/$SYNC_SAMPLES — RTT ${rtt} ms")
        }
        val clockOffset = offsets.sorted()[offsets.size / 2]
        onUpdate("Clock offset: ${"%.2f".format(clockOffset)} ms")
        if (!manager.sendTestControl("LAT_CONFIG|$testId|$clockOffset")) {
            return failure(started, "Could not send latency clock configuration")
        }

        val resultWait = manager.prepareTestSignal("LAT_RESULT|$testId")
        if (!manager.sendTestControl("LAT_START|$testId|${config.startDelayMs}|${config.durationMs}")) {
            return failure(started, "Could not send latency START")
        }
        delay(config.startDelayMs)

        val payload = ByteArray(config.payloadSize)
        val intervalNs = 1_000_000_000L / config.packetsPerSecond
        val end = System.nanoTime() + config.durationMs * 1_000_000L
        var seq = 0
        var sent = 0L
        var nextNs = System.nanoTime()
        onUpdate("Running latency test: 80B × 50pps × 10s…")
        while (job?.isActive == true && System.nanoTime() < end) {
            ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(LATENCY_MAGIC)
                putInt(seq++)
                putLong(System.currentTimeMillis())
            }
            if (manager.sendRaw(payload)) sent++
            nextNs += intervalNs
            val sleepNs = nextNs - System.nanoTime()
            if (sleepNs > 0) delay((sleepNs / 1_000_000L).coerceAtLeast(1L))
        }

        delay(1_500L)
        manager.sendTestControl("LAT_STOP|$testId|$sent")
        onUpdate("Transmission finished. Waiting for latency result…")
        val result = withTimeoutOrNull(5_000L) { resultWait.await() }
            ?: return failure(started, "Timed out waiting for latency result; TX=$sent")
        val p = result.split('|')
        val rx = p.getOrNull(2)?.toLongOrNull() ?: 0L
        val samples = p.getOrNull(3)?.toLongOrNull() ?: 0L
        val avg = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0
        val p50 = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val p95 = p.getOrNull(6)?.toDoubleOrNull() ?: 0.0
        val max = p.getOrNull(7)?.toDoubleOrNull() ?: 0.0
        val min = p.getOrNull(8)?.toDoubleOrNull() ?: 0.0
        val rttAvg = syncRtts.averageOrZero()
        val rttP95 = syncRtts.percentile(95.0)
        val receivedPct = if (sent == 0L) 0.0 else rx * 100.0 / sent
        val passed = sent > 0 && rx == sent && samples == rx
        val note = "TX=$sent RX=$rx received=${"%.2f".format(receivedPct)}%; oneWayMin=${"%.2f".format(min)}ms avg=${"%.2f".format(avg)}ms p50=${"%.2f".format(p50)}ms p95=${"%.2f".format(p95)}ms max=${"%.2f".format(max)}ms clockOffset=${"%.2f".format(clockOffset)}ms syncSamples=$SYNC_SAMPLES syncRttAvg=${"%.2f".format(rttAvg)}ms syncRttP95=${"%.2f".format(rttP95)}ms"
        onUpdate("Latency: avg ${"%.2f".format(avg)} ms, P95 ${"%.2f".format(p95)} ms, max ${"%.2f".format(max)} ms")
        return LabTestResult(
            testName = "NEARBY_LATENCY_10S_80B_50PPS",
            startEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            txPackets = sent,
            rxPackets = rx,
            uniqueRxPackets = rx,
            duplicatePackets = 0,
            outOfOrderPackets = 0,
            sequenceGaps = sent - rx,
            txBytes = sent * config.payloadSize,
            rxBytes = rx * config.payloadSize,
            averageInterArrivalMs = avg,
            p95InterArrivalMs = p95,
            maxInterArrivalMs = max,
            passed = passed,
            note = note
        )
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun List<Double>.percentile(percent: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = ((percent / 100.0) * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lower = index.toInt()
        val upper = kotlin.math.ceil(index).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = index - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fun cancel() { job?.cancel(); job = null }

    private fun failure(started: Long, note: String) = LabTestResult(
        testName = "NEARBY_LATENCY_10S_80B_50PPS", startEpochMs = started,
        durationMs = System.currentTimeMillis() - started, txPackets = 0, rxPackets = 0,
        uniqueRxPackets = 0, duplicatePackets = 0, outOfOrderPackets = 0, sequenceGaps = 0,
        txBytes = 0, rxBytes = 0, averageInterArrivalMs = 0.0, p95InterArrivalMs = 0.0,
        maxInterArrivalMs = 0.0, passed = false, note = note
    )

    companion object {
        private const val SYNC_SAMPLES = 10
        private const val SYNC_TIMEOUT_MS = 2_000L
        private const val LATENCY_MAGIC = 0x5249544C
    }
}
