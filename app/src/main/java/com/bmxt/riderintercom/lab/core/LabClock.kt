package com.bmxt.riderintercom.lab.core

object LabClock {
    fun elapsedNanos(): Long = System.nanoTime()
    fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}
