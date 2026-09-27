package com.bmxt.riderintercom.intercom.audio

import java.util.TreeMap

/**
 * Small sequence-aware jitter buffer for the one-peer Opus stream.
 *
 * The sender only emits frames while VAD detects speech, so a large sequence
 * jump is normally an intentional VAD silence gap rather than packet loss.
 * Small gaps are treated as probable UDP loss after a short wait for reordering.
 */
class OpusJitterBuffer(
    private val targetPackets: Int = 2,
    private val maxPackets: Int = 6,
    private val gapWaitMs: Long = 20L,
    private val largeGapThreshold: Int = 4,
) {
    private val lock = Any()
    private val packets = TreeMap<Int, OpusVoicePacket>()

    private var started = false
    private var expectedSequence = -1
    private var gapStartedAtMs = 0L

    private var lostPackets = 0L
    private var latePackets = 0L
    private var overflowDroppedPackets = 0L
    private var resyncCount = 0L

    sealed interface PollResult {
        data class Packet(val value: OpusVoicePacket) : PollResult
        data object Wait : PollResult
        data object MissingFrame : PollResult
    }

    data class Snapshot(
        val bufferedPackets: Int,
        val started: Boolean,
        val lostPackets: Long,
        val latePackets: Long,
        val overflowDroppedPackets: Long,
        val resyncCount: Long,
    )

    fun reset() {
        synchronized(lock) {
            packets.clear()
            started = false
            expectedSequence = -1
            gapStartedAtMs = 0L
            lostPackets = 0L
            latePackets = 0L
            overflowDroppedPackets = 0L
            resyncCount = 0L
        }
    }

    fun offer(packet: OpusVoicePacket): Boolean {
        synchronized(lock) {
            if (started) {
                val distance = sequenceDistance(expectedSequence, packet.sequence)
                if (distance < 0) {
                    latePackets++
                    return false
                }
            }

            if (packets.containsKey(packet.sequence)) {
                latePackets++
                return false
            }

            packets[packet.sequence] = packet

            while (packets.size > maxPackets) {
                packets.pollFirstEntry()
                overflowDroppedPackets++
            }

            return true
        }
    }

    /** Starts playback once enough packets are buffered to absorb small jitter. */
    fun startIfReady(): Boolean {
        synchronized(lock) {
            if (started) return true
            if (packets.size < targetPackets) return false

            expectedSequence = packets.firstKey()
            started = true
            gapStartedAtMs = 0L
            return true
        }
    }

    /**
     * Returns a packet when it can be played now, Wait while allowing a small
     * reordering window, or MissingFrame when a probable UDP loss has timed out.
     */
    fun pollNext(nowMs: Long = System.currentTimeMillis()): PollResult {
        synchronized(lock) {
            if (!started) return PollResult.Wait

            packets[expectedSequence]?.let { packet ->
                packets.remove(expectedSequence)
                expectedSequence++
                gapStartedAtMs = 0L
                return PollResult.Packet(packet)
            }

            while (true) {
                val first = packets.firstEntry() ?: return PollResult.Wait
                val distance = sequenceDistance(expectedSequence, first.key)

                if (distance < 0) {
                    packets.pollFirstEntry()
                    latePackets++
                    continue
                }

                // A large jump is usually an intentional VAD silence period.
                // Resynchronize immediately instead of simulating hundreds of losses.
                if (distance > largeGapThreshold) {
                    expectedSequence = first.key + 1
                    gapStartedAtMs = 0L
                    resyncCount++
                    return PollResult.Packet(packets.pollFirstEntry()!!.value)
                }

                if (gapStartedAtMs == 0L) {
                    gapStartedAtMs = nowMs
                    return PollResult.Wait
                }

                if (nowMs - gapStartedAtMs < gapWaitMs) {
                    return PollResult.Wait
                }

                // Small missing sequence number: count it as lost. The caller
                // should output one 20 ms silent frame to preserve timing.
                expectedSequence++
                lostPackets++
                gapStartedAtMs = 0L
                return PollResult.MissingFrame
            }
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { packets.isEmpty() }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            bufferedPackets = packets.size,
            started = started,
            lostPackets = lostPackets,
            latePackets = latePackets,
            overflowDroppedPackets = overflowDroppedPackets,
            resyncCount = resyncCount,
        )
    }

    private fun sequenceDistance(from: Int, to: Int): Int = to - from
}
