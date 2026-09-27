package com.bmxt.riderintercom.intercom.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder

/**
 * Real-time Opus codec using Android's MediaCodec API.
 *
 * The intercom uses 16 kHz mono, 20 ms PCM frames (320 samples/frame).
 */
class OpusEncoder {
    companion object {
        private const val TAG = "OpusEncoder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val BITRATE = 24_000
        private const val MAX_OUTPUT_SIZE = 4_000
    }

    private var codec: MediaCodec? = null
    private var _codecName: String = "Not started"
    val codecName: String get() = _codecName

    fun start() {
        if (codec != null) return

        val format = MediaFormat.createAudioFormat(
            MIME,
            NetworkAudioManager.SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, NetworkAudioManager.FRAME_BYTES)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val codecName = findEncoderName(format)
            ?: throw IllegalStateException("No Android Opus encoder is available")

        val encoder = MediaCodec.createByCodecName(codecName)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            _codecName = codecName
            Log.i(TAG, "Started encoder: $codecName")
        } catch (e: Exception) {
            _codecName = "Start failed"
            try { encoder.release() } catch (_: Exception) {}
            throw e
        }
    }

    /** Encodes one 20 ms mono PCM frame. */
    fun encode(pcm: ShortArray): ByteArray? {
        require(pcm.size == NetworkAudioManager.FRAME_SAMPLES) {
            "Expected ${NetworkAudioManager.FRAME_SAMPLES} PCM samples"
        }

        val encoder = codec ?: error("Opus encoder is not started")
        val inputIndex = encoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return null

        val input = encoder.getInputBuffer(inputIndex) ?: return null
        input.clear()
        input.order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(pcm)

        encoder.queueInputBuffer(
            inputIndex,
            0,
            pcm.size * 2,
            System.nanoTime() / 1_000L,
            0
        )

        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = encoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> {
                    if (outputIndex >= 0) {
                        val output = encoder.getOutputBuffer(outputIndex)
                        val result = if (
                            output != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val safeSize = minOf(info.size, MAX_OUTPUT_SIZE)
                            output.position(info.offset)
                            output.limit(info.offset + safeSize)
                            ByteArray(safeSize).also { output.get(it) }
                        } else {
                            null
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (result != null) return result
                    }
                }
            }
        }
    }

    fun stop() {
        val encoder = codec ?: return
        codec = null
        try { encoder.stop() } catch (e: Exception) { Log.w(TAG, "Encoder stop failed", e) }
        try { encoder.release() } catch (e: Exception) { Log.w(TAG, "Encoder release failed", e) }
        _codecName = "Stopped"
    }

    private fun findEncoderName(format: MediaFormat): String? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return infos.firstOrNull { info: MediaCodecInfo ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                runCatching {
                    val caps = info.getCapabilitiesForType(MIME)
                    caps.audioCapabilities.isSampleRateSupported(NetworkAudioManager.SAMPLE_RATE) &&
                        caps.audioCapabilities.getMaxInputChannelCount() >= 1
                }.getOrDefault(false)
        }?.name
    }
}

class OpusDecoder {
    companion object {
        private const val TAG = "OpusDecoder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    }

    private var codec: MediaCodec? = null
    private var _codecName: String = "Not started"
    val codecName: String get() = _codecName

    fun start() {
        if (codec != null) return

        val format = MediaFormat.createAudioFormat(
            MIME,
            NetworkAudioManager.SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_000)
        }

        val decoder = MediaCodec.createDecoderByType(MIME)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            _codecName = decoder.codecInfo.name
            Log.i(TAG, "Started decoder: $_codecName")
        } catch (e: Exception) {
            _codecName = "Start failed"
            try { decoder.release() } catch (_: Exception) {}
            throw e
        }
    }

    /** Decodes a raw Opus access unit into zero or more PCM frames. */
    fun decode(opusData: ByteArray): List<ShortArray> {
        val decoder = codec ?: error("Opus decoder is not started")
        val inputIndex = decoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return emptyList()

        val input = decoder.getInputBuffer(inputIndex) ?: return emptyList()
        input.clear()
        input.put(opusData)
        decoder.queueInputBuffer(
            inputIndex,
            0,
            opusData.size,
            System.nanoTime() / 1_000L,
            0
        )

        val frames = ArrayList<ShortArray>(1)
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return frames
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> {
                    if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (
                            output != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val shorts = output
                                .order(ByteOrder.nativeOrder())
                                .slice()
                                .order(ByteOrder.nativeOrder())
                                .asShortBuffer()
                            val samples = ShortArray(shorts.remaining())
                            shorts.get(samples)
                            frames += samples
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
    }

    fun stop() {
        val decoder = codec ?: return
        codec = null
        try { decoder.stop() } catch (e: Exception) { Log.w(TAG, "Decoder stop failed", e) }
        try { decoder.release() } catch (e: Exception) { Log.w(TAG, "Decoder release failed", e) }
        _codecName = "Stopped"
    }
}
