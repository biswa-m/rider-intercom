package com.bmxt.riderintercom.lab.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LabCsvBuffer {
    private val rows = mutableListOf<LabTestResult>()
    private val packetTraces = mutableListOf<com.bmxt.riderintercom.lab.nearby.NearbyPacketTrace>()
    fun add(result: LabTestResult) { rows += result }
    fun addAll(results: List<LabTestResult>) { rows += results }
    @Synchronized fun addPacketTraces(traces: List<com.bmxt.riderintercom.lab.nearby.NearbyPacketTrace>) { packetTraces += traces }
    fun clear() { rows.clear(); packetTraces.clear() }
    fun snapshot(): List<LabTestResult> = rows.toList()
    fun isEmpty(): Boolean = rows.isEmpty()
    fun build(): String = LabCsvWriter.build(snapshot(), synchronized(this) { packetTraces.toList() })
    fun defaultFileName(): String = "rider-intercom-lab-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"
}
