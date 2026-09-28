package com.bmxt.riderintercom.lab.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NearbyConnectionManager(private val context: Context) {
    companion object {
        const val SERVICE_ID = "com.bmxt.riderintercom.nearby.lab"
        private const val MAX_DISCOVERED = 20
    }

    private val identity = NearbyIdentityStore(context)
    private val client = Nearby.getConnectionsClient(context)
    private val _state = MutableStateFlow(NearbyState(localName = identity.localName))
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    private var localEndpointName = identity.localName
    private var connectedEndpointId: String? = null
    private var manualConnectEndpointId: String? = null
    private var autoMode = false
    private var incomingTestId: String? = null
    private var incomingTestActive = false
    private var incomingExpectedSeq = 0L
    private var incomingRx = 0L
    private var incomingUnique = 0L
    private var incomingDuplicates = 0L
    private var incomingGaps = 0L
    private var incomingOutOfOrder = 0L
    private var incomingBytes = 0L
    private val testSignals = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String>>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
            if (text != null && text.startsWith("RITC|")) {
                handleTestControl(text.removePrefix("RITC|"))
                return
            }
            if (incomingTestActive && bytes.size >= 16 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'D'.code.toByte()))) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buffer.position(4)
                val sequence = buffer.int.toLong()
                buffer.long // sender timestamp, reserved for future latency measurement
                incomingRx++
                incomingBytes += bytes.size
                when {
                    sequence < incomingExpectedSeq -> incomingDuplicates++
                    sequence > incomingExpectedSeq -> {
                        incomingGaps += sequence - incomingExpectedSeq
                        incomingExpectedSeq = sequence + 1
                        incomingUnique++
                    }
                    else -> {
                        incomingUnique++
                        incomingExpectedSeq++
                    }
                }
                _state.value = _state.value.copy(
                    bytesReceived = _state.value.bytesReceived + bytes.size,
                    packetsReceived = _state.value.packetsReceived + 1,
                    lastEvent = "Data received: seq=$sequence"
                )
                return
            }
            _state.value = _state.value.copy(
                bytesReceived = _state.value.bytesReceived + bytes.size,
                packetsReceived = _state.value.packetsReceived + 1,
                lastEvent = "Payload received: ${bytes.size} bytes"
            )
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            event("Connection initiated: ${connectionInfo.endpointName}")
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { event("Accept failed: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpointId = endpointId
                manualConnectEndpointId = null
                stopDiscoveryOnly()
                val peer = _state.value.discoveredPeers.firstOrNull { it.endpointId == endpointId }
                _state.value = _state.value.copy(
                    status = NearbyConnectionStatus.CONNECTED,
                    connectedPeer = peer,
                    lastError = null,
                    lastEvent = "CONNECTED: ${peer?.name ?: endpointId}"
                )
            } else {
                connectedEndpointId = null
                val message = "Connection failed: ${result.status.statusMessage} (${result.status.statusCode})"
                _state.value = _state.value.copy(status = NearbyConnectionStatus.ERROR, lastError = message, lastEvent = message)
                if (autoMode) startDiscoveryAndAdvertising()
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpointId == endpointId) connectedEndpointId = null
            _state.value = _state.value.copy(
                status = if (autoMode) NearbyConnectionStatus.RECONNECTING else NearbyConnectionStatus.DISCONNECTED,
                connectedPeer = null,
                lastEvent = "DISCONNECTED: $endpointId"
            )
            if (autoMode) startDiscoveryAndAdvertising()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = NearbyPeer(endpointId, info.endpointName, SERVICE_ID)
            val current = _state.value.discoveredPeers.filterNot { it.endpointId == endpointId }
            _state.value = _state.value.copy(
                discoveredPeers = (current + peer).takeLast(MAX_DISCOVERED),
                lastEvent = "PEER_FOUND: ${info.endpointName}"
            )
            if (connectedEndpointId == null && (manualConnectEndpointId == endpointId || shouldAutoInitiate(info.endpointName, endpointId))) {
                requestConnection(peer)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _state.value = _state.value.copy(
                discoveredPeers = _state.value.discoveredPeers.filterNot { it.endpointId == endpointId },
                lastEvent = "PEER_LOST: $endpointId"
            )
        }
    }

    fun startAutoMode() { autoMode = true; startDiscoveryAndAdvertising() }
    fun discover() { autoMode = false; startDiscoveryAndAdvertising() }
    fun connect(peer: NearbyPeer) { autoMode = false; manualConnectEndpointId = peer.endpointId; requestConnection(peer) }
    fun resumeAutoConnect() { autoMode = true; startDiscoveryAndAdvertising() }

    fun disconnect() {
        autoMode = false
        connectedEndpointId?.let { client.disconnectFromEndpoint(it) }
        client.stopAllEndpoints()
        connectedEndpointId = null
        _state.value = _state.value.copy(status = NearbyConnectionStatus.DISCONNECTED, connectedPeer = null, lastEvent = "Manual disconnect")
    }

    fun send(bytes: ByteArray): Boolean {
        val endpoint = connectedEndpointId ?: return false
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { _state.value = _state.value.copy(lastError = "Send failed: ${it.message}") }
        _state.value = _state.value.copy(
            bytesSent = _state.value.bytesSent + bytes.size,
            packetsSent = _state.value.packetsSent + 1,
            lastEvent = "Payload sent: ${bytes.size} bytes"
        )
        return true
    }


    fun sendTestControl(message: String): Boolean {
        val endpoint = connectedEndpointId ?: return false
        val bytes = ("RITC|" + message).toByteArray(StandardCharsets.UTF_8)
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { _state.value = _state.value.copy(lastError = "Control send failed: ${it.message}") }
        return true
    }

    fun prepareTestSignal(prefix: String): kotlinx.coroutines.CompletableDeferred<String> {
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        testSignals[prefix] = deferred
        return deferred
    }

    private fun completeTestSignal(message: String) {
        val match = testSignals.entries.firstOrNull { message.startsWith(it.key) }
        match?.let { testSignals.remove(it.key); it.value.complete(message) }
    }

    private fun handleTestControl(message: String) {
        completeTestSignal(message)
        val parts = message.split('|')
        when (parts.firstOrNull()) {
            "PREPARE" -> {
                incomingTestId = parts.getOrNull(1)
                incomingTestActive = false
                incomingExpectedSeq = 0L
                incomingRx = 0L
                incomingUnique = 0L
                incomingDuplicates = 0L
                incomingGaps = 0L
                incomingOutOfOrder = 0L
                incomingBytes = 0L
                incomingTestId?.let { id ->
                    _state.value = _state.value.copy(lastEvent = "Prepared synchronized receiver: $id")
                    sendTestControl("READY|$id")
                }
            }
            "START" -> {
                if (parts.getOrNull(1) == incomingTestId) {
                    incomingTestActive = true
                    _state.value = _state.value.copy(lastEvent = "Synchronized receiver started")
                }
            }
            "STOP" -> {
                if (parts.getOrNull(1) == incomingTestId) {
                    incomingTestActive = false
                    val id = incomingTestId ?: return
                    sendTestControl("RESULT|$id|$incomingRx|$incomingUnique|$incomingDuplicates|$incomingGaps|$incomingOutOfOrder|$incomingBytes")
                    _state.value = _state.value.copy(lastEvent = "Receiver result sent: RX=$incomingRx unique=$incomingUnique gaps=$incomingGaps")
                }
            }
        }
    }

    fun sendRaw(bytes: ByteArray): Boolean {
        val endpoint = connectedEndpointId ?: return false
        client.sendPayload(endpoint, Payload.fromBytes(bytes))
            .addOnFailureListener { _state.value = _state.value.copy(lastError = "Data send failed: ${it.message}") }
        _state.value = _state.value.copy(
            bytesSent = _state.value.bytesSent + bytes.size,
            packetsSent = _state.value.packetsSent + 1
        )
        return true
    }

    fun resetCounters() { _state.value = _state.value.copy(bytesSent = 0, bytesReceived = 0, packetsSent = 0, packetsReceived = 0) }

    fun forceUnexpectedDisconnectForTest() {
        autoMode = true
        client.stopAllEndpoints()
        connectedEndpointId = null
        _state.value = _state.value.copy(status = NearbyConnectionStatus.RECONNECTING, connectedPeer = null, lastEvent = "Test disconnect: automatic recovery")
        startDiscoveryAndAdvertising()
    }

    fun stop() {
        autoMode = false
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        connectedEndpointId = null
        _state.value = _state.value.copy(status = NearbyConnectionStatus.IDLE, connectedPeer = null, lastEvent = "Stopped")
    }

    private fun startDiscoveryAndAdvertising() {
        if (connectedEndpointId != null) return
        client.stopDiscovery()
        client.stopAdvertising()
        _state.value = _state.value.copy(status = NearbyConnectionStatus.ADVERTISING_DISCOVERING, lastError = null, lastEvent = "Starting advertise + discovery")
        val strategy = Strategy.P2P_POINT_TO_POINT
        client.startAdvertising(
            localEndpointName, SERVICE_ID, lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener { event("Advertising started") }
         .addOnFailureListener { event("Advertising failed: ${it.message}") }
        client.startDiscovery(
            SERVICE_ID, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener { event("Discovery started") }
         .addOnFailureListener { event("Discovery failed: ${it.message}") }
    }

    private fun stopDiscoveryOnly() { client.stopDiscovery(); client.stopAdvertising() }

    private fun requestConnection(peer: NearbyPeer) {
        if (connectedEndpointId != null) return
        _state.value = _state.value.copy(status = NearbyConnectionStatus.CONNECTING, lastError = null, lastEvent = "CONNECT_REQUEST: ${peer.name}")
        client.requestConnection(localEndpointName, peer.endpointId, lifecycleCallback)
            .addOnFailureListener {
                val message = "Request failed: ${it.message}"
                _state.value = _state.value.copy(status = NearbyConnectionStatus.ERROR, lastError = message, lastEvent = message)
                if (autoMode) startDiscoveryAndAdvertising()
            }
    }

    private fun shouldAutoInitiate(remoteName: String, endpointId: String): Boolean {
        val localKey = localEndpointName.lowercase()
        val remoteKey = remoteName.lowercase()
        return if (localKey != remoteKey) localKey < remoteKey else localEndpointName < endpointId
    }

    private fun event(message: String) { _state.value = _state.value.copy(lastEvent = message) }
    }
