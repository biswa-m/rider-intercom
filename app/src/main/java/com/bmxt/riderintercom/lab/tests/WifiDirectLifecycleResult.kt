package com.bmxt.riderintercom.lab.tests

data class WifiDirectLifecycleResult(
    val cycle: Int,
    val disconnectMs: Long,
    val reconnectMs: Long,
    val passed: Boolean,
    val note: String = ""
)
