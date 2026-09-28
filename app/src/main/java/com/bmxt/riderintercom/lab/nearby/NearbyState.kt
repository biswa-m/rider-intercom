package com.bmxt.riderintercom.lab.nearby

enum class NearbyConnectionStatus { IDLE, ADVERTISING_DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, ERROR }
data class NearbyPeer(val endpointId: String, val name: String, val serviceId: String)
data class NearbyState(
    val status: NearbyConnectionStatus = NearbyConnectionStatus.IDLE,
    val localName: String = "",
    val connectedPeer: NearbyPeer? = null,
    val discoveredPeers: List<NearbyPeer> = emptyList(),
    val lastError: String? = null,
    val lastEvent: String = "Idle",
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val incomingPacketTraceRows: Int = 0,
    val receiverPacketTraceFileName: String? = null
)
