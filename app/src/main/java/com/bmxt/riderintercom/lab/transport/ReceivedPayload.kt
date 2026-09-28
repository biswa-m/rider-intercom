package com.bmxt.riderintercom.lab.transport

/**
 * A payload delivered by a peer transport.
 *
 * The byte array is owned by the delivery boundary and must be treated as read-only
 * by consumers. The timestamp is captured at the transport callback boundary.
 */
data class ReceivedPayload(
    val endpointId: String,
    val bytes: ByteArray,
    val receivedAtNs: Long
)
