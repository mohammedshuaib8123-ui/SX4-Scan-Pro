package com.sx4scanpro

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the app process alive while a trip is recording or paused, so
 * switching apps or turning the screen off does not stop the OBD session. It owns no trip data:
 * the session lives in MainActivity and is persisted by [TripSession]/[TripCodec].
 * The notification carries a Stop action that ends the trip through [onStopRequested].
 */
class TripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val handler = onStopRequested
            if (handler != null) handler() else getSharedPreferences("sx4_scan_pro", MODE_PRIVATE).edit().remove("trip_active").apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Trip recording"
        val note = build(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, note)
        } catch (_: Exception) {
            stopSelf()   // foreground start refused by the system: no silent half-running service
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.sx4scanpro.TRIP_STOP"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL = "trip"
        private const val NOTIF_ID = 4101
        /** Set by MainActivity while it is alive. */
        @Volatile var onStopRequested: (() -> Unit)? = null

        private fun build(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trip recording", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, TripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("SX4 Scan Pro trip")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Stop trip", stop)
                .build()
        }

        /** Start, or refresh the notification text (a second start just re-posts it). */
        fun show(ctx: Context, text: String) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java).putExtra(EXTRA_TEXT, text)) } catch (_: Exception) { }
        }

        fun hide(ctx: Context) { ctx.stopService(Intent(ctx, TripService::class.java)) }
    }
}
