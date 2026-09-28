package com.bmxt.riderintercom.intercom.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Standalone UDP clock/RTT diagnostic. It never touches the voice/audio pipeline.
 * Both phones must start the test. Each phone completes after 30 successful
 * ping/pong samples of its own.
 */
class PingPongTestManager {
    companion object {
        const val DEFAULT_PORT = 45_001
        const val REQUIRED_SAMPLES = 30
        private const val HELLO_INTERVAL_MS = 200L
        private const val PING_INTERVAL_MS = 80L
        private const val SAMPLE_TIMEOUT_MS = 1_500L
        private const val MAGIC = 0x5250 // "RP"
        private const val VERSION: Byte = 1
        private const val TYPE_HELLO: Byte = 1
        private const val TYPE_PING: Byte = 2
        private const val TYPE_PONG: Byte = 3
        private const val TYPE_DONE: Byte = 4
    }

    data class State(
        val running: Boolean = false,
        val status: String = "Not started",
        val localSamples: Int = 0,
        val peerSamples: Int = 0,
        val clockOffsetMs: Long? = null,
        val offsetJitterMs: Long? = null,
        val rttMinMs: Long? = null,
        val rttAvgMs: Long? = null,
        val rttP95Ms: Long? = null,
        val rttMaxMs: Long? = null,
        val error: String? = null
    )

    private data class PendingPing(val id: Int, val sentAtMs: Long)

    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var peer: InetSocketAddress? = null
    private var sessionId: Long = 0L
    private var sequence = 0
    private var pending: PendingPing? = null
    private val offsets = ArrayDeque<Long>(REQUIRED_SAMPLES)
    private val rtts = ArrayDeque<Long>(REQUIRED_SAMPLES)
    private var peerSamples = 0
    private var completed = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(scope: CoroutineScope, peerHost: String): Boolean {
        stopInternal(emit = false)
        if (peerHost.isBlank()) {
            update(State(error = "Peer IP is required", status = "Not started"))
            return false
        }

        val address = try {
            InetSocketAddress(peerHost.trim(), DEFAULT_PORT)
        } catch (e: Exception) {
            update(State(error = "Invalid peer IP: ${e.message ?: "invalid address"}"))
            return false
        }

        val newSocket = try {
            DatagramSocket(DEFAULT_PORT).apply { soTimeout = 250 }
        } catch (e: Exception) {
            update(State(error = "Cannot open diagnostic UDP port $DEFAULT_PORT: ${e.message}"))
            return false
        }

        socket = newSocket
        peer = address
        sessionId = UUID.randomUUID().mostSignificantBits
        sequence = 0
        pending = null
        offsets.clear()
        rtts.clear()
        peerSamples = 0
        completed = false
        update(State(running = true, status = "Waiting for both phones..."))

        job = scope.launch(Dispatchers.IO) {
            try {
                runTest(newSocket, address)
            } catch (e: Exception) {
                if (isActive && !completed) {
                    update(State(running = false, status = "Test failed", error = e.message ?: "Unknown error"))
                }
            } finally {
                if (socket === newSocket) {
                    try { newSocket.close() } catch (_: Exception) {}
                    socket = null
                }
            }
        }
        return true
    }

    fun stop() {
        stopInternal(emit = true)
    }

    fun close() {
        stopInternal(emit = false)
    }

    private suspend fun runTest(socket: DatagramSocket, peer: InetSocketAddress) {
        val helloJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !completed) {
                send(socket, peer, TYPE_HELLO, sequence = 0, t1 = System.currentTimeMillis(), t2 = 0L, t3 = 0L)
                delay(HELLO_INTERVAL_MS)
            }
        }

        var peerReady = false
        var lastPingAt = 0L
        var peerDone = false

        try {
            val buffer = ByteArray(128)
            while (isActive && !completed) {
                val now = System.currentTimeMillis()
                if (_state.value.localSamples >= REQUIRED_SAMPLES) {
                    if (!completed) {
                        completed = true
                        send(socket, peer, TYPE_DONE, 0, now, 0L, 0L)
                        update(finalState("Test complete"))
                    }
                    break
                }

                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val receivedAt = System.currentTimeMillis()
                    val decoded = decode(packet.data, packet.length) ?: continue
                    if (decoded.type == TYPE_PONG && decoded.sessionId != sessionId) continue

                    when (decoded.type) {
                        TYPE_HELLO -> {
                            peerReady = true
                            if (_state.value.status != "Running ping-pong...") {
                                update(_state.value.copy(status = "Both phones ready — measuring..."))
                            }
                        }
                        TYPE_PING -> {
                            // Respond using the same session id so the initiator can calculate NTP offset.
                            val t2 = receivedAt
                            val t3 = System.currentTimeMillis()
                            send(socket, InetSocketAddress(packet.address, packet.port), TYPE_PONG, decoded.sequence, decoded.t1, t2, t3, decoded.sessionId)
                            peerSamples++
                            publishProgress(peerReady)
                        }
                        TYPE_PONG -> {
                            val p = pending
                            if (p != null && decoded.sequence == p.id) {
                                val t4 = receivedAt
                                val t1 = decoded.t1
                                val t2 = decoded.t2
                                val t3 = decoded.t3
                                val rtt = ((t4 - t1) - (t3 - t2)).coerceAtLeast(0L)
                                val offset = ((t2 - t1) + (t3 - t4)) / 2L
                                if (rtts.size >= REQUIRED_SAMPLES) rtts.removeFirst()
                                if (offsets.size >= REQUIRED_SAMPLES) offsets.removeFirst()
                                rtts.addLast(rtt)
                                offsets.addLast(offset)
                                pending = null
                                publishProgress(peerReady)
                            }
                        }
                        TYPE_DONE -> {
                            peerDone = true
                            publishProgress(peerReady)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Used only to periodically send HELLO/PING without blocking forever.
                }

                if (peerReady && pending == null && _state.value.localSamples < REQUIRED_SAMPLES) {
                    if (System.currentTimeMillis() - lastPingAt >= PING_INTERVAL_MS) {
                        val id = ++sequence
                        val t1 = System.currentTimeMillis()
                        pending = PendingPing(id, t1)
                        send(socket, peer, TYPE_PING, id, t1, 0L, 0L)
                        lastPingAt = t1
                    }
                } else if (pending != null && System.currentTimeMillis() - pending!!.sentAtMs > SAMPLE_TIMEOUT_MS) {
                    pending = null
                }
            }
        } finally {
            helloJob.cancel()
        }
    }

    private fun publishProgress(peerReady: Boolean) {
        val local = offsets.size
        val status = when {
            local >= REQUIRED_SAMPLES -> "Test complete"
            peerReady -> "Running ping-pong..."
            else -> "Waiting for both phones..."
        }
        update(
            State(
                running = local < REQUIRED_SAMPLES,
                status = status,
                localSamples = local,
                peerSamples = peerSamples,
                clockOffsetMs = median(offsets),
                offsetJitterMs = jitter(offsets),
                rttMinMs = rtts.minOrNull(),
                rttAvgMs = if (rtts.isNotEmpty()) rtts.average().toLong() else null,
                rttP95Ms = percentile(rtts, 0.95),
                rttMaxMs = rtts.maxOrNull()
            )
        )
    }

    private fun finalState(status: String): State = State(
        running = false,
        status = status,
        localSamples = offsets.size,
        peerSamples = peerSamples,
        clockOffsetMs = median(offsets),
        offsetJitterMs = jitter(offsets),
        rttMinMs = rtts.minOrNull(),
        rttAvgMs = if (rtts.isNotEmpty()) rtts.average().toLong() else null,
        rttP95Ms = percentile(rtts, 0.95),
        rttMaxMs = rtts.maxOrNull()
    )

    private fun send(
        socket: DatagramSocket,
        address: InetSocketAddress,
        type: Byte,
        sequence: Int,
        t1: Long,
        t2: Long,
        t3: Long,
        packetSessionId: Long = sessionId
    ) {
        val data = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(MAGIC.toShort())
            put(VERSION)
            put(type)
            putLong(packetSessionId)
            putInt(sequence)
            putLong(t1)
            putLong(t2)
            putLong(t3)
        }.array()
        socket.send(DatagramPacket(data, data.size, address))
    }

    private data class Packet(
        val type: Byte,
        val sessionId: Long,
        val sequence: Int,
        val t1: Long,
        val t2: Long,
        val t3: Long
    )

    private fun decode(data: ByteArray, length: Int): Packet? {
        if (length < 36) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if ((b.short.toInt() and 0xFFFF) != MAGIC) return null
        if (b.get() != VERSION) return null
        return Packet(b.get(), b.long, b.int, b.long, b.long, b.long)
    }

    private fun median(values: Collection<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun jitter(values: Collection<Long>): Long? {
        if (values.size < 2) return null
        val med = median(values) ?: return null
        return values.map { abs(it - med) }.sorted()[values.size / 2]
    }

    private fun percentile(values: Collection<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    private fun update(newState: State) {
        _state.value = newState
    }

    private fun stopInternal(emit: Boolean) {
        completed = true
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pending = null
        if (emit) update(State(status = "Stopped"))
    }
}
