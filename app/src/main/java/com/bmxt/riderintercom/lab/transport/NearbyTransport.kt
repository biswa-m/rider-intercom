package com.bmxt.riderintercom.lab.transport

import com.bmxt.riderintercom.lab.nearby.NearbyConnectionManager

/**
 * Adapter from the project's stable Nearby connection manager to the generic
 * peer-transport boundary used by future higher-level modules.
 */
class NearbyTransport(
    private val manager: NearbyConnectionManager
) : PeerTransport {
    override fun send(bytes: ByteArray): Boolean = manager.sendRaw(bytes)

    override fun isConnected(): Boolean = manager.state.value.connectedPeer != null

    override fun addPayloadListener(owner: Any, listener: PayloadListener) {
        manager.addPayloadListener(owner, listener)
    }

    override fun removePayloadListener(owner: Any) {
        manager.removePayloadListener(owner)
    }
}
