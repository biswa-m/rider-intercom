package com.bmxt.riderintercom.intercom.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioLoopbackManager {

    companion object {
        private const val TAG = "AudioLoopback"

        private const val SAMPLE_RATE = 16_000

        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO

        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var loopbackJob: Job? = null

    fun start(scope: CoroutineScope): Boolean {
        if (loopbackJob?.isActive == true) {
            return false
        }

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
            minTrackBuffer
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

        audioRecord?.startRecording()
        audioTrack?.play()

        loopbackJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize / 2)

            try {
                while (isActive) {
                    val read = audioRecord?.read(
                        buffer,
                        0,
                        buffer.size
                    ) ?: break

                    if (read > 0) {
                        audioTrack?.write(
                            buffer,
                            0,
                            read
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loopback failed", e)
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
    }
}