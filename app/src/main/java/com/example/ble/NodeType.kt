package com.example.ble

/** Node type carried in the trailing byte of every HELLO payload (phones) or flags byte (gateways). */
enum class NodeType(val wire: Byte) {
    PHONE(0x00),
    ESP32_RELAY(0x01),
    GATEWAY(0x02);

    companion object {
        fun fromWire(b: Byte): NodeType =
            entries.firstOrNull { it.wire == b } ?: PHONE
    }
}
