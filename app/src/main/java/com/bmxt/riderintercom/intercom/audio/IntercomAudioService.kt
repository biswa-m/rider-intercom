package com.bmxt.riderintercom.intercom.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.bmxt.riderintercom.MainActivity
import com.bmxt.riderintercom.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns the intercom audio lifecycle independently from the Activity/ViewModel.
 *
 * Desired lifecycle:
 * - Screen off / phone locked: keep running.
 * - App in background: keep running.
 * - User swipes the app task away from Recents: stop the intercom.
 * - Process is killed unexpectedly: do not intentionally resurrect the service.
 */
class IntercomAudioService : Service() {

    companion object {
        private const val TAG = "IntercomAudioService"
        private const val CHANNEL_ID = "intercom_audio"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bmxt.riderintercom.action.START_AUDIO"
        const val ACTION_STOP = "com.bmxt.riderintercom.action.STOP_AUDIO"
        const val EXTRA_COMMUNICATION_DEVICE_ID =
            "communication_device_id"
        const val NO_DEVICE_ID = -1
        const val EXTRA_PEER_HOST = "peer_host"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_PEER_PORT = "peer_port"

        private val _running = MutableStateFlow(false)
        val runningState: StateFlow<Boolean> = _running.asStateFlow()

        private val _voiceDetected = MutableStateFlow(false)
        val voiceDetectedState: StateFlow<Boolean> = _voiceDetected.asStateFlow()

        private val _debugState = MutableStateFlow(AudioDebugState())
        val debugState: StateFlow<AudioDebugState> = _debugState.asStateFlow()

        val isRunning: Boolean
            get() = _running.value

        private fun setRunning(running: Boolean) {
            _running.value = running
        }

        fun start(
            context: Context,
            communicationDeviceId: Int = NO_DEVICE_ID,
            peerHost: String? = null,
            localPort: Int = NetworkAudioManager.DEFAULT_PORT,
            peerPort: Int = NetworkAudioManager.DEFAULT_PORT,
            featureConfig: IntercomFeatureConfig = IntercomFeatureConfig.default()
        ) {
            val intent = Intent(context, IntercomAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COMMUNICATION_DEVICE_ID, communicationDeviceId)
                peerHost?.let { putExtra(EXTRA_PEER_HOST, it) }
                putExtra(EXTRA_LOCAL_PORT, localPort)
                putExtra(EXTRA_PEER_PORT, peerPort)
                putExtra(IntercomFeatureConfig.EXTRA_USE_VAD, featureConfig.useVad)
                putExtra(IntercomFeatureConfig.EXTRA_USE_OPUS, featureConfig.useOpus)
                putExtra(IntercomFeatureConfig.EXTRA_USE_JITTER_BUFFER, featureConfig.useJitterBuffer)
                putExtra(IntercomFeatureConfig.EXTRA_USE_VAD_PRE_ROLL, featureConfig.useVadPreRoll)
                putExtra(
                    IntercomFeatureConfig.EXTRA_USE_TIMESTAMP_LATENCY_TEST,
                    featureConfig.useTimestampLatencyTest
                )
                putExtra(
                    IntercomFeatureConfig.EXTRA_LATENCY_CLOCK_OFFSET_MS,
                    featureConfig.latencyClockOffsetMs
                )
                putExtra(
                    IntercomFeatureConfig.EXTRA_LATENCY_SAMPLE_EVERY_PACKETS,
                    featureConfig.latencySampleEveryPackets
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IntercomAudioService::class.java))
        }
    }

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var audioDeviceManager: AudioDeviceManager
    private var audioLoopback: AudioLoopbackManager? = null
    private var networkAudio: NetworkAudioManager? = null
    private var voiceStateJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        audioDeviceManager = AudioDeviceManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAudio()
                stopSelf()
            }

            ACTION_START, null -> {
                val communicationDeviceId =
                    intent?.getIntExtra(
                        EXTRA_COMMUNICATION_DEVICE_ID,
                        NO_DEVICE_ID
                    ) ?: NO_DEVICE_ID

                val peerHost = intent?.getStringExtra(EXTRA_PEER_HOST)
                val localPort = intent?.getIntExtra(
                    EXTRA_LOCAL_PORT,
                    NetworkAudioManager.DEFAULT_PORT
                ) ?: NetworkAudioManager.DEFAULT_PORT
                val peerPort = intent?.getIntExtra(
                    EXTRA_PEER_PORT,
                    NetworkAudioManager.DEFAULT_PORT
                ) ?: NetworkAudioManager.DEFAULT_PORT
                val featureConfig = IntercomFeatureConfig.fromIntent(intent)

                startAudio(
                    communicationDeviceId = communicationDeviceId,
                    peerHost = peerHost,
                    localPort = localPort,
                    peerPort = peerPort,
                    featureConfig = featureConfig
                )
            }
        }

        // Do not automatically recreate the intercom after the process is killed.
        return START_NOT_STICKY
    }

    private fun startAudio(
        communicationDeviceId: Int,
        peerHost: String?,
        localPort: Int,
        peerPort: Int,
        featureConfig: IntercomFeatureConfig
    ) {
        if (isRunning) return

        try {
            // Must enter foreground immediately for a microphone FGS.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    peerHost = peerHost
                )
            )

            audioDeviceManager.beginCommunicationMode()

            if (communicationDeviceId != NO_DEVICE_ID) {
                val device = audioDeviceManager
                    .getCommunicationDevices()
                    .firstOrNull { it.id == communicationDeviceId }

                if (device == null) {
                    Log.w(
                        TAG,
                        "Requested communication device is no longer available: $communicationDeviceId"
                    )
                } else if (!audioDeviceManager.setCommunicationDevice(device)) {
                    Log.w(
                        TAG,
                        "Android did not accept communication device: ${device.id}"
                    )
                }
            }

            voiceStateJob?.cancel()
            voiceStateJob = null

            if (!peerHost.isNullOrBlank()) {
                val network = NetworkAudioManager(featureConfig)

                if (!network.start(
                        scope = serviceScope,
                        peerHost = peerHost,
                        localPort = localPort,
                        peerPort = peerPort
                    )
                ) {
                    Log.e(TAG, "Unable to start network intercom audio")
                    network.stop()
                    cleanupAudioRouting()
                    stopForegroundCompat()
                    stopSelf()
                    return
                }

                networkAudio = network
                voiceStateJob = serviceScope.launch {
                    network.debugState.collect { state ->
                        _voiceDetected.value = state.voiceDetected
                        _debugState.value = state
                    }
                }
            } else {
                val loopback = AudioLoopbackManager()

                if (!loopback.start(serviceScope)) {
                    Log.e(TAG, "Unable to start audio loopback")
                    loopback.stop()
                    cleanupAudioRouting()
                    stopForegroundCompat()
                    stopSelf()
                    return
                }

                audioLoopback = loopback
                voiceStateJob = serviceScope.launch {
                    loopback.debugState.collect { state ->
                        _voiceDetected.value = state.voiceDetected
                        _debugState.value = state
                    }
                }
            }

            setRunning(true)
            Log.i(
                TAG,
                if (!peerHost.isNullOrBlank()) {
                    "Foreground network intercom started: $peerHost:$peerPort (${featureConfig.pipelineDescription})"
                } else {
                    "Foreground local audio test started"
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing microphone or Bluetooth permission", e)
            cleanupAudioRouting()
            stopForegroundCompat()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground intercom audio", e)
            cleanupAudioRouting()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopAudio() {
        voiceStateJob?.cancel()
        voiceStateJob = null
        audioLoopback?.stop()
        audioLoopback = null

        networkAudio?.stop()
        networkAudio = null

        _voiceDetected.value = false
        _debugState.value = AudioDebugState()
        cleanupAudioRouting()
        setRunning(false)
        stopForegroundCompat()

        Log.i(TAG, "Foreground intercom audio stopped")
    }

    private fun cleanupAudioRouting() {
        try {
            audioDeviceManager.clearCommunicationDevice()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clear communication device", e)
        }

        try {
            audioDeviceManager.endCommunicationMode()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to end communication mode", e)
        }
    }

    /**
     * Called when the app task is removed from Recents.
     * Explicitly stop the intercom rather than letting it continue invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed from Recents; stopping intercom")
        stopAudio()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Intercom audio",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while rider intercom audio is active."
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(peerHost: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(peerHost?.let { "Intercom active • peer $it" } ?: "Audio test is active")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        stopAudio()
        serviceScope.cancel()
        audioDeviceManager.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
