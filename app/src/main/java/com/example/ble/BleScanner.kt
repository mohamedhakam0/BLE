package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
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
import androidx.core.content.ContextCompat
import java.util.UUID

class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onPacketReceived: (MeshPacket) -> Unit
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
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
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
        if (scanner == null) {
            AppLogger.w(TAG, "BluetoothLeScanner not available")
            return false
        }
        if (_isScanning) return true
        if (!hasRequiredScanPermission()) {
            AppLogger.w(DIAG_TAG, "BLE: BLUETOOTH_SCAN permission not granted — scan will not start")
            return false
        }
        return try {
            scanner.startScan(buildFilters(), buildScanSettings(), scanCallback)
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
        if (!_isScanning) return
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            AppLogger.e(TAG, "stopScanning: ${e.message}")
        } finally {
            _isScanning = false
            AppLogger.d(DIAG_TAG, "BLE: Scanner stopped")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildFilters(): List<ScanFilter> =
        listOf(
            // Empty filter: receive all advertisements; we manually check serviceData.
            // This avoids vendor/OS quirks where the hardware filter silently drops
            // extended/Coded PHY advertisements.
            ScanFilter.Builder().build()
        )

    private fun buildScanSettings(): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
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
            val record      = result.scanRecord ?: return
            val serviceData = record.getServiceData(parcelUuid) ?: return

            // NOTE: Do not attempt self-loop filtering via BluetoothAdapter.address.
            // On Android 12+ the local MAC address is restricted and requires
            // LOCAL_MAC_ADDRESS, which we don't request. Our dedupe cache and
            // msgId-based handling already prevent harmful loops.

            // Pre-filter by size before touching the deserializer.
            if (serviceData.size < MIN_PACKET_BYTES || serviceData.size > MAX_PACKET_BYTES) return

            AppLogger.d(DIAG_TAG,
                "Scanner.onScanResult(): serviceDataLen=${serviceData.size} rssi=${result.rssi}")

            val packet = PacketSerializer.deserialize(serviceData) ?: run {
                AppLogger.d(DIAG_TAG, "Scanner.deserialize(): null len=${serviceData.size}")
                return
            }

            onPacketReceived(packet)
        }
    }
}