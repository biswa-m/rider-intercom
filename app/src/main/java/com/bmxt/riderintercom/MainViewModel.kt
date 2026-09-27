package com.bmxt.riderintercom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bmxt.riderintercom.intercom.audio.AudioLoopbackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    private val audioLoopback = AudioLoopbackManager()

    private val _audioRunning = MutableStateFlow(false)
    val audioRunning: StateFlow<Boolean> = _audioRunning.asStateFlow()

    fun startAudio() {
        val started = audioLoopback.start(viewModelScope)

        if (started) {
            _audioRunning.value = true
        }
    }

    fun stopAudio() {
        audioLoopback.stop()
        _audioRunning.value = false
    }

    override fun onCleared() {
        audioLoopback.stop()
        super.onCleared()
    }
}