package com.bmxt.riderintercom.lab.network

import com.bmxt.riderintercom.lab.core.LabClock
import com.bmxt.riderintercom.lab.core.LabConstants
import com.bmxt.riderintercom.lab.core.LabTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class UdpDummyTransport {
    suspend fun run(
        peerAddress: String,
        role: String,
        onProgress: (Long, Long) -> Unit
    ): LabTestResult = withContext(Dispatchers.IO) {
        val startEpoch = System.currentTimeMillis()
        val start = LabClock.elapsedMillis()
        val sessionId = System.nanoTime()
        val stats = NetworkStatistics("UDP_DUMMY", startEpoch)
        val socket = DatagramSocket(LabConstants.UDP_PORT).apply { soTimeout = LabConstants.SOCKET_TIMEOUT_MS }
        val running = AtomicBoolean(true)
        val receiver = Thread {
            val buffer = ByteArray(2048)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    NetworkTestPacket.decode(data)?.let { stats.onRx(it, packet.length, LabClock.elapsedNanos()) }
                } catch (_: Exception) { }
            }
        }
        receiver.start()
        try {
            val destination = InetAddress.getByName(peerAddress)
            var sequence = 0L
            var nextSend = LabClock.elapsedNanos()
            val end = start + LabConstants.TEST_DURATION_MS
            while (LabClock.elapsedMillis() < end) {
                val now = LabClock.elapsedNanos()
                if (now >= nextSend) {
                    val data = NetworkTestPacket.encode(sessionId, sequence++, now)
                    socket.send(DatagramPacket(data, data.size, destination, LabConstants.UDP_PORT))
                    stats.onTx(data.size)
                    onProgress(stats.txPackets, stats.rxPackets)
                    nextSend += 1_000_000_000L / LabConstants.PACKETS_PER_SECOND
                } else {
                    delay(1)
                }
            }
            delay(250)
        } finally {
            running.set(false)
            socket.close()
            receiver.join(500)
        }
        val duration = LabClock.elapsedMillis() - start
        stats.finish(duration, "UDP dummy payload; role=$role")
    }
}
