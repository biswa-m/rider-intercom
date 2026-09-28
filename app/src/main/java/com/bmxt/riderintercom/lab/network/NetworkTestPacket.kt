package com.bmxt.riderintercom.lab.network

import com.bmxt.riderintercom.lab.core.LabConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetworkTestPacket {
    private const val MAGIC = 0x52494C31 // RIL1
    private const val VERSION: Byte = 1
    private const val TYPE_DUMMY: Byte = 1
    private const val HEADER_BYTES = 4 + 1 + 1 + 8 + 8 + 8

    data class Packet(
        val sessionId: Long,
        val sequence: Long,
        val senderElapsedNanos: Long,
        val payloadBytes: Int
    )

    fun encode(sessionId: Long, sequence: Long, senderElapsedNanos: Long): ByteArray {
        val bytes = ByteArray(HEADER_BYTES + LabConstants.PACKET_PAYLOAD_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION)
        buffer.put(TYPE_DUMMY)
        buffer.putLong(sessionId)
        buffer.putLong(sequence)
        buffer.putLong(senderElapsedNanos)
        while (buffer.hasRemaining()) buffer.put(0x5A.toByte())
        return bytes
    }

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) return null
        if (buffer.get() != VERSION || buffer.get() != TYPE_DUMMY) return null
        return Packet(
            sessionId = buffer.long,
            sequence = buffer.long,
            senderElapsedNanos = buffer.long,
            payloadBytes = bytes.size - HEADER_BYTES
        )
    }
}
