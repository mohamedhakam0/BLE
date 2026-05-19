package com.example.ble.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ble.AvatarManager
import com.example.ble.ui.theme.NodeGreen
import java.security.MessageDigest

fun generateAvatarGradient(publicKeyBytes: ByteArray): Brush {
    val h1 = (publicKeyBytes[0].toInt() and 0xFF) / 255f * 360f
    val h2 = (publicKeyBytes[1].toInt() and 0xFF) / 255f * 360f
    val h3 = (publicKeyBytes[2].toInt() and 0xFF) / 255f * 360f
    return Brush.radialGradient(
        listOf(
            Color.hsl(h1, 0.7f, 0.6f),
            Color.hsl(h2, 0.7f, 0.55f),
            Color.hsl(h3, 0.65f, 0.45f)
        )
    )
}

@Composable
fun GradientAvatarCircle(
    gradientSeedHex: String,
    size: Dp = 80.dp
) {
    val bytes = remember(gradientSeedHex) {
        if (gradientSeedHex.length >= 6) {
            try {
                ByteArray(3) { i ->
                    gradientSeedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) { null }
        } else null
    }

    if (bytes != null) {
        val gradient = remember(bytes[0], bytes[1], bytes[2]) {
            generateAvatarGradient(bytes)
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(gradient)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun ContactAvatarCircle(
    senderIdHex: String,
    gradientSeedHex: String,
    size: Dp = 44.dp
) {
    val context = LocalContext.current
    val counter = AvatarManager.changeCounter
    val bitmap = remember(senderIdHex, counter) { AvatarManager.load(context, senderIdHex) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        GradientAvatarCircle(gradientSeedHex = gradientSeedHex, size = size)
    }
}

internal fun hexToFingerprintColor(hex: String): Color = try {
    val value = hex.toLong(16).toFloat()
    val hue = 220f + (value / 0xFFFF.toFloat()) * 80f
    Color.hsl(hue, 0.65f, 0.45f)
} catch (_: Exception) {
    Color.Gray
}

@Composable
fun TrustVerificationScreen(
    peerName: String,
    peerSenderId: String,
    publicKeyBytes: ByteArray,
    onTrust: (finalName: String) -> Unit,
    onCancel: () -> Unit
) {
    var displayName by remember { mutableStateOf(peerName) }
    var isEditingName by remember { mutableStateOf(false) }

    val fingerprint = remember(publicKeyBytes) {
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            digest.take(16).chunked(2).map { pair ->
                pair.joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) {
            List(8) { "----" }
        }
    }

    val senderIdDisplay = remember(peerSenderId) {
        if (peerSenderId.length > 12) {
            "${peerSenderId.take(8)}…${peerSenderId.takeLast(4)}"
        } else peerSenderId
    }

    val gradientSeedHex = remember(publicKeyBytes) {
        if (publicKeyBytes.size >= 3)
            "%02X%02X%02X".format(
                publicKeyBytes[0].toInt() and 0xFF,
                publicKeyBytes[1].toInt() and 0xFF,
                publicKeyBytes[2].toInt() and 0xFF
            )
        else ""
    }

    var showMitmDialog by remember { mutableStateOf(false) }

    if (showMitmDialog) {
        AlertDialog(
            onDismissRequest = { showMitmDialog = false },
            title = { Text("What am I checking?") },
            text = {
                Text(
                    "This visual fingerprint is derived from your peer's public key. " +
                    "When you and your peer see the same colors and code blocks, it proves " +
                    "nobody intercepted or modified the key during the exchange. " +
                    "If the display looks different on either device, cancel this connection — " +
                    "it may indicate a man-in-the-middle attack.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showMitmDialog = false }) { Text("Got it") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Top banner
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = Color(0xFF1A2E1A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = NodeGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Secure channel proposal received",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = NodeGreen
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 2. "You are verifying" card with rename
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "YOU ARE VERIFYING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(16.dp))
                GradientAvatarCircle(gradientSeedHex = gradientSeedHex, size = 80.dp)
                Spacer(Modifier.height(12.dp))

                // Name with edit button
                if (isEditingName) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(50) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { isEditingName = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Done")
                        }
                        OutlinedButton(
                            onClick = {
                                displayName = peerName
                                isEditingName = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit name",
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { isEditingName = true },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    senderIdDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. Visual fingerprint card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Visual fingerprint",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Both devices should match",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))

                // 4×2 colored tile grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fingerprint.chunked(4).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { block ->
                                val tileColor = remember(block) { hexToFingerprintColor(block) }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tileColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        block,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "Ask your peer to confirm that the colors and code blocks match on their screen. " +
                    "If anything looks different, cancel this connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "What am I checking?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showMitmDialog = true }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 4. Action buttons
        Button(
            onClick = { onTrust(displayName.trim().ifBlank { peerName }) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Trust and Continue", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface.copy(0.7f)
            )
        ) {
            Text("Cancel – details don't match")
        }
    }
}
