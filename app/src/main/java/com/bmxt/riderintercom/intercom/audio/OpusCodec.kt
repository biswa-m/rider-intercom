package com.bmxt.riderintercom.intercom.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time Opus codec using Android's MediaCodec API.
 *
 * The decoder is configured with Opus codec-specific data (csd-0/csd-1/csd-2)
 * because Android's platform Opus decoder expects an OpusHead header before raw
 * Opus access units.
 */
class OpusEncoder {
    companion object {
        private const val TAG = "OpusEncoder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val BITRATE = 24_000
        private const val MAX_OUTPUT_SIZE = 4_000
        private const val DEQUEUE_TIMEOUT_US = 20_000L
    }

    private var codec: MediaCodec? = null
    private var _codecName = "Not started"
    private var presentationTimeUs = 0L

    val codecName: String
        get() = _codecName

    fun start() {
        if (codec != null) return

        val format = MediaFormat.createAudioFormat(
            MIME,
            NetworkAudioManager.SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                NetworkAudioManager.FRAME_BYTES
            )
            setInteger(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }

        val codecName = findEncoderName(format)
            ?: throw IllegalStateException(
                "No Android Opus encoder is available for " +
                    "${NetworkAudioManager.SAMPLE_RATE} Hz mono"
            )

        val encoder = MediaCodec.createByCodecName(codecName)

        try {
            encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()

            codec = encoder
            _codecName = codecName
            presentationTimeUs = 0L

            Log.i(TAG, "Started encoder: $codecName")
        } catch (e: Exception) {
            _codecName = "Start failed"
            runCatching { encoder.release() }
            throw e
        }
    }

    fun encode(pcm: ShortArray): ByteArray? {
        require(
            pcm.size == NetworkAudioManager.FRAME_SAMPLES
        ) {
            "Expected ${NetworkAudioManager.FRAME_SAMPLES} PCM samples"
        }

        val encoder = codec ?: error("Opus encoder is not started")

        val inputIndex =
            encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)

        if (inputIndex < 0) {
            return null
        }

        val input =
            encoder.getInputBuffer(inputIndex) ?: return null

        input.clear()
        input.order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(pcm)

        encoder.queueInputBuffer(
            inputIndex,
            0,
            pcm.size * 2,
            presentationTimeUs,
            0
        )

        presentationTimeUs +=
            NetworkAudioManager.FRAME_MS * 1_000L

        return drainOutput(encoder)
    }

    private fun drainOutput(
        encoder: MediaCodec
    ): ByteArray? {
        val info = MediaCodec.BufferInfo()
        val deadlineNs =
            System.nanoTime() +
                DEQUEUE_TIMEOUT_US * 1_000L

        while (System.nanoTime() < deadlineNs) {
            when (
                val index =
                    encoder.dequeueOutputBuffer(
                        info,
                        DEQUEUE_TIMEOUT_US
                    )
            ) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(
                        TAG,
                        "Encoder output format: ${encoder.outputFormat}"
                    )
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> {
                    if (index < 0) continue

                    val output =
                        encoder.getOutputBuffer(index)

                    val result =
                        if (
                            output != null &&
                            info.size > 0 &&
                            (
                                info.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                            ) == 0
                        ) {
                            val safeSize =
                                minOf(
                                    info.size,
                                    MAX_OUTPUT_SIZE
                                )

                            output.position(info.offset)
                            output.limit(
                                info.offset + safeSize
                            )

                            ByteArray(safeSize).also {
                                output.get(it)
                            }
                        } else {
                            null
                        }

                    encoder.releaseOutputBuffer(
                        index,
                        false
                    )

                    if (result != null) {
                        return result
                    }
                }
            }
        }

        return null
    }

    fun stop() {
        val encoder = codec ?: return
        codec = null

        runCatching { encoder.stop() }
            .onFailure {
                Log.w(TAG, "Encoder stop failed", it)
            }

        runCatching { encoder.release() }
            .onFailure {
                Log.w(TAG, "Encoder release failed", it)
            }

        _codecName = "Stopped"
        presentationTimeUs = 0L
    }

    private fun findEncoderName(
        format: MediaFormat
    ): String? {
        val infos =
            MediaCodecList(
                MediaCodecList.REGULAR_CODECS
            ).codecInfos

        return infos.firstOrNull { info: MediaCodecInfo ->
            info.isEncoder &&
                info.supportedTypes.any {
                    it.equals(MIME, ignoreCase = true)
                } &&
                runCatching {
                    val capabilities =
                        info.getCapabilitiesForType(MIME)

                    capabilities.audioCapabilities
                        .isSampleRateSupported(
                            NetworkAudioManager.SAMPLE_RATE
                        ) &&
                        capabilities.audioCapabilities
                            .getMaxInputChannelCount() >= 1
                }.getOrDefault(false)
        }?.name
    }
}

data class DecodedOpusAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channelCount: Int
)

class OpusDecoder {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MIME =
            MediaFormat.MIMETYPE_AUDIO_OPUS

        // The Android platform Opus decoder commonly outputs 48 kHz PCM.
        private const val DEFAULT_OUTPUT_SAMPLE_RATE = 48_000
        private const val CHANNELS = 1

        // Standard Opus codec delay / seek pre-roll metadata.
        private const val CODEC_DELAY_NS = 0L
        private const val SEEK_PRE_ROLL_NS = 80_000_000L

        private const val DEQUEUE_TIMEOUT_US = 20_000L
    }

    private var codec: MediaCodec? = null
    private var _codecName = "Not started"

    @Volatile
    private var _outputSampleRate =
        DEFAULT_OUTPUT_SAMPLE_RATE

    @Volatile
    private var _outputChannelCount = CHANNELS

    val codecName: String
        get() = _codecName

    val outputSampleRate: Int
        get() = _outputSampleRate

    val outputChannelCount: Int
        get() = _outputChannelCount

    fun start() {
        if (codec != null) return

        val format = MediaFormat.createAudioFormat(
            MIME,
            NetworkAudioManager.SAMPLE_RATE,
            CHANNELS
        ).apply {
            setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                4_000
            )

            setByteBuffer(
                "csd-0",
                ByteBuffer.wrap(
                    buildOpusHead(
                        sampleRate =
                            NetworkAudioManager.SAMPLE_RATE,
                        channelCount = CHANNELS
                    )
                )
            )

            setByteBuffer(
                "csd-1",
                ByteBuffer.wrap(
                    nativeLongBytes(CODEC_DELAY_NS)
                )
            )

            setByteBuffer(
                "csd-2",
                ByteBuffer.wrap(
                    nativeLongBytes(SEEK_PRE_ROLL_NS)
                )
            )
        }

        val decoder =
            MediaCodec.createDecoderByType(MIME)

        try {
            decoder.configure(
                format,
                null,
                null,
                0
            )
            decoder.start()

            codec = decoder
            _codecName = decoder.codecInfo.name
            _outputSampleRate =
                DEFAULT_OUTPUT_SAMPLE_RATE
            _outputChannelCount = CHANNELS

            Log.i(
                TAG,
                "Started decoder: $_codecName"
            )
        } catch (e: Exception) {
            _codecName = "Start failed"
            runCatching { decoder.release() }
            throw e
        }
    }

    fun decode(
        opusData: ByteArray
    ): List<DecodedOpusAudio> {
        require(opusData.isNotEmpty()) {
            "Opus packet must not be empty"
        }

        val decoder = codec
            ?: error("Opus decoder is not started")

        /*
         * IMPORTANT:
         * Do not wait 20 ms for MediaCodec input/output on every packet.
         *
         * The previous implementation could spend up to ~20 ms waiting for
         * an output buffer for each 20 ms packet. That made the decoder loop
         * slower than the network receive rate and caused the direct RX queue
         * to overflow.
         *
         * We now use non-blocking MediaCodec polling. A packet that has not
         * produced output yet remains inside MediaCodec and will be drained by
         * the next decode call.
         */
        val frames = ArrayList<DecodedOpusAudio>(2)

        // First drain anything already produced by the codec.
        drainAvailableOutput(decoder, frames)

        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex >= 0) {
            val input = decoder.getInputBuffer(inputIndex)
            if (input != null) {
                input.clear()
                input.put(opusData)

                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    opusData.size,
                    System.nanoTime() / 1_000L,
                    0
                )

                // The newly queued packet may already be ready.
                drainAvailableOutput(decoder, frames)
            }
        }

        return frames
    }

    private fun drainAvailableOutput(
        decoder: MediaCodec,
        frames: MutableList<DecodedOpusAudio>
    ) {
        val info = MediaCodec.BufferInfo()

        // A single Opus packet should normally produce one 20 ms PCM frame.
        // Keep a small hard limit so a codec malfunction cannot monopolize
        // the decoder thread indefinitely.
        repeat(8) {
            when (
                val index = decoder.dequeueOutputBuffer(info, 0L)
            ) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    updateOutputFormat(decoder.outputFormat)
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> {
                    if (index < 0) return

                    val output = decoder.getOutputBuffer(index)

                    if (
                        output != null &&
                        info.size > 0 &&
                        (
                            info.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        ) == 0
                    ) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)

                        val shortBuffer = output
                            .duplicate()
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()

                        val samples = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(samples)

                        frames.add(
                            DecodedOpusAudio(
                                samples = samples,
                                sampleRate = outputSampleRate,
                                channelCount = outputChannelCount
                            )
                        )
                    }

                    decoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun updateOutputFormat(
        format: MediaFormat
    ) {
        val sampleRate =
            runCatching {
                format.getInteger(
                    MediaFormat.KEY_SAMPLE_RATE
                )
            }.getOrNull()

        val channelCount =
            runCatching {
                format.getInteger(
                    MediaFormat.KEY_CHANNEL_COUNT
                )
            }.getOrNull()

        if (sampleRate != null && sampleRate > 0) {
            _outputSampleRate = sampleRate
        }

        if (channelCount != null && channelCount > 0) {
            _outputChannelCount = channelCount
        }

        Log.i(
            TAG,
            "Decoder output format: " +
                "${_outputSampleRate} Hz, " +
                "${_outputChannelCount} ch"
        )
    }

    fun stop() {
        val decoder = codec ?: return
        codec = null

        runCatching { decoder.stop() }
            .onFailure {
                Log.w(TAG, "Decoder stop failed", it)
            }

        runCatching { decoder.release() }
            .onFailure {
                Log.w(TAG, "Decoder release failed", it)
            }

        _codecName = "Stopped"
        _outputSampleRate =
            DEFAULT_OUTPUT_SAMPLE_RATE
        _outputChannelCount = CHANNELS
    }

    private fun buildOpusHead(
        sampleRate: Int,
        channelCount: Int
    ): ByteArray {
        val result = ByteArray(19)

        byteArrayOf(
            'O'.code.toByte(),
            'p'.code.toByte(),
            'u'.code.toByte(),
            's'.code.toByte(),
            'H'.code.toByte(),
            'e'.code.toByte(),
            'a'.code.toByte(),
            'd'.code.toByte()
        ).copyInto(result, 0)

        result[8] = 1 // OpusHead version
        result[9] = channelCount.toByte()

        // Pre-skip = 0 samples.
        putUInt32Le(result, 10, 0)

        // Original input sample rate.
        putUInt32Le(result, 12, sampleRate)

        // Output gain = 0 dB.
        result[16] = 0
        result[17] = 0

        // Channel mapping family 0 for mono.
        result[18] = 0

        return result
    }

    private fun nativeLongBytes(value: Long): ByteArray {
        return ByteBuffer
            .allocate(Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putLong(value)
            .array()
    }

    private fun putUInt32Le(
        target: ByteArray,
        offset: Int,
        value: Int
    ) {
        target[offset] =
            (value and 0xFF).toByte()
        target[offset + 1] =
            ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] =
            ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] =
            ((value ushr 24) and 0xFF).toByte()
    }
}
