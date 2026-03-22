package com.example.ble

enum class DeliveryStatus {
    SENT,
    DELIVERED
}

data class ChatUiMessage(
    val msgIdHex: String,
    val text: String,
    val isMine: Boolean,
    val timestampMs: Long,
    val status: DeliveryStatus = DeliveryStatus.SENT
)

