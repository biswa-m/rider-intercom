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
import kotlinx.coroutines.Dispatchers
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
    private var incomingStartAtNs = 0L
    private var incomingLastArrivalNs = 0L
    private val incomingInterArrivalMs = mutableListOf<Double>()
    private val incomingSeenSequences = HashSet<Long>()
    private val incomingPacketTrace = java.util.Collections.synchronizedList(mutableListOf<NearbyPacketTrace>())
    private var incomingReceiveOrder = 0L
    private var incomingMaxSequence = -1L
    private var latencyTestId: String? = null
    private var latencyTestActive = false
    private var latencyClockOffsetMs = 0.0
    private var latencyRx = 0L
    private val latencySamplesMs = mutableListOf<Double>()
    private val testSignals = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String>>()
    private val payloadListeners = java.util.concurrent.CopyOnWriteArrayList<Pair<Any, (ByteArray) -> Unit>>()
    private val testControlListeners = java.util.concurrent.CopyOnWriteArrayList<Pair<Any, (String) -> Unit>>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
            if (text != null && text.startsWith("RITC|")) {
                val message = text.removePrefix("RITC|")
                testControlListeners.forEach { (_, listener) -> listener(message) }
                handleTestControl(message)
                return
            }
            payloadListeners.forEach { (_, listener) -> listener(bytes) }
            if (latencyTestActive && bytes.size >= 16 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte()))) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buffer.position(4)
                buffer.int
                val senderWallMs = buffer.long
                val receiverWallMs = System.currentTimeMillis()
                val oneWayMs = (receiverWallMs - (senderWallMs + latencyClockOffsetMs)).coerceAtLeast(0.0)
                latencyRx++
                latencySamplesMs += oneWayMs
                _state.value = _state.value.copy(
                    bytesReceived = _state.value.bytesReceived + bytes.size,
                    packetsReceived = _state.value.packetsReceived + 1,
                    lastEvent = "Latency packet: ${"%.2f".format(oneWayMs)} ms"
                )
                return
            }
            if (incomingTestActive && System.nanoTime() >= incomingStartAtNs && bytes.size >= 16 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'D'.code.toByte()))) {
                val nowNs = System.nanoTime()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buffer.position(4)
                val sequence = buffer.int.toLong()
                val senderElapsedNs = buffer.long
                incomingRx++
                incomingBytes += bytes.size
                val receiverElapsedMs = if (incomingStartAtNs == 0L) 0.0 else (nowNs - incomingStartAtNs) / 1_000_000.0
                val senderElapsedMs = senderElapsedNs / 1_000_000.0
                val interArrivalMs = if (incomingLastArrivalNs == 0L) 0.0 else (nowNs - incomingLastArrivalNs) / 1_000_000.0
                incomingLastArrivalNs = nowNs
                if (interArrivalMs > 0.0) incomingInterArrivalMs += interArrivalMs
                val alreadySeen = incomingSeenSequences.contains(sequence)
                val sequenceDelta = sequence - incomingExpectedSeq
                val isOutOfOrder = !alreadySeen && sequence < incomingExpectedSeq
                when {
                    alreadySeen -> incomingDuplicates++
                    sequence > incomingExpectedSeq -> {
                        incomingGaps += sequence - incomingExpectedSeq
                        incomingExpectedSeq = sequence + 1
                        incomingUnique++
                        incomingSeenSequences += sequence
                    }
                    sequence == incomingExpectedSeq -> {
                        incomingUnique++
                        incomingExpectedSeq++
                        incomingSeenSequences += sequence
                    }
                    else -> {
                        incomingUnique++
                        incomingOutOfOrder++
                        incomingSeenSequences += sequence
                    }
                }
                incomingPacketTrace += NearbyPacketTrace(
                    testId = incomingTestId ?: "",
                    sequence = sequence,
                    senderElapsedMs = senderElapsedMs,
                    receiverElapsedMs = receiverElapsedMs,
                    interArrivalMs = interArrivalMs,
                    sequenceDelta = sequenceDelta,
                    outOfOrder = isOutOfOrder,
                    duplicate = alreadySeen,
                    receiveOrder = incomingReceiveOrder++
                )
                _state.value = _state.value.copy(incomingPacketTraceRows = incomingPacketTrace.size)
                incomingMaxSequence = maxOf(incomingMaxSequence, sequence)
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

    fun addPayloadListener(owner: Any, listener: (ByteArray) -> Unit) {
        payloadListeners.removeAll { it.first === owner }
        payloadListeners += owner to listener
    }

    fun removePayloadListener(owner: Any) {
        payloadListeners.removeAll { it.first === owner }
    }

    fun addTestControlListener(owner: Any, listener: (String) -> Unit) {
        testControlListeners.removeAll { it.first === owner }
        testControlListeners += owner to listener
    }

    fun removeTestControlListener(owner: Any) {
        testControlListeners.removeAll { it.first === owner }
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
                incomingStartAtNs = 0L
                incomingLastArrivalNs = 0L
                incomingInterArrivalMs.clear()
                incomingSeenSequences.clear()
                incomingPacketTrace.clear()
                _state.value = _state.value.copy(incomingPacketTraceRows = 0, receiverPacketTraceFileName = null)
                incomingReceiveOrder = 0L
                incomingMaxSequence = -1L
                incomingTestId?.let { id ->
                    _state.value = _state.value.copy(lastEvent = "Prepared synchronized receiver: $id")
                    sendTestControl("READY|$id")
                }
            }
            "START" -> {
                if (parts.getOrNull(1) == incomingTestId) {
                    val delayMs = parts.getOrNull(2)?.toLongOrNull() ?: 1_500L
                    incomingStartAtNs = System.nanoTime() + delayMs * 1_000_000L
                    incomingTestActive = true
                    _state.value = _state.value.copy(lastEvent = "Receiver scheduled start in ${delayMs} ms")
                }
            }
            "STOP" -> {
                if (parts.getOrNull(1) == incomingTestId) {
                    incomingTestActive = false
                    val id = incomingTestId ?: return
                    val avg = incomingInterArrivalMs.averageOrZero()
                    val p95 = incomingInterArrivalMs.percentile(95.0)
                    val max = incomingInterArrivalMs.maxOrNull() ?: 0.0
                    sendTestControl("RESULT|$id|$incomingRx|$incomingUnique|$incomingDuplicates|$incomingGaps|$incomingOutOfOrder|$incomingBytes|$avg|$p95|$max|$incomingMaxSequence")
                    val traceFile = NearbyPacketTraceCsvWriter.writeToAppStorage(context, getIncomingPacketTrace(), id)
                    _state.value = _state.value.copy(
                        lastError = if (traceFile == null) "Could not save receiver packet trace CSV" else null,
                        receiverPacketTraceFileName = traceFile?.name,
                        lastEvent = if (traceFile != null) {
                            "Receiver result sent: RX=$incomingRx unique=$incomingUnique gaps=$incomingGaps p95=${"%.2f".format(p95)}ms; packet trace saved locally (${incomingPacketTrace.size} rows)"
                        } else {
                            "Receiver result sent: RX=$incomingRx unique=$incomingUnique gaps=$incomingGaps p95=${"%.2f".format(p95)}ms; packet trace save failed"
                        }
                    )
                }
            }
            "LAT_PREPARE" -> {
                latencyTestId = parts.getOrNull(1)
                latencyTestActive = false
                latencyClockOffsetMs = 0.0
                latencyRx = 0L
                latencySamplesMs.clear()
                latencyTestId?.let { id ->
                    _state.value = _state.value.copy(lastEvent = "Prepared latency receiver: $id")
                    sendTestControl("LAT_READY|$id")
                }
            }
            "LAT_SYNC_REQ" -> {
                val id = parts.getOrNull(1) ?: return
                val t1 = parts.getOrNull(2)?.toLongOrNull() ?: return
                val t2 = System.currentTimeMillis()
                val t3 = System.currentTimeMillis()
                sendTestControl("LAT_SYNC_RESP|$id|$t1|$t2|$t3")
            }
            "LAT_CONFIG" -> {
                if (parts.getOrNull(1) == latencyTestId) {
                    latencyClockOffsetMs = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                    _state.value = _state.value.copy(lastEvent = "Latency clock offset configured: ${"%.2f".format(latencyClockOffsetMs)} ms")
                }
            }
            "LAT_START" -> {
                if (parts.getOrNull(1) == latencyTestId) {
                    latencyTestActive = true
                    _state.value = _state.value.copy(lastEvent = "Latency receiver active")
                }
            }
            "LAT_STOP" -> {
                if (parts.getOrNull(1) == latencyTestId) {
                    latencyTestActive = false
                    val id = latencyTestId ?: return
                    val avg = latencySamplesMs.averageOrZero()
                    val p50 = latencySamplesMs.percentile(50.0)
                    val p95 = latencySamplesMs.percentile(95.0)
                    val max = latencySamplesMs.maxOrNull() ?: 0.0
                    val min = latencySamplesMs.minOrNull() ?: 0.0
                    sendTestControl("LAT_RESULT|$id|$latencyRx|${latencySamplesMs.size}|$avg|$p50|$p95|$max|$min")
                    _state.value = _state.value.copy(lastEvent = "Latency result sent: RX=$latencyRx avg=${"%.2f".format(avg)}ms p95=${"%.2f".format(p95)}ms")
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

    fun getIncomingPacketTrace(): List<NearbyPacketTrace> = synchronized(incomingPacketTrace) { incomingPacketTrace.toList() }

    fun resetCounters() {
        _state.value = _state.value.copy(bytesSent = 0, bytesReceived = 0, packetsSent = 0, packetsReceived = 0)
        incomingTestActive = false
        incomingExpectedSeq = 0L
        incomingRx = 0L
        incomingUnique = 0L
        incomingDuplicates = 0L
        incomingGaps = 0L
        incomingOutOfOrder = 0L
        incomingBytes = 0L
        incomingStartAtNs = 0L
        incomingLastArrivalNs = 0L
        incomingInterArrivalMs.clear()
        incomingSeenSequences.clear()
        incomingPacketTrace.clear()
        _state.value = _state.value.copy(incomingPacketTraceRows = 0, receiverPacketTraceFileName = null)
        incomingReceiveOrder = 0L
        incomingMaxSequence = -1L
        resetLatencyTest()
    }

    fun resetLatencyTest() {
        latencyTestActive = false
        latencyTestId = null
        latencyClockOffsetMs = 0.0
        latencyRx = 0L
        latencySamplesMs.clear()
    }

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
        payloadListeners.clear()
        testControlListeners.clear()
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

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun List<Double>.percentile(percent: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = ((percent / 100.0) * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lower = index.toInt()
        val upper = kotlin.math.ceil(index).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = index - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }
    }
