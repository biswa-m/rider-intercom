package com.bmxt.riderintercom.lab.tests

import com.bmxt.riderintercom.lab.wifidirect.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiDirectLifecycleTestRunner {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    fun run(
        manager: WifiDirectManager,
        cycles: Int = 3,
        onUpdate: (String) -> Unit,
        onComplete: (List<WifiDirectLifecycleResult>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        job?.cancel()
        job = scope.launch {
            try {
                val results = mutableListOf<WifiDirectLifecycleResult>()
                repeat(cycles) { index ->
                    val cycle = index + 1
                    onUpdate("Cycle $cycle/$cycles — disconnecting…")
                    val disconnectMs = manager.disconnectAndWait()
                    delay(700L)
                    onUpdate("Cycle $cycle/$cycles — reconnecting…")
                    val reconnectMs = manager.reconnectAndWait()
                    val result = WifiDirectLifecycleResult(
                        cycle = cycle,
                        disconnectMs = disconnectMs,
                        reconnectMs = reconnectMs,
                        passed = true,
                        note = "Connection restored after explicit disconnect"
                    )
                    results += result
                    onUpdate("Cycle $cycle/$cycles — PASS (disconnect ${disconnectMs} ms, reconnect ${reconnectMs} ms)")
                    delay(700L)
                }
                onComplete(results)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
