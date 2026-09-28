package com.bmxt.riderintercom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.bmxt.riderintercom.lab.core.LabCsvWriter
import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.tests.WifiDirectDummyTestRunner
import com.bmxt.riderintercom.lab.tests.WifiDirectLifecycleResult
import com.bmxt.riderintercom.lab.tests.WifiDirectLifecycleTestRunner
import com.bmxt.riderintercom.lab.ui.screens.WifiDirectLabScreen
import com.bmxt.riderintercom.lab.wifidirect.WifiDirectManager

class MainActivity : ComponentActivity() {
    private lateinit var wifiDirectManager: WifiDirectManager
    private val testRunner = WifiDirectDummyTestRunner()
    private val lifecycleRunner = WifiDirectLifecycleTestRunner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirectManager = WifiDirectManager(this)
        wifiDirectManager.start()

        setContent {
            val wifiState by wifiDirectManager.state.collectAsState()
            val devices by wifiDirectManager.devices.collectAsState()
            var testing by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("Ready") }
            var tx by remember { mutableStateOf(0L) }
            var rx by remember { mutableStateOf(0L) }
            var result by remember { mutableStateOf<LabTestResult?>(null) }
            var results by remember { mutableStateOf(emptyList<LabTestResult>()) }
            var lifecycleTesting by remember { mutableStateOf(false) }
            var lifecycleProgress by remember { mutableStateOf("Ready") }
            var lifecycleResults by remember { mutableStateOf(emptyList<WifiDirectLifecycleResult>()) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val allGranted = grants.values.all { it }
                if (allGranted) wifiDirectManager.discover()
            }

            val documentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv")
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                if (result == null || results.isEmpty()) return@rememberLauncherForActivityResult
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(LabCsvWriter.build(results))
                }
            }

            fun ensureWifiPermissionsThen(action: () -> Unit) {
                val required = buildList {
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                val missing = required.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
            }

            DisposableEffect(Unit) {
                onDispose {
                    wifiDirectManager.stop()
                    testRunner.cancel()
                    lifecycleRunner.cancel()
                }
            }

            MaterialTheme {
                Surface {
                    WifiDirectLabScreen(
                        state = wifiState,
                        devices = devices,
                        lastResult = result,
                        results = results,
                        testing = testing,
                        progressText = progressText,
                        tx = tx,
                        rx = rx,
                        onDiscover = { ensureWifiPermissionsThen(wifiDirectManager::discover) },
                        onConnect = wifiDirectManager::connect,
                        onDisconnect = wifiDirectManager::disconnect,
                        onRunTest = {
                            val ownerAddress = wifiState.groupOwnerAddress
                            if (!wifiState.connected || ownerAddress == null) {
                                progressText = "Wi-Fi Direct is not connected"
                            } else {
                                testing = true
                                tx = 0
                                rx = 0
                                progressText = "Preparing test…"
                                val wifiResult = LabTestResult(
                                    testName = "WIFI_DIRECT_CONNECTION",
                                    startEpochMs = System.currentTimeMillis(),
                                    durationMs = 0L,
                                    txPackets = 0L,
                                    rxPackets = 0L,
                                    uniqueRxPackets = 0L,
                                    duplicatePackets = 0L,
                                    outOfOrderPackets = 0L,
                                    sequenceGaps = 0L,
                                    txBytes = 0L,
                                    rxBytes = 0L,
                                    averageInterArrivalMs = 0.0,
                                    p95InterArrivalMs = 0.0,
                                    maxInterArrivalMs = 0.0,
                                    passed = wifiState.connected,
                                    note = "Wi-Fi Direct connected; role=${if (wifiState.isGroupOwner) "GROUP_OWNER" else "CLIENT"}; peer=${wifiState.peerName ?: "unknown"}"
                                )
                                results = results + wifiResult
                                testRunner.run(
                                    peerAddressHint = ownerAddress,
                                    isGroupOwner = wifiState.isGroupOwner,
                                    onUpdate = { text, newTx, newRx ->
                                        progressText = text
                                        tx = newTx
                                        rx = newRx
                                    },
                                    onComplete = { newResult ->
                                        result = newResult
                                        results = results + newResult
                                        testing = false
                                        progressText = "Test finished"
                                    },
                                    onError = { error ->
                                        testing = false
                                        progressText = "Test error: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                )
                            }
                        },
                        lifecycleTesting = lifecycleTesting,
                        lifecycleProgress = lifecycleProgress,
                        lifecycleResults = lifecycleResults,
                        onRunLifecycleTest = {
                            lifecycleTesting = true
                            lifecycleProgress = "Preparing lifecycle test…"
                            lifecycleResults = emptyList()
                            lifecycleRunner.run(
                                manager = wifiDirectManager,
                                cycles = 3,
                                onUpdate = { lifecycleProgress = it },
                                onComplete = { newResults ->
                                    lifecycleResults = newResults
                                    val labRows = newResults.map { lifecycle ->
                                        LabTestResult(
                                            testName = "WIFI_DIRECT_LIFECYCLE_CYCLE_${lifecycle.cycle}",
                                            startEpochMs = System.currentTimeMillis(),
                                            durationMs = lifecycle.disconnectMs + lifecycle.reconnectMs,
                                            txPackets = 0L,
                                            rxPackets = 0L,
                                            uniqueRxPackets = 0L,
                                            duplicatePackets = 0L,
                                            outOfOrderPackets = 0L,
                                            sequenceGaps = 0L,
                                            txBytes = 0L,
                                            rxBytes = 0L,
                                            averageInterArrivalMs = 0.0,
                                            p95InterArrivalMs = 0.0,
                                            maxInterArrivalMs = 0.0,
                                            passed = lifecycle.passed,
                                            note = "disconnect_ms=${lifecycle.disconnectMs}; reconnect_ms=${lifecycle.reconnectMs}; ${lifecycle.note}"
                                        )
                                    }
                                    results = results + labRows
                                    lifecycleTesting = false
                                    lifecycleProgress = "Lifecycle test finished"
                                },
                                onError = { error ->
                                    lifecycleTesting = false
                                    lifecycleProgress = "Lifecycle test error: ${error.message ?: error.javaClass.simpleName}"
                                }
                            )
                        },
                        onExport = {
                            if (results.isNotEmpty()) documentLauncher.launch(LabCsvWriter.defaultFileName())
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        wifiDirectManager.stop()
        testRunner.cancel()
        lifecycleRunner.cancel()
        super.onDestroy()
    }
}
