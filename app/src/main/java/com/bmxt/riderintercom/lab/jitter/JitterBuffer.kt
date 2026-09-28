package com.bmxt.riderintercom.lab.jitter

import com.bmxt.riderintercom.lab.protocol.TransportPacket
import java.util.TreeMap

/** Single-owner, sequence-ordered buffer. Playback code owns the next sequence number. */
class JitterBuffer(private val config: JitterBufferConfig) {
    private val packets = TreeMap<Long, TransportPacket>()
    private val depthSamples = mutableListOf<Int>()
    private var offered = 0L
    private var accepted = 0L
    private var duplicates = 0L
    private var late = 0L
    private var overflow = 0L
    private var played = 0L
    private var missing = 0L
    private var underruns = 0L
    private var maxDepth = 0
    private var resyncs = 0L

    @Synchronized
    fun offer(packet: TransportPacket, nextSequenceToPlay: Long): Boolean {
        offered++
        if (packet.sequence < nextSequenceToPlay) { late++; return false }
        if (packets.containsKey(packet.sequence)) { duplicates++; return false }
        if (packets.size >= config.maxPackets) {
            overflow++
            packets.pollFirstEntry()
        }
        packets[packet.sequence] = packet
        accepted++
        sampleDepth()
        return true
    }

    @Synchronized
    fun take(sequence: Long): TransportPacket? {
        val packet = packets.remove(sequence)
        if (packet != null) {
            played++
        } else {
            missing++
            // A missing sequence with later packets already buffered is packet loss/late
            // delivery, not a buffer underrun. Count underrun only when there is no
            // buffered audio available at all.
            if (packets.isEmpty()) underruns++
        }
        sampleDepth()
        return packet
    }

    @Synchronized
    fun has(sequence: Long): Boolean = packets.containsKey(sequence)

    @Synchronized
    fun depth(): Int = packets.size

    @Synchronized
    fun clearAndResync() {
        packets.clear()
        resyncs++
        sampleDepth()
    }

    @Synchronized
    fun snapshot(): JitterBufferStats = JitterBufferStats(
        offered, accepted, duplicates, late, overflow, played, missing, underruns,
        maxDepth, depthSamples.averageOrZero(), resyncs
    )

    private fun sampleDepth() {
        maxDepth = maxOf(maxDepth, packets.size)
        depthSamples += packets.size
    }

    private fun List<Int>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
