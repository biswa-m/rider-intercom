package com.bmxt.riderintercom.lab.jitter

data class JitterBufferConfig(
    val frameDurationMs: Long = 20L,
    val targetBufferMs: Long = 40L,
    val maxBufferMs: Long = 200L
) {
    val targetPackets: Int get() = (targetBufferMs / frameDurationMs).toInt().coerceAtLeast(1)
    val maxPackets: Int get() = (maxBufferMs / frameDurationMs).toInt().coerceAtLeast(targetPackets)
}
