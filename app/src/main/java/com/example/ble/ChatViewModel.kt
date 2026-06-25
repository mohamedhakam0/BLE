package com.example.ble

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * One-shot routing outcome emitted by [ChatViewModel] for each outgoing user message.
 *
 * [LoraRequired] fires when the recipient's node ID is not found in [NeighborTable] as either
 * a direct (hop-0) or extended/piggybacked (hop-1) neighbor, meaning the device is outside the
 * current BLE cluster and the message would need LoRa to reach them.
 */
sealed interface RouteEvent {
    data class LoraRequired(val recipientHex: String) : RouteEvent
}

val FIXED_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private const val EMOJI_INDEX_CUSTOM = 0xFF

class ChatViewModel(
    app: Application,
    private val nodeIdentity: NodeIdentity,
    private val advertiser: BleAdvertiser,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val contactId: String,
    private val contactSenderIdHex: String,
    private val contactPublicKeyB64: String = ""
) : AndroidViewModel(app) {

    // Emits one event per outgoing message that could not be routed via BLE.
    private val _routeEvents = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
    val routeEvents: SharedFlow<RouteEvent> = _routeEvents.asSharedFlow()

    private val sessionCrypto: Pair<ByteArray, ByteArray>? by lazy {
        if (contactPublicKeyB64.isBlank()) return@lazy null
        try {
            val identity = nodeIdentity.getOrCreateIdentity()
            val peerPubKey = Base64.decode(contactPublicKeyB64, Base64.NO_WRAP)
            if (peerPubKey.size != 32) return@lazy null
            val shared = CryptoManager.computeSharedSecret(identity.privateKey, peerPubKey)
            val myKey4   = identity.publicKey.copyOfRange(0, 4)
            val peerKey4 = peerPubKey.copyOfRange(0, 4)
            val (lo, hi) = if (myKey4.unsignedLexCompare(peerKey4) <= 0)
                Pair(myKey4, peerKey4) else Pair(peerKey4, myKey4)
            val full = CryptoManager.deriveSessionKey(shared, lo, hi)
            Pair(full.copyOfRange(0, 16), full.copyOfRange(16, 28))
        } catch (_: Exception) {
            null
        }
    }

    val messages: Flow<List<ChatUiMessage>> = combine(
        messageRepository.observeMessagesForContact(contactId),
        messageRepository.observeReactionsForContact(contactId)
    ) { msgs, reactions ->
        val reactionsByMsgId = reactions.groupBy { it.msgId }
        val myIdHex = nodeIdentity.getOrCreateIdentity().senderId.toHex().lowercase()
        msgs.map { entity ->
            ChatUiMessage(
                msgIdHex           = entity.msgId.toString(16),
                text               = entity.text,
                isMine             = entity.direction == MessageDirection.OUTGOING,
                timestampMs        = entity.timestamp,
                status             = if (entity.status == MessageStatus.DELIVERED) DeliveryStatus.DELIVERED else DeliveryStatus.SENT,
                starred            = entity.starred,
                hopCount           = entity.hopCount,
                ackTimestamp       = entity.ackTimestamp,
                ackRttMs           = entity.ackRttMs,
                sendTimestampMs    = entity.insertedAt,
                reactions          = if (entity.deletedForEveryone) emptyList()
                                     else reactionsByMsgId[entity.msgId]?.map { r ->
                                         ReactionDisplay(emoji = r.emoji, reactorId = r.reactorId, isMine = r.reactorId == myIdHex)
                                     } ?: emptyList(),
                deletedForEveryone = entity.deletedForEveryone,
                deletedAt          = entity.deletedAt
            )
        }
    }

    val starredMessages: Flow<List<ChatUiMessage>> =
        messageRepository.observeStarredForContact(contactId).map { list ->
            list.map { entity ->
                ChatUiMessage(
                    msgIdHex    = entity.msgId.toString(16),
                    text        = entity.text,
                    isMine      = entity.direction == MessageDirection.OUTGOING,
                    timestampMs = entity.timestamp,
                    status      = if (entity.status == MessageStatus.DELIVERED) DeliveryStatus.DELIVERED else DeliveryStatus.SENT,
                    starred     = true
                )
            }
        }

    val contacts: Flow<List<ContactLastMessageRow>> =
        contactRepository.observeContactsWithLastMessage()

    fun handlePacket(bytes: ByteArray) {
        val packet = PacketSerializer.deserialize(bytes) ?: return

        val me = nodeIdentity.getOrCreateIdentity().senderId
        if (!packet.receiverId.contentEquals(me)) return

        when (packet.type) {
            PacketType.ACK -> {
                val referencedMsgIdBytes = packet.payload
                    .takeIf { it.size >= 8 }
                    ?.copyOfRange(0, 8)
                    ?: run {
                        AppLogger.w("ChatVM", "ACK dropped — missing referenced msgId payload")
                        return
                    }

                val msgIdLong = referencedMsgIdBytes.toLongBE()
                if (DebugBuildFlags.isDebug) {
                    com.example.ble.debug.StressTestManager.onAckReceived(
                        referencedMsgIdBytes.joinToString("") { "%02x".format(it) }
                    )
                }
                AppLogger.d("ChatVM", "ACK received msgId=$msgIdLong contactId=$contactId")

                val ackHopCount = packet.hopCount.toInt() and 0xFF
                val ackTime = System.currentTimeMillis()

                viewModelScope.launch(Dispatchers.IO) {
                    val entity = messageRepository.getById(msgIdLong)
                    val rtt = if (entity != null) ackTime - entity.insertedAt else -1L
                    messageRepository.updateStatus(msgIdLong, MessageStatus.DELIVERED)
                    messageRepository.updateDeliveryInfo(msgIdLong, ackHopCount, ackTime, rtt)
                    AppLogger.d("ChatVM", "ACK recorded msgId=$msgIdLong hopCount=$ackHopCount rtt=${rtt}ms")
                }
            }

            PacketType.CHAT -> {
                val incomingSender = packet.senderId.toHex().trim().lowercase()
                val expectedSender = contactSenderIdHex.trim().lowercase()

                AppLogger.d("ChatVM", "CHAT received: sender=$incomingSender expected=$expectedSender contactId=$contactId")

                if (incomingSender != expectedSender) {
                    AppLogger.w("ChatVM", "CHAT dropped — sender mismatch: incoming=$incomingSender expected=$expectedSender")
                    return
                }

                val msgIdLong = packet.msgId.toLongBE()
                val incomingHopCount = packet.hopCount.toInt() and 0xFF

                val text = sessionCrypto?.let { (aesKey, nonceBase) ->
                    try {
                        val aad = CryptoManager.buildAad(packet)
                        CryptoManager.decrypt(aesKey, nonceBase, msgIdLong, packet.payload, packet.authTag, aad)
                            ?.decodeToString()
                    } catch (_: Exception) { null }
                } ?: packet.payload.decodeToString()

                AppLogger.d("ChatVM", "CHAT storing: msgId=$msgIdLong text='${text.take(40)}' contactId=$contactId")

                viewModelScope.launch(Dispatchers.IO) {
                    // Honour a tombstone that arrived before the message (delayed path)
                    val existing = messageRepository.getById(msgIdLong)
                    if (existing?.deletedForEveryone == true) return@launch

                    messageRepository.upsert(
                        MessageEntity(
                            msgId      = msgIdLong,
                            contactId  = contactId,
                            text       = text,
                            direction  = MessageDirection.INCOMING,
                            status     = MessageStatus.DELIVERED,
                            timestamp  = System.currentTimeMillis(),
                            insertedAt = System.currentTimeMillis(),
                            hopCount   = incomingHopCount
                        )
                    )
                    if (!isAppInForeground()) {
                        val contact = contactRepository.getContact(contactSenderIdHex)
                        val senderName = contact?.nickname ?: "Peer-${contactSenderIdHex.take(8)}"
                        NotificationHelper.showMessageNotification(
                            context    = getApplication(),
                            senderName = senderName,
                            preview    = text.take(80),
                            contactId  = contactSenderIdHex
                        )
                    }
                }
            }

            PacketType.REACTION -> {
                val incomingSender = packet.senderId.toHex().trim().lowercase()
                val expectedSender = contactSenderIdHex.trim().lowercase()
                if (incomingSender != expectedSender) return

                val (aesKey, nonceBase) = sessionCrypto ?: return  // no key exchange → drop

                val packetMsgIdLong = packet.msgId.toLongBE()
                val plainPayload = try {
                    val aad = CryptoManager.buildAad(packet)
                    CryptoManager.decrypt(aesKey, nonceBase, packetMsgIdLong, packet.payload, packet.authTag, aad)
                } catch (_: Exception) { null } ?: return

                if (plainPayload.size < 9) return

                val targetMsgIdLong = plainPayload.copyOfRange(0, 8).toLongBE()
                val emojiIndex = plainPayload[8].toInt() and 0xFF
                val emoji = when {
                    emojiIndex < FIXED_EMOJIS.size -> FIXED_EMOJIS[emojiIndex]
                    emojiIndex == EMOJI_INDEX_CUSTOM && plainPayload.size > 9 ->
                        plainPayload.copyOfRange(9, plainPayload.size).decodeToString()
                    else -> return
                }

                viewModelScope.launch(Dispatchers.IO) {
                    messageRepository.upsertReaction(
                        ReactionEntity(
                            msgId     = targetMsgIdLong,
                            reactorId = incomingSender,
                            emoji     = emoji,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    if (!isAppInForeground()) {
                        val contact = contactRepository.getContact(contactSenderIdHex)
                        val senderName = contact?.nickname ?: "Peer-${contactSenderIdHex.take(8)}"
                        val targetMsg = messageRepository.getById(targetMsgIdLong)
                        val msgPreview = targetMsg?.text?.take(40) ?: ""
                        NotificationHelper.showReactionNotification(
                            context        = getApplication(),
                            senderName     = senderName,
                            emoji          = emoji,
                            messagePreview = msgPreview,
                            contactId      = contactSenderIdHex
                        )
                    }
                }
            }

            PacketType.DELETE -> {
                val incomingSender = packet.senderId.toHex().trim().lowercase()
                val expectedSender = contactSenderIdHex.trim().lowercase()
                if (incomingSender != expectedSender) return

                val (aesKey, nonceBase) = sessionCrypto ?: return  // no key exchange → drop

                val packetMsgIdLong = packet.msgId.toLongBE()
                val payloadBytes = try {
                    val aad = CryptoManager.buildAad(packet)
                    CryptoManager.decrypt(aesKey, nonceBase, packetMsgIdLong, packet.payload, packet.authTag, aad)
                } catch (_: Exception) { null } ?: return

                if (payloadBytes.size < 8) return
                val targetMsgIdLong = payloadBytes.copyOfRange(0, 8).toLongBE()

                viewModelScope.launch(Dispatchers.IO) {
                    val existing = messageRepository.getById(targetMsgIdLong)
                    // Only the sender of a message may delete it. Reject if the targeted
                    // message was sent by us (OUTGOING), or hasn't arrived yet (null).
                    if (existing?.direction != MessageDirection.INCOMING) return@launch
                    messageRepository.softDeleteForEveryone(targetMsgIdLong, System.currentTimeMillis())
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

    private fun currentTtl(): Byte =
        getApplication<Application>()
            .getSharedPreferences(MeshSettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(MeshSettingsViewModel.KEY_TTL, 6)
            .coerceIn(2, 7)
            .toByte()

    private fun isAppInForeground(): Boolean {
        val app = getApplication<Application>()
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == app.packageName
        } == true
    }

    override fun onCleared() {
        val appCtx = getApplication<Application>()
        try {
            appCtx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
        super.onCleared()
    }

    fun sendMessage(text: String, forceLoraEligible: Boolean = false) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        val me         = nodeIdentity.getOrCreateIdentity().senderId
        val receiverId = contactSenderIdHex.hexToByteArray4()
        val msgIdBytes = Random.nextBytes(8)
        val msgIdLong  = msgIdBytes.toLongBE()

        val plainBytes = cleaned.encodeToByteArray()
        if (plainBytes.size > 208) return

        val loraFlags = if (forceLoraEligible) MeshPacket.FLAG_LORA_ELIGIBLE else 0x00.toByte()

        val skeleton = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = currentTtl(),
            hopCount   = 0.toByte(),
            timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
            flags      = loraFlags,
            payloadLen = plainBytes.size.toByte(),
            authTag    = ByteArray(16),
            payload    = plainBytes
        )

        val (payload, authTag) = sessionCrypto?.let { (aesKey, nonceBase) ->
            try {
                val aad = CryptoManager.buildAad(skeleton)
                val result = CryptoManager.encrypt(aesKey, nonceBase, msgIdLong, plainBytes, aad)
                Pair(result.ciphertext, result.authTag)
            } catch (_: Exception) { null }
        } ?: Pair(plainBytes, Random.nextBytes(16))

        if (payload.size > 208) return

        val packet = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = currentTtl(),
            hopCount   = 0.toByte(),
            timestamp  = skeleton.timestamp,
            flags      = loraFlags,
            payloadLen = payload.size.toByte(),
            authTag    = authTag,
            payload    = payload
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

        // Routing decision: consult the neighbor table to determine whether the recipient
        // is reachable within the local BLE cluster.
        //
        // • hopCount == 0  →  direct neighbor seen over BLE
        // • hopCount == 1  →  extended neighbor discovered via a piggybacked HELLO
        // Both cases are treated as BLE-local; LoRa bridges remote clusters.
        val recipientHex = contactSenderIdHex.trim().lowercase()
        val neighbor = NeighborTable.lookup(recipientHex)
        val hasGateway = NeighborTable.hasGatewayNeighbor()
        when {
            neighbor != null -> {
                AppLogger.d("Route", "CHAT → BLE dst=$recipientHex hopCount=${neighbor.hopCount}")
                advertiser.enqueue(packet)
            }
            hasGateway -> {
                // Recipient is outside the local BLE cluster but a LoRa gateway is visible.
                // Set LORA_ELIGIBLE so the gateway forwards this packet over the LoRa link.
                val loraPacket = packet.copy(flags = MeshPacket.FLAG_LORA_ELIGIBLE)
                AppLogger.i("Route", "CHAT → LoRa via gateway dst=$recipientHex")
                advertiser.enqueue(loraPacket)
            }
            else -> {
                AppLogger.i(
                    "Route",
                    "Recipient $recipientHex not in BLE cluster and no gateway visible"
                )
                _routeEvents.tryEmit(RouteEvent.LoraRequired(recipientHex))
            }
        }
    }

    fun sendReaction(targetMsgIdHex: String, emoji: String) {
        val (aesKey, nonceBase) = sessionCrypto ?: return  // no key exchange → don't send

        val me = nodeIdentity.getOrCreateIdentity().senderId
        val receiverId = try { contactSenderIdHex.hexToByteArray4() } catch (_: Exception) { return }

        val targetMsgIdLong = try { targetMsgIdHex.toLong(16) } catch (_: Exception) { return }
        val targetMsgIdBytes = targetMsgIdLong.to8BytesBE()

        val emojiIndex = FIXED_EMOJIS.indexOf(emoji)
        val plainPayload = if (emojiIndex >= 0) {
            targetMsgIdBytes + byteArrayOf(emojiIndex.toByte())
        } else {
            val emojiBytes = emoji.encodeToByteArray().take(4).toByteArray()
            targetMsgIdBytes + byteArrayOf(EMOJI_INDEX_CUSTOM.toByte()) + emojiBytes
        }

        if (plainPayload.size > 208) return

        val msgIdBytes = Random.nextBytes(8)
        val msgIdLong  = msgIdBytes.toLongBE()

        val skeleton = MeshPacket(
            type       = PacketType.REACTION,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = currentTtl(),
            hopCount   = 0.toByte(),
            timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = plainPayload.size.toByte(),
            authTag    = ByteArray(16),
            payload    = plainPayload
        )

        val encrypted = try {
            val aad = CryptoManager.buildAad(skeleton)
            CryptoManager.encrypt(aesKey, nonceBase, msgIdLong, plainPayload, aad)
        } catch (_: Exception) { return }

        if (encrypted.ciphertext.size > 208) return

        val packet = skeleton.copy(
            payloadLen = encrypted.ciphertext.size.toByte(),
            authTag    = encrypted.authTag,
            payload    = encrypted.ciphertext
        )

        advertiser.enqueue(packet)

        val myIdHex = me.toHex().lowercase()
        viewModelScope.launch(Dispatchers.IO) {
            messageRepository.upsertReaction(
                ReactionEntity(
                    msgId     = targetMsgIdLong,
                    reactorId = myIdHex,
                    emoji     = emoji,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun toggleStar(msgIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgIdLong = try { msgIdHex.toLong(16) } catch (_: Exception) { return@launch }
            val entity = messageRepository.getById(msgIdLong) ?: return@launch
            messageRepository.updateStarred(msgIdLong, !entity.starred)
        }
    }

    fun deleteForMe(msgIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgIdLong = try { msgIdHex.toLong(16) } catch (_: Exception) { return@launch }
            messageRepository.softDeleteForMe(msgIdLong, System.currentTimeMillis())
        }
    }

    fun deleteForEveryone(msgIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgIdLong = try { msgIdHex.toLong(16) } catch (_: Exception) { return@launch }
            val entity = messageRepository.getById(msgIdLong) ?: return@launch
            if (entity.direction != MessageDirection.OUTGOING) return@launch

            val now = System.currentTimeMillis()
            messageRepository.softDeleteForEveryone(msgIdLong, now)

            val me         = nodeIdentity.getOrCreateIdentity().senderId
            val receiverId = try { contactSenderIdHex.hexToByteArray4() } catch (_: Exception) { return@launch }
            val targetMsgIdBytes = msgIdLong.to8BytesBE()
            val packetMsgIdBytes = Random.nextBytes(8)
            val packetMsgIdLong  = packetMsgIdBytes.toLongBE()

            val skeleton = MeshPacket(
                type       = PacketType.DELETE,
                msgId      = packetMsgIdBytes,
                senderId   = me,
                receiverId = receiverId,
                ttl        = currentTtl(),
                hopCount   = 0.toByte(),
                timestamp  = (now / 1000L).toInt(),
                payloadLen = targetMsgIdBytes.size.toByte(),
                authTag    = ByteArray(16),
                payload    = targetMsgIdBytes
            )

            val (aesKey, nonceBase) = sessionCrypto ?: return@launch  // no key exchange → don't send
            val (payload, authTag) = try {
                val aad = CryptoManager.buildAad(skeleton)
                val result = CryptoManager.encrypt(aesKey, nonceBase, packetMsgIdLong, targetMsgIdBytes, aad)
                Pair(result.ciphertext, result.authTag)
            } catch (_: Exception) { return@launch }

            if (payload.size > 208) return@launch

            val packet = skeleton.copy(
                payloadLen = payload.size.toByte(),
                authTag    = authTag,
                payload    = payload
            )
            withContext(Dispatchers.Main) { advertiser.enqueue(packet) }
        }
    }

    fun resendMessage(msgIdHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgIdLong = try { msgIdHex.toLong(16) } catch (_: Exception) { return@launch }
            val entity = messageRepository.getById(msgIdLong) ?: return@launch
            if (entity.direction != MessageDirection.OUTGOING) return@launch
            if (entity.status == MessageStatus.DELIVERED) return@launch
            messageRepository.softDeleteForMe(msgIdLong, System.currentTimeMillis())
            withContext(Dispatchers.Main) { sendMessage(entity.text) }
        }
    }

    fun forwardMessage(text: String, targetContact: ContactLastMessageRow) {
        val me = nodeIdentity.getOrCreateIdentity().senderId
        val receiverId = try { targetContact.senderId.hexToByteArray4() } catch (_: Exception) { return }

        val msgIdBytes = Random.nextBytes(8)
        val msgIdLong  = msgIdBytes.toLongBE()
        val plainBytes = text.encodeToByteArray()
        if (plainBytes.size > 208) return

        // Compute session crypto for target contact
        val targetCrypto: Pair<ByteArray, ByteArray>? = if (targetContact.publicKey.isNotBlank()) {
            try {
                val identity = nodeIdentity.getOrCreateIdentity()
                val peerPubKey = Base64.decode(targetContact.publicKey, Base64.NO_WRAP)
                if (peerPubKey.size != 32) null else {
                    val shared = CryptoManager.computeSharedSecret(identity.privateKey, peerPubKey)
                    val myKey4   = identity.publicKey.copyOfRange(0, 4)
                    val peerKey4 = peerPubKey.copyOfRange(0, 4)
                    val (lo, hi) = if (myKey4.unsignedLexCompare(peerKey4) <= 0)
                        Pair(myKey4, peerKey4) else Pair(peerKey4, myKey4)
                    val full = CryptoManager.deriveSessionKey(shared, lo, hi)
                    Pair(full.copyOfRange(0, 16), full.copyOfRange(16, 28))
                }
            } catch (_: Exception) { null }
        } else null

        val skeleton = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = currentTtl(),
            hopCount   = 0.toByte(),
            timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
            payloadLen = plainBytes.size.toByte(),
            authTag    = ByteArray(16),
            payload    = plainBytes
        )

        val (payload, authTag) = targetCrypto?.let { (aesKey, nonceBase) ->
            try {
                val aad = CryptoManager.buildAad(skeleton)
                val result = CryptoManager.encrypt(aesKey, nonceBase, msgIdLong, plainBytes, aad)
                Pair(result.ciphertext, result.authTag)
            } catch (_: Exception) { null }
        } ?: Pair(plainBytes, Random.nextBytes(16))

        if (payload.size > 208) return

        val packet = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = currentTtl(),
            hopCount   = 0.toByte(),
            timestamp  = skeleton.timestamp,
            payloadLen = payload.size.toByte(),
            authTag    = authTag,
            payload    = payload
        )

        viewModelScope.launch(Dispatchers.IO) {
            messageRepository.upsert(
                MessageEntity(
                    msgId      = msgIdLong,
                    contactId  = targetContact.senderId,
                    text       = text,
                    direction  = MessageDirection.OUTGOING,
                    status     = MessageStatus.SENT,
                    timestamp  = System.currentTimeMillis(),
                    insertedAt = System.currentTimeMillis()
                )
            )
        }

        advertiser.enqueue(packet)
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

private fun ByteArray.unsignedLexCompare(other: ByteArray): Int {
    for (i in indices) {
        val diff = (this[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
        if (diff != 0) return diff
    }
    return size - other.size
}

private fun ByteArray.toLongBE(): Long {
    require(size == 8)
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

private fun Long.to8BytesBE(): ByteArray {
    val b = ByteArray(8)
    var v = this
    for (i in 7 downTo 0) {
        b[i] = (v and 0xFF).toByte()
        v = v ushr 8
    }
    return b
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
