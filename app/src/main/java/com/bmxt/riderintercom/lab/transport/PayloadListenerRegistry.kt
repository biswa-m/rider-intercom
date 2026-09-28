package com.bmxt.riderintercom.lab.transport

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe registry for transport payload listeners.
 *
 * Owners provide lifecycle identity so a module can replace/remove its listener
 * without affecting unrelated consumers.
 */
class PayloadListenerRegistry {
    private data class Registration(
        val owner: Any,
        val listener: PayloadListener
    )

    private val registrations = CopyOnWriteArrayList<Registration>()

    fun add(owner: Any, listener: PayloadListener) {
        remove(owner)
        registrations += Registration(owner, listener)
    }

    fun remove(owner: Any) {
        registrations.removeAll { it.owner === owner }
    }

    fun dispatch(endpointId: String, bytes: ByteArray, receivedAtNs: Long) {
        val payload = ReceivedPayload(
            endpointId = endpointId,
            bytes = bytes,
            receivedAtNs = receivedAtNs
        )
        registrations.forEach { registration ->
            runCatching { registration.listener.onPayload(payload) }
        }
    }

    fun clear() {
        registrations.clear()
    }
}
