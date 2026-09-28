package com.bmxt.riderintercom.lab.transport

/**
 * Minimal peer-to-peer byte transport boundary.
 *
 * Existing Nearby test code continues to use NearbyConnectionManager directly.
 * New higher-level modules can depend on this interface instead of the Nearby API.
 */
interface PeerTransport {
    fun send(bytes: ByteArray): Boolean
    fun isConnected(): Boolean
    fun addPayloadListener(owner: Any, listener: PayloadListener)
    fun removePayloadListener(owner: Any)
}
