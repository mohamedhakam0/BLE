package com.example.ble.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble.AppLogger
import com.example.ble.Contact
import com.example.ble.ContactRepository
import com.example.ble.KeysViewModel
import com.example.ble.QrIdentityPayload
import com.example.ble.ui.theme.NodeGreen
import com.example.ble.ui.theme.NodeGreenDim
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.Executors

private const val QR_TAG = "QRScan"

@Composable
fun KeysScreen(
    nickname: String,
    senderIdHex: String,
    publicKeyB64: String,
    contactRepository: ContactRepository,
    hasPendingVerification: Boolean = false,
    onVerifyContact: (payload: QrIdentityPayload, publicKeyBytes: ByteArray) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: KeysViewModel = viewModel()

    val avatarBitmap by vm.localAvatarBitmap.collectAsStateWithLifecycle()
    val displayName  by vm.localDisplayName.collectAsStateWithLifecycle()

    val publicKeyBytes = remember(publicKeyB64) {
        try { Base64.decode(publicKeyB64, Base64.NO_WRAP) } catch (_: Exception) { byteArrayOf() }
    }

    var selectedTabIndex   by remember { mutableIntStateOf(0) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editNameInput      by remember { mutableStateOf("") }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.onAvatarSelected(context, uri)
    }

    // ── Edit display name dialog ──────────────────────────────────────────────
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Display name") },
            text = {
                OutlinedTextField(
                    value = editNameInput,
                    onValueChange = { editNameInput = it.take(50) },
                    singleLine = true,
                    placeholder = { Text("e.g., Ahmed, Dr. Sara") },
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = editNameInput.trim()
                    if (cleaned.isNotEmpty()) vm.setDisplayName(cleaned)
                    showEditNameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        // ── Page header ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Key Exchange",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            // E2E badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = NodeGreenDim,
                border = BorderStroke(1.dp, NodeGreen.copy(alpha = 0.3f))
            ) {
                Text(
                    "Curve25519",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NodeGreen,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Profile identity header ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle with edit badge
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable {
                        avatarPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                    )
                } else {
                    GradientAvatarCircle(publicKeyBytes = publicKeyBytes, size = 56.dp)
                }
                // Edit badge — small filled circle at bottom-end
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Change photo",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName.ifBlank { "Node-$senderIdHex" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            editNameInput = displayName
                            showEditNameDialog = true
                        }
                    )
                }
                Text(
                    text = "Node $senderIdHex",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Tab switcher ──────────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                listOf("My QR", "Scan").forEachIndexed { index, label ->
                    val isSelected = selectedTabIndex == index
                    Surface(
                        onClick = { selectedTabIndex = index },
                        shape = RoundedCornerShape(9.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(vertical = 7.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(0.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        when (selectedTabIndex) {
            0 -> MyQrTab(
                displayName = displayName,
                senderIdHex = senderIdHex,
                publicKeyB64 = publicKeyB64
            )
            1 -> ScanTab(
                hasPendingVerification = hasPendingVerification,
                onVerifyContact = onVerifyContact
            )
        }
    }
}

@Composable
private fun MyQrTab(
    displayName: String,
    senderIdHex: String,
    publicKeyB64: String
) {
    val context = LocalContext.current

    val qrBitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = displayName
    ) {
        value = withContext(Dispatchers.IO) {
            val json = QrIdentityPayload(
                version = 2,
                nickname = displayName.trim().ifBlank { "Node-$senderIdHex" },
                displayName = displayName.trim(),
                senderId = senderIdHex,
                publicKey = publicKeyB64
            ).toJson()
            generateQrBitmap(json)
        }
    }

    val fingerprint = remember(publicKeyB64) {
        try {
            val keyBytes = android.util.Base64.decode(publicKeyB64, android.util.Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            digest.take(16).chunked(2).map { pair ->
                pair.joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) {
            List(8) { "----" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // QR card
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
                    "Show this to a peer to share your public key",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.height(14.dp))

                // QR frame — 220 dp gives ≥ 3 px/module even on low-density tablets
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(220.dp)
                ) {
                    val bitmap = qrBitmap  // Extract from delegated property for smart cast
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize().padding(4.dp)
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("QR Error", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Node ID row
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "NODE ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            senderIdHex,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Fingerprint
                Text(
                    "PUBLIC KEY FINGERPRINT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                // Fingerprint grid 4x2
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in fingerprint.chunked(4)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { block ->
                                val chipColor = remember(block) { hexToFingerprintColor(block) }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = chipColor,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        block,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White.copy(alpha = 0.85f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your peer can verify these colors and codes on their screen after scanning.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Key info cards
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            KeyInfoCard("Algorithm", "X25519", modifier = Modifier.weight(1f))
            KeyInfoCard("Cipher", "AES-GCM", modifier = Modifier.weight(1f))
            KeyInfoCard("Status", "Active", isGreen = true, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KeyInfoCard(
    label: String,
    value: String,
    isGreen: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (isGreen) NodeGreen else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private data class PendingKey(
    val payload: QrIdentityPayload,
    val publicKeyBytes: ByteArray
)

@Composable
private fun ScanTab(
    hasPendingVerification: Boolean,
    onVerifyContact: (payload: QrIdentityPayload, publicKeyBytes: ByteArray) -> Unit
) {
    val context = LocalContext.current

    // Non-null while the processing animation is running.
    var pendingKey by remember { mutableStateOf<PendingKey?>(null) }

    // Reset when TrustVerificationScreen is dismissed and we return to the scan tab.
    LaunchedEffect(hasPendingVerification) {
        if (!hasPendingVerification) pendingKey = null
    }

    // Cancel processing and discard the parsed key if the user presses back.
    BackHandler(enabled = pendingKey != null) {
        pendingKey = null
    }

    // Wait for the full processing animation (≈1800ms), then navigate.
    // Cancels automatically if the user presses back and pendingKey becomes null.
    LaunchedEffect(pendingKey) {
        val key = pendingKey ?: return@LaunchedEffect
        delay(1800L)
        onVerifyContact(key.payload, key.publicKeyBytes)
    }

    val cameraGranted = remember {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            if (pendingKey != null) {
                ProcessingOverlay()
            } else if (cameraGranted) {
                CameraScanPreview(
                    onQrDecoded = { json ->
                        if (pendingKey != null) return@CameraScanPreview
                        val payload = QrIdentityPayload.fromJson(json)
                        if (payload == null) {
                            Toast.makeText(context, "Invalid QR", Toast.LENGTH_SHORT).show()
                            return@CameraScanPreview
                        }
                        val publicKeyBytes = try {
                            Base64.decode(payload.publicKey, Base64.NO_WRAP)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Invalid public key in QR", Toast.LENGTH_SHORT).show()
                            return@CameraScanPreview
                        }
                        pendingKey = PendingKey(payload, publicKeyBytes)
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        "Camera permission required",
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            if (pendingKey != null) "" else "Point camera at peer's QR code",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
        )
    }
}

@Composable
private fun ProcessingOverlay() {
    // Step labels with their display durations in ms.
    val steps = remember {
        listOf(
            "Reading key..."            to 800L,
            "Verifying integrity..."    to 100L,
            "Preparing verification..." to 600L,
        )
    }
    var stepIndex by remember { mutableIntStateOf(0) }
    val labelAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        steps.forEachIndexed { index, (_, durationMs) ->
            stepIndex = index
            labelAlpha.snapTo(0f)
            labelAlpha.animateTo(1f, tween(durationMillis = 180))
            delay(durationMs - 180 - 120)
            labelAlpha.animateTo(0f, tween(durationMillis = 120))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(52.dp)
            )
            Text(
                text = steps[stepIndex].first,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = labelAlpha.value)
            )
        }
    }
}

@Composable
private fun CameraScanPreview(
    onQrDecoded: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val currentOnQrDecoded by rememberUpdatedState(onQrDecoded)

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(executor, object : androidx.camera.core.ImageAnalysis.Analyzer {
                @androidx.camera.core.ExperimentalGetImage
                override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val image = InputImage.fromMediaImage(mediaImage, rotation)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                        val qrData = barcode.rawValue
                                        if (qrData != null) {
                                            currentOnQrDecoded(qrData)
                                            break
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply { this.controller = controller }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun generateQrBitmap(data: String, size: Int = 512): android.graphics.Bitmap? = try {
    // EC Level L maximises data capacity per QR version, giving larger modules
    // at a given display size.  Margin=2 keeps a minimal quiet zone while still
    // meeting scanner requirements (spec says ≥4 but ML Kit tolerates 2).
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 2
    )
    val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { idx ->
        if (bitMatrix.get(idx % size, idx / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
