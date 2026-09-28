package com.bmxt.riderintercom.lab.jitter

data class JitterBufferStats(
    val offered: Long = 0,
    val accepted: Long = 0,
    val duplicates: Long = 0,
    val late: Long = 0,
    val overflow: Long = 0,
    val played: Long = 0,
    val missing: Long = 0,
    val underruns: Long = 0,
    val maxDepth: Int = 0,
    val averageDepth: Double = 0.0,
    val resyncs: Long = 0
)
