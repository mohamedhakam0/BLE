package com.example.ble.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.widget.Toast
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.example.ble.AppLogger
import com.example.ble.Contact
import com.example.ble.ContactRepository
import com.example.ble.NodeIdentity
import com.example.ble.QrIdentityPayload
import com.example.ble.ui.theme.NodeGreen
import com.example.ble.ui.theme.NodeGreenDim
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val nodeIdentity = remember { NodeIdentity(context.applicationContext) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        // Page header
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

        Spacer(Modifier.height(14.dp))

        // Tab switcher
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
                            color = if (isSelected) Color.White
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
                nodeIdentity = nodeIdentity,
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
    nodeIdentity: NodeIdentity,
    senderIdHex: String,
    publicKeyB64: String
) {
    var displayName by remember { mutableStateOf(nodeIdentity.getNickname().orEmpty()) }

    val payload = remember(displayName, senderIdHex, publicKeyB64) {
        QrIdentityPayload(
            version = 2,
            nickname = displayName.trim().ifBlank { "Node-$senderIdHex" },
            displayName = displayName.trim(),
            senderId = senderIdHex,
            publicKey = publicKeyB64
        ).toJson()
    }
    val bitmap = remember(payload) { generateQrBitmap(payload) }

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

                // QR frame
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(160.dp)
                ) {
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

        Spacer(Modifier.height(14.dp))

        // Display name editor
        OutlinedTextField(
            value = displayName,
            onValueChange = {
                displayName = it.take(50)
                nodeIdentity.setNickname(displayName.trim())
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            placeholder = { Text("e.g., Ahmed, Dr. Sara") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(0.5f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(12.dp)
        )

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

@Composable
private fun ScanTab(
    hasPendingVerification: Boolean,
    onVerifyContact: (payload: QrIdentityPayload, publicKeyBytes: ByteArray) -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(hasPendingVerification) {
        if (!hasPendingVerification) isProcessing = false
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
            if (cameraGranted) {
                CameraScanPreview(
                    onQrDecoded = { json ->
                        if (isProcessing) return@CameraScanPreview
                        isProcessing = true
                        val payload = QrIdentityPayload.fromJson(json)
                        if (payload == null) {
                            Toast.makeText(context, "Invalid QR", Toast.LENGTH_SHORT).show()
                            isProcessing = false
                            return@CameraScanPreview
                        }
                        val publicKeyBytes = try {
                            Base64.decode(payload.publicKey, Base64.NO_WRAP)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Invalid public key in QR", Toast.LENGTH_SHORT).show()
                            isProcessing = false
                            return@CameraScanPreview
                        }
                        onVerifyContact(payload, publicKeyBytes)
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
            "Point camera at peer's QR code",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
        )
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
    val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { idx ->
        if (bitMatrix.get(idx % size, idx / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
