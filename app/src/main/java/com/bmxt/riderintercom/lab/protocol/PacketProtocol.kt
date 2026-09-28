package com.bmxt.riderintercom.lab.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketProtocol {
    private const val MAGIC = 0x52495054 // RIPT
    private const val VERSION: Byte = 1
    private const val HEADER_BYTES = 4 + 1 + 1 + 8 + 8 + 4

    fun encode(packet: TransportPacket): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + packet.payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(VERSION)
        buffer.put(packet.type.code)
        buffer.putLong(packet.sequence)
        buffer.putLong(packet.timestampNs)
        buffer.putInt(packet.payload.size)
        buffer.put(packet.payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): TransportPacket? {
        if (bytes.size < HEADER_BYTES) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC) return null
            if (buffer.get() != VERSION) return null
            val type = PacketType.fromCode(buffer.get()) ?: return null
            val sequence = buffer.long
            val timestampNs = buffer.long
            val length = buffer.int
            if (length < 0 || length != buffer.remaining()) return null
            val payload = ByteArray(length)
            buffer.get(payload)
            TransportPacket(type, sequence, timestampNs, payload)
        }.getOrNull()
    }
}
