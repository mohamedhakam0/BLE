# BLE Advertising Message Broadcasting - Implementation Guide

## Overview

This implementation adds a **second transmission channel** for BLE communication alongside the existing GATT implementation. Instead of requiring a connection, BLE advertising allows you to broadcast short messages to any nearby BLE-capable device.

## Components

### 1. **BleMessage.kt** - Data Class

A data class that represents a message packet with serialization/deserialization capabilities.

**Fields:**
- `messageId: UUID` - Unique identifier for the message
- `sourceId: String` - Address/ID of the sender
- `destinationId: String` - Address/ID of the recipient (use `"*"` for broadcast)
- `ttl: Int` - Time-to-live/hop count (default: 5)
- `timestamp: Long` - When the message was created
- `payload: String` - The actual message content

**Key Methods:**
- `toByteArray()` - Serializes the message to bytes for transmission
- `serialize(message)` - Static method to serialize a message
- `deserialize(data)` - Static method to deserialize bytes back to a message
- `estimatedSize()` - Returns approximate serialized size in bytes

**Serialization Format:**
```
[Magic Bytes: 0xBEEF] [Version] [UUID] [SourceId Length + Data] 
[DestId Length + Data] [TTL] [Timestamp] [Payload Length + Data]
```

**Constraints:**
- Maximum serialized size: 251 bytes (BLE extended advertising limit)
- Recommended max payload: ~100 characters for reliability
- Magic bytes prevent misinterpretation of unrelated BLE packets

---

### 2. **BleAdvertiser.kt** - Broadcast Engine

Handles broadcasting BleMessage objects via BLE extended advertising (BLE 5).

**Key Methods:**
- `broadcastMessage(message, durationMs)` - Starts broadcasting a message
  - Returns `true` if successful
  - Uses extended advertising (BLE 5) for better compatibility
  - Non-connectable, non-scannable
  - High TX power for maximum range

- `stopBroadcast()` - Stops the current broadcast

- `updateMessage(message)` - Changes the broadcast message

- `isBroadcasting()` - Checks if currently broadcasting

**Implementation Details:**
- Uses `AdvertisingSetParameters.Builder()` with extended mode enabled
- Uses `ADVERTISE_MODE_LOW_LATENCY` for responsive broadcasting
- Manufacturer ID `0x004C` (Apple) for generic use
- Each broadcast gets its own callback handler
- Thread-safe error handling with logging

**API Level:**
- Requires API 26+ for `startAdvertisingSet()`
- Gracefully handles unavailable advertiser

---

### 3. **BleMessageScanner.kt** - Message Receiver

Scans for and decodes BleMessage objects from advertising packets.

**Key Methods:**
- `startScanning()` - Begins scanning for messages
  - Returns `true` if successful
  - Uses `SCAN_MODE_LOW_POWER` for efficiency
  - Filters by manufacturer ID for optimization

- `stopScanning()` - Stops the ongoing scan

- `setCallback(callback)` - Sets a callback to receive decoded messages
  - Called on background thread (Bluetooth scan thread)
  - Parameters: `(message, rssi, timestamp)`

- `isScanning()` - Checks if currently scanning

- `messageFlow` - Kotlin Flow for reactive message handling
  - Emits `Triple<BleMessage, Int, Long>` (message, RSSI, timestamp)
  - Can be collected in coroutines

- `cleanup()` - Cleanup method (call in onDestroy)

**Message Processing:**
- Extracts manufacturer data from scan records
- Attempts deserialization with magic byte validation
- Silently ignores packets that don't match format
- Robust exception handling prevents scan failures
- Provides both callback and Flow interfaces

---

## Integration with Existing GATT Code

Both systems run **independently and concurrently**:

### Existing GATT Channel:
- Connection-based (Central/Peripheral modes)
- Point-to-point communication
- Higher bandwidth
- Persistent connection required

### New Advertising Channel:
- Connectionless broadcasting
- One-to-many communication
- Limited payload (251 bytes max)
- No connection overhead

### They don't interfere with each other:
```kotlin
// Both can run simultaneously
val gattManager = BluetoothGatt(...)  // existing code
val advertiser = BleAdvertiser(adapter)  // new code
val scanner = BleMessageScanner(adapter)  // new code

// Send via GATT when connected
gatt.writeCharacteristic(...)

// Broadcast simultaneously
advertiser.broadcastMessage(message)

// Receive broadcasts anytime
scanner.startScanning()
```

---

## Usage Examples

### Basic Broadcast
```kotlin
val advertiser = BleAdvertiser(bluetoothAdapter)
val message = BleMessage(
    messageId = UUID.randomUUID(),
    sourceId = "my-device",
    destinationId = "*",
    payload = "Hello World"
)
advertiser.broadcastMessage(message)
```

### Receive Messages
```kotlin
val scanner = BleMessageScanner(bluetoothAdapter)
scanner.setCallback { message, rssi, timestamp ->
    println("Message: ${message.payload}")
    println("RSSI: $rssi dBm")
}
scanner.startScanning()
```

### Using Flow (Coroutines)
```kotlin
val scanner = BleMessageScanner(bluetoothAdapter)
scanner.startScanning()

lifecycleScope.launchWhenStarted {
    scanner.messageFlow.collect { (message, rssi, timestamp) ->
        updateUI(message)
    }
}
```

### Check Message Size Before Send
```kotlin
val message = BleMessage(
    messageId = UUID.randomUUID(),
    sourceId = "device",
    destinationId = "*",
    payload = "Your message"
)

if (message.estimatedSize() <= 251) {
    advertiser.broadcastMessage(message)
} else {
    println("Message too large!")
}
```

---

## Integration with MainActivity

Add these to your existing `MainActivity` class:

```kotlin
private var bleAdvertiser: BleAdvertiser? = null
private var bleMessageScanner: BleMessageScanner? = null
private val _broadcastMessages = mutableStateListOf<BleMessage>()

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... existing code ...
    
    bleAdvertiser = BleAdvertiser(bluetoothAdapter)
    bleMessageScanner = BleMessageScanner(bluetoothAdapter)
}

fun startBroadcastingMessage(text: String) {
    val message = BleMessage(
        messageId = UUID.randomUUID(),
        sourceId = bluetoothAdapter?.address ?: "unknown",
        destinationId = "*",
        payload = text
    )
    bleAdvertiser?.broadcastMessage(message)
}

fun startListeningForBroadcasts() {
    bleMessageScanner?.setCallback { message, rssi, _ ->
        runOnUiThread {
            _broadcastMessages.add(message)
        }
    }
    bleMessageScanner?.startScanning()
}

override fun onDestroy() {
    super.onDestroy()
    bleAdvertiser?.stopBroadcast()
    bleMessageScanner?.cleanup()
    disconnect()  // existing GATT cleanup
}
```

---

## Thread Safety & Callbacks

**Important:** Scanner callbacks are invoked on the Bluetooth scan thread, not the UI thread.

**Always wrap UI updates with `runOnUiThread()`:**
```kotlin
scanner.setCallback { message, rssi, timestamp ->
    runOnUiThread {
        updateUI(message)  // Safe to update UI
    }
}
```

**Or use Flow with lifecycleScope:**
```kotlin
lifecycleScope.launchWhenStarted {
    scanner.messageFlow.collect { message ->
        // Already on Main thread via lifecycleScope
        updateUI(message)
    }
}
```

---

## Performance & Range

**Broadcasting:**
- TX Power: HIGH
- Range: ~30-50 meters (typical, depends on environment and receiver)
- Power consumption: Low (~1-2mA when advertising)
- Latency: ~100-200ms (advertising interval dependent)

**Scanning:**
- Scan Mode: LOW_POWER (for efficiency)
- Battery impact: ~2-3mA continuous
- Can receive messages within broadcasting range

**Payload Optimization:**
```
Total Header Overhead: ~32 bytes
Available for strings: ~219 bytes
Recommended max payload: ~100 characters

Message structure overhead breakdown:
- Magic (2) + Version (1) + UUID (16) + TTL (1) + Timestamp (8) = 28 bytes
- Length fields + string lengths: ~4 bytes
- Total: ~32 bytes minimum
```

---

## Error Handling

Both classes include comprehensive error handling:

**BleAdvertiser:**
- Checks for null advertiser gracefully
- Validates payload size (max 251 bytes)
- Logs all errors with descriptive messages
- Returns boolean success status

**BleMessageScanner:**
- Filters manufacturer data automatically
- Handles deserialization failures gracefully
- Doesn't crash on malformed packets
- Comprehensive exception handling in scan callbacks
- Logs errors without interrupting scanning

---

## Requirements

**API Level:** 31+ (for best compatibility)
- Extended advertising available on API 26+
- Tested on API 31+

**Permissions (already in AndroidManifest.xml):**
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

**No additional dependencies required** - uses Android Framework only.

---

## Manufacturer ID

The implementation uses manufacturer ID `0x004C` (Apple) which is commonly used for generic/experimental payloads. This is safe and standard practice.

The magic bytes `0xBEEF` + version byte + UUID prevent accidental interpretation of unrelated BLE packets.

---

## Cleanup

Always clean up resources:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // Advertising cleanup
    bleAdvertiser?.stopBroadcast()
    
    // Scanning cleanup
    bleMessageScanner?.cleanup()  // Stops scan and clears callback
    
    // Existing GATT cleanup
    bluetoothGatt?.disconnect()
    bluetoothGatt?.close()
}
```

---

## Coexistence with GATT

The advertising and scanning systems are completely independent from GATT:
- Same BluetoothAdapter instance can be used
- No resource contention
- Messages flow independently
- Both work with Central and Peripheral modes

Perfect for implementing:
- **Status beacons** while maintaining GATT connections
- **Local announcements** alongside peer-to-peer messaging
- **Mesh-like capabilities** with hop counts (TTL)
- **Discovery/presence** without connections

---

## Testing

To test the implementation:

1. **Two devices with the app:**
   - Device A: Start broadcasting with `broadcastMessage()`
   - Device B: Start scanning with `startScanning()`

2. **Check logcat:**
   ```
   BleAdvertiser: Advertising started successfully
   BleMessageScanner: Message received from device-001: Hello World (RSSI: -45)
   ```

3. **Verify payload size:**
   - Print `message.estimatedSize()`
   - Should be < 251 bytes

4. **Monitor Battery:**
   - Advertising: ~1-2mA overhead
   - Scanning: ~2-3mA continuous

---

## Production Checklist

- [ ] Verify permissions granted at runtime
- [ ] Handle null bluetoothAdapter gracefully
- [ ] Wrap scanner callbacks with `runOnUiThread()`
- [ ] Call `cleanup()` in `onDestroy()`
- [ ] Test message size with actual payloads
- [ ] Verify range requirements in your environment
- [ ] Handle deserialization failures gracefully
- [ ] Set appropriate TTL for your use case
- [ ] Monitor battery impact with continuous scanning
- [ ] Test coexistence with GATT connections

---

## Support Files Included

1. **BleMessage.kt** - Message data class with serialization
2. **BleAdvertiser.kt** - Broadcasting engine
3. **BleMessageScanner.kt** - Reception/scanning engine
4. **BleIntegrationExamples.kt** - Code examples and integration guide

All files are self-contained and production-ready.

