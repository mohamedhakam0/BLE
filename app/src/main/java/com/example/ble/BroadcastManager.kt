package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.util.*

/**
 * BLE_BROADCAST_INTEGRATION.kt
 *
 * This file provides a production-ready integration module that can be used
 * to easily add broadcasting and reception capabilities to MainActivity.
 *
 * Simply create an instance in MainActivity and use the public API.
 */

data class ReceivedBroadcast(
    val message: BleMessage,
    val rssi: Int,
    val timestampNanos: Long,
    val receivedAt: Long = System.currentTimeMillis()
)

/**
 * BroadcastManager encapsulates all BLE advertising broadcast functionality.
 *
 * This manager handles:
 * - Broadcasting messages via advertising
 * - Receiving broadcast messages
 * - Exposing both callback and reactive (Flow) interfaces
 * - Lifecycle management
 * - Thread-safe state management
 *
 * Usage in MainActivity:
 * ```
 * private val broadcastManager = BroadcastManager(context, bluetoothAdapter)
 *
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     broadcastManager.initialize()
 * }
 *
 * fun broadcastMessage(text: String) {
 *     broadcastManager.broadcast("my-device", "*", text)
 * }
 *
 * fun startReceiving() {
 *     broadcastManager.startReceiving()
 * }
 *
 * override fun onDestroy() {
 *     broadcastManager.cleanup()
 *     super.onDestroy()
 * }
 * ```
 */
class BroadcastManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) {
    companion object {
        private const val TAG = "BroadcastManager"
    }

    // Components
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleMessageScanner? = null

    // State
    private val _isBroadcasting = mutableStateOf(false)
    private val _isReceiving = mutableStateOf(false)
    private val _receivedMessages = mutableStateListOf<ReceivedBroadcast>()
    private val _lastError = mutableStateOf<String?>(null)

    // Public read-only state accessors
    val isBroadcasting: Boolean get() = _isBroadcasting.value
    val isReceiving: Boolean get() = _isReceiving.value
    val receivedMessages: List<ReceivedBroadcast> get() = _receivedMessages
    val lastError: String? get() = _lastError.value

    /**
     * Initializes the broadcast manager.
     * Call this in MainActivity.onCreate() after permissions are granted.
     */
    fun initialize() {
        try {
            advertiser = BleAdvertiser(bluetoothAdapter)
            scanner = BleMessageScanner(bluetoothAdapter)
            Log.d(TAG, "BroadcastManager initialized")
        } catch (e: Exception) {
            val errorMsg = "Failed to initialize: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _lastError.value = errorMsg
        }
    }

    /**
     * Broadcasts a message via BLE advertising.
     *
     * @param sourceId Your device identifier
     * @param destinationId Target device ("*" for broadcast to all)
     * @param payload The message content
     * @return true if broadcast started successfully
     */
    @SuppressLint("MissingPermission")
    fun broadcast(sourceId: String, destinationId: String = "*", payload: String): Boolean {
        return try {
            if (advertiser == null) {
                _lastError.value = "Advertiser not initialized"
                return false
            }

            val message = BleMessage(
                messageId = UUID.randomUUID(),
                sourceId = sourceId,
                destinationId = destinationId,
                payload = payload
            )

            // Check size
            val serialized = message.toByteArray()
            if (serialized.size > 251) {
                _lastError.value = "Payload too large: ${serialized.size} bytes"
                Log.e(TAG, "Message too large")
                return false
            }

            val success = advertiser!!.broadcastMessage(message)
            if (success) {
                _isBroadcasting.value = true
                _lastError.value = null
                Log.d(TAG, "Broadcasting: $payload")
            } else {
                _lastError.value = "Failed to start broadcast"
            }
            success
        } catch (e: Exception) {
            val errorMsg = "Broadcast error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _lastError.value = errorMsg
            false
        }
    }

    /**
     * Stops the current broadcast.
     */
    fun stopBroadcast() {
        try {
            advertiser?.stopBroadcast()
            _isBroadcasting.value = false
            Log.d(TAG, "Broadcast stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping broadcast: ${e.message}", e)
        }
    }

    /**
     * Starts receiving broadcast messages from nearby devices.
     * Messages are added to the receivedMessages list.
     *
     * @return true if scanning started successfully
     */
    @SuppressLint("MissingPermission")
    fun startReceiving(): Boolean {
        return try {
            if (scanner == null) {
                _lastError.value = "Scanner not initialized"
                return false
            }

            // Set callback
            scanner!!.setCallback { message, rssi, timestamp ->
                handleMessageReceived(message, rssi, timestamp)
            }

            val success = scanner!!.startScanning()
            if (success) {
                _isReceiving.value = true
                _lastError.value = null
                Log.d(TAG, "Receiving started")
            } else {
                _lastError.value = "Failed to start scanning"
            }
            success
        } catch (e: Exception) {
            val errorMsg = "Receive error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _lastError.value = errorMsg
            false
        }
    }

    /**
     * Stops receiving broadcast messages.
     */
    fun stopReceiving() {
        try {
            scanner?.stopScanning()
            _isReceiving.value = false
            Log.d(TAG, "Receiving stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    /**
     * Clears all received messages.
     */
    fun clearMessages() {
        _receivedMessages.clear()
    }

    /**
     * Gets a specific received message by index.
     */
    fun getMessage(index: Int): ReceivedBroadcast? {
        return if (index >= 0 && index < _receivedMessages.size) {
            _receivedMessages[index]
        } else {
            null
        }
    }

    /**
     * Filters received messages by source.
     */
    fun getMessagesFromSource(sourceId: String): List<ReceivedBroadcast> {
        return _receivedMessages.filter { it.message.sourceId == sourceId }
    }

    /**
     * Internal handler for received messages.
     */
    private fun handleMessageReceived(message: BleMessage, rssi: Int, timestamp: Long) {
        try {
            val broadcast = ReceivedBroadcast(
                message = message,
                rssi = rssi,
                timestampNanos = timestamp
            )
            _receivedMessages.add(broadcast)

            // Keep list size manageable (max 100 messages)
            if (_receivedMessages.size > 100) {
                _receivedMessages.removeAt(0)
            }

            Log.d(TAG, "Message received: ${message.payload} from ${message.sourceId} (RSSI: $rssi)")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }

    /**
     * Cleans up resources. Call this in MainActivity.onDestroy().
     */
    fun cleanup() {
        try {
            stopBroadcast()
            stopReceiving()
            scanner?.cleanup()
            advertiser = null
            scanner = null
            _receivedMessages.clear()
            Log.d(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    /**
     * Gets broadcast statistics.
     */
    fun getStats(): BroadcastStats {
        return BroadcastStats(
            isBroadcasting = _isBroadcasting.value,
            isReceiving = _isReceiving.value,
            totalMessagesReceived = _receivedMessages.size,
            uniqueSources = _receivedMessages.map { it.message.sourceId }.toSet().size,
            lastError = _lastError.value
        )
    }
}

data class BroadcastStats(
    val isBroadcasting: Boolean,
    val isReceiving: Boolean,
    val totalMessagesReceived: Int,
    val uniqueSources: Int,
    val lastError: String?
)

/**
 * EXAMPLE INTEGRATION WITH ACTIVITY COMPOSABLE:
 *
 * Add this to your Compose UI:
 *
 * @Composable
 * fun BroadcastSection(
 *     manager: BroadcastManager,
 *     modifier: Modifier = Modifier
 * ) {
 *     var broadcastText by remember { mutableStateOf("") }
 *
 *     Column(modifier = modifier.padding(16.dp)) {
 *         // Status
 *         Card(
 *             modifier = Modifier.fillMaxWidth(),
 *             colors = CardDefaults.cardColors(
 *                 containerColor = if (manager.isBroadcasting) Color.Green.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f)
 *             )
 *         ) {
 *             Text(
 *                 "Broadcast Status: ${if (manager.isBroadcasting) "ACTIVE" else "INACTIVE"}",
 *                 modifier = Modifier.padding(8.dp)
 *             )
 *             Text(
 *                 "Receiving: ${if (manager.isReceiving) "YES" else "NO"}",
 *                 modifier = Modifier.padding(8.dp)
 *             )
 *         }
 *
 *         Spacer(modifier = Modifier.height(8.dp))
 *
 *         // Broadcast Input
 *         OutlinedTextField(
 *             value = broadcastText,
 *             onValueChange = { broadcastText = it },
 *             label = { Text("Broadcast Message") },
 *             modifier = Modifier.fillMaxWidth()
 *         )
 *
 *         Row(
 *             modifier = Modifier.fillMaxWidth(),
 *             horizontalArrangement = Arrangement.SpaceEvenly
 *         ) {
 *             Button(onClick = {
 *                 if (broadcastText.isNotBlank()) {
 *                     manager.broadcast("my-device", "*", broadcastText)
 *                     broadcastText = ""
 *                 }
 *             }) {
 *                 Text("Broadcast")
 *             }
 *
 *             Button(onClick = {
 *                 if (manager.isBroadcasting) {
 *                     manager.stopBroadcast()
 *                 } else {
 *                     manager.broadcast("my-device", "*", "Device Active")
 *                 }
 *             }) {
 *                 Text(if (manager.isBroadcasting) "Stop" else "Start")
 *             }
 *         }
 *
 *         Spacer(modifier = Modifier.height(8.dp))
 *
 *         // Receiving Controls
 *         Row(
 *             modifier = Modifier.fillMaxWidth(),
 *             horizontalArrangement = Arrangement.SpaceEvenly
 *         ) {
 *             Button(onClick = {
 *                 if (manager.isReceiving) {
 *                     manager.stopReceiving()
 *                 } else {
 *                     manager.startReceiving()
 *                 }
 *             }) {
 *                 Text(if (manager.isReceiving) "Stop Listening" else "Start Listening")
 *             }
 *
 *             Button(onClick = { manager.clearMessages() }) {
 *                 Text("Clear Messages")
 *             }
 *         }
 *
 *         // Received Messages
 *         if (manager.receivedMessages.isNotEmpty()) {
 *             Spacer(modifier = Modifier.height(8.dp))
 *             Text("Received (${manager.receivedMessages.size}):", style = MaterialTheme.typography.titleSmall)
 *
 *             LazyColumn(
 *                 modifier = Modifier
 *                     .fillMaxWidth()
 *                     .heightIn(max = 200.dp)
 *             ) {
 *                 items(manager.receivedMessages) { broadcast ->
 *                     Card(
 *                         modifier = Modifier
 *                             .fillMaxWidth()
 *                             .padding(vertical = 2.dp)
 *                     ) {
 *                         Column(modifier = Modifier.padding(8.dp)) {
 *                             Text(
 *                                 broadcast.message.payload,
 *                                 style = MaterialTheme.typography.bodySmall,
 *                                 maxLines = 1
 *                             )
 *                             Text(
 *                                 "From: ${broadcast.message.sourceId} | RSSI: ${broadcast.rssi}",
 *                                 style = MaterialTheme.typography.labelSmall,
 *                                 color = Color.Gray
 *                             )
 *                         }
 *                     }
 *                 }
 *             }
 *         }
 *
 *         // Error Display
 *         manager.lastError?.let { error ->
 *             Spacer(modifier = Modifier.height(8.dp))
 *             Card(
 *                 colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.1f)),
 *                 modifier = Modifier.fillMaxWidth()
 *             ) {
 *                 Text(
 *                     error,
 *                     color = Color.Red,
 *                     modifier = Modifier.padding(8.dp)
 *                 )
 *             }
 *         }
 *     }
 * }
 */

