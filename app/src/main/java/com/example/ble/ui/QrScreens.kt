package com.example.ble.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ble.Contact
import com.example.ble.ContactRepository
import com.example.ble.QrIdentityPayload
import com.example.ble.R
import com.example.ble.NodeIdentity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ble.AppLogger
import androidx.camera.core.ExperimentalGetImage

private const val QR_TAG = "QRScan"

@Composable
fun QrGenerateScreen(
    nickname: String,
    senderIdHex: String,
    publicKeyB64: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val nodeIdentity = remember { NodeIdentity(context.applicationContext) }

    var displayName by remember { mutableStateOf(nodeIdentity.getNickname().orEmpty()) }

    val payload = remember(displayName, senderIdHex, publicKeyB64) {
        QrIdentityPayload(
            version = 2,
            nickname = nickname,
            displayName = displayName.trim(),
            senderId = senderIdHex,
            publicKey = publicKeyB64
        ).toJson()
    }
    val bitmap = remember(payload) { generateQrBitmap(payload) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Show this QR code to your friend so they can add you as a contact",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("Your QR Identity", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = {
                displayName = it.take(50)
                nodeIdentity.setNickname(displayName.trim())
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            placeholder = { Text("e.g., Ahmed, Dr. Sara") },
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))
        Text("Sender ID: $senderIdHex")
        Spacer(Modifier.height(12.dp))

        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR Code")
        } else {
            Text("Failed to generate QR", color = Color.Red)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

private fun generateQrBitmap(data: String, size: Int = 512): android.graphics.Bitmap? = try {
    val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { idx -> if (bitMatrix.get(idx % size, idx / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}

@Composable
fun QrScanScreen(
    contactRepository: ContactRepository,
    onContactAdded: (Contact) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastResult by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var pendingPayload by remember { mutableStateOf<QrIdentityPayload?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }

    val cameraGranted = remember {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(cameraGranted) {
        AppLogger.d(QR_TAG, "Camera permission granted: $cameraGranted")
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Point your camera at your friend's QR code to add them as a contact",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onQrDecoded = { json ->
                if (isProcessing || showDialog) return@CameraPreview
                isProcessing = true
                val payload = QrIdentityPayload.fromJson(json)
                if (payload == null) {
                    Toast.makeText(context, "Invalid QR", Toast.LENGTH_SHORT).show()
                    isProcessing = false
                    return@CameraPreview
                }
                pendingPayload = payload
                val suggested = payload.resolvedName().ifBlank { "Peer-${payload.senderId}" }
                nameInput = suggested
                showDialog = true
            },
            onError = {
                Toast.makeText(context, "Camera error: $it", Toast.LENGTH_SHORT).show()
                AppLogger.e(QR_TAG, "Camera error: $it")
            }
        )
        Spacer(Modifier.height(8.dp))
        if (lastResult != null) {
            Text("Added contact: ${lastResult}", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("Back") }
    }

    if (showDialog && pendingPayload != null) {
        val payload = pendingPayload!!
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                pendingPayload = null
                isProcessing = false
            },
            title = { Text("Add Contact") },
            text = {
                Column {
                    Text("Enter a name for this contact")
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = nameInput,
                        onValueChange = { input ->
                            nameInput = input.take(50)
                        },
                        singleLine = true,
                        placeholder = { Text("e.g., Mohamed, Omar, Mom") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = nameInput.trim()
                    if (cleaned.isEmpty()) {
                        Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        val contact = Contact(
                            senderId = payload.senderId.trim().lowercase(),
                            nickname = cleaned,
                            publicKey = payload.publicKey,
                            dateAdded = System.currentTimeMillis()
                        )
                        contactRepository.upsertContact(contact)
                        lastResult = cleaned
                        withContextMain { onContactAdded(contact) }
                        withContext(Dispatchers.Main) {
                            showDialog = false
                            pendingPayload = null
                            isProcessing = false
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    pendingPayload = null
                    isProcessing = false
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onQrDecoded: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(executor, object : ImageAnalysis.Analyzer {
                @androidx.camera.core.ExperimentalGetImage
                override fun analyze(imageProxy: ImageProxy) {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        AppLogger.d(QR_TAG, "Analyzer frame received rot=$rotation size=${imageProxy.width}x${imageProxy.height}")
                        val image = InputImage.fromMediaImage(mediaImage, rotation)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                if (barcodes.isNotEmpty()) {
                                    AppLogger.d(QR_TAG, "MLKit barcodes size=${barcodes.size}")
                                }
                                for (barcode in barcodes) {
                                    if (barcode.format == Barcode.FORMAT_QR_CODE) {
                                        val qrData = barcode.rawValue
                                        if (qrData != null) {
                                            AppLogger.d(QR_TAG, "QR detected: $qrData")
                                            onQrDecoded(qrData)
                                            break
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                AppLogger.e(QR_TAG, "MLKit scan error: ${e.message}")
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) {
        AppLogger.d(QR_TAG, "Camera preview starting (ML Kit)")
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            AppLogger.d(QR_TAG, "Camera preview stopping (ML Kit)")
            controller.unbind()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
            }
        },
        modifier = modifier
    )

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Text("Point camera at QR code", modifier = Modifier.padding(12.dp), color = Color.White)
    }
}

private suspend fun withContextMain(block: () -> Unit) {
    kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
}
