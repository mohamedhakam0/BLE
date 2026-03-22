package com.example.ble

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

class ChatViewModel(
    app: Application,
    private val nodeIdentity: NodeIdentity,
    private val advertiser: BleAdvertiser,
    private val messageRepository: MessageRepository,
    private val contactId: String,
    private val contactSenderIdHex: String
) : AndroidViewModel(app) {

    val messages: Flow<List<ChatUiMessage>> =
        messageRepository.observeMessagesForContact(contactId).map { list ->
            list.map { entity ->
                ChatUiMessage(
                    msgIdHex    = entity.msgId.toString(16),
                    text        = entity.text,
                    isMine      = entity.direction == MessageDirection.OUTGOING,
                    timestampMs = entity.timestamp,
                    status      = if (entity.status == MessageStatus.DELIVERED)
                        DeliveryStatus.DELIVERED
                    else
                        DeliveryStatus.SENT
                )
            }
        }

    /** Called by the host (Activity/Service) when a mesh packet arrives for the app. */
    fun handlePacket(bytes: ByteArray) {
        val packet = PacketSerializer.deserialize(bytes) ?: return

        val me = nodeIdentity.getOrCreateIdentity().senderId
        if (!packet.receiverId.contentEquals(me)) return

        when (packet.type) {
            PacketType.ACK -> {
                val msgIdLong = packet.msgId.toLongBE()
                AppLogger.d("ChatVM", "ACK received msgId=$msgIdLong contactId=$contactId")
                viewModelScope.launch(Dispatchers.IO) {
                    val result = messageRepository.updateStatus(msgIdLong, MessageStatus.DELIVERED)
                    AppLogger.d("ChatVM", "ACK updateStatus msgId=$msgIdLong updated=$result")
                }
            }

            PacketType.CHAT -> {
                if (packet.senderId.toHex() != contactSenderIdHex.lowercase()) return

                val msgIdLong = packet.msgId.toLongBE()
                val text      = packet.payload.decodeToString()

                viewModelScope.launch(Dispatchers.IO) {
                    messageRepository.upsert(
                        MessageEntity(
                            msgId      = msgIdLong,
                            contactId  = contactId,
                            text       = text,
                            direction  = MessageDirection.INCOMING,
                            status     = MessageStatus.DELIVERED,
                            timestamp  = System.currentTimeMillis(),
                            insertedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            else -> Unit
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bytes = intent?.getByteArrayExtra(ForegroundMeshService.EXTRA_PACKET_BYTES) ?: return
            handlePacket(bytes)
        }
    }

    init {
        val appCtx = getApplication<Application>()
        val filter = IntentFilter(ForegroundMeshService.ACTION_PACKET_RECEIVED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            (registerReceiver(
                appCtx,
                receiver,
                filter,
                RECEIVER_NOT_EXPORTED
            ))
        }
    }

    override fun onCleared() {
        val appCtx = getApplication<Application>()
        try {
            appCtx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered / never registered; ignore.
        }
        super.onCleared()
    }

    fun sendMessage(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        val me           = nodeIdentity.getOrCreateIdentity().senderId
        val receiverId   = contactSenderIdHex.hexToByteArray4()
        val payloadBytes = cleaned.encodeToByteArray()
        if (payloadBytes.size > 209) return

        val msgIdBytes = Random.nextBytes(8)
        val msgIdLong  = msgIdBytes.toLongBE()

        val packet = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = 7.toByte(),
            hopCount   = 0.toByte(),
            timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = payloadBytes.size.toByte(),
            authTag    = Random.nextBytes(16),
            payload    = payloadBytes
        )

        viewModelScope.launch(Dispatchers.IO) {
            messageRepository.upsert(
                MessageEntity(
                    msgId      = msgIdLong,
                    contactId  = contactId,
                    text       = cleaned,
                    direction  = MessageDirection.OUTGOING,
                    status     = MessageStatus.SENT,
                    timestamp  = System.currentTimeMillis(),
                    insertedAt = System.currentTimeMillis()
                )
            )
        }

        advertiser.broadcast(PacketSerializer.serialize(packet))
    }

    fun deleteHistory(contactId: String = this.contactId) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgIds = messageRepository.getMessageIdsForContact(contactId)
            messageRepository.deleteHistory(contactId)
            ForegroundMeshService.addSeenMsgIds(msgIds)
        }
    }
}

// ── Private extension helpers ─────────────────────────────────────────────────

private fun ByteArray.toLongBE(): Long {
    require(size == 8)
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray4(): ByteArray {
    val clean = trim().lowercase()
    require(clean.length == 8) { "Expected 8 hex chars, got ${clean.length}" }
    return ByteArray(4) { i ->
        val idx = i * 2
        clean.substring(idx, idx + 2).toInt(16).toByte()
    }
}
