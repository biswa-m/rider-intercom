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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.bmxt.riderintercom.lab.core.LabCsvBuffer
import com.bmxt.riderintercom.lab.core.LabTestResult
import com.bmxt.riderintercom.lab.nearby.NearbyConnectionManager
import com.bmxt.riderintercom.lab.nearby.NearbyDataTestRunner
import com.bmxt.riderintercom.lab.nearby.NearbyLifecycleTestRunner
import com.bmxt.riderintercom.lab.nearby.NearbyLatencyTestRunner
import com.bmxt.riderintercom.lab.ui.screens.NearbyLabScreen

class MainActivity : ComponentActivity() {
    private lateinit var nearbyManager: NearbyConnectionManager
    private val dataRunner = NearbyDataTestRunner()
    private val lifecycleRunner = NearbyLifecycleTestRunner()
    private val latencyRunner = NearbyLatencyTestRunner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearbyManager = NearbyConnectionManager(this)

        setContent {
            val state by nearbyManager.state.collectAsState()
            var dataTesting by remember { mutableStateOf(false) }
            var dataProgress by remember { mutableStateOf("Ready") }
            var lifecycleTesting by remember { mutableStateOf(false) }
            var lifecycleProgress by remember { mutableStateOf("Ready") }
            var latencyTesting by remember { mutableStateOf(false) }
            var latencyProgress by remember { mutableStateOf("Ready") }
            var results by remember { mutableStateOf(emptyList<LabTestResult>()) }
            val buffer = remember { LabCsvBuffer() }

            LaunchedEffect(Unit) {
                if (hasNearbyPermissions()) nearbyManager.startAutoMode()
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                if (hasNearbyPermissions()) {
                    nearbyManager.startAutoMode()
                } else {
                    dataProgress = "Nearby permissions were not granted: ${grants.filterValues { !it }.keys.joinToString()}"
                }
            }

            fun ensurePermissionsThen(action: () -> Unit) {
                val missing = nearbyPermissions().filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
            }

            DisposableEffect(Unit) {
                onDispose {
                    nearbyManager.stop()
                    dataRunner.cancel()
                    lifecycleRunner.cancel()
                    latencyRunner.cancel()
                }
            }

            MaterialTheme {
                Surface {
                    NearbyLabScreen(
                        state = state,
                        dataTesting = dataTesting,
                        dataProgress = dataProgress,
                        lifecycleTesting = lifecycleTesting,
                        lifecycleProgress = lifecycleProgress,
                        latencyTesting = latencyTesting,
                        latencyProgress = latencyProgress,
                        results = results,
                        onStartAuto = { ensurePermissionsThen(nearbyManager::startAutoMode) },
                        onDiscover = { ensurePermissionsThen(nearbyManager::discover) },
                        onConnect = { peer -> ensurePermissionsThen { nearbyManager.connect(peer) } },
                        onDisconnect = nearbyManager::disconnect,
                        onRunDataTest = {
                            dataTesting = true
                            dataProgress = "Starting…"
                            dataRunner.runStandard(
                                nearbyManager,
                                onUpdate = { dataProgress = it },
                                onComplete = { result ->
                                    buffer.add(result)
                                    results = buffer.snapshot()
                                    dataTesting = false
                                    dataProgress = "Finished"
                                }
                            )
                        },
                        onRunVoiceTest = {
                            dataTesting = true
                            dataProgress = "Starting voice-sized test…"
                            dataRunner.runVoiceSized(
                                nearbyManager,
                                onUpdate = { dataProgress = it },
                                onComplete = { result ->
                                    buffer.add(result)
                                    results = buffer.snapshot()
                                    dataTesting = false
                                    dataProgress = "Finished"
                                }
                            )
                        },
                        onRunRateSweep = {
                            dataTesting = true
                            dataProgress = "Starting rate sweep…"
                            dataRunner.runRateSweep(
                                nearbyManager,
                                onUpdate = { dataProgress = it },
                                onComplete = { newResults ->
                                    buffer.addAll(newResults)
                                    results = buffer.snapshot()
                                    dataTesting = false
                                    dataProgress = "Rate sweep finished"
                                }
                            )
                        },
                        onRunLatencyTest = {
                            latencyTesting = true
                            latencyProgress = "Starting…"
                            latencyRunner.run(
                                nearbyManager,
                                onUpdate = { latencyProgress = it },
                                onComplete = { result ->
                                    buffer.add(result)
                                    results = buffer.snapshot()
                                    latencyTesting = false
                                    latencyProgress = "Finished"
                                }
                            )
                        },
                        onRunLifecycleTest = {
                            lifecycleTesting = true
                            lifecycleProgress = "Starting…"
                            lifecycleRunner.run(
                                nearbyManager,
                                onUpdate = { lifecycleProgress = it },
                                onComplete = { newResults ->
                                    buffer.addAll(newResults)
                                    results = buffer.snapshot()
                                    lifecycleTesting = false
                                    lifecycleProgress = "Finished"
                                }
                            )
                        },
                        onExport = { exportCsv(buffer.build(), buffer.defaultFileName()) },
                        onReset = {
                            dataRunner.cancel()
                            lifecycleRunner.cancel()
                            latencyRunner.cancel()
                            buffer.clear()
                            results = emptyList()
                            dataProgress = "Ready"
                            lifecycleProgress = "Ready"
                        }
                    )
                }
            }
        }
    }

    private fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT <= 30) {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT in 29..31) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 32) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun hasNearbyPermissions(): Boolean = nearbyPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun exportCsv(content: String, fileName: String) {
        startActivityForResult(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_TITLE, fileName)
                },
                "Export Unified CSV"
            ),
            901
        )
        // ACTION_CREATE_DOCUMENT result is intentionally handled below via the legacy callback.
        pendingCsv = content
    }

    private var pendingCsv: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == 901 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                pendingCsv?.let { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.write(it) } }
            }
            pendingCsv = null
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        nearbyManager.stop()
        dataRunner.cancel()
        lifecycleRunner.cancel()
        latencyRunner.cancel()
        super.onDestroy()
    }
}
