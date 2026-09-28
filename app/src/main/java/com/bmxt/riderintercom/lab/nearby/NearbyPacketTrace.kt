package com.bmxt.riderintercom.lab.nearby

data class NearbyPacketTrace(
    val testId: String,
    val sequence: Long,
    val senderElapsedMs: Double,
    val callbackElapsedMs: Double,
    val receiverElapsedMs: Double,
    val callbackInterArrivalMs: Double,
    val interArrivalMs: Double,
    val callbackProcessingMs: Double,
    val sequenceDelta: Long,
    val outOfOrder: Boolean,
    val duplicate: Boolean,
    val receiveOrder: Long
)
