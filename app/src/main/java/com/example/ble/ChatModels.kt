package com.example.ble

/** Delivery marker rendered in the chat bubble checkmark UI. */
enum class DeliveryStatus {
    SENT,
    DELIVERED
}

/** A single emoji reaction on a message, as displayed in the UI. */
data class ReactionDisplay(
    val emoji: String,
    val reactorId: String,
    val isMine: Boolean
)

/**
 * Single message row model consumed by chat composables.
 *
 * @param msgIdHex stable message identifier in hex form.
 * @param text human-readable message body.
 * @param isMine true when the local user authored this message.
 * @param timestampMs wall-clock timestamp used for HH:mm formatting.
 * @param status delivery marker shown for outgoing messages.
 * @param starred whether the user has starred this message locally.
 * @param hopCount hop count from the transport layer (-1 if unknown).
 * @param ackTimestamp wall-clock time the ACK was received (0 if pending).
 * @param ackRttMs round-trip latency in ms (-1 if not yet delivered).
 * @param sendTimestampMs wall-clock time the message was inserted locally.
 * @param reactions emoji reactions on this message.
 */
data class ChatUiMessage(
    val msgIdHex: String,
    val text: String,
    val isMine: Boolean,
    val timestampMs: Long,
    val status: DeliveryStatus = DeliveryStatus.SENT,
    val starred: Boolean = false,
    val hopCount: Int = -1,
    val ackTimestamp: Long = 0L,
    val ackRttMs: Long = -1L,
    val sendTimestampMs: Long = 0L,
    val reactions: List<ReactionDisplay> = emptyList(),
    val deletedForEveryone: Boolean = false,
    val deletedAt: Long = 0L
)
