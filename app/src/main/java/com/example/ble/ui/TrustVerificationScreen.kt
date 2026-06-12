package com.example.ble.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ── Avatar data model ─────────────────────────────────────────────────────────

private data class AvatarBlob(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val color: Color,
    val alpha: Float
)

private data class AvatarSpec(
    val colorStops: Array<Pair<Float, Color>>,
    val angleRad: Float,
    val blobs: List<AvatarBlob>,
    val grain: List<Pair<Offset, Float>>
)

// ── Gradient derivation ───────────────────────────────────────────────────────

private fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)

/**
 * Builds a fully deterministic AvatarSpec from raw public-key bytes.
 *
 * Algorithm:
 *  - SHA-256(key) → 32-byte hash h
 *  - 6 hue values from h[0..5]: hue = byte/255 × 360°, sat = 70%, light = 55%
 *  - 6-stop sorted linear gradient; positions from h[6..11], endpoints pinned to 0/1
 *  - Direction angle from h[20]
 *  - 8 radial blobs: 4 large (28–68% of diameter, alpha 36–64%) +
 *                    4 accent (8–28%, alpha 52–88%)
 *  - 64 grain dots for texture (alpha 4–12%)
 *
 * Same publicKeyBytes → identical spec; different keys → visually distinct avatars.
 */
private fun buildAvatarSpec(publicKeyBytes: ByteArray): AvatarSpec {
    val source = if (publicKeyBytes.isNotEmpty()) publicKeyBytes else byteArrayOf(0)
    val h = sha256(source)

    // Spec requires fixed sat + lightness so only hue varies per key.
    fun hslFrom(byteIdx: Int) = Color.hsl(
        hue        = (h[byteIdx % 32].toInt() and 0xFF) / 255f * 360f,
        saturation = 0.70f,
        lightness  = 0.55f
    )

    // 6-stop gradient — positions driven by h[6..11], sorted, endpoints pinned.
    val stops: Array<Pair<Float, Color>> = run {
        val raw = (0 until 6).map { i ->
            // Spread positions across (0.05, 0.95) before sort so they don't crowd edges.
            val pos = 0.05f + (h[(i + 6) % 32].toInt() and 0xFF) / 255f * 0.90f
            pos to hslFrom(i)
        }.sortedBy { it.first }.toMutableList()
        raw[0]             = 0f to raw[0].second
        raw[raw.lastIndex] = 1f to raw[raw.lastIndex].second
        raw.toTypedArray()
    }

    // Gradient direction angle from h[20].
    val angle = (h[20].toInt() and 0xFF) / 255f * 2f * PI.toFloat()

    // 8 blobs.  Byte layout per blob i: [cx=h[13+i*3], cy=h[14+i*3], r=h[15+i*3], α=h[16+i*3]].
    val blobs = (0 until 8).map { i ->
        val o     = 13 + i * 3
        val cx    = (h[o % 32].toInt()       and 0xFF) / 255f
        val cy    = (h[(o + 1) % 32].toInt() and 0xFF) / 255f
        val large = i < 4
        val radius = if (large)
            0.28f + (h[(o + 2) % 32].toInt() and 0xFF) / 255f * 0.40f   // 28–68 %
        else
            0.08f + (h[(o + 2) % 32].toInt() and 0xFF) / 255f * 0.20f   //  8–28 %
        val alpha = if (large)
            0.36f + (h[(o + 3) % 32].toInt() and 0xFF) / 255f * 0.28f   // 36–64 %
        else
            0.52f + (h[(o + 3) % 32].toInt() and 0xFF) / 255f * 0.36f   // 52–88 %
        AvatarBlob(cx, cy, radius, hslFrom(i + 1), alpha)
    }

    // 64 grain dots — positions and alphas derived by cycling through h[].
    val grain = (0 until 64).map { i ->
        val gx = (h[(i * 3)       % 32].toInt() and 0xFF) / 255f
        val gy = (h[(i * 3 + 1)   % 32].toInt() and 0xFF) / 255f
        val ga = 0.04f + (h[(i * 3 + 2) % 32].toInt() and 0xFF) / 255f * 0.08f
        Offset(gx, gy) to ga
    }

    return AvatarSpec(stops, angle, blobs, grain)
}

// ── Public composables ────────────────────────────────────────────────────────

/**
 * Renders a deterministic mesh-gradient circle derived from [publicKeyBytes].
 * No photos, no initials — always a pure gradient shape.
 */
@Composable
fun GradientAvatarCircle(
    publicKeyBytes: ByteArray,
    size: Dp = 80.dp
) {
    // toList() gives structural equality so remember reuses the spec when contents are equal.
    val spec = remember(publicKeyBytes.toList()) { buildAvatarSpec(publicKeyBytes) }

    Canvas(modifier = Modifier.size(size).clip(CircleShape)) {
        val w  = this.size.width
        val h  = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // Gradient extends beyond the circle so edges are fully saturated.
        val r  = max(w, h) * 0.76f
        val dx = cos(spec.angleRad) * r
        val dy = sin(spec.angleRad) * r

        // Layer 1: 6-stop linear gradient base.
        drawRect(
            brush = Brush.linearGradient(
                colorStops = spec.colorStops,
                start = Offset(cx - dx, cy - dy),
                end   = Offset(cx + dx, cy + dy)
            )
        )

        // Layer 2: radial blob overlays — center-opaque, edge-transparent.
        spec.blobs.forEach { blob ->
            val bc = Offset(blob.centerX * w, blob.centerY * h)
            val br = blob.radius * max(w, h)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob.color.copy(alpha = blob.alpha), Color.Transparent),
                    center = bc,
                    radius = br
                ),
                center = bc,
                radius = br
            )
        }

        // Layer 3: grain texture — tiny white/black dots at low alpha.
        val dotRadius = max(w, h) * 0.0085f
        spec.grain.forEachIndexed { i, (pt, alpha) ->
            drawCircle(
                color  = (if (i % 2 == 0) Color.White else Color.Black).copy(alpha = alpha),
                radius = dotRadius,
                center = Offset(pt.x * w, pt.y * h)
            )
        }
    }
}

/**
 * Shows a circular peer avatar: the user's locally-assigned photo if one has been saved,
 * otherwise a deterministic gradient generated from the peer's node ID.
 * No photos are transmitted; all state is local-only.
 */
@Composable
fun ContactAvatarCircle(
    senderIdHex: String,
    publicKeyB64: String,
    size: Dp = 44.dp,
    onLongPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val changeCounter = AvatarManager.changeCounter
    val customBitmap: Bitmap? = remember(senderIdHex, changeCounter) {
        val bytes = AvatarManager.loadPeerAvatar(context, senderIdHex) ?: return@remember null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    val interactionModifier = if (onLongPress != null)
        Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(onLongClick = onLongPress, onClick = {})
    else
        Modifier.size(size).clip(CircleShape)

    if (customBitmap != null) {
        Image(
            bitmap = customBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = interactionModifier
        )
    } else {
        NodeIdGradientAvatar(
            nodeId = senderIdHex,
            modifier = if (onLongPress != null)
                Modifier.combinedClickable(onLongClick = onLongPress, onClick = {})
            else Modifier,
            size = size
        )
    }
}

internal fun hexToFingerprintColor(hex: String): Color = try {
    val value = hex.toLong(16).toFloat()
    val hue = 220f + (value / 0xFFFF.toFloat()) * 80f
    Color.hsl(hue, 0.65f, 0.45f)
} catch (_: Exception) {
    Color.Gray
}

// ── Trust Verification screen ─────────────────────────────────────────────────

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
        if (peerSenderId.length > 12) "${peerSenderId.take(8)}…${peerSenderId.takeLast(4)}"
        else peerSenderId
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
                GradientAvatarCircle(publicKeyBytes = publicKeyBytes, size = 80.dp)
                Spacer(Modifier.height(12.dp))

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
                        ) { Text("Done") }
                        OutlinedButton(
                            onClick = { displayName = peerName; isEditingName = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
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

        Button(
            onClick = { onTrust(displayName.trim().ifBlank { peerName }) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary
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
