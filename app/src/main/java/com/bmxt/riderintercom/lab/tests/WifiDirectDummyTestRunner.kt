package com.bmxt.riderintercom.lab.tests

import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.network.PeerControlHandshake
import com.bmxt.riderintercom.lab.network.UdpDummyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiDirectDummyTestRunner {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    fun run(
        peerAddressHint: String,
        isGroupOwner: Boolean,
        onUpdate: (String, Long, Long) -> Unit,
        onComplete: (LabTestResult) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        job?.cancel()
        job = scope.launch {
            try {
                onUpdate("Synchronizing both phones…", 0, 0)
                val peerAddress = PeerControlHandshake().resolvePeer(isGroupOwner, peerAddressHint)
                onUpdate("Peer ready: $peerAddress", 0, 0)
                val result = withContext(Dispatchers.IO) {
                    UdpDummyTransport().run(peerAddress, if (isGroupOwner) "GROUP_OWNER" else "CLIENT") { tx, rx ->
                        onUpdate("Testing…", tx, rx)
                    }
                }
                onComplete(result)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
