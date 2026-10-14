package com.brandonwallace.starshot.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.brandonwallace.starshot.MainActivity

/** Foreground service keeping camera + processing alive while the screen is dark/off. */
class CaptureService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Capturing the night sky"
        runCatching { startForeground(ID, build(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) }
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarShot:session").apply { acquire(6 * 60 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { runCatching { wake?.release() }; wake = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "session"
        private const val ID = 7
        private const val EXTRA_TEXT = "text"

        private fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capture session", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("StarShot").setContentText(text).setOngoing(true).setContentIntent(pi).build()
        }

        fun start(ctx: Context, text: String = "Starting session") {
            runCatching { ctx.startForegroundService(Intent(ctx, CaptureService::class.java).putExtra(EXTRA_TEXT, text)) }
        }

        fun update(ctx: Context, text: String) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, text)) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, CaptureService::class.java)) } }
    }
}
