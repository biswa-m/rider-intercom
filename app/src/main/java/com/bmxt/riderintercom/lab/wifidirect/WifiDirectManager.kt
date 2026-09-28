package com.bmxt.riderintercom.lab.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class WifiDirectManager(private val context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val devices: StateFlow<List<WifiP2pDevice>> = _devices.asStateFlow()
    private var registered = false
    private var lastPeerDevice: WifiP2pDevice? = null
    private var lastPeerAddress: String? = null
    private var localDeviceAddress: String? = null
    private var wasConnected = false
    private var autoReconnectEnabled = false
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val peerControlChannel = PeerControlChannel { localDeviceAddress }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.value = _state.value.copy(wifiEnabled = enabled, status = if (enabled) "Wi-Fi Direct enabled" else "Enable Wi-Fi")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestGroupInfo()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (device != null) localDeviceAddress = device.deviceAddress
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        requestPeers()
        requestGroupInfo()
    }

    fun stop() {
        reconnectJob?.cancel()
        peerControlChannel.stop()
        reconnectJob = null
        scope.coroutineContext.cancel()
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    fun discover() {
        if (!hasWifiPermission()) return updateError("Wi-Fi Direct permission is not granted")
        _state.value = _state.value.copy(status = "Discovering nearby devices…", error = null, peerDiscoveryActive = true)
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = updateError("Discovery failed: ${reasonText(reason)}")
        })
    }

    fun connect(device: WifiP2pDevice) {
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnectEnabled = true
        lastPeerDevice = device
        lastPeerAddress = device.deviceAddress
        if (!hasWifiPermission()) return updateError("Wi-Fi Direct permission is not granted")
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        _state.value = _state.value.copy(status = "Connecting to ${device.deviceName}", error = null)
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = updateError("Connection failed: ${reasonText(reason)}")
        })
    }

    /** User-initiated disconnect. The peer is explicitly told not to auto-reconnect. */
    fun disconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            if (_state.value.connected) {
                peerControlChannel.sendManualDisconnect()
            }
            removeGroupOnce {
                clearConnection("Disconnected", reconnecting = false)
            }
        }
    }

    /** Cancel an automatic recovery attempt without changing an already-disconnected group. */
    fun cancelReconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        _state.value = _state.value.copy(
            reconnecting = false,
            status = "Reconnect cancelled",
            error = null
        )
    }

    suspend fun disconnectAndWait(timeoutMs: Long = 8_000L): Long {
        val started = System.nanoTime()
        var lastFailure: Int? = null
        repeat(4) { attempt ->
            if (!_state.value.connected) return elapsedMs(started)
            val completed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onFailure(reason: Int) {
                        lastFailure = reason
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            }
            if (!completed && lastFailure == WifiP2pManager.BUSY) {
                // Android 11 can report BUSY while the previous P2P transition is still
                // being processed. The connection-change broadcast may still arrive shortly.
                try {
                    kotlinx.coroutines.withTimeout(2_000L) {
                        _state.first { !it.connected }
                    }
                    return elapsedMs(started)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // Continue with the normal retry path.
                }
            }
            if (completed) {
                try {
                    kotlinx.coroutines.withTimeout(timeoutMs) {
                        _state.first { !it.connected }
                    }
                    return elapsedMs(started)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // retry below
                }
            }
            if (attempt < 3) delay(500L * (attempt + 1))
        }
        error("Disconnect failed: ${reasonText(lastFailure ?: WifiP2pManager.ERROR)}")
    }

    suspend fun reconnectAndWait(timeoutMs: Long = 30_000L): Long {
        val started = System.nanoTime()
        autoReconnectEnabled = true
        ensureAutoReconnectLoop()
        try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                _state.first { it.connected }
            }
            return elapsedMs(started)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            error("Reconnect timed out after ${timeoutMs} ms")
        }
    }

    private fun ensureAutoReconnectLoop() {
        if (!autoReconnectEnabled || reconnectJob?.isActive == true || _state.value.connected) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (autoReconnectEnabled && !_state.value.connected) {
                attempt++
                _state.value = _state.value.copy(
                    reconnecting = true,
                    status = "RECONNECTING — discovering peer (attempt $attempt)…",
                    error = null
                )

                val address = lastPeerAddress
                if (address != null) {
                    discoverAndWaitForPeer(address, timeoutMs = 4_000L)
                } else {
                    discoverOnce()
                }

                val target = findRememberedPeer(address)
                val shouldConnect = target != null && shouldInitiateReconnect(target)
                    if (shouldConnect) {
                    val connected = tryConnect(target)
                    if (connected) {
                        reconnectJob = null
                        return@launch
                    }
                }

                // Give the peer time to discover us too. Both phones run this loop,
                // so an out-of-range recovery does not depend on manually pressing
                // Discover on the other phone.
                delay(if (attempt < 4) 750L else 2_000L)
            }
            reconnectJob = null
        }
    }

    private suspend fun tryConnect(device: WifiP2pDevice): Boolean {
        val completed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
            }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
        if (!completed) return false
        return try {
            kotlinx.coroutines.withTimeout(8_000L) {
                _state.first { it.connected }
            }
            true
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        }
    }

    private fun shouldInitiateReconnect(device: WifiP2pDevice): Boolean {
        val local = localDeviceAddress ?: return true
        return local.compareTo(device.deviceAddress, ignoreCase = true) < 0
    }

    private suspend fun discoverOnce(): Boolean {
        if (!hasWifiPermission()) return false
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    private fun findRememberedPeer(address: String?): WifiP2pDevice? {
        if (address == null) return lastPeerDevice ?: _devices.value.firstOrNull()
        return _devices.value.firstOrNull { it.deviceAddress.equals(address, ignoreCase = true) }
            ?: lastPeerDevice?.takeIf { it.deviceAddress.equals(address, ignoreCase = true) }
    }

    private suspend fun discoverAndWaitForPeer(address: String, timeoutMs: Long = 8_000L) {
        if (!hasWifiPermission()) return
        repeat(4) { attempt ->
            val started = System.nanoTime()
            val accepted = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            }
            if (accepted) {
                try {
                    kotlinx.coroutines.withTimeout(timeoutMs) {
                        _devices.first { devices ->
                            devices.any { it.deviceAddress.equals(address, ignoreCase = true) }
                        }
                    }
                    return
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // Continue with another discovery attempt.
                }
            }
            if (attempt < 3) {
                val elapsed = elapsedMs(started)
                delay((500L - elapsed).coerceAtLeast(250L))
            }
        }
    }

    private fun removeGroupOnce(onSuccess: () -> Unit) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onSuccess()
            override fun onFailure(reason: Int) = updateError("Disconnect failed: ${reasonText(reason)}")
        })
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000L

    private fun requestPeers() {
        if (!hasWifiPermission()) return
        manager.requestPeers(channel) { list: WifiP2pDeviceList ->
            _devices.value = list.deviceList.toList()
            if (!_state.value.connected) {
                _state.value = _state.value.copy(status = "Found ${_devices.value.size} peer(s)")
            }
        }
    }

    private fun requestGroupInfo() {
        if (!hasWifiPermission()) return
        manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
            if (group == null) {
                peerControlChannel.stop()
                val unexpectedLoss = wasConnected && autoReconnectEnabled
                clearConnection(
                    if (unexpectedLoss) "Connection lost — reconnecting…" else "Not connected",
                    reconnecting = unexpectedLoss
                )
                if (unexpectedLoss) ensureAutoReconnectLoop()
                wasConnected = false
                return@requestGroupInfo
            }
            val owner = group.isGroupOwner
            val peer = if (owner) group.clientList.firstOrNull() else group.owner
            if (peer != null) {
                lastPeerDevice = peer
                lastPeerAddress = peer.deviceAddress
            }
            wasConnected = true
            peerControlChannel.start {
                // The peer explicitly ended this session. Do not interpret the
                // resulting group removal as an unexpected disconnect.
                autoReconnectEnabled = false
                reconnectJob?.cancel()
                reconnectJob = null
                _state.value = _state.value.copy(
                    reconnecting = false,
                    status = "Peer requested disconnect",
                    error = null
                )
            }
            // Do not cancel reconnectJob here. This callback can be reached from the
            // reconnect coroutine itself; cancelling it here would cancel the coroutine
            // that is currently waiting for this connected state.
            _state.value = _state.value.copy(
                connected = true,
                reconnecting = false,
                isGroupOwner = owner,
                groupOwnerAddress = "192.168.49.1",
                peerName = peer?.deviceName ?: if (owner) "—" else "Group Owner",
                peerAddress = peer?.deviceAddress,
                status = if (owner) "Connected — Group Owner" else "Connected — Client",
                error = null
            )
        }
    }

    private fun clearConnection(status: String, reconnecting: Boolean) {
        _state.value = _state.value.copy(
            connected = false,
            reconnecting = reconnecting,
            isGroupOwner = false,
            groupOwnerAddress = null,
            peerAddress = null,
            peerName = null,
            status = status
        )
    }

    private fun hasWifiPermission(): Boolean {
        val nearby = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearby && location
    }

    private fun updateError(message: String) { _state.value = _state.value.copy(status = "Error", error = message, peerDiscoveryActive = false) }
    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "Busy"
        WifiP2pManager.ERROR -> "Internal error"
        else -> "code=$reason"
    }
}
