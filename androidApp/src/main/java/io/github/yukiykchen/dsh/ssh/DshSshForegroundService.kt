package io.github.yukiykchen.dsh.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.yukiykchen.dsh.R

/** Keeps an explicitly enabled SSH tunnel visible to the user while backgrounded. */
internal class DshSshForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("SSH 隧道保活中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SshTunnelManager.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SshTunnelManager.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val stop = Intent(this, DshSshForegroundService::class.java).apply { action = ACTION_STOP }
        val pending = android.app.PendingIntent.getService(
            this, 0, stop,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DSH SSH")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "断开", pending)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH SSH 连接", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_STOP = "io.github.yukiykchen.dsh.ssh.STOP"
        private const val CHANNEL_ID = "dsh-ssh"
        private const val NOTIFICATION_ID = 3080
    }
}