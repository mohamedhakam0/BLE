package com.example.ble

import java.util.*

/**
 * INTEGRATION GUIDE FOR BLE ADVERTISING MESSAGE BROADCASTING
 *
 * This file provides examples of how to use the new BLE advertising components
 * (BleMessage, BleAdvertiser, and BleMessageScanner) alongside the existing GATT implementation.
 */

/**
 * EXAMPLE 1: Basic Broadcast (Fire and Forget)
 *
 * Use this when you want to broadcast a message to all nearby devices
 * without establishing any connection.
 */
fun exampleBasicBroadcast(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    val message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "device-001",
        destinationId = "*",  // Broadcast to all
        payload = "Hello, everyone!"
    )

    val success = advertiser.broadcastMessage(message)
    if (success) {
        println("Broadcasting message...")
    } else {
        println("Failed to start broadcast")
    }

    // Broadcast runs until explicitly stopped
    // advertiser.stopBroadcast()
}

/**
 * EXAMPLE 2: Scanning and Receiving Messages
 *
 * Use this to listen for incoming BLE messages from other devices.
 */
fun exampleScanForMessages(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val scanner = BleMessageScanner(bluetoothAdapter)

    // Set up callback for receiving messages
    scanner.setCallback { message, rssi, timestamp ->
        println("Received: '${message.payload}'")
        println("  From: ${message.sourceId}")
        println("  To: ${message.destinationId}")
        println("  RSSI: $rssi dBm")
    }

    // Start scanning
    scanner.startScanning()

    // Later: stop scanning
    // scanner.stopScanning()
}

/**
 * EXAMPLE 3: Using Flow for Reactive Reception
 *
 * Use this if you're using Kotlin coroutines and want to handle messages reactively.
 */
suspend fun exampleFlowBasedReception(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val scanner = BleMessageScanner(bluetoothAdapter)
    scanner.startScanning()

    scanner.messageFlow.collect { (message, rssi, timestamp) ->
        println("Message: ${message.payload} (RSSI: $rssi)")
    }
}

/**
 * EXAMPLE 4: Targeted Message (Specific Recipient)
 *
 * Use this when you know the address of the recipient device.
 */
fun exampleTargetedBroadcast(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    val message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "my-device",
        destinationId = "device-ABC123",  // Specific target
        payload = "Message for you specifically"
    )

    advertiser.broadcastMessage(message)
}

/**
 * EXAMPLE 5: Message with Low TTL (Local Only)
 *
 * Use this for local-only messages with minimal hop count.
 */
fun exampleLocalBroadcast(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    val message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "my-device",
        destinationId = "*",
        payload = "Local beacon"
    )

    advertiser.broadcastMessage(message)
}

/**
 * EXAMPLE 6: Update Broadcast Message
 *
 * Use this to change what you're broadcasting without stopping/starting.
 */
fun exampleUpdateBroadcast(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    var message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "beacon",
        destinationId = "*",
        payload = "Status: Active"
    )

    advertiser.broadcastMessage(message)

    // ... later ...

    message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "beacon",
        destinationId = "*",
        payload = "Status: Inactive"
    )

    advertiser.updateMessage(message)
}

/**
 * EXAMPLE 7: Integration with MainActivity
 *
 * Here's how to integrate into the existing MainActivity:
 *
 * In MainActivity class:
 *
 *     private var bleAdvertiser: BleAdvertiser? = null
 *     private var bleMessageScanner: BleMessageScanner? = null
 *     private val _broadcastMessages = mutableStateListOf<BleMessage>()
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         // ... existing code ...
 *
 *         bleAdvertiser = BleAdvertiser(bluetoothAdapter)
 *         bleMessageScanner = BleMessageScanner(bluetoothAdapter)
 *     }
 *
 *     fun startBroadcastingMessage(text: String) {
 *         val message = BleMessage(
 *             messageId = UUID.randomUUID(),
 *             sourceId = bluetoothAdapter?.address ?: "unknown",
 *             destinationId = "*",
 *             payload = text
 *         )
 *         bleAdvertiser?.broadcastMessage(message)
 *     }
 *
 *     fun startScanningForMessages() {
 *         bleMessageScanner?.setCallback { message, rssi, timestamp ->
 *             runOnUiThread {
 *                 _broadcastMessages.add(message)
 *             }
 *         }
 *         bleMessageScanner?.startScanning()
 *     }
 *
 *     override fun onDestroy() {
 *         super.onDestroy()
 *         bleAdvertiser?.stopBroadcast()
 *         bleMessageScanner?.cleanup()
 *         disconnect()
 *     }
 */

/**
 * EXAMPLE 8: Filtering Messages by Destination
 *
 * Use this to filter messages meant for you specifically.
 */
fun exampleFilteredMessageReception(
    bluetoothAdapter: android.bluetooth.BluetoothAdapter?,
    myDeviceId: String
) {
    val scanner = BleMessageScanner(bluetoothAdapter)

    scanner.setCallback { message, rssi, timestamp ->
        // Filter messages meant for this device or broadcasts
        if (message.destinationId == myDeviceId || message.destinationId == "*") {
            println("Processing message: ${message.payload}")
        }
    }

    scanner.startScanning()
}

/**
 * EXAMPLE 9: Checking Message Size Before Broadcast
 *
 * Use this to ensure your message fits in the BLE advertising payload.
 */
fun exampleCheckMessageSize(bluetoothAdapter: android.bluetooth.BluetoothAdapter?) {
    val advertiser = BleAdvertiser(bluetoothAdapter)

    val message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = "device",
        destinationId = "*",
        payload = "Your message here"
    )

    val serialized = message.toByteArray()
    val size = serialized.size
    println("Message size: $size bytes (max: 251 bytes for extended advertising)")

    if (size <= 251) {
        advertiser.broadcastMessage(message)
    } else {
        println("Message too large!")
    }
}

/**
 * EXAMPLE 10: Parallel GATT and Advertising
 *
 * This demonstrates that both GATT and advertising-based messaging
 * can run simultaneously without interference.
 */
fun exampleParallelOperation(
    bluetoothAdapter: android.bluetooth.BluetoothAdapter?,
    gattConnectedDevice: android.bluetooth.BluetoothDevice?
) {
    // GATT connection is active (from existing MainActivity code)
    // Now also start advertising a message

    val advertiser = BleAdvertiser(bluetoothAdapter)
    val scanner = BleMessageScanner(bluetoothAdapter)

    // Start scanning for messages from other devices
    scanner.setCallback { message, rssi, _ ->
        println("Overheard: ${message.payload}")
    }
    scanner.startScanning()

    // While GATT is connected, also broadcast your availability
    val beacon = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = bluetoothAdapter?.address ?: "unknown",
        destinationId = "*",
        payload = "I'm available and connected to: ${gattConnectedDevice?.name ?: "nobody"}"
    )
    advertiser.broadcastMessage(beacon)

    // Both GATT and advertising work independently
}

/**
 * KEY DIFFERENCES BETWEEN GATT AND ADVERTISING-BASED MESSAGING:
 *
 * GATT (Existing Implementation):
 * - Requires a persistent connection
 * - Higher bandwidth
 * - Lower latency
 * - Bidirectional communication
 * - Point-to-point
 * - Suitable for continuous data exchange
 *
 * BLE Advertising (New Implementation):
 * - No connection required
 * - Limited payload (251 bytes max per message)
 * - No guaranteed delivery
 * - One-directional (broadcast)
 * - One-to-many
 * - Suitable for status updates, beacons, local announcements
 * - Lower power consumption
 *
 * USE BOTH TOGETHER FOR COMPREHENSIVE BLE COMMUNICATION!
 */

/**
 * PAYLOAD SIZE RECOMMENDATIONS:
 *
 * Keep messages short for reliability:
 * - Keep sourceId and destinationId short (< 20 chars)
 * - Keep payload under 100 characters for best compatibility
 * - Maximum serialized size depends on string lengths
 *
 * Example overhead calculation:
 * - MessageId UUID (16 bytes) = 16 bytes
 * - SourceId (variable) = UTF-8 encoded length
 * - DestinationId (variable) = UTF-8 encoded length
 * - Payload (variable) = UTF-8 encoded length
 * - Total: depends on your string content
 */

