package com.bmxt.riderintercom.lab.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LabCsvBuffer {
    private val rows = mutableListOf<LabTestResult>()
    fun add(result: LabTestResult) { rows += result }
    fun addAll(results: List<LabTestResult>) { rows += results }
    fun clear() { rows.clear() }
    fun snapshot(): List<LabTestResult> = rows.toList()
    fun isEmpty(): Boolean = rows.isEmpty()
    fun build(): String = LabCsvWriter.build(snapshot())
    fun defaultFileName(): String = "rider-intercom-lab-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv"
}
