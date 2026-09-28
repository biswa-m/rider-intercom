package com.bmxt.riderintercom.lab.core

/**
 * Shared in-memory buffer for every lab test run.
 * The buffer is intentionally process-local and is cleared by the Reset action.
 */
class LabCsvBuffer {
    private val entries = mutableListOf<LabTestResult>()

    @Synchronized
    fun add(result: LabTestResult) {
        entries += result
    }

    @Synchronized
    fun addAll(results: Collection<LabTestResult>) {
        entries += results
    }

    @Synchronized
    fun snapshot(): List<LabTestResult> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun size(): Int = entries.size
}
