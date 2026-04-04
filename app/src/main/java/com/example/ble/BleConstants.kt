/**
 * Shared BLE protocol constants used across advertiser, scanner, and service layers.
 *
 * Keeping these values in a single file prevents UUID mismatches between transmit and receive paths.
 */
package com.example.ble

import java.util.UUID

/** Shared BLE constants used by both advertiser and scanner to avoid UUID mismatches. */
object BleConstants {
    /** Service UUID that tags every Peer Reach mesh advertisement payload. */
    val MESH_SERVICE_UUID: UUID = UUID.fromString("12E61727-B41A-45D9-A60F-7C3B4E1D9F2A")
}
