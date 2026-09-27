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
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-phone networking audio engine.
 *
 * Mic -> PCM -> VAD -> Opus -> UDP
 * UDP -> Opus -> PCM -> AudioTrack
 */
class NetworkAudioManager {
    companion object {
        private const val TAG = "NetworkAudioManager"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_PORT = 45_000
        private const val DEBUG_EMIT_INTERVAL_MS = 100L
        private const val RX_ACTIVE_WINDOW_MS = 750L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var transport: UdpVoiceTransport? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null

    private val _voiceDetected = MutableStateFlow(false)
    val voiceDetected: StateFlow<Boolean> = _voiceDetected.asStateFlow()

    private val _networkState = MutableStateFlow("Stopped")
    val networkState: StateFlow<String> = _networkState.asStateFlow()

    private val _debugState = MutableStateFlow(AudioDebugState())
    val debugState: StateFlow<AudioDebugState> = _debugState.asStateFlow()

    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val malformedPackets = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val encodedFrames = AtomicLong(0)
    private val encodeNoOutputFrames = AtomicLong(0)
    private val decodedFrames = AtomicLong(0)
    private val decodeNoOutputFrames = AtomicLong(0)
    private val playbackSamples = AtomicLong(0)
    private val playbackWriteFailures = AtomicLong(0)

    @Volatile private var micLevelDb = -96f
    @Volatile private var remoteLevelDb = -96f
    @Volatile private var transmittingAudio = false
    @Volatile private var lastReceivedAtMs = 0L
    @Volatile private var lastSentPayloadBytes = 0
    @Volatile private var lastReceivedPayloadBytes = 0
    @Volatile private var lastReceivedSequence = -1
    @Volatile private var lastError: String? = null
    @Volatile private var peer = ""
    @Volatile private var lastDebugEmitMs = 0L
    @Volatile private var playbackSampleRate = 0
    @Volatile private var playbackChannelCount = 0

    fun start(
        scope: CoroutineScope,
        peerHost: String,
        localPort: Int = DEFAULT_PORT,
        peerPort: Int = DEFAULT_PORT
    ): Boolean {
        if (audioJob?.isActive == true) return false
        if (peerHost.isBlank()) {
            setError("Peer IP is required")
            return false
        }

        val minRecordBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING
            )

        if (minRecordBuffer <= 0) {
            setError("Android could not determine microphone buffer size")
            return false
        }

        val bufferSize =
            maxOf(
                minRecordBuffer,
                FRAME_BYTES * 2
            )

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize
        )

        val opusEncoder = OpusEncoder()
        val opusDecoder = OpusDecoder()

        try {
            opusEncoder.start()
            opusDecoder.start()
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start Opus/audio devices", e)
            opusEncoder.stop()
            opusDecoder.stop()
            try { record.release() } catch (_: Exception) {}
            setError("Unable to start Opus/microphone/speaker: ${e.message ?: "unknown error"}")
            return false
        }

        resetStats()
        peer = "$peerHost:$peerPort"
        encoder = opusEncoder
        decoder = opusDecoder
        audioRecord = record
        audioTrack = null

        val udp = UdpVoiceTransport(
            localPort = localPort,
            peerHost = peerHost,
            peerPort = peerPort,
            onPacket = { packet, packetBytes ->
                packetsReceived.incrementAndGet()
                bytesReceived.addAndGet(packetBytes.toLong())
                lastReceivedAtMs = System.currentTimeMillis()
                lastReceivedPayloadBytes = packet.payload.size
                lastReceivedSequence = packet.sequence

                try {
                    val decodedFrames = decoder?.decode(packet.payload).orEmpty()
                    if (decodedFrames.isEmpty()) {
                        decodeNoOutputFrames.incrementAndGet()
                    }

                    decodedFrames.forEach { decoded ->
                        remoteLevelDb =
                            AudioLevelUtils.rmsDb(
                                decoded.samples
                            )

                        val trackInstance =
                            ensureAudioTrack(
                                sampleRate =
                                    decoded.sampleRate,
                                channelCount =
                                    decoded.channelCount
                            )

                        if (trackInstance == null) {
                            playbackWriteFailures.incrementAndGet()
                            return@forEach
                        }

                        val written = trackInstance.write(
                            decoded.samples,
                            0,
                            decoded.samples.size,
                            AudioTrack.WRITE_BLOCKING
                        )

                        if (written > 0) {
                            this@NetworkAudioManager.decodedFrames
                                .incrementAndGet()
                            playbackSamples
                                .addAndGet(written.toLong())
                        } else {
                            playbackWriteFailures.incrementAndGet()
                            setError(
                                "AudioTrack write failed: return code $written"
                            )
                        }
                    }
                    publishDebug(force = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Remote Opus playback failed", e)
                    setError("Opus decode/playback error: ${e.message ?: "unknown error"}")
                }
            },
            onMalformedPacket = { packetBytes ->
                malformedPackets.incrementAndGet()
                setError("Received malformed UDP packet ($packetBytes bytes)")
                publishDebug(force = true)
            },
            onState = { state ->
                _networkState.value = state
                publishDebug(force = true)
            },
            onError = { message ->
                setError(message)
                publishDebug(force = true)
            }
        )

        if (!udp.start(scope)) {
            opusEncoder.stop()
            opusDecoder.stop()
            stop()
            return false
        }

        transport = udp
        _networkState.value = "Opus ready → $peer"
        _voiceDetected.value = false
        publishDebug(force = true)

        val vad = VoiceActivityDetector()
        val readBuffer = ShortArray(maxOf(bufferSize / 2, FRAME_SAMPLES))
        val vadFrame = ShortArray(FRAME_SAMPLES)

        audioJob = scope.launch(Dispatchers.IO) {
            var vadFrameSize = 0
            try {
                while (isActive) {
                    val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
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
                            micLevelDb = AudioLevelUtils.rmsDb(vadFrame)
                            val speaking = vad.process(vadFrame)
                            _voiceDetected.value = speaking
                            transmittingAudio = false

                            if (speaking) {
                                val opusData = try {
                                    encoder?.encode(vadFrame)
                                } catch (e: Exception) {
                                    setError("Opus encode error: ${e.message ?: "unknown error"}")
                                    null
                                }

                                if (opusData == null) {
                                    encodeNoOutputFrames.incrementAndGet()
                                } else {
                                    encodedFrames.incrementAndGet()
                                    val bytes = transport?.send(
                                        sampleCount = FRAME_SAMPLES,
                                        opusData = opusData
                                    ) ?: 0
                                    if (bytes > 0) {
                                        packetsSent.incrementAndGet()
                                        bytesSent.addAndGet(bytes.toLong())
                                        lastSentPayloadBytes = opusData.size
                                        transmittingAudio = true
                                    }
                                }
                            }

                            publishDebug()
                            vadFrameSize = 0
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Network audio capture failed", e)
                    setError("Audio capture/Opus error: ${e.message ?: "unknown error"}")
                    publishDebug(force = true)
                }
            } finally {
                _voiceDetected.value = false
                transmittingAudio = false
                publishDebug(force = true)
            }
        }

        return true
    }

    private fun ensureAudioTrack(
        sampleRate: Int,
        channelCount: Int
    ): AudioTrack? {
        if (sampleRate <= 0 || channelCount <= 0) {
            setError(
                "Invalid decoder output format: " +
                    "$sampleRate Hz / $channelCount ch"
            )
            return null
        }

        val existing = audioTrack

        if (
            existing != null &&
            playbackSampleRate == sampleRate &&
            playbackChannelCount == channelCount
        ) {
            return existing
        }

        try {
            existing?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }

            val channelMask =
                when (channelCount) {
                    1 -> CHANNEL_OUT
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> {
                        setError(
                            "Unsupported decoder channel count: " +
                                channelCount
                        )
                        audioTrack = null
                        playbackSampleRate = 0
                        playbackChannelCount = 0
                        return null
                    }
                }

            val minTrackBuffer =
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    ENCODING
                )

            if (minTrackBuffer <= 0) {
                setError(
                    "Android could not determine playback buffer size for " +
                        "$sampleRate Hz / $channelCount ch"
                )
                audioTrack = null
                playbackSampleRate = 0
                playbackChannelCount = 0
                return null
            }

            val trackBuffer =
                maxOf(
                    minTrackBuffer,
                    FRAME_BYTES * 4
                )

            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_VOICE_COMMUNICATION
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(ENCODING)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                setError(
                    "AudioTrack is not initialized: " +
                        "$sampleRate Hz / $channelCount ch"
                )
                audioTrack = null
                playbackSampleRate = 0
                playbackChannelCount = 0
                return null
            }

            track.play()

            audioTrack = track
            playbackSampleRate = sampleRate
            playbackChannelCount = channelCount

            Log.i(
                TAG,
                "Created network AudioTrack: " +
                    "$sampleRate Hz / $channelCount ch"
            )

            return track
        } catch (e: Exception) {
            setError(
                "Unable to create playback AudioTrack: " +
                    (e.message ?: "unknown error")
            )
            audioTrack = null
            playbackSampleRate = 0
            playbackChannelCount = 0
            return null
        }
    }

    private fun resetStats() {
        packetsSent.set(0)
        packetsReceived.set(0)
        malformedPackets.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        encodedFrames.set(0)
        encodeNoOutputFrames.set(0)
        decodedFrames.set(0)
        decodeNoOutputFrames.set(0)
        playbackSamples.set(0)
        playbackWriteFailures.set(0)
        micLevelDb = -96f
        remoteLevelDb = -96f
        transmittingAudio = false
        lastReceivedAtMs = 0L
        lastSentPayloadBytes = 0
        lastReceivedPayloadBytes = 0
        lastReceivedSequence = -1
        lastError = null
        lastDebugEmitMs = 0L
        playbackSampleRate = 0
        playbackChannelCount = 0
        _debugState.value = AudioDebugState()
    }

    private fun setError(message: String) {
        lastError = message
        _networkState.value = message
        Log.e(TAG, message)
    }

    private fun publishDebug(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDebugEmitMs < DEBUG_EMIT_INTERVAL_MS) return
        lastDebugEmitMs = now

        val receiving = lastReceivedAtMs > 0L && now - lastReceivedAtMs <= RX_ACTIVE_WINDOW_MS
        _debugState.update {
            it.copy(
                mode = "Network / Opus",
                networkState = _networkState.value,
                micLevelDb = micLevelDb,
                remoteLevelDb = if (receiving) remoteLevelDb else -96f,
                voiceDetected = _voiceDetected.value,
                transmittingAudio = transmittingAudio,
                receivingAudio = receiving,
                packetsSent = packetsSent.get(),
                packetsReceived = packetsReceived.get(),
                malformedPackets = malformedPackets.get(),
                bytesSent = bytesSent.get(),
                bytesReceived = bytesReceived.get(),
                encodedFrames = encodedFrames.get(),
                encodeNoOutputFrames = encodeNoOutputFrames.get(),
                decodedFrames = decodedFrames.get(),
                decodeNoOutputFrames = decodeNoOutputFrames.get(),
                playbackSamples = playbackSamples.get(),
                playbackWriteFailures = playbackWriteFailures.get(),
                lastReceivedAtMs = lastReceivedAtMs,
                lastSentPayloadBytes = lastSentPayloadBytes,
                lastReceivedPayloadBytes = lastReceivedPayloadBytes,
                lastReceivedSequence = lastReceivedSequence,
                audioRecordState =
                    audioRecord?.recordingState?.let(::recordStateName)
                        ?: "Null",
                audioTrackState =
                    audioTrack?.playState?.let(::playStateName)
                        ?: "Null",
                playbackSampleRate = playbackSampleRate,
                playbackChannelCount = playbackChannelCount,
                opusEncoderName = encoder?.codecName ?: "Not started",
                opusDecoderName = decoder?.codecName ?: "Not started",
                lastError = lastError
            )
        }
    }

    private fun recordStateName(value: Int): String = when (value) {
        AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
        AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
        else -> "UNKNOWN($value)"
    }

    private fun playStateName(value: Int): String = when (value) {
        AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
        AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
        AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
        else -> "UNKNOWN($value)"
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
        transport?.stop()
        transport = null
        encoder?.stop()
        encoder = null
        decoder?.stop()
        decoder = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        playbackSampleRate = 0
        playbackChannelCount = 0

        _voiceDetected.value = false
        _networkState.value = "Stopped"
        transmittingAudio = false
        publishDebug(force = true)
    }
}
