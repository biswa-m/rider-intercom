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
            val results = mutableListOf<WifiDirectLifecycleResult>()
            try {
                repeat(cycles) { index ->
                    val cycle = index + 1
                    var disconnectMs = -1L
                    var reconnectMs = -1L
                    try {
                        onUpdate("Cycle $cycle/$cycles — disconnecting…")
                        disconnectMs = manager.disconnectAndWait()
                        delay(1_000L)

                        onUpdate("Cycle $cycle/$cycles — waiting for automatic peer discovery and reconnect…")
                        reconnectMs = manager.reconnectAndWait()
                        val result = WifiDirectLifecycleResult(
                            cycle = cycle,
                            disconnectMs = disconnectMs,
                            reconnectMs = reconnectMs,
                            passed = true,
                            note = "Connection restored after explicit disconnect"
                        )
                        results += result
                        onUpdate("Cycle $cycle/$cycles — PASS (disconnect ${disconnectMs} ms, reconnect ${reconnectMs} ms)")
                        delay(1_000L)
                    } catch (t: Throwable) {
                        val failure = WifiDirectLifecycleResult(
                            cycle = cycle,
                            disconnectMs = disconnectMs,
                            reconnectMs = reconnectMs,
                            passed = false,
                            note = t.message ?: t.javaClass.simpleName
                        )
                        results += failure
                        onUpdate("Cycle $cycle/$cycles — FAIL: ${failure.note}")
                        // Stop here. A failed cycle means the precondition for the next
                        // cycle (a connected peer) is not guaranteed.
                        onComplete(results)
                        return@launch
                    }
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
