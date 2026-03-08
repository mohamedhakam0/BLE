package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*

/**
 * Callback interface for receiving BleMessage objects from BLE advertising scans.
 */
fun interface BleMessageCallback {
    fun onMessageReceived(message: BleMessage, rssi: Int, timestampNanos: Long)
}

/**
 * BleMessageScanner handles scanning for and decoding BleMessage objects from BLE advertising packets.
 *
 * This class scans for BLE advertisements, attempts to deserialize the raw bytes into BleMessage objects,
 * and exposes received messages via both a callback interface and a Flow (Kotlin Coroutines).
 * It gracefully handles packets that don't match the custom BleMessage format.
 *
 * The scanner runs independently alongside the existing GATT implementation.
 *
 * Usage with callback:
 * ```
 * val scanner = BleMessageScanner(bluetoothAdapter)
 * scanner.setCallback { message, rssi, timestamp ->
 *     println("Received: ${message.payload} from ${message.sourceId} (RSSI: $rssi)")
 * }
 * scanner.startScanning()
 * // ... later ...
 * scanner.stopScanning()
 * ```
 *
 * Usage with Flow:
 * ```
 * val scanner = BleMessageScanner(bluetoothAdapter)
 * scanner.messageFlow.collect { (message, rssi, timestamp) ->
 *     println("Received: ${message.payload} from ${message.sourceId} (RSSI: $rssi)")
 * }
 * scanner.startScanning()
 * ```
 */
class BleMessageScanner(private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "BleMessageScanner"
        private const val SCAN_PERIOD_MS = 10000  // Default scan period

        // Manufacturer ID for Apple (0x004C) - we use this as our custom format identifier
        private const val MANUFACTURER_ID = 0x004C
    }

    private var bleScanner = bluetoothAdapter?.bluetoothLeScanner
    private var isScanning = false
    private var callback: BleMessageCallback? = null
    private val _messageFlow = MutableSharedFlow<Triple<BleMessage, Int, Long>>(replay = 0)

    /**
     * A Flow that emits triples of (BleMessage, RSSI, timestamp) for each decoded message.
     */
    val messageFlow: Flow<Triple<BleMessage, Int, Long>> = _messageFlow.asSharedFlow()

    init {
        if (bleScanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available on this device")
        }
    }

    /**
     * Sets a callback to be invoked when a BleMessage is received.
     * The callback is invoked on the same thread that processes the scan results (usually a background thread).
     *
     * @param callback The callback to invoke, or null to clear the current callback
     */
    fun setCallback(callback: BleMessageCallback?) {
        this.callback = callback
    }

    /**
     * Starts scanning for BLE advertising packets containing BleMessage data.
     * This will scan indefinitely until stopScanning() is called.
     * Automatically enables scanning for Coded PHY packets for extended range support.
     *
     * @return true if scanning was started successfully, false otherwise
     */
    @SuppressLint("MissingPermission")
    fun startScanning(): Boolean {
        if (bleScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is not available")
            return false
        }

        if (isScanning) {
            Log.w(TAG, "Already scanning")
            return false
        }

        try {
            // Create a filter for our manufacturer data (optional, but improves efficiency)
            val scanFilter = ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, byteArrayOf())
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                // Enable Coded PHY scanning for extended range (~240m)
                // This allows receiving broadcasts sent with Coded PHY
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)  // Scan all PHY types including Coded
                .build()

            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Log.d(TAG, "Scanning started (with Coded PHY support for extended range)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}", e)
            isScanning = false
            return false
        }
    }

    /**
     * Stops the ongoing BLE scan.
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            if (isScanning && bleScanner != null) {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "Scanning stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    /**
     * Returns whether the scanner is currently scanning.
     */
    fun isScanning(): Boolean = isScanning

    /**
     * The internal scan callback that processes raw BLE scan results.
     */
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                processResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
        }

        private fun processResult(result: ScanResult) {
            try {
                val scanRecord = result.scanRecord
                if (scanRecord != null) {
                    // Attempt to extract manufacturer data (our custom BleMessage format)
                    val manufacturerData = scanRecord.getManufacturerSpecificData(MANUFACTURER_ID)

                    if (manufacturerData != null && manufacturerData.isNotEmpty()) {
                        // Try to deserialize the raw bytes into a BleMessage
                        val message = BleMessage.deserialize(manufacturerData)

                        if (message != null) {
                            val rssi = result.rssi
                            val timestamp = result.timestampNanos

                            Log.d(TAG, "Message received from ${message.sourceId}: ${message.payload} (RSSI: $rssi)")

                            // Invoke callback if set
                            callback?.onMessageReceived(message, rssi, timestamp)

                            // Emit to Flow
                            try {
                                _messageFlow.tryEmit(Triple(message, rssi, timestamp))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error emitting to messageFlow: ${e.message}", e)
                            }
                        }
                        // If deserialization fails, silently ignore (not our format)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing scan result: ${e.message}", e)
            }
        }
    }

    /**
     * Clears the current callback and stops scanning.
     * Call this during cleanup (e.g., onDestroy).
     */
    fun cleanup() {
        stopScanning()
        callback = null
    }
}

