package com.bmxt.riderintercom

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bmxt.riderintercom.ui.screens.AudioTestScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = viewModel()

            val audioRunning by viewModel.audioRunning.collectAsState()
            val inputDevices by viewModel.inputDevices.collectAsState()
            val outputDevices by viewModel.outputDevices.collectAsState()
            val communicationDevices by viewModel.communicationDevices.collectAsState()
            val currentCommunicationDevice by
                viewModel.currentCommunicationDevice.collectAsState()
            val routingError by viewModel.routingError.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.refreshAudioDevices()
            }

            var hasMicrophonePermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            var hasBluetoothConnectPermission by remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val microphonePermissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMicrophonePermission = granted
                    if (granted) {
                        viewModel.startAudio()
                    }
                }

            val bluetoothPermissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasBluetoothConnectPermission = granted
                    if (granted) {
                        viewModel.refreshAudioDevices()
                    }
                }

            MaterialTheme {
                Surface {
                    AudioTestScreen(
                        audioRunning = audioRunning,
                        microphonePermission = hasMicrophonePermission,
                        inputDevices = inputDevices,
                        outputDevices = outputDevices,
                        communicationDevices = communicationDevices,
                        currentCommunicationDevice = currentCommunicationDevice,
                        routingError = routingError,
                        inputDeviceName = viewModel::inputDeviceName,
                        outputDeviceName = viewModel::outputDeviceName,
                        describeDevice = viewModel::describeDevice,
                        onSelectCommunicationDevice = { device ->
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                                !hasBluetoothConnectPermission
                            ) {
                                bluetoothPermissionLauncher.launch(
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            } else {
                                viewModel.selectCommunicationDevice(device)
                            }
                        },
                        onClearCommunicationDevice = {
                            viewModel.clearCommunicationDevice()
                        },
                        onStart = {
                            if (!hasMicrophonePermission) {
                                microphonePermissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            } else {
                                viewModel.startAudio()
                            }
                        },
                        onStop = {
                            viewModel.stopAudio()
                        }
                    )
                }
            }
        }
    }
}
