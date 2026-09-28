package com.bmxt.riderintercom.lab.transport

import com.bmxt.riderintercom.lab.nearby.NearbyConnectionManager

class NearbyTransport(private val manager: NearbyConnectionManager) : PeerTransport {
    private var receiver: ((ByteArray) -> Unit)? = null

    init {
        manager.addPayloadListener(this) { bytes -> receiver?.invoke(bytes) }
    }

    override fun send(bytes: ByteArray): Boolean = manager.sendRaw(bytes)

    override fun setReceiver(receiver: ((ByteArray) -> Unit)?) {
        this.receiver = receiver
    }

    override fun isConnected(): Boolean = manager.state.value.connectedPeer != null

    override fun close() {
        receiver = null
        manager.removePayloadListener(this)
    }
}
