package com.bmxt.riderintercom.lab.network

import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlin.math.roundToLong

class NetworkStatistics(private val testName: String, private val startEpochMs: Long) {
    var txPackets = 0L; private set
    var rxPackets = 0L; private set
    var uniqueRxPackets = 0L; private set
    var duplicatePackets = 0L; private set
    var outOfOrderPackets = 0L; private set
    var sequenceGaps = 0L; private set
    var txBytes = 0L; private set
    var rxBytes = 0L; private set

    private val arrivalIntervals = ArrayList<Double>()
    private var previousArrivalNanos: Long? = null
    private var highestSequence = -1L
    private val seenSequences = HashSet<Long>()

    fun onTx(bytes: Int) { txPackets++; txBytes += bytes }

    @Synchronized
    fun onRx(packet: NetworkTestPacket.Packet, bytes: Int, arrivalNanos: Long) {
        rxPackets++
        rxBytes += bytes
        val previous = previousArrivalNanos
        if (previous != null) arrivalIntervals += (arrivalNanos - previous) / 1_000_000.0
        previousArrivalNanos = arrivalNanos

        if (!seenSequences.add(packet.sequence)) {
            duplicatePackets++
            return
        }
        uniqueRxPackets++
        if (highestSequence >= 0) {
            when {
                packet.sequence > highestSequence + 1 -> sequenceGaps += packet.sequence - highestSequence - 1
                packet.sequence <= highestSequence -> outOfOrderPackets++
            }
        }
        if (packet.sequence > highestSequence) highestSequence = packet.sequence
    }

    fun finish(durationMs: Long, note: String): LabTestResult {
        val sorted = arrivalIntervals.sorted()
        val avg = sorted.averageOrZero()
        val p95 = sorted.percentile(95.0)
        val max = sorted.maxOrNull() ?: 0.0
        // The sender and receiver stop at slightly different times, so the final
        // packet can legitimately be outside the receiver's measurement window.
        // Treat one trailing packet shortfall as a test-boundary effect. Actual
        // packets missing in the middle are still detected by sequenceGaps.
        val boundaryLossAllowance = 1L
        val passed = txPackets > 0 &&
            rxPackets > 0 &&
            sequenceGaps == 0L &&
            duplicatePackets == 0L &&
            uniqueRxPackets >= (txPackets - boundaryLossAllowance).coerceAtLeast(1L)
        return LabTestResult(
            testName = testName,
            startEpochMs = startEpochMs,
            durationMs = durationMs,
            txPackets = txPackets,
            rxPackets = rxPackets,
            uniqueRxPackets = uniqueRxPackets,
            duplicatePackets = duplicatePackets,
            outOfOrderPackets = outOfOrderPackets,
            sequenceGaps = sequenceGaps,
            txBytes = txBytes,
            rxBytes = rxBytes,
            averageInterArrivalMs = avg,
            p95InterArrivalMs = p95,
            maxInterArrivalMs = max,
            passed = passed,
            note = "$note; tx_rx_difference=${txPackets - uniqueRxPackets}"
        )
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val index = ((p / 100.0) * (size - 1)).roundToLong().toInt().coerceIn(0, size - 1)
        return this[index]
    }
}
