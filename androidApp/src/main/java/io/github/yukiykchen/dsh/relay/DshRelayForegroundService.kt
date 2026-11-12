package io.github.yukiykchen.dsh.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.yukiykchen.dsh.R

internal class DshRelayForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ requires startForeground() after startForegroundService(),
        // including when the service instance already exists and onCreate is skipped.
        promoteToForeground()
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            DshRelayManager.disconnect()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        createChannel()
        val notification = notification("扫码隧道保活中")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val stop = Intent(this, DshRelayForegroundService::class.java).apply { action = ACTION_STOP }
        val pending = android.app.PendingIntent.getService(
            this, 0, stop,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DSH 扫码连接")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "断开", pending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH 扫码连接", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_STOP = "io.github.yukiykchen.dsh.relay.STOP"
        private const val CHANNEL_ID = "dsh-relay"
        private const val NOTIFICATION_ID = 8787
    }
}