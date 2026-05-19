package com.example.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

object AvatarManager {

    var changeCounter by mutableIntStateOf(0)
        private set

    private fun file(context: Context, id: String) =
        File(context.filesDir, "avatar_${id.trim().lowercase()}.jpg")

    fun hasAvatar(context: Context, id: String): Boolean = file(context, id).exists()

    fun save(context: Context, id: String, src: Bitmap) {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val cropped = if (size == src.width && size == src.height) src
                      else Bitmap.createBitmap(src, x, y, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        file(context, id).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (cropped !== src) cropped.recycle()
        if (scaled !== cropped) scaled.recycle()
        changeCounter++
    }

    fun load(context: Context, id: String): Bitmap? {
        val f = file(context, id)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        changeCounter++
    }
}
