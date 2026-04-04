/**
 * Lightweight UI models used by chat presentation code.
 *
 * These types represent message delivery state and normalized message fields for rendering.
 */
package com.example.ble

/** Delivery marker rendered in the chat bubble checkmark UI. */
enum class DeliveryStatus {
    SENT,
    DELIVERED
}

/**
 * Single message row model consumed by chat composables.
 *
 * @param msgIdHex stable message identifier in hex form.
 * @param text human-readable message body.
 * @param isMine true when the local user authored this message.
 * @param timestampMs wall-clock timestamp used for HH:mm formatting.
 * @param status delivery marker shown for outgoing messages.
 */
data class ChatUiMessage(
    val msgIdHex: String,
    val text: String,
    val isMine: Boolean,
    val timestampMs: Long,
    val status: DeliveryStatus = DeliveryStatus.SENT
)
