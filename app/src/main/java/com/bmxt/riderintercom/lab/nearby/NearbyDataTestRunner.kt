package com.bmxt.riderintercom.lab.nearby

import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NearbyDataTestRunner {
    private var job: Job? = null

    fun run(manager: NearbyConnectionManager, config: NearbyDataTestConfig, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) {
        cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            onComplete(execute(manager, config, onUpdate))
        }
    }

    fun runStandard(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) =
        run(manager, NearbyDataTestConfig("NEARBY_BYTES_10S_640B_50PPS", 640, 50), onUpdate, onComplete)

    fun runVoiceSized(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) =
        run(manager, NearbyDataTestConfig("NEARBY_VOICE_SIZE_10S_80B_50PPS", 80, 50), onUpdate, onComplete)

    fun runRateSweep(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (List<LabTestResult>) -> Unit) {
        cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            val results = mutableListOf<LabTestResult>()
            for (rate in listOf(10, 25, 50, 100)) {
                if (job?.isActive != true) break
                val result = execute(manager, NearbyDataTestConfig("NEARBY_RATE_${rate}PPS_640B_10S", 640, rate), onUpdate)
                results += result
                delay(750L)
            }
            onComplete(results)
        }
    }

    private suspend fun execute(manager: NearbyConnectionManager, config: NearbyDataTestConfig, onUpdate: (String) -> Unit): LabTestResult {
        val testId = "${config.name}-${System.currentTimeMillis()}"
        val started = System.currentTimeMillis()
        manager.resetCounters()

        onUpdate("Preparing receiver…")
        val readyWait = manager.prepareTestSignal("READY|$testId")
        if (!manager.sendTestControl("PREPARE|$testId|${config.durationMs}|${config.packetsPerSecond}|${config.payloadSize}")) {
            return failure(started, config, "Could not send PREPARE; no connected endpoint")
        }
        if (withTimeoutOrNull(5_000L) { readyWait.await() } == null) {
            return failure(started, config, "Peer did not become READY within 5 seconds")
        }

        manager.resetCounters()
        onUpdate("Both phones ready. Synchronizing ${config.startDelayMs} ms start…")
        if (!manager.sendTestControl("START|$testId|${config.startDelayMs}")) {
            return failure(started, config, "Could not send START")
        }
        delay(config.startDelayMs)

        val testStart = System.currentTimeMillis()
        val end = System.nanoTime() + config.durationMs * 1_000_000L
        val intervalNs = 1_000_000_000L / config.packetsPerSecond
        val payload = ByteArray(config.payloadSize)
        var seq = 0
        var sent = 0L
        var nextSendNs = System.nanoTime()
        onUpdate("Running: ${config.payloadSize}B × ${config.packetsPerSecond}/sec × ${config.durationMs / 1000}s…")
        while (job?.isActive == true && System.nanoTime() < end) {
            ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(MAGIC)
                putInt(seq++)
                putLong(System.nanoTime())
            }
            if (manager.sendRaw(payload)) sent++
            nextSendNs += intervalNs
            val sleepNs = nextSendNs - System.nanoTime()
            if (sleepNs > 0) delay((sleepNs / 1_000_000L).coerceAtLeast(1L))
        }

        delay(1_000L)
        val resultWait = manager.prepareTestSignal("RESULT|$testId")
        if (!manager.sendTestControl("STOP|$testId|$sent")) {
            return failure(testStart, config, "Could not send STOP; senderTX=$sent")
        }
        onUpdate("Transmission finished. Waiting for receiver result…")
        val resultMessage = withTimeoutOrNull(5_000L) { resultWait.await() }
            ?: return failure(testStart, config, "Timed out waiting for receiver RESULT; senderTX=$sent")

        val parts = resultMessage.split('|')
        val rx = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val unique = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val duplicates = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val gaps = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val outOfOrder = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val rxBytes = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        val avgInterArrival = parts.getOrNull(8)?.toDoubleOrNull() ?: 0.0
        val p95InterArrival = parts.getOrNull(9)?.toDoubleOrNull() ?: 0.0
        val maxInterArrival = parts.getOrNull(10)?.toDoubleOrNull() ?: 0.0
        val maxSeq = parts.getOrNull(11)?.toLongOrNull() ?: -1L
        val missing = (sent - unique).coerceAtLeast(0L)
        val receivedPct = if (sent == 0L) 0.0 else unique * 100.0 / sent
        val throughputKbps = if (config.durationMs == 0L) 0.0 else rxBytes * 8.0 / config.durationMs
        val passed = sent > 0 && unique == sent && duplicates == 0L && gaps == 0L && outOfOrder == 0L
        val note = "${config.payloadSize}B/${config.packetsPerSecond}pps; TX=$sent RX=$rx unique=$unique received=${"%.2f".format(receivedPct)}% missing=$missing gaps=$gaps duplicates=$duplicates outOfOrder=$outOfOrder maxSeq=$maxSeq throughput=${"%.2f".format(throughputKbps)}kbps interArrivalAvg=${"%.2f".format(avgInterArrival)}ms p95=${"%.2f".format(p95InterArrival)}ms max=${"%.2f".format(maxInterArrival)}ms"
        onUpdate("Finished: TX=$sent RX=$rx unique=$unique missing=$missing; ${"%.2f".format(throughputKbps)} kbps")
        return LabTestResult(
            testName = config.name,
            startEpochMs = testStart,
            durationMs = System.currentTimeMillis() - testStart,
            txPackets = sent, rxPackets = rx, uniqueRxPackets = unique,
            duplicatePackets = duplicates, outOfOrderPackets = outOfOrder, sequenceGaps = gaps,
            txBytes = sent * config.payloadSize, rxBytes = rxBytes,
            averageInterArrivalMs = avgInterArrival, p95InterArrivalMs = p95InterArrival, maxInterArrivalMs = maxInterArrival,
            passed = passed, note = note
        )
    }

    fun cancel() { job?.cancel(); job = null }

    private fun failure(started: Long, config: NearbyDataTestConfig, note: String) = LabTestResult(
        testName = config.name, startEpochMs = started, durationMs = System.currentTimeMillis() - started,
        txPackets = 0, rxPackets = 0, uniqueRxPackets = 0, duplicatePackets = 0, outOfOrderPackets = 0, sequenceGaps = 0,
        txBytes = 0, rxBytes = 0, averageInterArrivalMs = 0.0, p95InterArrivalMs = 0.0, maxInterArrivalMs = 0.0,
        passed = false, note = note
    )

    companion object { private const val MAGIC = 0x52495444 }
}
