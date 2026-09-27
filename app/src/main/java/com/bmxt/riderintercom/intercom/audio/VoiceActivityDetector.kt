package com.bmxt.riderintercom.intercom.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight energy-based VAD for the first intercom prototype.
 *
 * It operates on short PCM frames and deliberately has no external/native
 * dependency. It is intended to validate the audio pipeline and provide a
 * replaceable VAD boundary before network transmission is added.
 */
class VoiceActivityDetector(
    private val calibrationFrameCount: Int = 25, // 500 ms at 20 ms/frame
    private val speechMarginDb: Float = 9f,
    private val minimumSpeechDb: Float = -42f,
    private val silenceHangoverFrames: Int = 12, // 240 ms
    private val noiseFloorAttack: Float = 0.05f,
    private val noiseFloorRelease: Float = 0.20f,
) {
    private var initialized = false
    private var calibrationFramesSeen = 0
    private var noiseFloorDb = -60f
    private var speechActive = false
    private var silenceFrames = 0

    fun reset() {
        initialized = false
        calibrationFramesSeen = 0
        noiseFloorDb = -60f
        speechActive = false
        silenceFrames = 0
    }

    /**
     * Returns true while speech is considered active.
     * [samples] contains signed 16-bit PCM samples.
     */
    fun process(samples: ShortArray, length: Int = samples.size): Boolean {
        if (length <= 0) return speechActive

        val rmsDb = calculateRmsDb(samples, length)

        if (!initialized) {
            noiseFloorDb = if (calibrationFramesSeen == 0) {
                rmsDb
            } else {
                noiseFloorDb + (rmsDb - noiseFloorDb) * noiseFloorAttack
            }

            calibrationFramesSeen++
            if (calibrationFramesSeen >= calibrationFrameCount) {
                initialized = true
            }

            speechActive = false
            silenceFrames = 0
            return false
        }

        val thresholdDb = max(
            minimumSpeechDb,
            noiseFloorDb + speechMarginDb
        )

        if (rmsDb >= thresholdDb) {
            speechActive = true
            silenceFrames = 0
            return true
        }

        if (speechActive) {
            silenceFrames++
            if (silenceFrames >= silenceHangoverFrames) {
                speechActive = false
                silenceFrames = 0
            }
        }

        // Only adapt the noise floor while we are not classifying the frame
        // as speech. This prevents speech from raising the threshold.
        if (!speechActive) {
            val alpha = if (rmsDb > noiseFloorDb) {
                noiseFloorAttack
            } else {
                noiseFloorRelease
            }
            noiseFloorDb += (rmsDb - noiseFloorDb) * alpha
        }

        return speechActive
    }

    fun noiseFloorDb(): Float = noiseFloorDb

    private fun calculateRmsDb(samples: ShortArray, length: Int): Float {
        var sumSquares = 0.0
        val count = length.coerceAtMost(samples.size)

        for (i in 0 until count) {
            val normalized = samples[i].toDouble() / Short.MAX_VALUE.toDouble()
            sumSquares += normalized * normalized
        }

        if (sumSquares <= 0.0) return -96f

        val rms = sqrt(sumSquares / count.toDouble())
        return (20.0 * log10(rms)).toFloat().coerceAtLeast(-96f)
    }
}
