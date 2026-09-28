package com.bmxt.riderintercom.lab.network

import com.bmxt.riderintercom.lab.core.LabConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class PeerControlHandshake {
    suspend fun resolvePeer(isGroupOwner: Boolean, groupOwnerAddress: String): String = withContext(Dispatchers.IO) {
        if (isGroupOwner) resolveAsOwner() else resolveAsClient(groupOwnerAddress)
    }

    private fun resolveAsOwner(): String {
        ServerSocket(LabConstants.CONTROL_PORT).use { server ->
            server.soTimeout = 15_000
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                PrintWriter(socket.getOutputStream(), true).println("RIDER_LAB_READY")
                val response = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                if (response != "RIDER_LAB_CLIENT") error("Unexpected client handshake: $response")
                return socket.inetAddress.hostAddress ?: error("Peer address unavailable")
            }
        }
    }

    private fun resolveAsClient(ownerAddress: String): String {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(ownerAddress, LabConstants.CONTROL_PORT), 5_000)
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            if (reader.readLine() != "RIDER_LAB_READY") error("Group owner did not start test")
            writer.println("RIDER_LAB_CLIENT")
            return ownerAddress
        }
    }
}
