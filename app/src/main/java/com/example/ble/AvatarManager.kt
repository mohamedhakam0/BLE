package com.example.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream

object AvatarManager {

    var changeCounter by mutableIntStateOf(0)
        private set

    private const val PREFS_NAME = "avatar_store"
    private const val KEY_MY_AVATAR = "my_avatar_jpeg"

    @Volatile private var cachedPrefs: android.content.SharedPreferences? = null

    private fun openPrefs(context: Context): android.content.SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs ?: buildPrefs(context.applicationContext).also { cachedPrefs = it }
        }
    }

    private fun buildPrefs(ctx: Context): android.content.SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            ctx, PREFS_NAME,
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return try {
            create()
        } catch (_: Exception) {
            ctx.deleteSharedPreferences(PREFS_NAME)
            create()
        }
    }

    fun saveMyAvatar(context: Context, jpeg: ByteArray) {
        val compressed = compressAvatar(jpeg, 96) ?: return
        val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        openPrefs(context).edit().putString(KEY_MY_AVATAR, b64).apply()
        changeCounter++
    }

    fun loadMyAvatar(context: Context): ByteArray? {
        val b64 = openPrefs(context).getString(KEY_MY_AVATAR, null) ?: return null
        return try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { null }
    }

    fun savePeerAvatar(context: Context, senderId: String, jpeg: ByteArray) {
        val compressed = compressAvatar(jpeg, 96) ?: return
        val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        openPrefs(context).edit().putString("peer_avatar_$senderId", b64).apply()
        changeCounter++
    }

    fun loadPeerAvatar(context: Context, senderId: String): ByteArray? {
        val b64 = openPrefs(context).getString("peer_avatar_$senderId", null) ?: return null
        return try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { null }
    }

    fun clearPeerAvatar(context: Context, senderId: String) {
        openPrefs(context).edit().remove("peer_avatar_$senderId").apply()
        changeCounter++
    }

    private fun compressAvatar(jpeg: ByteArray, maxSize: Int): ByteArray? {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val scaled = if (src.width > maxSize || src.height > maxSize)
            Bitmap.createScaledBitmap(src, maxSize, maxSize, true)
        else src
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return out.toByteArray()
    }
}
