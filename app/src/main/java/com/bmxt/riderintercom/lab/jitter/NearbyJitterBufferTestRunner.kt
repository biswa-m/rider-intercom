package com.bmxt.riderintercom.lab.jitter

import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.nearby.NearbyConnectionManager
import com.bmxt.riderintercom.lab.protocol.PacketProtocol
import com.bmxt.riderintercom.lab.protocol.PacketType
import com.bmxt.riderintercom.lab.protocol.TransportPacket
import com.bmxt.riderintercom.lab.transport.NearbyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Real two-phone jitter test.
 *
 * Sender: PREPARE -> START -> sends 500 packets at 20 ms intervals.
 * Receiver: feeds packets into JitterBuffer immediately and runs a separate
 * deterministic 20 ms playback scheduler after a 40 ms startup buffer.
 */
class NearbyJitterBufferTestRunner {
    companion object {
        private const val PACKET_COUNT = 500
        private const val FRAME_MS = 20L
        private const val TARGET_MS = 40L
        private const val MAX_MS = 200L
        private const val PAYLOAD_SIZE = 80
    }

    private var job: Job? = null
    private var receiverJob: Job? = null
    private var receiverManager: NearbyConnectionManager? = null
    private var receiverTransport: NearbyTransport? = null
    private var receiverTestId: String? = null
    private var receiverBuffer: JitterBuffer? = null
    private var receiverNextSequence = 0L
    private var receiverPlayedTicks = 0
    private var receiverPacketCount = PACKET_COUNT
    private var receiverStartedAtMs = 0L
    private var receiverDepthSamples = 0
    private var receiverDepthTotal = 0L
    private var receiverMaxDepth = 0

    fun attachReceiver(manager: NearbyConnectionManager) {
        if (receiverManager === manager) return
        detachReceiver()
        receiverManager = manager
        manager.addTestControlListener(this) { message -> handleReceiverControl(manager, message) }
        receiverTransport = NearbyTransport(manager).also { transport ->
            transport.setReceiver { bytes ->
                val packet = PacketProtocol.decode(bytes)?.takeIf { it.type == PacketType.TEST } ?: return@setReceiver
                val id = receiverTestId ?: return@setReceiver
                receiverBuffer?.offer(packet, receiverNextSequence)
                val depth = receiverBuffer?.depth() ?: 0
                receiverDepthTotal += depth
                receiverDepthSamples++
                receiverMaxDepth = max(receiverMaxDepth, depth)
            }
        }
    }

    private fun handleReceiverControl(manager: NearbyConnectionManager, message: String) {
        val parts = message.split('|')
        when (parts.firstOrNull()) {
            "JITTER_PREPARE" -> {
                val id = parts.getOrNull(1) ?: return
                receiverTestId = id
                receiverPacketCount = parts.getOrNull(2)?.toIntOrNull() ?: PACKET_COUNT
                receiverBuffer = JitterBuffer(JitterBufferConfig(FRAME_MS, TARGET_MS, MAX_MS))
                receiverNextSequence = 0L
                receiverPlayedTicks = 0
                receiverStartedAtMs = 0L
                receiverDepthSamples = 0
                receiverDepthTotal = 0L
                receiverMaxDepth = 0
                receiverJob?.cancel()
                manager.sendTestControl("JITTER_READY|$id")
            }
            "JITTER_START" -> {
                if (parts.getOrNull(1) != receiverTestId) return
                val id = receiverTestId ?: return
                val startDelayMs = parts.getOrNull(2)?.toLongOrNull() ?: TARGET_MS
                // startDelayMs synchronizes the sender/receiver start. Playback itself should
                // begin only after the configured target buffer has had time to accumulate.
                // Starting playback after the full synchronization delay would allow roughly
                // 500 ms of packets to arrive before playback, overflowing the 200 ms buffer.
                val playbackDelayMs = startDelayMs + TARGET_MS
                receiverStartedAtMs = System.currentTimeMillis() + playbackDelayMs
                receiverJob?.cancel()
                receiverJob = CoroutineScope(Dispatchers.Default).launch {
                    delay(playbackDelayMs)
                    while (receiverPlayedTicks < receiverPacketCount) {
                        receiverBuffer?.take(receiverNextSequence)
                        receiverNextSequence++
                        receiverPlayedTicks++
                        if (receiverPlayedTicks < receiverPacketCount) {
                            delay(FRAME_MS)
                        }
                    }
                    // Tell the sender that the complete deterministic playback schedule
                    // has finished. The sender must not request statistics early, because
                    // doing so cancels the playback job before all 500 ticks are consumed.
                    manager.sendTestControl("JITTER_DONE|$id")
                }
                manager.sendTestControl("JITTER_STARTED|$id")
            }
            "JITTER_STOP" -> {
                if (parts.getOrNull(1) != receiverTestId) return
                receiverJob?.cancel()
                receiverJob = null
                val st = receiverBuffer?.snapshot() ?: JitterBufferStats()
                val avgDepth = if (receiverDepthSamples == 0) 0.0 else receiverDepthTotal.toDouble() / receiverDepthSamples
                val id = receiverTestId ?: return
                manager.sendTestControl(
                    "JITTER_RESULT|$id|${st.offered}|${st.accepted}|${st.duplicates}|${st.late}|${st.overflow}|${st.played}|${st.missing}|${st.underruns}|$receiverMaxDepth|$avgDepth"
                )
                receiverTestId = null
            }
        }
    }

    fun run(manager: NearbyConnectionManager, onUpdate: (String) -> Unit, onComplete: (LabTestResult) -> Unit) {
        cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            onComplete(execute(manager, onUpdate))
        }
    }

    private suspend fun execute(manager: NearbyConnectionManager, onUpdate: (String) -> Unit): LabTestResult {
        val started = System.currentTimeMillis()
        val transport = NearbyTransport(manager)
        val testId = "J${System.currentTimeMillis()}"
        val readySignal = manager.prepareTestSignal("JITTER_READY|$testId")
        val startedSignal = manager.prepareTestSignal("JITTER_STARTED|$testId")
        val resultSignal = manager.prepareTestSignal("JITTER_RESULT|$testId")
        return try {
            if (!transport.isConnected()) {
                return result(started, false, 0, 0, "No Nearby connection")
            }

            onUpdate("Nearby jitter: preparing receiver…")
            if (!manager.sendTestControl("JITTER_PREPARE|$testId|$PACKET_COUNT|$FRAME_MS|$TARGET_MS|$MAX_MS")) {
                return result(started, false, 0, 0, "Failed to send JITTER_PREPARE")
            }
            if (withTimeoutOrNull(3_000L) { readySignal.await() } == null) {
                return result(started, false, 0, 0, "Receiver did not acknowledge JITTER_PREPARE")
            }

            val startDelayMs = 500L
            onUpdate("Nearby jitter: starting receiver, then sending 500 × 20ms packets…")
            manager.sendTestControl("JITTER_START|$testId|$startDelayMs")
            withTimeoutOrNull(2_000L) { startedSignal.await() }

            val sendStartNs = System.nanoTime() + startDelayMs * 1_000_000L
            var nextSendNs = sendStartNs
            repeat(PACKET_COUNT) { seq ->
                val sleepNs = nextSendNs - System.nanoTime()
                if (sleepNs > 0) delay((sleepNs / 1_000_000L).coerceAtLeast(1L))
                val packet = TransportPacket(PacketType.TEST, seq.toLong(), System.nanoTime(), ByteArray(PAYLOAD_SIZE))
                transport.send(PacketProtocol.encode(packet))
                nextSendNs += FRAME_MS * 1_000_000L
            }

            // Wait for the receiver's deterministic 20 ms playback scheduler to finish
            // all packets. Do not use a short fixed delay here: the complete playback
            // schedule spans roughly PACKET_COUNT * FRAME_MS.
            val doneSignal = manager.prepareTestSignal("JITTER_DONE|$testId")
            if (withTimeoutOrNull((PACKET_COUNT * FRAME_MS) + TARGET_MS + 2_000L) { doneSignal.await() } == null) {
                return result(started, false, PACKET_COUNT, 0, "Receiver did not finish JITTER playback")
            }
            onUpdate("Nearby jitter: collecting receiver statistics…")
            manager.sendTestControl("JITTER_STOP|$testId")
            val resultMessage = withTimeoutOrNull(3_000L) { resultSignal.await() }
            if (resultMessage == null) {
                return result(started, false, PACKET_COUNT, 0, "Receiver did not return JITTER_RESULT")
            }
            parseResult(started, resultMessage, onUpdate)
        } finally {
            transport.close()
        }
    }

    private fun parseResult(started: Long, message: String, onUpdate: (String) -> Unit): LabTestResult {
        val p = message.split('|')
        val accepted = p.getOrNull(3)?.toLongOrNull() ?: 0L
        val duplicates = p.getOrNull(4)?.toLongOrNull() ?: 0L
        val late = p.getOrNull(5)?.toLongOrNull() ?: 0L
        val overflow = p.getOrNull(6)?.toLongOrNull() ?: 0L
        val played = p.getOrNull(7)?.toLongOrNull() ?: 0L
        val missing = p.getOrNull(8)?.toLongOrNull() ?: 0L
        val underruns = p.getOrNull(9)?.toLongOrNull() ?: 0L
        val maxDepth = p.getOrNull(10)?.toIntOrNull() ?: 0
        val avgDepth = p.getOrNull(11)?.toDoubleOrNull() ?: 0.0
        val passed = accepted == PACKET_COUNT.toLong() && played == PACKET_COUNT.toLong() && missing == 0L && duplicates == 0L && overflow == 0L
        val note = "frame=20ms target=40ms max=200ms sent=$PACKET_COUNT accepted=$accepted played=$played missing=$missing duplicates=$duplicates late=$late overflow=$overflow underruns=$underruns avgDepth=${"%.2f".format(avgDepth)} maxDepth=$maxDepth"
        onUpdate("Nearby jitter: played=$played/$PACKET_COUNT missing=$missing maxDepth=$maxDepth")
        return LabTestResult("NEARBY_JITTER_REALTIME_20MS_TARGET40MS_500", started, System.currentTimeMillis() - started, PACKET_COUNT.toLong(), accepted, accepted, duplicates, late, missing, PACKET_COUNT.toLong() * PAYLOAD_SIZE, accepted * PAYLOAD_SIZE, 20.0, 20.0, 20.0, passed, note)
    }

    private fun result(started: Long, passed: Boolean, sent: Int, received: Int, note: String): LabTestResult =
        LabTestResult("NEARBY_JITTER_REALTIME_20MS_TARGET40MS_500", started, System.currentTimeMillis() - started, sent.toLong(), received.toLong(), received.toLong(), 0, 0, 0, sent.toLong() * PAYLOAD_SIZE, received.toLong() * PAYLOAD_SIZE, 20.0, 20.0, 20.0, passed, note)

    fun cancel() {
        job?.cancel()
        job = null
        receiverJob?.cancel()
        receiverJob = null
    }

    fun detachReceiver() {
        receiverJob?.cancel()
        receiverJob = null
        receiverTransport?.close()
        receiverTransport = null
        receiverManager?.removeTestControlListener(this)
        receiverManager = null
        receiverTestId = null
    }
}
