/**
 * Listens for BLE mesh packets and delivers them for deserialization.
 *
 * This file is responsible for continuously scanning for BLE advertisements containing
 * mesh packets, extracting the service data, deserializing to MeshPacket objects,
 * and calling a callback with each valid packet received.
 * No GATT connections are established - this is connectionless listening.
 *
 * Main Class:
 * - BleScanner: Manages the BLE LE scanner and scan callback lifecycle
 *
 * Key Functions:
 * - startScanning(): Begins BLE scan with proper filter and settings
 *   - Checks BLUETOOTH_SCAN permission (Android 12+)
 *   - Starts watchdog to prevent Android 30-min opportunistic demotion
 *   - Uses empty ScanFilter to catch all advertisements (manual UUID check)
 *   - Sets SCAN_MODE_LOW_LATENCY for responsive packet delivery
 *
 * - stopScanning(): Stops active scan and clears watchdog
 *   - Removes watchdog handler callbacks
 *   - Gracefully handles exceptions
 *
 * - isScanning property: Public getter to check if scanner is actively running
 *   - Used by ForegroundMeshService to detect and recover from Samsung scan resets
 *
 * Scan Configuration:
 * - Filter: Empty (ScanFilter.Builder().build())
 *   Why: Hardware UUID filters have known issues with Coded PHY on some devices.
 *   Instead, we receive all BLE packets and manually check for our service UUID.
 *
 * - Scan Mode: SCAN_MODE_LOW_LATENCY
 *   Result: ~100ms callback delays, responsive message delivery
 *
 * - Legacy Mode: false (API 26+)
 *   Enables: Extended advertising (Coded PHY) reception
 *
 * - PHY: PHY_LE_ALL_SUPPORTED
 *   Catches: Both legacy 1M and extended Coded PHY advertisements
 *
 * Watchdog Timer:
 * - Runs every 25 minutes (WATCHDOG_PERIOD_MS)
 * - Stops and restarts scan to prevent Android's 30-min scan demotion
 * - Applies to: API 24+ (Android 7.0+)
 * - Not a workaround for Samsung background resets (different mechanism)
 *
 * Packet Pipeline:
 * 1. onScanResult() called by Android BLE system
 * 2. Extract service data for UUID 12E61727-B41A-45D9-A60F-7C3B4E1D9F2A
 * 3. Verify size: 41-250 bytes (skip if not our packet size)
 * 4. Call PacketSerializer.deserialize() -> MeshPacket or null
 * 5. If valid MeshPacket, call onPacketReceived callback
 *
 * Interactions:
 * - BleConstants.kt: MESH_SERVICE_UUID = 12E61727-B41A-45D9-A60F-7C3B4E1D9F2A
 * - PacketSerializer.kt: Deserializes raw bytes to MeshPacket
 * - MeshPacket.kt: The deserialized packet object
 * - ForegroundMeshService.kt: Constructs BleScanner, passes callback for onPacketReceived
 * - ForegroundMeshService.kt: Calls startScanning()/stopScanning() for lifecycle
 * - BleAdvertiser.kt: Sender side (this is the receiver side)
 *
 * Thread Safety:
 * - Callbacks come on a Binder thread pool (not main thread)
 * - onPacketReceived callback is passed to ForegroundMeshService (handles threading)
 * - Scan stop/start operations are serialized via ScanCallback
 *
 * Android Permissions Required:
 * - android.permission.BLUETOOTH_SCAN (Android 12+, with neverForLocation flag)
 * - android.permission.ACCESS_FINE_LOCATION (Android <12)
 *
 * Error Handling:
 * - onScanFailed() logs error code if scan stops unexpectedly
 * - Permission check prevents crash if BLUETOOTH_SCAN not granted
 * - Null checks on scanRecord and serviceData prevent exceptions
 *
 * Background Behavior:
 * - Continues scanning in background if ForegroundMeshService keeps it alive
 * - Samsung firmware may kill scan on background transition (detected via ACTION_SCREEN_ON)
 * - ForegroundMeshService.scanResumeReceiver restarts scanning on foreground return
 */
package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onPacketReceived: (MeshPacket, Int) -> Unit
) {

    companion object {
        private const val TAG      = "BleScanner"
        private const val DIAG_TAG = "BLE"

        private const val WATCHDOG_PERIOD_MS = 25 * 60 * 1000L   // 25 minutes

        private const val MIN_PACKET_BYTES = 41
        private const val MAX_PACKET_BYTES = 250

        /** Must match the UUID used by BleAdvertiser. */
        val SERVICE_UUID: UUID = BleConstants.MESH_SERVICE_UUID
    }

    private val parcelUuid = ParcelUuid(SERVICE_UUID)
    private var _isScanning = false

    /** True if startScan() has been called successfully and we haven't stopped it. */
    val isScanning: Boolean
        get() = _isScanning

    // ── Watchdog: restarts the scan every 25 minutes to prevent Android's
    //    30-minute opportunistic-scan demotion on API 24+. ──────────────────
    private val watchdogHandler  = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val scanner = bluetoothAdapter?.bluetoothLeScanner
            if (!_isScanning || scanner == null) return
            try { scanner.stopScan(scanCallback) } catch (_: Exception) { }
            try {
                scanner.startScan(buildFilters(), buildScanSettings(), scanCallback)
                AppLogger.d(DIAG_TAG, "BLE: Scanner restarted by watchdog (30-min demotion prevention)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Watchdog restart failed: ${e.message}", e)
            }
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScanning(): Boolean {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothAdapter == null) {
            AppLogger.e(TAG, "BluetoothAdapter is null — BLE not supported on this device")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            AppLogger.e(TAG, "Bluetooth is off — cannot start scanner")
            return false
        }
        if (scanner == null) {
            AppLogger.e(TAG, "BluetoothLeScanner unavailable — is Bluetooth enabled?")
            return false
        }
        if (_isScanning) return true
        if (!hasRequiredScanPermission()) {
            AppLogger.w(DIAG_TAG, "BLE: BLUETOOTH_SCAN permission not granted — scan will not start")
            return false
        }
        return try {
            val filters = buildFilters()
            val settings = buildScanSettings()
            Log.i("BLE", "Scanner starting with filters: ${filters.map { it.serviceUuid?.uuid.toString() }}")
            scanner.startScan(filters, settings, scanCallback)
            _isScanning = true
            watchdogHandler.removeCallbacks(watchdogRunnable)
            watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_PERIOD_MS)
            AppLogger.d(DIAG_TAG, "BLE: Scanner started")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "startScanning failed: ${e.message}", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _isScanning = false
            return
        }
        if (!_isScanning) return
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            AppLogger.e(TAG, "stopScanning: ${e.message}")
        } finally {
            _isScanning = false
            AppLogger.d(DIAG_TAG, "BLE: Scanner stopped")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildFilters(): List<ScanFilter> {
        val meshFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("12E61727-B41A-45D9-A60F-7C3B4E1D9F2A"))
            .build()
        return listOf(meshFilter)
    }

    private fun buildScanSettings(): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        Log.i("BLE", "ScanSettings: legacy=false phy=ALL_SUPPORTED mode=BALANCED")
        return b.build()
    }

    private fun hasRequiredScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    // ── Scan callback ──────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            process(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { process(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            AppLogger.e(TAG, "Scan failed with error code: $errorCode")
        }

        private fun process(result: ScanResult) {
            val record = result.scanRecord ?: return

            // Primary lookup. Fails silently on Samsung Android 12 for Coded PHY extended PDUs.
            var serviceData: ByteArray? = record.getServiceData(parcelUuid)

            // Fallback: iterate the raw map and match by UUID value.
            // Samsung's BLE stack populates serviceData entries but doesn't key them
            // correctly for getServiceData() when the source PHY is Coded.
            if (serviceData == null) {
                serviceData = record.serviceData
                    ?.entries
                    ?.firstOrNull { it.key?.uuid == SERVICE_UUID }
                    ?.value
            }

            if (serviceData == null) return

            // NOTE: Do not attempt self-loop filtering via BluetoothAdapter.address.
            // On Android 12+ the local MAC address is restricted and requires
            // LOCAL_MAC_ADDRESS, which we don't request. Our dedupe cache and
            // msgId-based handling already prevent harmful loops.

            // Pre-filter by size before touching the deserializer.
            if (serviceData.size < MIN_PACKET_BYTES || serviceData.size > MAX_PACKET_BYTES) return

            com.example.ble.debug.StressTestManager.onRssiObserved(result.rssi)

            AppLogger.d(DIAG_TAG,
                "Scanner.onScanResult(): serviceDataLen=${serviceData.size} rssi=${result.rssi}")

            val packet = PacketSerializer.deserialize(serviceData) ?: run {
                Log.v("BLE", "Scanner.deserialize(): null len=${serviceData.size}")
                return
            }

            onPacketReceived(packet, result.rssi)
        }
    }
}