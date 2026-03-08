package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.util.Log

/**
 * BleAdvertiser handles broadcasting BleMessage objects via BLE extended advertising.
 *
 * This class uses BLE 5 extended advertising capabilities (available on API 26+) to broadcast
 * custom message payloads without establishing any connection. It's designed to run
 * independently alongside the existing GATT implementation.
 *
 * Usage:
 * ```
 * val advertiser = BleAdvertiser(bluetoothAdapter)
 * val message = BleMessage(
 *     messageId = UUID.randomUUID(),
 *     sourceId = "device-001",
 *     destinationId = "*",
 *     payload = "Hello, BLE World!"
 * )
 * advertiser.broadcastMessage(message)
 * // ... later ...
 * advertiser.stopBroadcast()
 * ```
 */
class BleAdvertiser(private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "BleAdvertiser"
    }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var ongoingCallback: AdvertisingSetCallback? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (bleAdvertiser == null) {
                Log.w(TAG, "BluetoothLeAdvertiser not available on this device")
            }
        }
    }

    /**
     * Broadcasts a BleMessage via BLE extended advertising.
     *
     * @param message The BleMessage to broadcast
     * @return true if advertising was started successfully, false otherwise
     */
    @SuppressLint("MissingPermission")
    fun broadcastMessage(message: BleMessage, durationMs: Int = 0): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || bleAdvertiser == null) {
            Log.e(TAG, "Extended advertising requires API 26+ and a compatible advertiser.")
            return false
        }
        
        // If already broadcasting, stop the old one first
        if (isBroadcasting()) {
            stopBroadcast()
        }

        try {
            // This assumes a `toByteArray()` method exists on your BleMessage class
            val payload = message.toByteArray()

            if (payload.size > 251) {
                Log.e(TAG, "Message payload too large: ${'$'}{payload.size} bytes (max 251)")
                return false
            }

            // Create advertising parameters for extended advertising with Coded PHY
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)   // Use Coded PHY for long range
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED) // Use Coded PHY for long range
                .build()

            val advertiseData = AdvertiseData.Builder()
                .addManufacturerData(0x004C, payload) // Using a generic manufacturer ID
                .setIncludeDeviceName(false)
                .build()

            ongoingCallback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                    if (status == 0) {  // 0 = ADVERTISE_SUCCESS
                        Log.d(TAG, "Advertising started successfully. TX Power: $txPower")
                    } else {
                        Log.e(TAG, "Advertising failed to start with status: $status")
                        ongoingCallback = null
                    }
                }

                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                    Log.d(TAG, "Advertising stopped")
                    ongoingCallback = null
                }
            }

            // Correctly call startAdvertisingSet for non-connectable, non-scannable advertising
            bleAdvertiser?.startAdvertisingSet(params, advertiseData, null, null, null, ongoingCallback)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting broadcast: ${'$'}{e.message}", e)
            return false
        }
    }

    /**
     * Stops the current BLE advertisement broadcast.
     */
    @SuppressLint("MissingPermission")
    fun stopBroadcast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bleAdvertiser != null && ongoingCallback != null) {
            try {
                bleAdvertiser?.stopAdvertisingSet(ongoingCallback)
                Log.d(TAG, "Broadcast stop requested.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping broadcast: ${'$'}{e.message}", e)
            } finally {
                ongoingCallback = null
            }
        }
    }

    /**
     * Updates the message being broadcast.
     * This stops the current broadcast and starts a new one with the new message.
     */
    fun updateMessage(message: BleMessage): Boolean {
        stopBroadcast()
        return broadcastMessage(message)
    }

    /**
     * Checks if the advertiser is currently broadcasting.
     */
    fun isBroadcasting(): Boolean = ongoingCallback != null

    /**
     * Returns the current BLE advertiser instance, or null if not available.
     */
    fun getAdvertiser(): BluetoothLeAdvertiser? = bleAdvertiser
}
