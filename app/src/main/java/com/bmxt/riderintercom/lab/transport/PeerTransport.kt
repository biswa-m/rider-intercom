package com.bmxt.riderintercom.lab.transport

interface PeerTransport {
    fun send(bytes: ByteArray): Boolean
    fun setReceiver(receiver: ((ByteArray) -> Unit)?)
    fun isConnected(): Boolean
    fun close()
}
