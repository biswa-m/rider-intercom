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

/**
 * Simple one-peer UDP transport used for the networking prototype.
 *
 * Both phones use the same local UDP port and send to the other phone's IP.
 * There is no discovery, encryption, retransmission, or NAT traversal yet.
 */
class UdpVoiceTransport(
    private val localPort: Int,
    private val peerHost: String,
    private val peerPort: Int,
    private val onPacket: (PcmVoicePacket) -> Unit,
    private val onState: (String) -> Unit
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
            socket = DatagramSocket(localPort).apply {
                soTimeout = 1000
            }

            onState("UDP listening on $localPort; peer $peerHost:$peerPort")

            receiveJob = scope.launch(Dispatchers.IO) {
                receiveLoop()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start UDP transport", e)
            onState("UDP start failed: ${e.message ?: "unknown error"}")
            stop()
            false
        }
    }

    fun send(samples: ShortArray) {
        val targetAddress = peerAddress ?: return
        val currentSocket = socket ?: return

        try {
            val payload = PcmVoicePacket.encode(
                sequence.incrementAndGet(),
                samples
            )
            val packet = DatagramPacket(
                payload,
                payload.size,
                targetAddress,
                peerPort
            )
            currentSocket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed", e)
            onState("UDP send error: ${e.message ?: "unknown error"}")
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val currentSocket = socket ?: break

            try {
                val packet = DatagramPacket(buffer, buffer.size)
                currentSocket.receive(packet)

                val decoded = PcmVoicePacket.decode(
                    packet.data,
                    packet.length
                )

                if (decoded != null) {
                    onPacket(decoded)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Periodically wake so cancellation is observed.
            } catch (e: Exception) {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    Log.e(TAG, "UDP receive failed", e)
                    onState("UDP receive error: ${e.message ?: "unknown error"}")
                }
                break
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        peerAddress = null
        onState("UDP stopped")
    }
}
