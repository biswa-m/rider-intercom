package com.bmxt.riderintercom.lab.protocol

data class TransportPacket(
    val type: PacketType,
    val sequence: Long,
    val timestampNs: Long,
    val payload: ByteArray
)
