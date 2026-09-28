package com.bmxt.riderintercom.lab.nearby

data class NearbyPacketTrace(
    val testId: String,
    val sequence: Long,
    val senderElapsedMs: Double,
    val receiverElapsedMs: Double,
    val interArrivalMs: Double,
    val sequenceDelta: Long,
    val outOfOrder: Boolean,
    val duplicate: Boolean,
    val receiveOrder: Long
)
