package com.bmxt.riderintercom.lab.core

import com.bmxt.riderintercom.lab.nearby.NearbyPacketTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LabCsvWriter {
    private val header = listOf(
        "row_type", "test_name", "start_epoch_ms", "duration_ms", "tx_packets", "rx_packets",
        "unique_rx_packets", "duplicate_packets", "out_of_order_packets", "sequence_gaps", "tx_bytes",
        "rx_bytes", "avg_inter_arrival_ms", "p95_inter_arrival_ms", "max_inter_arrival_ms",
        "passed", "note", "receive_order", "sequence", "sender_elapsed_ms", "receiver_elapsed_ms",
        "inter_arrival_ms", "sequence_delta", "packet_out_of_order", "packet_duplicate"
    )

    fun build(results: List<LabTestResult>, packetTraces: List<NearbyPacketTrace> = emptyList()): String = buildString {
        appendLine(header.joinToString(","))

        results.forEach { r ->
            val row = mutableListOf(
                "TEST_SUMMARY", quote(r.testName), r.startEpochMs.toString(), r.durationMs.toString(),
                r.txPackets.toString(), r.rxPackets.toString(), r.uniqueRxPackets.toString(),
                r.duplicatePackets.toString(), r.outOfOrderPackets.toString(), r.sequenceGaps.toString(),
                r.txBytes.toString(), r.rxBytes.toString(),
                "%.3f".format(Locale.US, r.averageInterArrivalMs),
                "%.3f".format(Locale.US, r.p95InterArrivalMs),
                "%.3f".format(Locale.US, r.maxInterArrivalMs),
                r.passed.toString(), quote(r.note)
            )
            repeat(8) { row += "" }
            appendLine(row.joinToString(","))
        }

        packetTraces.forEach { r ->
            val row = mutableListOf("PACKET_TRACE", quote(r.testId))
            repeat(15) { row += "" }
            row += r.receiveOrder.toString()
            row += r.sequence.toString()
            row += "%.3f".format(Locale.US, r.senderElapsedMs)
            row += "%.3f".format(Locale.US, r.receiverElapsedMs)
            row += "%.3f".format(Locale.US, r.interArrivalMs)
            row += r.sequenceDelta.toString()
            row += r.outOfOrder.toString()
            row += r.duplicate.toString()
            appendLine(row.joinToString(","))
        }
    }

    fun defaultFileName(): String = "rider-intercom-lab-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
