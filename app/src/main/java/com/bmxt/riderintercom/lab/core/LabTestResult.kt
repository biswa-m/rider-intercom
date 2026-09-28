package com.bmxt.riderintercom.lab.core

data class LabTestResult(
    val testName: String,
    val startEpochMs: Long,
    val durationMs: Long,
    val txPackets: Long,
    val rxPackets: Long,
    val uniqueRxPackets: Long,
    val duplicatePackets: Long,
    val outOfOrderPackets: Long,
    val sequenceGaps: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val averageInterArrivalMs: Double,
    val p95InterArrivalMs: Double,
    val maxInterArrivalMs: Double,
    val passed: Boolean,
    val note: String
)
