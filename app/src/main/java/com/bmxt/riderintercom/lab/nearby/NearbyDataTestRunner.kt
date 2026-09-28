package com.bmxt.riderintercom.lab.nearby

import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Coordinated two-phone transport test: PREPARE -> READY -> START -> DATA -> STOP -> RESULT. */
class NearbyDataTestRunner {
    companion object {
        private const val TEST_ID_PREFIX = "DATA10-"
        private const val DURATION_MS = 10_000L
        private const val PACKETS_PER_SECOND = 50
        private const val PAYLOAD_SIZE = 640
        private const val START_DELAY_MS = 1_000L
    }

    private var job: Job? = null
    private var manager: NearbyConnectionManager? = null

    fun run(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) {
        cancel()
        this.manager = manager
        job = CoroutineScope(Dispatchers.Default).launch {
            manager.resetCounters()
            val id = "$TEST_ID_PREFIX${System.currentTimeMillis()}"
            val started = System.currentTimeMillis()
            onUpdate("Preparing both phones…")
            val readyWait = manager.prepareTestSignal("READY|$id")
            if (!manager.sendTestControl("PREPARE|$id|$DURATION_MS|$PACKETS_PER_SECOND|$PAYLOAD_SIZE")) {
                onComplete(failure(started, "Could not send PREPARE; no connected endpoint"))
                return@launch
            }
            onUpdate("Waiting for peer READY…")
            if (withTimeoutOrNull(5_000L) { readyWait.await() } == null) {
                onComplete(failure(started, "Peer did not become READY within 5 seconds"))
                return@launch
            }

            manager.resetCounters()
            val startAt = System.currentTimeMillis() + START_DELAY_MS
            onUpdate("Peer ready. Starting both phones in ${START_DELAY_MS} ms…")
            if (!manager.sendTestControl("START|$id|$startAt")) {
                onComplete(failure(started, "Could not send START"))
                return@launch
            }
            delay(START_DELAY_MS)

            val testStart = System.currentTimeMillis()
            val end = testStart + DURATION_MS
            val interval = 1000L / PACKETS_PER_SECOND
            val payload = ByteArray(PAYLOAD_SIZE)
            var seq = 0
            var sent = 0L
            onUpdate("Running synchronized test: $PACKETS_PER_SECOND packets/sec, ${PAYLOAD_SIZE} bytes…")
            while (isActive && System.currentTimeMillis() < end) {
                ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0x52495444) // RITD
                    putInt(seq++)
                    putLong(System.nanoTime())
                }
                if (manager.sendRaw(payload)) sent++
                delay(interval)
            }

            delay(500L)
            val resultWait = manager.prepareTestSignal("RESULT|$id")
            if (!manager.sendTestControl("STOP|$id")) {
                onComplete(failure(testStart, "Could not send STOP"))
                return@launch
            }
            onUpdate("Transmission finished. Waiting for synchronized receiver result…")
            val resultMessage = withTimeoutOrNull(5_000L) { resultWait.await() }
            if (resultMessage == null) {
                onComplete(failure(testStart, "Timed out waiting for receiver RESULT; senderTX=$sent"))
                return@launch
            }

            val parts = resultMessage.split('|')
            val rx = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val unique = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val duplicates = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            val gaps = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val outOfOrder = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val rxBytes = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            val passed = sent > 0 && unique > 0 && gaps == 0L && duplicates == 0L && outOfOrder == 0L
            val result = LabTestResult(
                testName = "NEARBY_BYTES_10S",
                startEpochMs = testStart,
                durationMs = System.currentTimeMillis() - testStart,
                txPackets = sent,
                rxPackets = rx,
                uniqueRxPackets = unique,
                duplicatePackets = duplicates,
                outOfOrderPackets = outOfOrder,
                sequenceGaps = gaps,
                txBytes = sent * PAYLOAD_SIZE,
                rxBytes = rxBytes,
                averageInterArrivalMs = 0.0,
                p95InterArrivalMs = 0.0,
                maxInterArrivalMs = 0.0,
                passed = passed,
                note = "Synchronized two-phone test; payload=${PAYLOAD_SIZE}B; target=${PACKETS_PER_SECOND}pps; RX=$rx unique=$unique gaps=$gaps duplicates=$duplicates outOfOrder=$outOfOrder"
            )
            onUpdate("Finished: TX=$sent RX=$rx unique=$unique gaps=$gaps")
            onComplete(result)
        }
    }

    private fun failure(started: Long, note: String) = LabTestResult(
        testName = "NEARBY_BYTES_10S",
        startEpochMs = started,
        durationMs = System.currentTimeMillis() - started,
        txPackets = 0, rxPackets = 0, uniqueRxPackets = 0,
        duplicatePackets = 0, outOfOrderPackets = 0, sequenceGaps = 0,
        txBytes = 0, rxBytes = 0,
        averageInterArrivalMs = 0.0, p95InterArrivalMs = 0.0, maxInterArrivalMs = 0.0,
        passed = false, note = note
    )

    fun cancel() { job?.cancel(); job = null; manager = null }
}
