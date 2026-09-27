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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Two-phone networking audio engine.
 *
 * Baseline mode (default):
 * Mic -> 16 kHz mono PCM -> UDP -> 16 kHz mono PCM -> AudioTrack
 *
 * Optional features can be enabled with [IntercomFeatureConfig].
 */
class NetworkAudioManager(
    private val config: IntercomFeatureConfig = IntercomFeatureConfig.default()
) {
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
        private const val JITTER_TARGET_PACKETS = 2
        private const val JITTER_MAX_PACKETS = 6
        private const val PRE_ROLL_FRAMES = 5 // 100 ms
        private const val DIRECT_RX_QUEUE_MAX = 20
        private const val DECODED_PCM_QUEUE_MAX = 6
        private const val PLAYBACK_POLL_MS = 2L
        private const val PLAYBACK_WAIT_MS = 3L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var playbackJob: Job? = null
    private var decoderJob: Job? = null
    private var transport: UdpVoiceTransport? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null

    private val jitterBuffer = OpusJitterBuffer(
        targetPackets = JITTER_TARGET_PACKETS,
        maxPackets = JITTER_MAX_PACKETS
    )

    private sealed interface DirectRxPacket {
        data class Pcm(val packet: PcmVoicePacket) : DirectRxPacket
        data class Opus(val packet: OpusVoicePacket) : DirectRxPacket
    }

    /** Small handoff queue, not a deliberate jitter buffer. It is bounded to avoid latency growth. */
    private val directRxQueue = ArrayDeque<DirectRxPacket>(DIRECT_RX_QUEUE_MAX)
    private val directRxQueueLock = Any()
    private var directRxDropped = 0L

    private data class DecodedPcmFrame(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int
    )

    private val decodedPcmQueue = ArrayDeque<DecodedPcmFrame>(DECODED_PCM_QUEUE_MAX)
    private val decodedPcmQueueLock = Any()
    private var decodedPcmDropped = 0L

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
    private val playbackUnderruns = AtomicLong(0)

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

        val minRecordBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING
        )

        if (minRecordBuffer <= 0) {
            setError("Android could not determine microphone buffer size")
            return false
        }

        val bufferSize = maxOf(
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

        val opusEncoder = if (config.useOpus) OpusEncoder() else null
        val opusDecoder = if (config.useOpus) OpusDecoder() else null

        try {
            opusEncoder?.start()
            opusDecoder?.start()
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start audio devices", e)
            opusEncoder?.stop()
            opusDecoder?.stop()
            try { record.release() } catch (_: Exception) {}
            setError("Unable to start audio/Opus: ${e.message ?: "unknown error"}")
            return false
        }

        resetStats()
        jitterBuffer.reset()
        synchronized(directRxQueueLock) {
            directRxQueue.clear()
            directRxDropped = 0L
        }
        synchronized(decodedPcmQueueLock) {
            decodedPcmQueue.clear()
            decodedPcmDropped = 0L
        }
        peer = "$peerHost:$peerPort"
        encoder = opusEncoder
        decoder = opusDecoder
        audioRecord = record
        audioTrack = null

        val udp = UdpVoiceTransport(
            localPort = localPort,
            peerHost = peerHost,
            peerPort = peerPort,
            onPacket = { received ->
                packetsReceived.incrementAndGet()
                bytesReceived.addAndGet(
                    when (received) {
                        is ReceivedVoicePacket.Pcm -> received.packetBytes
                        is ReceivedVoicePacket.Opus -> received.packetBytes
                    }.toLong()
                )

                val sequence: Int
                val payloadBytes: Int
                when (received) {
                    is ReceivedVoicePacket.Pcm -> {
                        sequence = received.packet.sequence
                        payloadBytes = received.packet.samples.size * 2

                        if (config.useOpus) {
                            setError("Received PCM but this phone expects Opus. Use the same feature settings on both phones.")
                        } else {
                            enqueueDirectPacket(DirectRxPacket.Pcm(received.packet))
                        }
                    }

                    is ReceivedVoicePacket.Opus -> {
                        sequence = received.packet.sequence
                        payloadBytes = received.packet.payload.size

                        if (!config.useOpus) {
                            setError("Received Opus but this phone expects PCM. Use the same feature settings on both phones.")
                        } else if (config.effectiveJitterBuffer) {
                            jitterBuffer.offer(received.packet)
                        } else {
                            enqueueDirectPacket(DirectRxPacket.Opus(received.packet))
                        }
                    }
                }

                lastReceivedAtMs = System.currentTimeMillis()
                lastReceivedPayloadBytes = payloadBytes
                lastReceivedSequence = sequence
                publishDebug()
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
            opusEncoder?.stop()
            opusDecoder?.stop()
            stop()
            return false
        }

        transport = udp
        _networkState.value = "${modeLabel()} ready → $peer"
        _voiceDetected.value = false
        publishDebug(force = true)

        startPlaybackLoop(scope)

        val vad = if (config.useVad) VoiceActivityDetector() else null
        val readBuffer = ShortArray(maxOf(bufferSize / 2, FRAME_SAMPLES))
        val vadFrame = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)

        audioJob = scope.launch(Dispatchers.IO) {
            var vadFrameSize = 0
            var wasSpeaking = false

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

                            if (vad == null) {
                                // Baseline mode: VAD is disabled, so every frame is sent.
                                _voiceDetected.value = false
                                sendAudioFrame(vadFrame)
                            } else {
                                val speaking = vad.process(vadFrame)
                                _voiceDetected.value = speaking
                                transmittingAudio = false

                                if (config.effectiveVadPreRoll) {
                                    val frameCopy = vadFrame.copyOf()
                                    preRoll.addLast(frameCopy)
                                    while (preRoll.size > PRE_ROLL_FRAMES) {
                                        preRoll.removeFirst()
                                    }
                                }

                                if (speaking) {
                                    if (!wasSpeaking && config.effectiveVadPreRoll) {
                                        preRoll.forEach { frame -> sendAudioFrame(frame) }
                                    } else {
                                        sendAudioFrame(vadFrame)
                                    }
                                }

                                wasSpeaking = speaking
                            }

                            publishDebug()
                            vadFrameSize = 0
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Network audio capture failed", e)
                    setError("Audio capture/network error: ${e.message ?: "unknown error"}")
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

    private fun sendAudioFrame(frame: ShortArray) {
        val bytes = try {
            if (config.useOpus) {
                val opusData = encoder?.encode(frame)
                if (opusData == null) {
                    encodeNoOutputFrames.incrementAndGet()
                    return
                }

                encodedFrames.incrementAndGet()
                val sentBytes = transport?.sendOpus(
                    sampleCount = frame.size,
                    opusData = opusData
                ) ?: 0

                if (sentBytes > 0) {
                    lastSentPayloadBytes = opusData.size
                }

                sentBytes
            } else {
                val sentBytes = transport?.sendPcm(frame) ?: 0
                if (sentBytes > 0) {
                    lastSentPayloadBytes = frame.size * 2
                }
                sentBytes
            }
        } catch (e: Exception) {
            setError(
                if (config.useOpus) {
                    "Opus encode/send error: ${e.message ?: "unknown error"}"
                } else {
                    "PCM send error: ${e.message ?: "unknown error"}"
                }
            )
            0
        }

        if (bytes > 0) {
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(bytes.toLong())
            transmittingAudio = true
        }
    }

    private fun enqueueDirectPacket(packet: DirectRxPacket) {
        synchronized(directRxQueueLock) {
            if (directRxQueue.size >= DIRECT_RX_QUEUE_MAX) {
                directRxQueue.removeFirst()
                directRxDropped++
            }
            directRxQueue.addLast(packet)
        }
    }

    private fun pollDirectPacket(): DirectRxPacket? = synchronized(directRxQueueLock) {
        if (directRxQueue.isEmpty()) null else directRxQueue.removeFirst()
    }

    private fun startPlaybackLoop(scope: CoroutineScope) {
        playbackJob?.cancel()
        decoderJob?.cancel()

        when {
            config.effectiveJitterBuffer -> {
                playbackJob = scope.launch(Dispatchers.IO) { runJitterPlaybackLoop() }
            }

            config.useOpus -> {
                // Diagnostic/low-latency Opus path: network receive, decode and
                // playback are separate stages. This prevents AudioTrack.write()
                // from blocking the UDP packet consumer and overflowing the
                // compressed-packet queue.
                startOpusDirectPipelines(scope)
            }

            else -> {
                playbackJob = scope.launch(Dispatchers.IO) { runDirectPlaybackLoop() }
            }
        }
    }

    private fun startOpusDirectPipelines(scope: CoroutineScope) {
        decoderJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val packet = pollDirectPacket()
                    if (packet !is DirectRxPacket.Opus) {
                        delay(PLAYBACK_POLL_MS)
                        continue
                    }

                    val decodedFrames = try {
                        decoder?.decode(packet.packet.payload).orEmpty()
                    } catch (e: Exception) {
                        setError("Opus decode error: ${e.message ?: "unknown error"}")
                        emptyList()
                    }

                    if (decodedFrames.isEmpty()) {
                        decodeNoOutputFrames.incrementAndGet()
                    } else {
                        for (decoded in decodedFrames) {
                            enqueueDecodedPcmFrame(
                                DecodedPcmFrame(
                                    samples = decoded.samples,
                                    sampleRate = decoded.sampleRate,
                                    channelCount = decoded.channelCount
                                )
                            )
                        }
                    }
                    publishDebug()
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Opus decoder loop failed", e)
                    setError("Opus decoder loop error: ${e.message ?: "unknown error"}")
                    publishDebug(force = true)
                }
            }
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = pollDecodedPcmFrame()
                    if (frame == null) {
                        delay(PLAYBACK_POLL_MS)
                        continue
                    }

                    val played = playDecodedPcm(frame)
                    if (!played) {
                        playbackUnderruns.incrementAndGet()
                    }
                    publishDebug()
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Opus PCM playback loop failed", e)
                    setError("Opus playback error: ${e.message ?: "unknown error"}")
                    publishDebug(force = true)
                }
            }
        }
    }

    private fun enqueueDecodedPcmFrame(frame: DecodedPcmFrame) {
        synchronized(decodedPcmQueueLock) {
            if (decodedPcmQueue.size >= DECODED_PCM_QUEUE_MAX) {
                // Drop the oldest decoded frame to keep latency bounded.
                decodedPcmQueue.removeFirst()
                decodedPcmDropped++
            }
            decodedPcmQueue.addLast(frame)
        }
    }

    private fun pollDecodedPcmFrame(): DecodedPcmFrame? =
        synchronized(decodedPcmQueueLock) {
            if (decodedPcmQueue.isEmpty()) null
            else decodedPcmQueue.removeFirst()
        }

    private fun playDecodedPcm(frame: DecodedPcmFrame): Boolean {
        if (frame.samples.isEmpty()) return false

        remoteLevelDb = AudioLevelUtils.rmsDb(frame.samples)
        val track = ensureAudioTrack(
            sampleRate = frame.sampleRate,
            channelCount = frame.channelCount
        ) ?: return false

        val written = track.write(
            frame.samples,
            0,
            frame.samples.size,
            AudioTrack.WRITE_BLOCKING
        )

        if (written > 0) {
            decodedFrames.incrementAndGet()
            playbackSamples.addAndGet(written.toLong())
            return true
        }

        playbackWriteFailures.incrementAndGet()
        setError("AudioTrack write failed: return code $written")
        return false
    }

    private suspend fun runDirectPlaybackLoop() {
        var waitingForPacket = false

        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val packet = pollDirectPacket()
                if (packet == null) {
                    if (!waitingForPacket) {
                        waitingForPacket = true
                    }
                    delay(PLAYBACK_POLL_MS)
                    continue
                }

                waitingForPacket = false
                val played = when (packet) {
                    is DirectRxPacket.Pcm -> playPcm(packet.packet.samples)
                    is DirectRxPacket.Opus -> playOpus(packet.packet)
                }

                if (!played) {
                    playbackUnderruns.incrementAndGet()
                }
                publishDebug()
            }
        } catch (e: Exception) {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                Log.e(TAG, "Direct network playback loop failed", e)
                setError("Network playback error: ${e.message ?: "unknown error"}")
                publishDebug(force = true)
            }
        }
    }

    private suspend fun runJitterPlaybackLoop() {
        var primed = false
        var waitingForPacket = false
        var nextPlaybackDeadlineNs = 0L

        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (!primed) {
                    if (jitterBuffer.startIfReady()) {
                        primed = true
                        waitingForPacket = false
                        nextPlaybackDeadlineNs = 0L
                        _networkState.value = "RX jitter buffer primed"
                        publishDebug(force = true)
                    } else {
                        delay(PLAYBACK_WAIT_MS)
                        continue
                    }
                }

                when (val result = jitterBuffer.pollNext()) {
                    OpusJitterBuffer.PollResult.Wait -> {
                        if (jitterBuffer.isEmpty()) {
                            if (!waitingForPacket) {
                                playbackUnderruns.incrementAndGet()
                                waitingForPacket = true
                            }
                        }
                        delay(PLAYBACK_WAIT_MS)
                        continue
                    }

                    OpusJitterBuffer.PollResult.MissingFrame -> {
                        waitingForPacket = false
                        writeSilenceFrame()
                    }

                    is OpusJitterBuffer.PollResult.Packet -> {
                        waitingForPacket = false
                        val played = playOpus(result.value)
                        if (!played) {
                            playbackUnderruns.incrementAndGet()
                        }
                    }
                }

                val nowNs = System.nanoTime()
                if (
                    nextPlaybackDeadlineNs == 0L ||
                    nowNs - nextPlaybackDeadlineNs > 100_000_000L
                ) {
                    nextPlaybackDeadlineNs = nowNs
                }
                nextPlaybackDeadlineNs += FRAME_MS * 1_000_000L
                val remainingNs = nextPlaybackDeadlineNs - System.nanoTime()
                if (remainingNs > 0L) {
                    delay(remainingNs / 1_000_000L)
                }

                publishDebug()
            }
        } catch (e: Exception) {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                Log.e(TAG, "Jitter network playback loop failed", e)
                setError("Network playback error: ${e.message ?: "unknown error"}")
                publishDebug(force = true)
            }
        }
    }

    private fun playOpus(packet: OpusVoicePacket): Boolean {
        val decodedFrames = try {
            decoder?.decode(packet.payload).orEmpty()
        } catch (e: Exception) {
            setError("Opus decode error: ${e.message ?: "unknown error"}")
            emptyList()
        }

        if (decodedFrames.isEmpty()) {
            decodeNoOutputFrames.incrementAndGet()
            if (config.effectiveJitterBuffer) {
                writeSilenceFrame()
            }
            return false
        }

        var wroteAny = false
        for (decoded in decodedFrames) {
            remoteLevelDb = AudioLevelUtils.rmsDb(decoded.samples)
            val trackInstance = ensureAudioTrack(
                sampleRate = decoded.sampleRate,
                channelCount = decoded.channelCount
            ) ?: continue

            val written = trackInstance.write(
                decoded.samples,
                0,
                decoded.samples.size,
                AudioTrack.WRITE_BLOCKING
            )

            if (written > 0) {
                this@NetworkAudioManager.decodedFrames.incrementAndGet()
                playbackSamples.addAndGet(written.toLong())
                wroteAny = true
            } else {
                playbackWriteFailures.incrementAndGet()
                setError("AudioTrack write failed: return code $written")
            }
        }
        return wroteAny
    }

    private fun playPcm(samples: ShortArray): Boolean {
        if (samples.isEmpty()) return false

        remoteLevelDb = AudioLevelUtils.rmsDb(samples)
        val track = ensureAudioTrack(
            sampleRate = SAMPLE_RATE,
            channelCount = 1
        ) ?: return false

        val written = track.write(
            samples,
            0,
            samples.size,
            AudioTrack.WRITE_BLOCKING
        )

        if (written > 0) {
            playbackSamples.addAndGet(written.toLong())
            return true
        }

        playbackWriteFailures.incrementAndGet()
        setError("AudioTrack write failed: return code $written")
        return false
    }

    /** Writes one 20 ms silent output frame when a packet is lost or the decoder yields no PCM. */
    private fun writeSilenceFrame() {
        val sampleRate = playbackSampleRate
        val channelCount = playbackChannelCount
        if (sampleRate <= 0 || channelCount <= 0) return

        val samplesPerFrame = sampleRate * FRAME_MS / 1000
        val silence = ShortArray(samplesPerFrame * channelCount)
        val track = ensureAudioTrack(sampleRate, channelCount) ?: return
        val written = track.write(
            silence,
            0,
            silence.size,
            AudioTrack.WRITE_BLOCKING
        )
        if (written <= 0) {
            playbackWriteFailures.incrementAndGet()
        } else {
            playbackSamples.addAndGet(written.toLong())
        }
    }

    private fun ensureAudioTrack(
        sampleRate: Int,
        channelCount: Int
    ): AudioTrack? {
        if (sampleRate <= 0 || channelCount <= 0) {
            setError(
                "Invalid playback format: " +
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

            val channelMask = when (channelCount) {
                1 -> CHANNEL_OUT
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> {
                    setError("Unsupported playback channel count: $channelCount")
                    audioTrack = null
                    playbackSampleRate = 0
                    playbackChannelCount = 0
                    return null
                }
            }

            val minTrackBuffer = AudioTrack.getMinBufferSize(
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

            val samplesPerFrame = sampleRate * FRAME_MS / 1000
            val oneFrameBytes = samplesPerFrame * channelCount * 2
            val trackBuffer = maxOf(minTrackBuffer, oneFrameBytes * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                setError("AudioTrack is not initialized: $sampleRate Hz / $channelCount ch")
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
                "Created network AudioTrack: $sampleRate Hz / $channelCount ch"
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

    private fun modeLabel(): String = when {
        config.useOpus && config.effectiveJitterBuffer && config.useVad -> "VAD/Opus/jitter"
        config.useOpus && config.effectiveJitterBuffer -> "Opus/jitter"
        config.useOpus && config.useVad -> "VAD/Opus"
        config.useOpus -> "Opus"
        config.useVad -> "VAD/PCM"
        else -> "PCM"
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
        playbackUnderruns.set(0)
        synchronized(decodedPcmQueueLock) {
            decodedPcmDropped = 0L
        }
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
        val jitter = jitterBuffer.snapshot()

        val queueDropped = synchronized(directRxQueueLock) { directRxDropped }
        val queueDepth = synchronized(directRxQueueLock) { directRxQueue.size }
        val decodedQueueDropped = synchronized(decodedPcmQueueLock) { decodedPcmDropped }
        val decodedQueueDepth = synchronized(decodedPcmQueueLock) { decodedPcmQueue.size }

        _debugState.update {
            it.copy(
                mode = modeLabel(),
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
                jitterBufferedPackets = if (config.effectiveJitterBuffer) jitter.bufferedPackets else queueDepth,
                jitterTargetPackets = if (config.effectiveJitterBuffer) JITTER_TARGET_PACKETS else 0,
                jitterMaxPackets = if (config.effectiveJitterBuffer) JITTER_MAX_PACKETS else DIRECT_RX_QUEUE_MAX,
                estimatedLostPackets = if (config.effectiveJitterBuffer) jitter.lostPackets else queueDropped,
                latePackets = if (config.effectiveJitterBuffer) jitter.latePackets else 0L,
                overflowDroppedPackets = if (config.effectiveJitterBuffer) jitter.overflowDroppedPackets else queueDropped,
                decodedPcmQueueDepth = decodedQueueDepth,
                decodedPcmQueueDropped = decodedQueueDropped,
                jitterResyncs = if (config.effectiveJitterBuffer) jitter.resyncCount else 0L,
                playbackUnderruns = playbackUnderruns.get(),
                audioRecordState = audioRecord?.recordingState?.let(::recordStateName) ?: "Null",
                audioTrackState = audioTrack?.playState?.let(::playStateName) ?: "Null",
                playbackSampleRate = playbackSampleRate,
                playbackChannelCount = playbackChannelCount,
                opusEncoderName = encoder?.codecName ?: if (config.useOpus) "Not started" else "Disabled",
                opusDecoderName = decoder?.codecName ?: if (config.useOpus) "Not started" else "Disabled",
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
        playbackJob?.cancel()
        playbackJob = null
        decoderJob?.cancel()
        decoderJob = null
        transport?.stop()
        transport = null
        encoder?.stop()
        encoder = null
        decoder?.stop()
        decoder = null
        jitterBuffer.reset()

        synchronized(directRxQueueLock) {
            directRxQueue.clear()
            directRxDropped = 0L
        }
        synchronized(decodedPcmQueueLock) {
            decodedPcmQueue.clear()
            decodedPcmDropped = 0L
        }

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
