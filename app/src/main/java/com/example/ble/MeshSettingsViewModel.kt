package com.example.ble

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.ui.OnboardingPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen, backed by plain SharedPreferences
 * ("peer_reach_settings"). Every setter persists immediately and updates its
 * StateFlow; nothing here touches the X25519 keypair, packet format, or Room.
 *
 * Preference consumers:
 *  - "relay_mode_enabled" — read at the relay gate in ForegroundMeshService.
 *  - "tx_power_level"     — applied via [BleAdvertiser.txPowerLevel], read when
 *                           each advertising set is built.
 *  - "packet_ttl"         — persisted only for now (see [onTtlChanged]).
 */
class MeshSettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val PREFS_NAME       = "peer_reach_settings"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_MESH_BG      = "mesh_bg_enabled"
        const val KEY_RELAY_MODE   = "relay_mode_enabled"
        const val KEY_TX_POWER     = "tx_power_level"
        const val KEY_TTL          = "packet_ttl"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nodeIdentity = NodeIdentity(application)

    private val _displayName = MutableStateFlow(
        nodeIdentity.getNickname() ?: prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
    )
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _meshInBackground = MutableStateFlow(prefs.getBoolean(KEY_MESH_BG, true))
    val meshInBackground: StateFlow<Boolean> = _meshInBackground.asStateFlow()

    private val _relayModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_RELAY_MODE, true))
    val relayModeEnabled: StateFlow<Boolean> = _relayModeEnabled.asStateFlow()

    private val _txPowerLevel = MutableStateFlow(prefs.getInt(KEY_TX_POWER, 3).coerceIn(0, 3))
    val txPowerLevel: StateFlow<Int> = _txPowerLevel.asStateFlow()

    private val _ttl = MutableStateFlow(prefs.getInt(KEY_TTL, 6).coerceIn(2, 7))
    val ttl: StateFlow<Int> = _ttl.asStateFlow()

    init {
        // Re-apply the persisted TX power so the advertiser honours it after a
        // process restart (the companion default matches the preference default).
        BleAdvertiser.txPowerLevel = _txPowerLevel.value
    }

    fun onDisplayNameChanged(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        // setNickname() writes the same profile-prefs key NodeIdentity already
        // reads from; it never generates or touches key material.
        nodeIdentity.setNickname(trimmed)
        _displayName.value = trimmed
    }

    fun onBackgroundToggleChanged(enabled: Boolean, context: Context) {
        prefs.edit().putBoolean(KEY_MESH_BG, enabled).apply()
        _meshInBackground.value = enabled
        val intent = Intent(context, ForegroundMeshService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }

    fun onRelayModeChanged(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RELAY_MODE, enabled).apply()
        _relayModeEnabled.value = enabled
    }

    fun onTxPowerChanged(level: Int) {
        val clamped = level.coerceIn(0, 3)
        prefs.edit().putInt(KEY_TX_POWER, clamped).apply()
        _txPowerLevel.value = clamped
        // Advertising parameters are rebuilt for every broadcast attempt, so the
        // new level applies from the next advertising set — no restart needed.
        BleAdvertiser.txPowerLevel = clamped
    }

    fun onTtlChanged(value: Int) {
        val clamped = value.coerceIn(2, 7)
        prefs.edit().putInt(KEY_TTL, clamped).apply()
        _ttl.value = clamped
    }

    /** Deletes all contacts and their full chat history from the local DB. */
    fun clearAllKeys(onDone: () -> Unit) {
        viewModelScope.launch {
            ContactRepository.getInstance(getApplication()).deleteAllContactsAndChats()
            onDone()
        }
    }

    /** Wipes the local X25519 keypair and resets the onboarding flag so the intro replays. */
    fun resetNodeIdentity() {
        nodeIdentity.resetIdentity()
        OnboardingPrefs.reset(getApplication())
    }
}
