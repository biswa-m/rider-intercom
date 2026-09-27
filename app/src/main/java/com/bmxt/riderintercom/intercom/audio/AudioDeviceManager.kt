package com.bmxt.riderintercom.intercom.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Handles communication-audio routing across Android versions.
 *
 * Android 12+ exposes explicit communication-device selection through
 * AudioManager.availableCommunicationDevices / setCommunicationDevice().
 * Android 11 and lower use the legacy Bluetooth SCO APIs instead, so there
 * is no AudioDeviceInfo-based selectable communication-device list for that
 * path. We therefore expose the connected Bluetooth Headset profile device
 * separately on legacy Android.
 */
class AudioDeviceManager(
    context: Context,
    private val onLegacyBluetoothHeadsetsChanged: (() -> Unit)? = null
) {
    private val appContext = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothAdapter: BluetoothAdapter? =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } else {
            null
        }

    private var bluetoothHeadset: BluetoothHeadset? = null

    private val headsetServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                onLegacyBluetoothHeadsetsChanged?.invoke()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                onLegacyBluetoothHeadsetsChanged?.invoke()
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            try {
                bluetoothAdapter?.getProfileProxy(
                    appContext,
                    headsetServiceListener,
                    BluetoothProfile.HEADSET
                )
            } catch (_: SecurityException) {
                // Android 11 uses the normal BLUETOOTH permission.
                // The UI will simply report that no legacy headset was found.
            }
        }
    }

    fun getInputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun getOutputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun getCommunicationDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices
        }

        // Legacy Android does not provide the S+ selectable communication-device
        // API. Some devices still expose a SCO AudioDeviceInfo, so include it
        // when present, along with normal wired communication outputs.
        return (getInputDevices() + getOutputDevices())
            .filter { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                    else -> false
                }
            }
            .distinctBy { device -> device.id to device.type }
    }

    /** Connected classic Bluetooth HFP/Headset devices on Android 11 and lower. */
    fun getLegacyBluetoothHeadsets(): List<LegacyBluetoothHeadset> {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return emptyList()

        return try {
            bluetoothHeadset
                ?.connectedDevices
                ?.map { device ->
                    LegacyBluetoothHeadset(
                        name = device.displayNameCompat(),
                        address = device.address
                    )
                }
                ?.distinctBy { it.address }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun isLegacyBluetoothScoOn(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && audioManager.isBluetoothScoOn

    /**
     * Requests legacy Bluetooth SCO asynchronously.
     * startBluetoothSco() does not mean SCO is immediately connected; callers
     * should observe isBluetoothScoOn / routing state rather than treating the
     * request as an immediate connection.
     */
    fun startLegacyBluetoothSco(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return false

        return try {
            if (getLegacyBluetoothHeadsets().isEmpty()) return false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stopLegacyBluetoothSco() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }

    fun getCurrentCommunicationDevice(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            null
        }

    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.setCommunicationDevice(device)
        }

        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return startLegacyBluetoothSco()
        }

        // Wired devices normally route automatically on Android 10/11.
        return false
    }

    fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            stopLegacyBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun beginCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun endCommunicationMode() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            stopLegacyBluetoothSco()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun describeDevice(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown"
        return "$name | ${deviceTypeName(device.type)} | id=${device.id}"
    }

    fun getInputDeviceName(device: AudioDeviceInfo): String = describeDevice(device)
    fun getOutputDeviceName(device: AudioDeviceInfo): String = describeDevice(device)

    fun close() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            try {
                bluetoothAdapter?.let { adapter ->
                    bluetoothHeadset?.let { headset ->
                        adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset)
                    }
                }
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
            bluetoothHeadset = null
        }
    }

    private fun BluetoothDevice.displayNameCompat(): String =
        name?.takeIf { it.isNotBlank() } ?: "Bluetooth Headset"

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
        else -> "Unknown ($type)"
    }
}

data class LegacyBluetoothHeadset(
    val name: String,
    val address: String
)
