package com.bmxt.riderintercom.intercom.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small UDP packet format for the first two-phone networking test.
 *
 * This milestone intentionally sends uncompressed 16 kHz mono PCM.
 * It is only a transport validation step; Opus replaces this payload later.
 */
data class PcmVoicePacket(
    val sequence: Int,
    val sentAtMs: Long,
    val samples: ShortArray
) {
    companion object {
        private const val MAGIC = 0x5249 // "RI"
        private const val VERSION: Byte = 2
        private const val HEADER_SIZE = 20

        fun encode(sequence: Int, sentAtMs: Long, samples: ShortArray): ByteArray {
            val payloadBytes = samples.size * 2
            val buffer = ByteBuffer
                .allocate(HEADER_SIZE + payloadBytes)
                .order(ByteOrder.BIG_ENDIAN)

            buffer.putShort(MAGIC.toShort())
            buffer.put(VERSION)
            buffer.put(0) // packet type: PCM voice
            buffer.putInt(sequence)
            buffer.putLong(sentAtMs)
            buffer.putShort(samples.size.toShort())
            buffer.putShort(payloadBytes.toShort())

            for (sample in samples) {
                buffer.putShort(sample)
            }

            return buffer.array()
        }

        fun decode(data: ByteArray, length: Int): PcmVoicePacket? {
            if (length < HEADER_SIZE) return null

            val buffer = ByteBuffer
                .wrap(data, 0, length)
                .order(ByteOrder.BIG_ENDIAN)

            if ((buffer.short.toInt() and 0xFFFF) != MAGIC) return null
            if (buffer.get() != VERSION) return null

            buffer.get() // packet type
            val sequence = buffer.int
            val sentAtMs = buffer.long
            val sampleCount = buffer.short.toInt() and 0xFFFF
            val payloadBytes = buffer.short.toInt() and 0xFFFF

            if (sampleCount <= 0 || payloadBytes != sampleCount * 2) {
                return null
            }

            if (HEADER_SIZE + payloadBytes > length) {
                return null
            }

            val samples = ShortArray(sampleCount)
            for (i in samples.indices) {
                samples[i] = buffer.short
            }

            return PcmVoicePacket(sequence, sentAtMs, samples)
        }
    }
}
