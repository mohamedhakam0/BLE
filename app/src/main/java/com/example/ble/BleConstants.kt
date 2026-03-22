package com.example.ble

import java.util.UUID

/** Shared BLE constants used by both advertiser and scanner to avoid UUID mismatches. */
object BleConstants {
    val MESH_SERVICE_UUID: UUID = UUID.fromString("12E61727-B41A-45D9-A60F-7C3B4E1D9F2A")
}
