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

        private val _running = MutableStateFlow(false)
        val runningState: StateFlow<Boolean> = _running.asStateFlow()

        val isRunning: Boolean
            get() = _running.value

        private fun setRunning(running: Boolean) {
            _running.value = running
        }

        fun start(
            context: Context,
            communicationDeviceId: Int = NO_DEVICE_ID
        ) {
            val intent = Intent(context, IntercomAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COMMUNICATION_DEVICE_ID, communicationDeviceId)
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

                startAudio(communicationDeviceId)
            }
        }

        // Do not automatically recreate the intercom after the process is killed.
        return START_NOT_STICKY
    }

    private fun startAudio(communicationDeviceId: Int) {
        if (isRunning) return

        try {
            // Must enter foreground immediately for a microphone FGS.
            startForeground(
                NOTIFICATION_ID,
                buildNotification()
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
            setRunning(true)
            Log.i(TAG, "Foreground intercom audio started")
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
        audioLoopback?.stop()
        audioLoopback = null

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

    private fun buildNotification(): Notification {
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
            .setContentText("Intercom audio is active")
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
