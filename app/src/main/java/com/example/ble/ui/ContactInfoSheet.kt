package com.example.ble.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ble.AvatarManager
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoSheet(
    contactName: String,
    senderIdHex: String,
    gradientSeedHex: String,
    publicKeyB64: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val counter = AvatarManager.changeCounter
    val hasCustom = remember(senderIdHex, counter) { AvatarManager.hasAvatar(context, senderIdHex) }

    val fingerprint = remember(publicKeyB64) {
        if (publicKeyB64.isBlank()) return@remember emptyList()
        try {
            val keyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            digest.take(16).chunked(2).map { pair ->
                pair.joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    val senderShort = remember(senderIdHex) {
        if (senderIdHex.length > 12) "${senderIdHex.take(8)}…${senderIdHex.takeLast(4)}"
        else senderIdHex
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Contact Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(20.dp))

            if (hasCustom) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GradientAvatarCircle(gradientSeedHex = gradientSeedHex, size = 64.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "generated",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ContactAvatarCircle(
                            senderIdHex = senderIdHex,
                            gradientSeedHex = gradientSeedHex,
                            size = 64.dp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "custom",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                ContactAvatarCircle(
                    senderIdHex = senderIdHex,
                    gradientSeedHex = gradientSeedHex,
                    size = 80.dp
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                contactName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                senderShort,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )

            if (fingerprint.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "PUBLIC KEY FINGERPRINT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fingerprint.chunked(4).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { block ->
                                val chipColor = remember(block) { hexToFingerprintColor(block) }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = chipColor,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        block,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.85f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
