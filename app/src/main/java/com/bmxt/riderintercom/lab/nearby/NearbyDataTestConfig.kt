package com.bmxt.riderintercom.lab.nearby

data class NearbyDataTestConfig(
    val name: String,
    val payloadSize: Int,
    val packetsPerSecond: Int,
    val durationMs: Long = 10_000L,
    val startDelayMs: Long = 1_500L
)
