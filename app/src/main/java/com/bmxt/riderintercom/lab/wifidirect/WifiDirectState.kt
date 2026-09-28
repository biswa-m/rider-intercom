package com.bmxt.riderintercom.lab.wifidirect

data class WifiDirectState(
    val wifiEnabled: Boolean = false,
    val peerDiscoveryActive: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val localAddress: String? = null,
    val peerAddress: String? = null,
    val peerName: String? = null,
    val status: String = "Idle",
    val error: String? = null
)
