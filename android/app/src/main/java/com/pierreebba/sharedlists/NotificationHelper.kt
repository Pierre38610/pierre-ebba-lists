package com.pierreebba.sharedlists

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_PARTNER_UPDATES = "partner_updates"
    private const val CHANNEL_LIVE_STATUS = "live_sync_status"
    private const val LIVE_STATUS_NOTIF_ID = 9001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val updatesChannel = NotificationChannel(
            CHANNEL_PARTNER_UPDATES,
            "Partner Updates & Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Instant message-style alerts when your partner updates lists, notes, recipes, or daily words."
            enableLights(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 90L, 180L)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(updatesChannel)

        // Clean up legacy foreground status notification & channel from v1.0.0
        nm.cancel(LIVE_STATUS_NOTIF_ID)
        try {
            nm.deleteNotificationChannel(CHANNEL_LIVE_STATUS)
        } catch (_: Exception) {
        }
    }

    fun hasPostPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun showPartnerNotification(
        context: Context,
        notifId: String,
        title: String,
        body: String,
        tab: String = "lists",
        deduplicate: Boolean = true
    ) {
        ensureChannels(context)
        if (!hasPostPermission(context)) return
        if (deduplicate && !AppPrefs.markNotifSeen(context, notifId)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
        }
        val reqCode = (notifId.hashCode() and 0x7FFFFFFF).coerceAtLeast(200)
        val pi = PendingIntent.getActivity(
            context,
            reqCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_PARTNER_UPDATES)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setContentTitle(title.ifBlank { "Pierre & Ebba" })
            .setContentText(body.ifBlank { "Shared update" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Shared update" }))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(reqCode, notif)
        } catch (_: SecurityException) {
        }
    }
}
