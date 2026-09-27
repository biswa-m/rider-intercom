package com.bmxt.riderintercom.intercom.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class AudioDeviceManager(
    context: Context
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getInputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun getOutputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun getCommunicationDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices
        }

        return getOutputDevices().filter { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> false
            }
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
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            return true
        }

        // Wired devices normally route automatically on Android 10/11.
        return false
    }

    fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun beginCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun endCommunicationMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun describeDevice(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown"
        return "$name | ${deviceTypeName(device.type)} | id=${device.id}"
    }

    fun getInputDeviceName(device: AudioDeviceInfo): String = describeDevice(device)
    fun getOutputDeviceName(device: AudioDeviceInfo): String = describeDevice(device)

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
