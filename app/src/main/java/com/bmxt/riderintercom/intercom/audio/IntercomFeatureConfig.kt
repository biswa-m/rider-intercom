package com.bmxt.riderintercom.intercom.audio

/**
 * Optional network-audio processing switches.
 *
 * The current defaults intentionally use the simplest possible network path:
 * 16 kHz mono PCM -> UDP -> 16 kHz mono PCM.
 *
 * Change these before starting the intercom. The configuration is session based
 * and is sent to the foreground service when the intercom starts.
 */
data class IntercomFeatureConfig(
    val useVad: Boolean = false,
    val useOpus: Boolean = false,
    val useJitterBuffer: Boolean = false,
    val useVadPreRoll: Boolean = false,

    // Testing-only timestamp latency measurement.
    val useTimestampLatencyTest: Boolean = false,
    /** Clock offset = peer clock - this phone's clock, in milliseconds. */
    val latencyClockOffsetMs: Long = 0L,
    /** Measure every Nth received audio packet. */
    val latencySampleEveryPackets: Int = 1
) {
    /** Jitter buffering is currently implemented for the Opus receive path. */
    val effectiveJitterBuffer: Boolean
        get() = useJitterBuffer && useOpus

    /** Pre-roll only has meaning when VAD is enabled. */
    val effectiveVadPreRoll: Boolean
        get() = useVad && useVadPreRoll

    val effectiveLatencySampleEveryPackets: Int
        get() = latencySampleEveryPackets.coerceIn(1, 100)

    val pipelineDescription: String
        get() {
            val capture = buildString {
                append("16 kHz mono PCM")
                if (useVad) append(" → VAD")
                if (useOpus) append(" → Opus")
                append(" → UDP")
            }

            val receive = buildString {
                append("UDP")
                if (effectiveJitterBuffer) append(" → jitter buffer")
                if (useOpus) append(" → Opus decode")
                append(" → PCM → AudioTrack")
            }

            return "$capture | $receive"
        }

    companion object {
        const val DEFAULT_USE_VAD = false
        const val DEFAULT_USE_OPUS = false
        const val DEFAULT_USE_JITTER_BUFFER = false
        const val DEFAULT_USE_VAD_PRE_ROLL = false
        const val DEFAULT_USE_TIMESTAMP_LATENCY_TEST = false
        const val DEFAULT_LATENCY_CLOCK_OFFSET_MS = 0L
        const val DEFAULT_LATENCY_SAMPLE_EVERY_PACKETS = 1

        const val EXTRA_USE_VAD = "use_vad"
        const val EXTRA_USE_OPUS = "use_opus"
        const val EXTRA_USE_JITTER_BUFFER = "use_jitter_buffer"
        const val EXTRA_USE_VAD_PRE_ROLL = "use_vad_pre_roll"
        const val EXTRA_USE_TIMESTAMP_LATENCY_TEST = "use_timestamp_latency_test"
        const val EXTRA_LATENCY_CLOCK_OFFSET_MS = "latency_clock_offset_ms"
        const val EXTRA_LATENCY_SAMPLE_EVERY_PACKETS = "latency_sample_every_packets"

        fun fromIntent(intent: android.content.Intent?): IntercomFeatureConfig {
            if (intent == null) return default()
            return IntercomFeatureConfig(
                useVad = intent.getBooleanExtra(EXTRA_USE_VAD, DEFAULT_USE_VAD),
                useOpus = intent.getBooleanExtra(EXTRA_USE_OPUS, DEFAULT_USE_OPUS),
                useJitterBuffer = intent.getBooleanExtra(
                    EXTRA_USE_JITTER_BUFFER,
                    DEFAULT_USE_JITTER_BUFFER
                ),
                useVadPreRoll = intent.getBooleanExtra(
                    EXTRA_USE_VAD_PRE_ROLL,
                    DEFAULT_USE_VAD_PRE_ROLL
                ),
                useTimestampLatencyTest = intent.getBooleanExtra(
                    EXTRA_USE_TIMESTAMP_LATENCY_TEST,
                    DEFAULT_USE_TIMESTAMP_LATENCY_TEST
                ),
                latencyClockOffsetMs = intent.getLongExtra(
                    EXTRA_LATENCY_CLOCK_OFFSET_MS,
                    DEFAULT_LATENCY_CLOCK_OFFSET_MS
                ),
                latencySampleEveryPackets = intent.getIntExtra(
                    EXTRA_LATENCY_SAMPLE_EVERY_PACKETS,
                    DEFAULT_LATENCY_SAMPLE_EVERY_PACKETS
                ).coerceIn(1, 100)
            )
        }

        fun default(): IntercomFeatureConfig = IntercomFeatureConfig()
    }
}
