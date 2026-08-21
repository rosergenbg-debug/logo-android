package com.rosergenbg.logo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground companion service for a running DAF session.
 * It keeps the process in foreground priority while the Activity is covered or backgrounded.
 * The real-time audio engine remains owned by MainActivity in v1.1.
 */
class AudioKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "logo_daf_active"
        private const val NOTIFICATION_ID = 1101
        private const val EXTRA_DELAY = "delay_ms"

        fun start(context: Context, delayMs: Int) {
            val intent = Intent(context, AudioKeepAliveService::class.java)
                .putExtra(EXTRA_DELAY, delayMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioKeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Лого — активный DAF",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает, что слуховая обратная связь продолжает работать"
                setSound(null, null)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val delayMs = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("Лого работает")
            .setContentText("DAF активен • добавлено $delayMs мс")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
