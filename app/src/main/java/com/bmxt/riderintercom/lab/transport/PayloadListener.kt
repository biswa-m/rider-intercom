package com.bmxt.riderintercom.lab.transport

/**
 * Receives payloads from a peer transport.
 *
 * This is intentionally transport-facing only. Higher layers can be added later
 * without changing the Nearby connection lifecycle implementation.
 */
fun interface PayloadListener {
    fun onPayload(payload: ReceivedPayload)
}
