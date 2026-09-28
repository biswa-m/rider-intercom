package com.bmxt.riderintercom.lab.wifidirect

import com.bmxt.riderintercom.lab.core.LabConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Small app-level control channel used only while a Wi-Fi Direct group exists.
 *
 * It lets a user-initiated disconnect tell the peer not to treat the loss as
 * an unexpected disconnect. This prevents the peer from immediately trying
 * to recreate a connection that the user intentionally ended.
 */
class PeerControlChannel(
    private val localAddressProvider: () -> String?
) {
    companion object {
        private const val MANUAL_DISCONNECT = "RIDER_LAB_MANUAL_DISCONNECT"
        private const val SEPARATOR = '|'
        private const val RECEIVE_TIMEOUT_MS = 500
    }

    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    fun start(onManualDisconnect: () -> Unit) {
        stop()
        val localAddress = localAddressProvider()
        running.set(true)
        worker = Thread {
            try {
                DatagramSocket(LabConstants.CONTROL_UDP_PORT).also {
                    it.broadcast = true
                    it.soTimeout = RECEIVE_TIMEOUT_MS
                    socket = it
                }.use { udp ->
                    val buffer = ByteArray(512)
                    while (running.get()) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            udp.receive(packet)
                            val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            if (isManualDisconnect(message, localAddress)) {
                                onManualDisconnect()
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Periodically check running.
                        } catch (_: Exception) {
                            if (running.get()) continue
                        }
                    }
                }
            } catch (_: Exception) {
                // Control signalling is best-effort. Wi-Fi Direct state remains authoritative.
            } finally {
                socket = null
            }
        }.apply {
            name = "rider-lab-peer-control"
            isDaemon = true
            start()
        }
    }

    suspend fun sendManualDisconnect() = withContext(Dispatchers.IO) {
        val sender = localAddressProvider() ?: return@withContext
        val payload = "$MANUAL_DISCONNECT$SEPARATOR$sender$SEPARATOR${System.currentTimeMillis()}"
            .toByteArray(Charsets.UTF_8)

        try {
            DatagramSocket().use { udp ->
                udp.broadcast = true
                repeat(3) {
                    broadcastAddresses().forEach { address ->
                        udp.send(DatagramPacket(payload, payload.size, address, LabConstants.CONTROL_UDP_PORT))
                    }
                    if (it < 2) delay(75L)
                }
            }
        } catch (_: Exception) {
            // Best-effort only. The local disconnect must still proceed.
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun isManualDisconnect(message: String, localAddress: String?): Boolean {
        val parts = message.split(SEPARATOR)
        if (parts.size < 3 || parts[0] != MANUAL_DISCONNECT) return false
        val sender = parts[1]
        return !sender.equals(localAddress, ignoreCase = true)
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        addresses += InetAddress.getByName("255.255.255.255")
        addresses += InetAddress.getByName("192.168.49.255")

        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                networkInterface.interfaceAddresses.forEach { address ->
                    address.broadcast?.let(addresses::add)
                }
            }
        } catch (_: Exception) {
            // Keep the known Wi-Fi Direct broadcasts above.
        }
        return addresses.toList()
    }
}
