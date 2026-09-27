package com.bmxt.riderintercom.intercom.audio

/**
 * Live diagnostic information for the audio/network pipeline.
 * Values are intentionally simple so the Compose UI can render them directly.
 */
data class AudioDebugState(
    val mode: String = "Stopped",
    val networkState: String = "Stopped",
    val micLevelDb: Float = -96f,
    val remoteLevelDb: Float = -96f,
    val voiceDetected: Boolean = false,
    val transmittingAudio: Boolean = false,
    val receivingAudio: Boolean = false,
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val malformedPackets: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val encodedFrames: Long = 0L,
    val encodeNoOutputFrames: Long = 0L,
    val decodedFrames: Long = 0L,
    val decodeNoOutputFrames: Long = 0L,
    val playbackSamples: Long = 0L,
    val playbackWriteFailures: Long = 0L,
    val lastReceivedAtMs: Long = 0L,
    val lastSentPayloadBytes: Int = 0,
    val lastReceivedPayloadBytes: Int = 0,
    val lastReceivedSequence: Int = -1,
    val audioRecordState: String = "Unknown",
    val audioTrackState: String = "Unknown",
    val playbackSampleRate: Int = 0,
    val playbackChannelCount: Int = 0,
    val opusEncoderName: String = "Not started",
    val opusDecoderName: String = "Not started",
    val lastError: String? = null
)
