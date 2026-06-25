package com.example.ble

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KeysViewModel(application: Application) : AndroidViewModel(application) {

    private val nodeIdentity = NodeIdentity(application)

    private val _localAvatarBitmap = MutableStateFlow<Bitmap?>(null)
    val localAvatarBitmap: StateFlow<Bitmap?> = _localAvatarBitmap.asStateFlow()

    private val _localDisplayName = MutableStateFlow(nodeIdentity.getNickname().orEmpty())
    val localDisplayName: StateFlow<String> = _localDisplayName.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = AvatarManager.loadMyAvatar(application) ?: return@launch
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) _localAvatarBitmap.value = bmp
        }
    }

    fun onAvatarSelected(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch
                AvatarManager.saveMyAvatar(context, bytes)
                val stored = AvatarManager.loadMyAvatar(context) ?: return@launch
                val bmp = BitmapFactory.decodeByteArray(stored, 0, stored.size)
                if (bmp != null) _localAvatarBitmap.value = bmp
            } catch (_: Exception) {
                // Silently skip on any decode / IO failure.
            }
        }
    }

    fun setDisplayName(name: String) {
        nodeIdentity.setNickname(name)
        _localDisplayName.value = name
    }
}
