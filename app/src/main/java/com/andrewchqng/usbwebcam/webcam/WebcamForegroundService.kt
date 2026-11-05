package com.andrewchqng.usbwebcam.webcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.andrewchqng.usbwebcam.MainActivity
import com.andrewchqng.usbwebcam.R
import timber.log.Timber

class WebcamForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WebcamController.getInstance(application).stopUvcSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val videoDevicePath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: "/dev/video2"
                val maxPayload = intent.getIntExtra(EXTRA_MAX_PAYLOAD, 3072)
                startForegroundWithNotification(videoDevicePath)
                acquireWakeLock()
                WebcamController.getInstance(application).startUvcSession(videoDevicePath, maxPayload)
                return START_REDELIVER_INTENT
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(videoDevicePath: String) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WebcamForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("USB Webcam Active")
            .setContentText("UVC node: $videoDevicePath")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "USBWebcam::UvcStreamingWakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // up to 6 hours
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        Timber.i("WebcamForegroundService onDestroy")
        WebcamController.getInstance(application).stopUvcSession()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USB Webcam Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps USB UVC webcam streaming active while screen is off"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "usb_webcam_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.andrewchqng.usbwebcam.action.START"
        const val ACTION_STOP = "com.andrewchqng.usbwebcam.action.STOP"
        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_MAX_PAYLOAD = "extra_max_payload"

        fun start(context: Context, videoPath: String, maxPayloadSize: Int = 3072) {
            val intent = Intent(context, WebcamForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VIDEO_PATH, videoPath)
                putExtra(EXTRA_MAX_PAYLOAD, maxPayloadSize)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebcamForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
