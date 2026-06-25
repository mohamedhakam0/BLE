package com.example.ble

sealed class TestEvent {

    /** A test message was queued for transmission by this device. */
    data class Sent(
        val msgId: String,
        val msgCounter: Long,
        val sentTs: Long,
        val attemptNumber: Int = 1
    ) : TestEvent()

    /** A test message arrived at this device (first time seen). */
    data class Received(
        val msgId: String,
        val receivedTs: Long,
        val packetSentTs: Long,       // packet.timestamp * 1000 (unix-second from header)
        val hopCount: Int,
        val rssiDbm: Int,
        val immediateSenderId: String, // BLE device address of the advertising node
        val originSenderId: String,    // packet.senderId hex (original author)
        val decryptionSuccess: Boolean?,  // null = no session key available to verify
        val payloadVerified: Boolean?     // null = no session key available to verify
    ) : TestEvent()

    /** An ACK for one of our sent messages was observed on the scan. */
    data class AckReceived(
        val msgId: String,
        val ackTs: Long,
        val ackRssiDbm: Int,
        val immediateSenderId: String,
        val originSenderId: String,    // packet.senderId of the ACK (= the peer who acked)
        val ackWithinTimeout: Boolean
    ) : TestEvent()

    /** No ACK was observed for a sent message within the settling window. */
    data class AckTimeout(
        val msgId: String,
        val timeoutTs: Long
    ) : TestEvent()

    /** A CHAT packet arrived but AES-GCM auth-tag verification failed. */
    data class DecryptionFailed(
        val msgId: String,
        val receivedTs: Long,
        val packetSentTs: Long,
        val hopCount: Int,
        val rssiDbm: Int,
        val immediateSenderId: String,
        val originSenderId: String
    ) : TestEvent()

    /** A CHAT packet arrived with a msgId already seen (relay duplicate). */
    data class DuplicateDropped(
        val msgId: String,
        val receivedTs: Long,
        val packetSentTs: Long,
        val hopCount: Int,
        val rssiDbm: Int,
        val immediateSenderId: String,
        val originSenderId: String
    ) : TestEvent()
}
