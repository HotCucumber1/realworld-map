package com.worldexplorer.app.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.worldexplorer.app.MainActivity
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.Formatters

object TrackingNotifications {
    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_TRACKING = 1001
    const val ID_INTERRUPTED = 1002

    enum class Signal { WAITING, OK, LOST, PROVIDER_OFF }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, context.getString(R.string.notif_channel_tracking), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_tracking_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
            },
        )
    }

    fun tracking(context: Context, distanceMeters: Double, points: Int, signal: Signal, startedAtUtc: Long?): Notification {
        val text = when (signal) {
            Signal.WAITING -> context.getString(R.string.notif_waiting_gps)
            Signal.LOST -> context.getString(R.string.notif_gps_lost)
            Signal.PROVIDER_OFF -> context.getString(R.string.notif_gps_off)
            Signal.OK -> context.getString(R.string.notif_tracking_text, Formatters.distance(context, distanceMeters), points)
        }
        val stop = PendingIntent.getService(
            context, 1, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.notif_tracking_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply { if (startedAtUtc != null) setWhen(startedAtUtc).setUsesChronometer(true).setShowWhen(true) }
            .addAction(0, context.getString(R.string.notif_stop), stop)
            .build()
    }

    fun showInterrupted(context: Context, text: String = context.getString(R.string.notif_interrupted_text)) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.notif_interrupted_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_INTERRUPTED, n) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
