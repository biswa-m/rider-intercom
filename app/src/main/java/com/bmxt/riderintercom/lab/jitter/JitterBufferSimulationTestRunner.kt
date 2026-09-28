package com.bmxt.riderintercom.lab.jitter

import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.protocol.PacketType
import com.bmxt.riderintercom.lab.protocol.TransportPacket
import kotlin.random.Random

/**
 * Deterministic, single-threaded model of real jitter-buffer operation.
 * Packets arrive according to an arrival timeline while a separate 20 ms
 * playback clock consumes exactly one sequence number per tick.
 */
class JitterBufferSimulationTestRunner {
    data class Scenario(
        val name: String,
        val packetCount: Int,
        val jitterMs: Long,
        val loss: Set<Long> = emptySet(),
        val reorder: Boolean = false,
        val duplicate: Boolean = false
    )

    fun runAll(onUpdate: (String) -> Unit): List<LabTestResult> {
        val scenarios = listOf(
            Scenario("JITTER_SIM_NORMAL", 500, 0),
            Scenario("JITTER_SIM_RANDOM_15MS", 500, 15),
            Scenario("JITTER_SIM_REORDER", 500, 10, reorder = true),
            Scenario("JITTER_SIM_2PCT_LOSS", 500, 10, loss = (0L until 500L).filter { it % 50L == 17L }.toSet()),
            Scenario("JITTER_SIM_BURST_LOSS", 500, 10, loss = (200L..207L).toSet()),
            Scenario("JITTER_SIM_DUPLICATE", 500, 10, duplicate = true)
        )
        val results = scenarios.map { scenario ->
            onUpdate("Running ${scenario.name}…")
            runScenario(scenario)
        }
        onUpdate("Jitter simulation complete: ${results.count { it.passed }}/${results.size} PASS")
        return results
    }

    private fun runScenario(s: Scenario): LabTestResult {
        val started = System.currentTimeMillis()
        val config = JitterBufferConfig(20, 40, 200)
        val buffer = JitterBuffer(config)
        val packets = (0 until s.packetCount).map { seq ->
            TransportPacket(PacketType.TEST, seq.toLong(), seq * 20L * 1_000_000L, ByteArray(80))
        }.filterNot { it.sequence in s.loss }

        // Network arrival timeline. Base transport delay is 10 ms. Jitter is
        // bounded and deterministic so every run produces comparable results.
        val arrivals = packets.map { packet ->
            val jitter = if (s.jitterMs == 0L) 0L
            else Random(packet.sequence.toInt() + 7).nextLong(-s.jitterMs, s.jitterMs + 1)
            packet to (packet.sequence * 20L + 10L + jitter).coerceAtLeast(0L)
        }.toMutableList()

        if (s.reorder) {
            // Delay selected packets enough to arrive after the next packet,
            // but keep the delay below the 40 ms target in most cases.
            arrivals.forEachIndexed { index, pair ->
                if (pair.first.sequence % 17L == 0L) {
                    // Keep the reordered packet inside the 40 ms target window so this
                    // scenario tests reordering rather than intentionally creating late loss.
                    arrivals[index] = pair.first to (pair.second + 10L)
                }
            }
        }
        arrivals.sortBy { it.second }

        val arrivalEvents = arrivals.toMutableList()
        if (s.duplicate) {
            packets.filter { it.sequence % 73L == 0L }.forEach { packet ->
                val arrival = arrivals.firstOrNull { it.first.sequence == packet.sequence }?.second ?: return@forEach
                arrivalEvents += packet to (arrival + 1L)
            }
            arrivalEvents.sortBy { it.second }
        }

        val playbackStartMs = config.targetBufferMs.toLong()
        val totalPlaybackTicks = s.packetCount
        var arrivalIndex = 0
        var nextSequence = 0L
        var playedTicks = 0

        // This is the key model: receive and playback advance independently.
        while (playedTicks < totalPlaybackTicks) {
            val playTimeMs = playbackStartMs + playedTicks * config.frameDurationMs
            while (arrivalIndex < arrivalEvents.size && arrivalEvents[arrivalIndex].second <= playTimeMs) {
                buffer.offer(arrivalEvents[arrivalIndex].first, nextSequence)
                arrivalIndex++
            }
            buffer.take(nextSequence)
            nextSequence++
            playedTicks++
        }

        // Anything arriving after the final playback tick is genuinely late.
        while (arrivalIndex < arrivalEvents.size) {
            buffer.offer(arrivalEvents[arrivalIndex].first, nextSequence)
            arrivalIndex++
        }

        val st = buffer.snapshot()
        val expectedLoss = s.loss.size.toLong()
        val expected = s.packetCount.toLong()
        val note = "scenario=${s.name} target=${config.targetBufferMs}ms max=${config.maxBufferMs}ms offered=${st.offered} accepted=${st.accepted} played=${st.played} missing=${st.missing} duplicates=${st.duplicates} late=${st.late} overflow=${st.overflow} underruns=${st.underruns} avgDepth=${"%.2f".format(st.averageDepth)} maxDepth=${st.maxDepth}"

        val passed = when {
            s.loss.isEmpty() && !s.reorder && !s.duplicate ->
                st.played == expected && st.missing == 0L && st.underruns == 0L && st.overflow == 0L
            s.duplicate && s.loss.isEmpty() ->
                st.played == expected && st.missing == 0L && st.duplicates > 0L && st.overflow == 0L
            else ->
                st.played == expected - expectedLoss &&
                    st.overflow == 0L &&
                    st.missing == expectedLoss &&
                    if (s.name == "JITTER_SIM_BURST_LOSS") {
                        // Eight consecutive 20 ms losses = 160 ms, which is larger than
                        // the 40 ms target buffer. Underruns are therefore expected; the
                        // test is checking that the buffer reports them without overflow
                        // or sequence corruption.
                        st.underruns > 0L
                    } else {
                        st.underruns == 0L
                    }
        }

        return LabTestResult(
            s.name,
            started,
            System.currentTimeMillis() - started,
            expected,
            st.accepted,
            st.accepted,
            st.duplicates,
            st.late,
            st.missing,
            expected * 80,
            st.accepted * 80,
            20.0,
            20.0,
            20.0,
            passed,
            note
        )
    }
}
