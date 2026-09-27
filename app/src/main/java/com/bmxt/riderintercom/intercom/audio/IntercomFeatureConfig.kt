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
    val useVadPreRoll: Boolean = false
) {
    /** Jitter buffering is currently implemented for the Opus receive path. */
    val effectiveJitterBuffer: Boolean
        get() = useJitterBuffer && useOpus

    /** Pre-roll only has meaning when VAD is enabled. */
    val effectiveVadPreRoll: Boolean
        get() = useVad && useVadPreRoll

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

        const val EXTRA_USE_VAD = "use_vad"
        const val EXTRA_USE_OPUS = "use_opus"
        const val EXTRA_USE_JITTER_BUFFER = "use_jitter_buffer"
        const val EXTRA_USE_VAD_PRE_ROLL = "use_vad_pre_roll"

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
                )
            )
        }

        fun default(): IntercomFeatureConfig = IntercomFeatureConfig()
    }
}
