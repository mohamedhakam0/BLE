/**
 * Builds and posts message notifications for incoming mesh chat packets.
 *
 * Notifications are grouped per sender using `MessagingStyle` and a deterministic notification ID.
 * The helper also keeps a small in-memory history so expanding a notification shows recent lines.
 */
package com.example.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Utility object for notification channel management and per-conversation updates. */
object NotificationHelper {

    private const val CHANNEL_ID = "mesh_messages"

    // Keep the last few messages per senderId for MessagingStyle expansion.
    private const val MAX_PER_SENDER = 6
    private val messageBuffers: MutableMap<String, ArrayDeque<NotifLine>> = ConcurrentHashMap()

    private data class NotifLine(
        val text: String,
        val timestampMs: Long
    )

    /** Clears the buffered lines for a sender and cancels the notification. */
    fun clearConversation(context: Context, senderId: String) {
        val key = senderId.trim().lowercase()
        messageBuffers.remove(key)
        NotificationManagerCompat.from(context).cancel(notificationIdForSender(key))
    }

    /**
     * Shows or updates a sender-specific notification with recent messages in MessagingStyle.
     *
     * @param senderName display name shown in the notification title.
     * @param preview latest received message text.
     * @param contactId sender ID used for deterministic notification ID and deep-link routing.
     */
    fun showMessageNotification(
        context    : Context,
        senderName : String,
        preview    : String,
        contactId  : String
    ) {
        val senderKey = contactId.trim().lowercase()
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

        // Append message to per-sender buffer.
        val now = System.currentTimeMillis()
        val deque = messageBuffers.getOrPut(senderKey) { ArrayDeque() }
        deque.addLast(NotifLine(text = preview, timestampMs = now))
        while (deque.size > MAX_PER_SENDER) deque.removeFirst()

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("contactId", senderKey)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationIdForSender(senderKey),
            openChatIntent,
            flags
        )

        // MessagingStyle with a running list of messages for this sender.
        // Note: This project’s androidx.core version doesn’t expose NotificationCompat.Person
        // or setConversationTitle(). Use the legacy-compatible MessagingStyle API.
        val style = NotificationCompat.MessagingStyle(/* userDisplayName = */ "Peer Reach")
            .setGroupConversation(true)

        // Oldest → newest.
        for (line in deque) {
            style.addMessage(line.text, line.timestampMs, /* sender = */ senderName)
        }

        val latestText = deque.lastOrNull()?.text ?: preview
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(latestText)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setStyle(style)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .build()

        // Large icon (full color) in notification body.
        val largeIcon: Bitmap? = runCatching {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        }.getOrNull()


        try {
            nm.notify(notificationIdForSender(senderKey), notification)
        } catch (se: SecurityException) {
            AppLogger.e("Notify", "notify() SecurityException: ${se.message}", se)
        }
    }

    /** Maps sender key to stable notification ID so updates replace existing conversation cards. */
    private fun notificationIdForSender(senderKey: String): Int = senderKey.hashCode()

    /** Creates the high-importance notification channel if missing (safe to call repeatedly). */
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