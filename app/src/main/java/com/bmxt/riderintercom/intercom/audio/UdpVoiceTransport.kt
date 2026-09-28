package com.bmxt.riderintercom.intercom.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** One-peer UDP transport supporting both raw PCM and Opus voice packets. */
class UdpVoiceTransport(
    private val localPort: Int,
    private val peerHost: String,
    private val peerPort: Int,
    private val onPacket: (packet: ReceivedVoicePacket) -> Unit,
    private val onMalformedPacket: (packetBytes: Int) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "UdpVoiceTransport"
        private const val RECEIVE_BUFFER_SIZE = 1500
    }

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val sequence = AtomicInteger(0)

    @Volatile
    private var peerAddress: InetAddress? = null

    fun start(scope: CoroutineScope): Boolean {
        if (socket != null) return false

        return try {
            peerAddress = InetAddress.getByName(peerHost)
            socket = DatagramSocket(localPort).apply { soTimeout = 1000 }

            onState("UDP listening on $localPort; peer $peerHost:$peerPort")
            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start UDP transport", e)
            val message = "UDP start failed: ${e.message ?: "unknown error"}"
            onState(message)
            onError(message)
            stop()
            false
        }
    }

    /** Returns the number of bytes sent, or 0 on failure. */
    fun sendPcm(samples: ShortArray): Int {
        val payload = PcmVoicePacket.encode(
            sequence = sequence.incrementAndGet(),
            sentAtMs = System.currentTimeMillis(),
            samples = samples
        )
        return sendDatagram(payload)
    }

    /** Returns the number of bytes sent, or 0 on failure. */
    fun sendOpus(sampleCount: Int, opusData: ByteArray): Int {
        val payload = OpusVoicePacket.encode(
            sequence = sequence.incrementAndGet(),
            sentAtMs = System.currentTimeMillis(),
            sampleCount = sampleCount,
            payload = opusData
        )
        return sendDatagram(payload)
    }

    private fun sendDatagram(payload: ByteArray): Int {
        val targetAddress = peerAddress ?: return 0
        val currentSocket = socket ?: return 0

        return try {
            currentSocket.send(
                DatagramPacket(payload, payload.size, targetAddress, peerPort)
            )
            payload.size
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed", e)
            val message = "UDP send error: ${e.message ?: "unknown error"}"
            onState(message)
            onError(message)
            0
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val currentSocket = socket ?: break
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                currentSocket.receive(packet)

                val pcm = PcmVoicePacket.decode(packet.data, packet.length)
                if (pcm != null) {
                    onPacket(ReceivedVoicePacket.Pcm(pcm, packet.length))
                    continue
                }

                val opus = OpusVoicePacket.decode(packet.data, packet.length)
                if (opus != null) {
                    onPacket(ReceivedVoicePacket.Opus(opus, packet.length))
                    continue
                }

                onMalformedPacket(packet.length)
            } catch (_: java.net.SocketTimeoutException) {
                // Periodically wake so cancellation is observed.
            } catch (e: Exception) {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    Log.e(TAG, "UDP receive failed", e)
                    val message = "UDP receive error: ${e.message ?: "unknown error"}"
                    onState(message)
                    onError(message)
                }
                break
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        peerAddress = null
        onState("UDP stopped")
    }
}
