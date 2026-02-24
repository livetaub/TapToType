package com.taptotype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the Bluetooth HID connection alive
 * when the app is backgrounded or the screen is locked.
 */
class HidForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "taptotype_hid_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.taptotype.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val deviceName = intent?.getStringExtra("device_name") ?: "PC"
        startForeground(NOTIFICATION_ID, buildNotification(deviceName))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keyboard Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when TapToType is connected as a keyboard"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(deviceName: String): Notification {
        // Tap notification → open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect action
        val stopIntent = Intent(this, HidForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⌨️ TapToType Active")
            .setContentText("Connected to $deviceName")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Disconnect", stopPending)
            .build()
    }
}
