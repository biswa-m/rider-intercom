package com.bmxt.riderintercom.lab.nearby

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NearbyPacketTraceCsvWriter {
    private val header = listOf(
        "row_type",
        "test_name",
        "receive_order",
        "sequence",
        "sender_elapsed_ms",
        "receiver_elapsed_ms",
        "inter_arrival_ms",
        "sequence_delta",
        "packet_out_of_order",
        "packet_duplicate"
    )

    fun writeToAppStorage(context: Context, traces: List<NearbyPacketTrace>, testId: String): File? {
        return runCatching {
            val directory = File(context.filesDir, "packet-traces").apply { mkdirs() }
            val safeId = testId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(directory, "${safeId}.csv")
            file.writeText(build(traces))
            file
        }.getOrNull()
    }

    fun build(traces: List<NearbyPacketTrace>): String = buildString {
        appendLine(header.joinToString(","))
        traces.forEach { trace ->
            appendLine(
                listOf(
                    "PACKET_TRACE",
                    quote(trace.testId),
                    trace.receiveOrder.toString(),
                    trace.sequence.toString(),
                    "%.3f".format(Locale.US, trace.senderElapsedMs),
                    "%.3f".format(Locale.US, trace.receiverElapsedMs),
                    "%.3f".format(Locale.US, trace.interArrivalMs),
                    trace.sequenceDelta.toString(),
                    trace.outOfOrder.toString(),
                    trace.duplicate.toString()
                ).joinToString(",")
            )
        }
    }

    fun defaultFileName(): String =
        "rider-intercom-receiver-packet-trace-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
