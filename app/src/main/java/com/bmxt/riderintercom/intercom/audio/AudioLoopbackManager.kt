package com.bmxt.riderintercom.intercom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioLoopbackManager {
    companion object {
        private const val TAG = "AudioLoopback"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_FRAME_MS = 20
        private const val VAD_FRAME_SAMPLES = SAMPLE_RATE * VAD_FRAME_MS / 1000
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loopbackJob: Job? = null

    private val _voiceDetected = MutableStateFlow(false)
    val voiceDetected: StateFlow<Boolean> = _voiceDetected.asStateFlow()

    fun start(scope: CoroutineScope): Boolean {
        if (loopbackJob?.isActive == true) return false

        val minRecordBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING
        )
        val minTrackBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_OUT,
            ENCODING
        )

        if (minRecordBuffer <= 0 || minTrackBuffer <= 0) {
            Log.e(TAG, "Unable to determine audio buffer sizes")
            return false
        }

        val bufferSize = maxOf(
            minRecordBuffer,
            minTrackBuffer,
            VAD_FRAME_SAMPLES * 2
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioRecord?.startRecording()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start audio devices", e)
            stop()
            return false
        }

        val vad = VoiceActivityDetector()

        loopbackJob = scope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(bufferSize / 2)
            val vadFrame = ShortArray(VAD_FRAME_SAMPLES)
            var vadFrameSize = 0

            try {
                while (isActive) {
                    val read = audioRecord?.read(
                        readBuffer,
                        0,
                        readBuffer.size
                    ) ?: break

                    if (read > 0) {
                        audioTrack?.write(readBuffer, 0, read)

                        var sourceOffset = 0
                        while (sourceOffset < read) {
                            val copyCount = minOf(
                                VAD_FRAME_SAMPLES - vadFrameSize,
                                read - sourceOffset
                            )

                            readBuffer.copyInto(
                                destination = vadFrame,
                                destinationOffset = vadFrameSize,
                                startIndex = sourceOffset,
                                endIndex = sourceOffset + copyCount
                            )

                            vadFrameSize += copyCount
                            sourceOffset += copyCount

                            if (vadFrameSize == VAD_FRAME_SAMPLES) {
                                _voiceDetected.value = vad.process(vadFrame)
                                vadFrameSize = 0
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loopback failed", e)
            } finally {
                _voiceDetected.value = false
            }
        }

        return true
    }

    fun stop() {
        loopbackJob?.cancel()
        loopbackJob = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        _voiceDetected.value = false
    }
}
