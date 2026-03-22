package com.example.ble

import android.Manifest
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

    private const val CHANNEL_ID = "mesh_messages"

    fun showMessageNotification(
        context    : Context,
        senderName : String,
        preview    : String,
        contactId  : String
    ) {
        val nm = NotificationManagerCompat.from(context)

        // Explicitly handle "notifications disabled" and Android 13+ runtime permission.
        if (!nm.areNotificationsEnabled()) {
            AppLogger.w("Notify", "Notifications disabled; skip notification")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                AppLogger.w("Notify", "POST_NOTIFICATIONS not granted; skip notification")
                return
            }
        }

        ensureChannel(context)

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("contactId", contactId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pendingIntent = PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            openChatIntent,
            flags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            nm.notify(contactId.hashCode(), notification)
        } catch (se: SecurityException) {
            AppLogger.e("Notify", "notify() SecurityException: ${se.message}", se)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        // Safe to call repeatedly — Android ignores duplicate channel creation.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setShowBadge(true)
        }

        manager.createNotificationChannel(channel)
    }
}