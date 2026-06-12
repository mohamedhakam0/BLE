package com.example.ble

/**
 * Builds and posts message notifications for incoming mesh chat packets.
 *
 * Notifications are grouped per sender using `MessagingStyle` and a deterministic notification ID.
 * The helper also keeps a small in-memory history so expanding a notification shows recent lines.
 */

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {

    private const val CHANNEL_ID = "mesh_messages"

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
     * Uses the contact's saved avatar photo, or falls back to a colored initial circle.
     */
    fun showMessageNotification(
        context    : Context,
        senderName : String,
        preview    : String,
        contactId  : String
    ) {
        val senderKey = contactId.trim().lowercase()
        val nm = NotificationManagerCompat.from(context)

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

        val avatarBitmap = loadAvatarBitmap(context, senderKey, senderName)
        val avatarIcon   = IconCompat.createWithBitmap(avatarBitmap)

        val me = Person.Builder().setName("Me").build()
        val senderPerson = Person.Builder()
            .setName(senderName)
            .setIcon(avatarIcon)
            .build()

        val style = NotificationCompat.MessagingStyle(me)

        for (line in deque) {
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(line.text, line.timestampMs, senderPerson)
            )
        }

        val latestText = deque.lastOrNull()?.text ?: preview
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(latestText)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setLargeIcon(avatarBitmap)
            .setStyle(style)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .build()

        try {
            nm.notify(notificationIdForSender(senderKey), notification)
        } catch (se: SecurityException) {
            AppLogger.e("Notify", "notify() SecurityException: ${se.message}", se)
        }
    }

    /**
     * Shows a reaction notification: "'Name' reacted ❤️ to 'message text'"
     */
    fun showReactionNotification(
        context       : Context,
        senderName    : String,
        emoji         : String,
        messagePreview: String,
        contactId     : String
    ) {
        val senderKey = contactId.trim().lowercase()
        val nm = NotificationManagerCompat.from(context)

        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannel(context)

        val body = if (messagePreview.isNotBlank())
            "reacted $emoji to $messagePreview"
        else
            "reacted $emoji"

        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("contactId", senderKey)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            context,
            (senderKey.hashCode() xor 0x7F),
            openChatIntent,
            flags
        )

        val avatarBitmap = loadAvatarBitmap(context, senderKey, senderName)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setLargeIcon(avatarBitmap)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(senderKey.hashCode() xor 0x7F, notification)
        } catch (se: SecurityException) {
            AppLogger.e("Notify", "notify() SecurityException: ${se.message}", se)
        }
    }

    /**
     * Returns the contact's saved avatar bitmap, or a deterministic colored circle
     * with the contact's initial letter when no photo has been set.
     */
    private fun loadAvatarBitmap(context: Context, senderKey: String, senderName: String): Bitmap {
        val avatarBytes = AvatarManager.loadPeerAvatar(context, senderKey)
        if (avatarBytes != null) {
            android.graphics.BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size)?.let { return it }
        }

        // Fallback: draw a solid-colored circle with the initial letter.
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Derive a hue from the sender key so each contact gets a consistent color.
        val hue = ((senderKey.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        val hsv = floatArrayOf(hue, 0.55f, 0.72f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.HSVToColor(hsv)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        val initial = senderName.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?"
        paint.color = android.graphics.Color.WHITE
        paint.textSize = size * 0.42f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, textY, paint)

        return bmp
    }

    private fun notificationIdForSender(senderKey: String): Int = senderKey.hashCode()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
