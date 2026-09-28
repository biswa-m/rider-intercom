package com.bmxt.riderintercom.lab.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WpsInfo
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

class WifiDirectManager(private val context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val devices: StateFlow<List<WifiP2pDevice>> = _devices.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.value = _state.value.copy(wifiEnabled = enabled, status = if (enabled) "Wi-Fi Direct enabled" else "Enable Wi-Fi")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestGroupInfo()
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

    fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = clearConnection("Disconnected")
            override fun onFailure(reason: Int) = updateError("Disconnect failed: ${reasonText(reason)}")
        })
    }

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
                clearConnection("Not connected")
                return@requestGroupInfo
            }
            val owner = group.isGroupOwner
            val peer = group.clientList.firstOrNull()
            _state.value = _state.value.copy(
                connected = true,
                isGroupOwner = owner,
                groupOwnerAddress = if (owner) "192.168.49.1" else "192.168.49.1",
                peerName = if (owner) peer?.deviceName else "Group Owner",
                peerAddress = peer?.deviceAddress,
                status = if (owner) "Connected — Group Owner" else "Connected — Client",
                error = null
            )
        }
    }

    private fun clearConnection(status: String) {
        _state.value = _state.value.copy(connected = false, isGroupOwner = false, groupOwnerAddress = null, peerAddress = null, peerName = null, status = status)
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
