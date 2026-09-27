package com.bmxt.riderintercom

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmxt.riderintercom.intercom.audio.AudioDeviceManager
import com.bmxt.riderintercom.intercom.audio.AudioLoopbackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioLoopback = AudioLoopbackManager()
    private val audioDeviceManager = AudioDeviceManager(application)

    private val _audioRunning = MutableStateFlow(false)
    val audioRunning: StateFlow<Boolean> = _audioRunning.asStateFlow()

    private val _inputDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val inputDevices: StateFlow<List<AudioDeviceInfo>> = _inputDevices.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val outputDevices: StateFlow<List<AudioDeviceInfo>> = _outputDevices.asStateFlow()

    private val _communicationDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val communicationDevices: StateFlow<List<AudioDeviceInfo>> =
        _communicationDevices.asStateFlow()

    private val _currentCommunicationDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val currentCommunicationDevice: StateFlow<AudioDeviceInfo?> =
        _currentCommunicationDevice.asStateFlow()

    private val _routingError = MutableStateFlow<String?>(null)
    val routingError: StateFlow<String?> = _routingError.asStateFlow()

    fun refreshAudioDevices() {
        _inputDevices.value = audioDeviceManager.getInputDevices()
        _outputDevices.value = audioDeviceManager.getOutputDevices()
        _communicationDevices.value = audioDeviceManager.getCommunicationDevices()
        _currentCommunicationDevice.value =
            audioDeviceManager.getCurrentCommunicationDevice()
    }

    fun selectCommunicationDevice(device: AudioDeviceInfo): Boolean {
        _routingError.value = null

        return try {
            audioDeviceManager.beginCommunicationMode()
            val selected = audioDeviceManager.setCommunicationDevice(device)

            if (selected) {
                refreshAudioDevices()
            } else {
                _routingError.value = "Android did not accept this audio route."
            }

            selected
        } catch (e: SecurityException) {
            _routingError.value =
                "Bluetooth permission is required to select this device."
            false
        } catch (e: Exception) {
            _routingError.value =
                e.message ?: "Unable to select audio device."
            false
        }
    }

    fun clearCommunicationDevice() {
        audioDeviceManager.clearCommunicationDevice()
        audioDeviceManager.endCommunicationMode()
        refreshAudioDevices()
    }

    fun startAudio() {
        refreshAudioDevices()
        audioDeviceManager.beginCommunicationMode()

        val started = audioLoopback.start(viewModelScope)
        if (started) {
            _audioRunning.value = true
        }
    }

    fun stopAudio() {
        audioLoopback.stop()
        audioDeviceManager.clearCommunicationDevice()
        audioDeviceManager.endCommunicationMode()
        _audioRunning.value = false
        refreshAudioDevices()
    }

    fun inputDeviceName(device: AudioDeviceInfo): String =
        audioDeviceManager.getInputDeviceName(device)

    fun outputDeviceName(device: AudioDeviceInfo): String =
        audioDeviceManager.getOutputDeviceName(device)

    fun describeDevice(device: AudioDeviceInfo): String =
        audioDeviceManager.describeDevice(device)

    override fun onCleared() {
        audioLoopback.stop()
        audioDeviceManager.clearCommunicationDevice()
        audioDeviceManager.endCommunicationMode()
        super.onCleared()
    }
}
