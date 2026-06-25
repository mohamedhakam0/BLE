package com.example.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide sink that BleScanner posts to immediately after a valid packet is parsed,
 * before ForegroundMeshService routing. TestModeViewModel subscribes to collect
 * packets with their raw scan metadata (RSSI, device address).
 *
 * The flow is always active; subscribers filter by their own session state.
 * tryEmit is used (non-suspending, safe from the BLE scanner callback thread).
 */
object TestScanObserver {

    private val _packetScanned = MutableSharedFlow<Pair<MeshPacket, ScanMeta>>(
        extraBufferCapacity = 64
    )
    val packetScanned: SharedFlow<Pair<MeshPacket, ScanMeta>> = _packetScanned.asSharedFlow()

    fun onPacketScanned(packet: MeshPacket, meta: ScanMeta) {
        _packetScanned.tryEmit(Pair(packet, meta))
    }
}
