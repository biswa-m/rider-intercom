package com.bmxt.riderintercom.intercom.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OpusVoicePacket(
    val sequence: Int,
    val sentAtMs: Long,
    val sampleCount: Int,
    val payload: ByteArray
) {
    companion object {
        private const val MAGIC = 0x5249
        private const val VERSION: Byte = 3
        private const val TYPE_OPUS: Byte = 1
        private const val HEADER_SIZE = 20

        fun encode(sequence: Int, sentAtMs: Long, sampleCount: Int, payload: ByteArray): ByteArray {
            require(sampleCount > 0)
            require(payload.isNotEmpty())
            require(payload.size <= 0xFFFF)

            return ByteBuffer
                .allocate(HEADER_SIZE + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    putShort(MAGIC.toShort())
                    put(VERSION)
                    put(TYPE_OPUS)
                    putInt(sequence)
                    putLong(sentAtMs)
                    putShort(sampleCount.toShort())
                    putShort(payload.size.toShort())
                    put(payload)
                }
                .array()
        }

        fun decode(data: ByteArray, length: Int): OpusVoicePacket? {
            if (length < HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            if ((buffer.short.toInt() and 0xFFFF) != MAGIC) return null
            if (buffer.get() != VERSION) return null
            if (buffer.get() != TYPE_OPUS) return null

            val sequence = buffer.int
            val sentAtMs = buffer.long
            val sampleCount = buffer.short.toInt() and 0xFFFF
            val payloadBytes = buffer.short.toInt() and 0xFFFF

            if (sampleCount <= 0 || payloadBytes <= 0) return null
            if (HEADER_SIZE + payloadBytes > length) return null

            val payload = ByteArray(payloadBytes)
            buffer.get(payload)
            return OpusVoicePacket(sequence, sentAtMs, sampleCount, payload)
        }
    }
}
