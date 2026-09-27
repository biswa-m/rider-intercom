package com.bmxt.riderintercom.intercom.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioLevelUtils {
    fun rmsDb(samples: ShortArray, length: Int = samples.size): Float {
        val count = length.coerceIn(0, samples.size)
        if (count == 0) return -96f

        var sumSquares = 0.0
        val scale = Short.MAX_VALUE.toDouble()
        for (i in 0 until count) {
            val normalized = samples[i].toDouble() / scale
            sumSquares += normalized * normalized
        }

        if (sumSquares <= 0.0) return -96f

        val rms = sqrt(sumSquares / count.toDouble())
        return (20.0 * log10(rms)).toFloat().coerceIn(-96f, 0f)
    }
}
