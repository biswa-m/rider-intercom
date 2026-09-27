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
import java.util.concurrent.atomic.AtomicInteger

/**
 * First two-phone networking audio engine.
 *
 * Capture:
 * Mic -> 20 ms PCM frame -> VAD -> UDP
 *
 * Playback:
 * UDP PCM frame -> AudioTrack -> Bluetooth SCO / communication output
 *
 * This intentionally uses raw PCM only to validate transport and latency.
 * Opus will replace the packet payload in the next codec milestone.
 */
class NetworkAudioManager {

    companion object {
        private const val TAG = "NetworkAudioManager"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_PORT = 45_000
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null

    private var transport: UdpVoiceTransport? = null
    private var transportScope: CoroutineScope? = null

    private val _voiceDetected = MutableStateFlow(false)
    val voiceDetected: StateFlow<Boolean> = _voiceDetected.asStateFlow()

    private val _networkState = MutableStateFlow("Stopped")
    val networkState: StateFlow<String> = _networkState.asStateFlow()

    private val _packetsSent = AtomicInteger(0)
    private val _packetsReceived = AtomicInteger(0)

    fun packetsSent(): Int = _packetsSent.get()
    fun packetsReceived(): Int = _packetsReceived.get()

    fun start(
        scope: CoroutineScope,
        peerHost: String,
        localPort: Int = DEFAULT_PORT,
        peerPort: Int = DEFAULT_PORT
    ): Boolean {
        if (audioJob?.isActive == true) return false
        if (peerHost.isBlank()) {
            _networkState.value = "Peer IP is required"
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
            _networkState.value = "Android could not determine audio buffer sizes"
            return false
        }

        val bufferSize = maxOf(
            minRecordBuffer,
            minTrackBuffer,
            FRAME_SAMPLES * 2
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize
        )

        val track = AudioTrack.Builder()
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
            record.startRecording()
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start network audio devices", e)
            try { record.release() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            _networkState.value =
                "Unable to start microphone/speaker: ${e.message ?: "unknown error"}"
            return false
        }

        audioRecord = record
        audioTrack = track
        transportScope = scope

        val udp = UdpVoiceTransport(
            localPort = localPort,
            peerHost = peerHost,
            peerPort = peerPort,
            onPacket = { packet ->
                _packetsReceived.incrementAndGet()
                try {
                    audioTrack?.write(
                        packet.samples,
                        0,
                        packet.samples.size
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Remote audio playback failed", e)
                }
            },
            onState = { state ->
                _networkState.value = state
            }
        )

        if (!udp.start(scope)) {
            stop()
            return false
        }

        transport = udp
        _packetsSent.set(0)
        _packetsReceived.set(0)
        _networkState.value = "Connected to $peerHost:$peerPort"

        val vad = VoiceActivityDetector()
        val readBuffer = ShortArray(maxOf(bufferSize / 2, FRAME_SAMPLES))
        val vadFrame = ShortArray(FRAME_SAMPLES)

        audioJob = scope.launch(Dispatchers.IO) {
            var vadFrameSize = 0

            try {
                while (isActive) {
                    val read = audioRecord?.read(
                        readBuffer,
                        0,
                        readBuffer.size
                    ) ?: break

                    if (read <= 0) continue

                    var sourceOffset = 0

                    while (sourceOffset < read) {
                        val copyCount = minOf(
                            FRAME_SAMPLES - vadFrameSize,
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

                        if (vadFrameSize == FRAME_SAMPLES) {
                            val speaking = vad.process(vadFrame)
                            _voiceDetected.value = speaking

                            if (speaking) {
                                transport?.send(vadFrame.copyOf())
                                _packetsSent.incrementAndGet()
                            }

                            vadFrameSize = 0
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Network audio capture failed", e)
                    _networkState.value =
                        "Audio capture error: ${e.message ?: "unknown error"}"
                }
            } finally {
                _voiceDetected.value = false
            }
        }

        return true
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null

        transport?.stop()
        transport = null
        transportScope = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}

        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null

        _voiceDetected.value = false
        _networkState.value = "Stopped"
        _packetsSent.set(0)
        _packetsReceived.set(0)
    }
}
