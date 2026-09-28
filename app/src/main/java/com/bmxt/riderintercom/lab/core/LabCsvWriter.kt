package com.bmxt.riderintercom.lab.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LabCsvWriter {
    fun build(results: List<LabTestResult>): String = buildString {
        appendLine("test_name,start_epoch_ms,duration_ms,tx_packets,rx_packets,unique_rx_packets,duplicate_packets,out_of_order_packets,sequence_gaps,tx_bytes,rx_bytes,avg_inter_arrival_ms,p95_inter_arrival_ms,max_inter_arrival_ms,passed,note")
        results.forEach { r ->
            appendLine(
                listOf(
                    r.testName,
                    r.startEpochMs,
                    r.durationMs,
                    r.txPackets,
                    r.rxPackets,
                    r.uniqueRxPackets,
                    r.duplicatePackets,
                    r.outOfOrderPackets,
                    r.sequenceGaps,
                    r.txBytes,
                    r.rxBytes,
                    "%.3f".format(Locale.US, r.averageInterArrivalMs),
                    "%.3f".format(Locale.US, r.p95InterArrivalMs),
                    "%.3f".format(Locale.US, r.maxInterArrivalMs),
                    r.passed,
                    quote(r.note)
                ).joinToString(",")
            )
        }
    }

    fun defaultFileName(): String = "rider-intercom-lab-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
