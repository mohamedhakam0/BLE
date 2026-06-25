package com.example.ble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble.MeshSettingsViewModel
import kotlin.math.roundToInt

/**
 * Settings screen: identity, connectivity, and protocol preferences.
 * Purely additive — persists via MeshSettingsViewModel ("peer_reach_settings").
 */
@Composable
fun MeshSettingsScreen(
    onBack: () -> Unit,
    onIdentityReset: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val vm: MeshSettingsViewModel = viewModel()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val displayName by vm.displayName.collectAsStateWithLifecycle()
    val meshInBackground by vm.meshInBackground.collectAsStateWithLifecycle()
    val relayModeEnabled by vm.relayModeEnabled.collectAsStateWithLifecycle()
    val txPowerLevel by vm.txPowerLevel.collectAsStateWithLifecycle()
    val ttl by vm.ttl.collectAsStateWithLifecycle()

    var nameInput by remember(displayName) { mutableStateOf(displayName) }
    var nameHadFocus by remember { mutableStateOf(false) }
    var showClearKeysDialog by remember { mutableStateOf(false) }
    var showResetIdentityDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .navigationBarsPadding()
    ) {
        // ── Header ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ── IDENTITY ──
        SettingsSectionLabel("Identity")

        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Display Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                vm.onDisplayNameChanged(nameInput)
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (nameHadFocus && !state.isFocused) vm.onDisplayNameChanged(nameInput)
                    nameHadFocus = state.isFocused
                }
        )

        Spacer(Modifier.height(20.dp))

        // ── CONNECTIVITY ──
        SettingsSectionLabel("Connectivity")

        SettingSwitchRow(
            icon = Icons.Default.Sync,
            title = "Run Mesh in Background",
            subtitle = "Keeps mesh active when app is closed",
            checked = meshInBackground,
            onCheckedChange = { vm.onBackgroundToggleChanged(it, context) }
        )

        SettingSwitchRow(
            icon = Icons.Default.Router,
            title = "Act as Relay",
            subtitle = if (meshInBackground) "Forward packets from other users"
                       else "Enable background mesh to use relay",
            checked = relayModeEnabled,
            enabled = meshInBackground,
            onCheckedChange = { vm.onRelayModeChanged(it) }
        )

        SettingRowHeader(
            icon = Icons.Default.SignalCellularAlt,
            title = "Transmit Power"
        )
        Slider(
            value = txPowerLevel.toFloat(),
            onValueChange = {
                val level = it.roundToInt()
                if (level != txPowerLevel) vm.onTxPowerChanged(level)
            },
            valueRange = 0f..3f,
            steps = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SliderTickLabel("Short")
            SliderTickLabel("Low")
            SliderTickLabel("Medium")
            SliderTickLabel("Long Range")
        }

        Spacer(Modifier.height(20.dp))

        // ── PROTOCOL ──
        SettingsSectionLabel("Protocol")

        SettingRowHeader(
            icon = Icons.Default.Hub,
            title = "Message Range (Hops)",
            subtitle = "How far your messages travel through the mesh"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (2..7).forEach { hops ->
                val selected = hops == ttl
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { vm.onTtlChanged(hops) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$hops",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Higher values reach more devices but use more battery on relay nodes.",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )

        Spacer(Modifier.height(24.dp))

        // ── DANGER ZONE ──
        SettingsSectionLabel("Danger Zone")

        DangerActionRow(
            icon = Icons.Filled.DeleteForever,
            title = "Clear all contacts",
            subtitle = "Permanently deletes all contacts and chat history",
            onClick = { showClearKeysDialog = true }
        )

        DangerActionRow(
            icon = Icons.Filled.Key,
            title = "Reset node identity",
            subtitle = "Wipes your keypair and replays onboarding on next launch",
            onClick = { showResetIdentityDialog = true }
        )

        Spacer(Modifier.height(32.dp))
    }

    if (showClearKeysDialog) {
        AlertDialog(
            onDismissRequest = { showClearKeysDialog = false },
            title = { Text("Clear all contacts?") },
            text = {
                Text("All contacts and their full chat history will be permanently deleted. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearKeysDialog = false
                        vm.clearAllKeys { onBack() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearKeysDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showResetIdentityDialog) {
        AlertDialog(
            onDismissRequest = { showResetIdentityDialog = false },
            title = { Text("Reset node identity?") },
            text = {
                Text("Your encryption keypair will be permanently wiped. You'll receive a new identity and will need to re-add all contacts. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetIdentityDialog = false
                        vm.resetNodeIdentity()
                        onIdentityReset()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetIdentityDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** Small-caps monospace section label with a divider, matching the app's header style. */
@Composable
private fun SettingsSectionLabel(text: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
    Spacer(Modifier.height(12.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    )
    Spacer(Modifier.height(10.dp))
}

/** Icon + title (+ optional subtitle) header for slider settings. */
@Composable
private fun SettingRowHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/** Icon + texts + trailing switch row. Dimmed (but not value-forced) when [enabled] is false. */
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SliderTickLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
    )
}

@Composable
private fun DangerActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            )
        }
    }
}
