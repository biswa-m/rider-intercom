package com.bmxt.riderintercom

import android.app.Application
import android.media.AudioDeviceInfo
import com.bmxt.riderintercom.intercom.audio.LegacyBluetoothHeadset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmxt.riderintercom.intercom.audio.AudioDebugState
import com.bmxt.riderintercom.intercom.audio.AudioDeviceManager
import com.bmxt.riderintercom.intercom.audio.IntercomAudioService
import com.bmxt.riderintercom.intercom.audio.IntercomFeatureConfig
import com.bmxt.riderintercom.intercom.audio.NetworkAudioManager
import com.bmxt.riderintercom.intercom.audio.NetworkUtils
import com.bmxt.riderintercom.intercom.diagnostics.PingPongTestManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioDeviceManager =
        AudioDeviceManager(application) { refreshAudioDevices() }

    val audioRunning: StateFlow<Boolean> =
        IntercomAudioService.runningState

    val voiceDetected: StateFlow<Boolean> =
        IntercomAudioService.voiceDetectedState

    val debugState: StateFlow<AudioDebugState> =
        IntercomAudioService.debugState

    val localIpv4Addresses: List<String>
        get() = NetworkUtils.getLocalIpv4Addresses()

    val defaultNetworkPort: Int = NetworkAudioManager.DEFAULT_PORT

    private val pingPongTestManager = PingPongTestManager()

    val pingPongState: StateFlow<PingPongTestManager.State> =
        pingPongTestManager.state

    fun startPingPong(peerHost: String) {
        pingPongTestManager.start(viewModelScope, peerHost)
    }

    fun stopPingPong() {
        pingPongTestManager.stop()
    }

    private val _featureConfig =
        MutableStateFlow(IntercomFeatureConfig.default())
    val featureConfig: StateFlow<IntercomFeatureConfig> =
        _featureConfig.asStateFlow()

    fun setUseVad(enabled: Boolean) {
        _featureConfig.update {
            it.copy(
                useVad = enabled,
                useVadPreRoll = if (enabled) it.useVadPreRoll else false
            )
        }
    }

    fun setUseOpus(enabled: Boolean) {
        _featureConfig.update {
            it.copy(
                useOpus = enabled,
                useJitterBuffer = if (enabled) it.useJitterBuffer else false
            )
        }
    }

    fun setUseJitterBuffer(enabled: Boolean) {
        _featureConfig.update { it.copy(useJitterBuffer = enabled) }
    }

    fun setUseVadPreRoll(enabled: Boolean) {
        _featureConfig.update { it.copy(useVadPreRoll = enabled) }
    }

    fun setUseTimestampLatencyTest(enabled: Boolean) {
        _featureConfig.update { it.copy(useTimestampLatencyTest = enabled) }
    }

    fun setLatencyClockOffsetMs(offsetMs: Long) {
        _featureConfig.update { it.copy(latencyClockOffsetMs = offsetMs) }
    }

    fun setLatencySampleEveryPackets(value: Int) {
        _featureConfig.update {
            it.copy(latencySampleEveryPackets = value.coerceIn(1, 100))
        }
    }

    fun applyPingPongClockOffset() {
        pingPongTestManager.state.value.clockOffsetMs?.let { offset ->
            setLatencyClockOffsetMs(offset)
        }
    }

    fun resetFeatureConfig() {
        _featureConfig.value = IntercomFeatureConfig.default()
    }

    private val _inputDevices =
        MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val inputDevices: StateFlow<List<AudioDeviceInfo>> =
        _inputDevices.asStateFlow()

    private val _outputDevices =
        MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val outputDevices: StateFlow<List<AudioDeviceInfo>> =
        _outputDevices.asStateFlow()

    private val _legacyBluetoothHeadsets =
        MutableStateFlow<List<LegacyBluetoothHeadset>>(emptyList())
    val legacyBluetoothHeadsets: StateFlow<List<LegacyBluetoothHeadset>> =
        _legacyBluetoothHeadsets.asStateFlow()

    private val _legacyBluetoothScoActive = MutableStateFlow(false)
    val legacyBluetoothScoActive: StateFlow<Boolean> =
        _legacyBluetoothScoActive.asStateFlow()

    private val _communicationDevices =
        MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val communicationDevices: StateFlow<List<AudioDeviceInfo>> =
        _communicationDevices.asStateFlow()

    private val _currentCommunicationDevice =
        MutableStateFlow<AudioDeviceInfo?>(null)
    val currentCommunicationDevice: StateFlow<AudioDeviceInfo?> =
        _currentCommunicationDevice.asStateFlow()

    private val _routingError =
        MutableStateFlow<String?>(null)
    val routingError: StateFlow<String?> =
        _routingError.asStateFlow()

    fun refreshAudioDevices() {
        _inputDevices.value = audioDeviceManager.getInputDevices()
        _outputDevices.value = audioDeviceManager.getOutputDevices()
        _communicationDevices.value = audioDeviceManager.getCommunicationDevices()
        _legacyBluetoothHeadsets.value = audioDeviceManager.getLegacyBluetoothHeadsets()
        _legacyBluetoothScoActive.value = audioDeviceManager.isLegacyBluetoothScoOn()
        _currentCommunicationDevice.value =
            audioDeviceManager.getCurrentCommunicationDevice()
    }

    fun selectLegacyBluetoothSco(): Boolean {
        _routingError.value = null
        val started = audioDeviceManager.startLegacyBluetoothSco()
        if (!started) {
            _routingError.value =
                if (audioDeviceManager.getLegacyBluetoothHeadsets().isEmpty()) {
                    "No connected Bluetooth headset with a classic Headset/HFP profile was reported by Android."
                } else {
                    "Android could not start Bluetooth SCO."
                }
        }
        refreshAudioDevices()
        return started
    }

    fun selectCommunicationDevice(device: AudioDeviceInfo): Boolean {
        _routingError.value = null

        return try {
            audioDeviceManager.beginCommunicationMode()
            val selected = audioDeviceManager.setCommunicationDevice(device)

            if (selected) {
                refreshAudioDevices()
            } else {
                _routingError.value =
                    "Android did not accept this audio route."
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

        val deviceId =
            audioDeviceManager
                .getCurrentCommunicationDevice()
                ?.id
                ?: IntercomAudioService.NO_DEVICE_ID

        _routingError.value = null

        try {
            IntercomAudioService.start(
                context = getApplication(),
                communicationDeviceId = deviceId
            )
        } catch (e: SecurityException) {
            _routingError.value =
                "Microphone or Bluetooth permission is required to start intercom."
        } catch (e: Exception) {
            _routingError.value =
                e.message ?: "Unable to start intercom audio service."
        }
    }

    fun startIntercom(peerHost: String) {
        refreshAudioDevices()

        val deviceId =
            audioDeviceManager
                .getCurrentCommunicationDevice()
                ?.id
                ?: IntercomAudioService.NO_DEVICE_ID

        _routingError.value = null

        try {
            IntercomAudioService.start(
                context = getApplication(),
                communicationDeviceId = deviceId,
                peerHost = peerHost.trim(),
                localPort = NetworkAudioManager.DEFAULT_PORT,
                peerPort = NetworkAudioManager.DEFAULT_PORT,
                featureConfig = _featureConfig.value
            )
        } catch (e: SecurityException) {
            _routingError.value =
                "Microphone or Bluetooth permission is required to start intercom."
        } catch (e: Exception) {
            _routingError.value =
                e.message ?: "Unable to start network intercom."
        }
    }

    fun stopAudio() {
        IntercomAudioService.stop(getApplication())
        refreshAudioDevices()
    }

    fun inputDeviceName(device: AudioDeviceInfo): String =
        audioDeviceManager.getInputDeviceName(device)

    fun outputDeviceName(device: AudioDeviceInfo): String =
        audioDeviceManager.getOutputDeviceName(device)

    fun describeDevice(device: AudioDeviceInfo): String =
        audioDeviceManager.describeDevice(device)

    override fun onCleared() {
        pingPongTestManager.close()
        audioDeviceManager.close()
        // The foreground service owns the audio lifecycle.
        // Do not stop audio when the Activity/ViewModel is recreated.
        super.onCleared()
    }
}
