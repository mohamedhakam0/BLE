/**
 * Chat business logic and lifecycle management for a single contact conversation.
 *
 * This file bridges the UI layer (ChatScreen) with the BLE/database layers.
 * It handles sending messages, receiving messages, ACK processing, and persistence.
 * One instance per open chat screen (keyed by contactId in MainActivity).
 *
 * Main Class:
 * - ChatViewModel: Android ViewModel for lifecycle-aware state management
 *
 * Key Functions:
 * - sendMessage(text): User sends a message
 *   1. Creates MeshPacket(type=CHAT, senderId=me, receiverId=contact)
 *   2. Serializes to ByteArray via PacketSerializer
 *   3. Calls BleAdvertiser.broadcast() to start transmission
 *   4. Stores MessageEntity in Room with status=SENT
 *   5. Message appears in UI immediately with single checkmark
 *
 * - handlePacket(bytes): Receives packet from ForegroundMeshService broadcast
 *   1. Deserializes ByteArray to MeshPacket
 *   2. Verifies receiverId is me (filter out broadcast/misdirected packets)
 *   3. If type=ACK: Update matching sent message status to DELIVERED (double checkmark)
 *   4. If type=CHAT: Store incoming message in Room, add to UI
 *   5. Send ACK back (now done in ForegroundMeshService, not here)
 *
 * - deleteHistory(contactId): Delete all messages with this contact
 *   1. Query Room for all MessageEntity rows with this contactId
 *   2. Extract msgId values from those messages
 *   3. Delete all rows from database
 *   4. Add msgIds to ForegroundMeshService.addSeenMsgIds() dedup cache
 *   5. Clear message list in UI
 *   Result: If other device re-broadcasts same messages, they're silently dropped
 *
 * Data Flow (Message Send):
 * User types "Hello" → ChatScreen.input state updated
 *   ↓
 * User taps Send → sendMessage("Hello") called
 *   ↓
 * Create MeshPacket(type=CHAT, payload="Hello", ...)
 *   ↓
 * Serialize to 41-byte header + payload
 *   ↓
 * BleAdvertiser.broadcast(bytes) → starts 5 retries over ~25 seconds
 *   ↓
 * MessageEntity stored in Room: status=SENT, timestamp=now
 *   ↓
 * ChatScreen re-renders, message appears with single checkmark
 *   ↓
 * [If recipient's device receives within retry window]
 *   ↓
 * ForegroundMeshService sends ACK back
 *   ↓
 * BleAdvertiser broadcasts ACK packet
 *   ↓
 * This device's BleScanner receives ACK
 *   ↓
 * ForegroundMeshService.onPacketReceived handles ACK
 *   ↓
 * Broadcasts ACTION_PACKET_RECEIVED intent with ACK bytes
 *   ↓
 * ChatViewModel.mPacketReceiver handles ACK
 *   ↓
 * Finds MessageEntity with matching msgId
 *   ↓
 * Updates status to DELIVERED, saves to Room
 *   ↓
 * messages Flow emits updated list
 *   ↓
 * ChatScreen re-renders, single checkmark becomes double checkmark ✓
 *
 * Data Flow (Message Receive):
 * [Another device sends CHAT packet]
 *   ↓
 * BleScanner receives advertising packet
 *   ↓
 * Deserializes to MeshPacket(type=CHAT, payload="Hi", senderId=friend, receiverId=me)
 *   ↓
 * ForegroundMeshService.onPacketReceived callback fires
 *   ↓
 * Dedup check passes (new msgId)
 *   ↓
 * ForegroundMeshService sends ACK back
 *   ↓
 * Broadcasts ACTION_PACKET_RECEIVED with CHAT packet bytes
 *   ↓
 * ChatViewModel.mPacketReceiver.onReceive() fires
 *   ↓
 * handlePacket(bytes) deserializes CHAT packet
 *   ↓
 * Stores MessageEntity in Room: direction=INCOMING, status=DELIVERED
 *   ↓
 * messages Flow emits updated list
 *   ↓
 * ChatScreen re-renders, new message bubble appears with animation
 *
 * BroadcastReceiver (mPacketReceiver):
 * - Registers in onCleared() → onCreate()
 * - Listens for ForegroundMeshService.ACTION_PACKET_RECEIVED
 * - Calls handlePacket() with packet bytes
 * - Unregisters in onCleared() to avoid memory leaks
 * - Context.RECEIVER_NOT_EXPORTED for Android 14+ safety
 *
 * Room Integration:
 * - messageRepository.observeMessagesForContact(contactId)
 *   Returns Flow<List<MessageEntity>> ordered by timestamp
 *   Auto-updates UI when messages added/updated in database
 *
 * - messageRepository.upsert(message)
 *   Insert or update MessageEntity
 *   Returns msgId for later ACK matching
 *
 * - messageRepository.updateStatus(msgId, status)
 *   Update only the status field (SENT → DELIVERED)
 *   Minimal database write
 *
 * State Mapping:
 * - MessageEntity (in database):
 *   msgId (Long), contactId (String), text (String),
 *   direction (INCOMING/OUTGOING), status (SENT/DELIVERED), timestamp (Long)
 *
 * - ChatUiMessage (in UI):
 *   msgIdHex (String), text (String), isMine (Boolean),
 *   timestampMs (Long), status (DeliveryStatus)
 *   Transformation in messages Flow.map()
 *
 * Thread Model:
 * - Main thread: ChatScreen Compose rendering
 * - IO thread: Room database operations (via messageRepository coroutines)
 * - Binder thread: BroadcastReceiver.onReceive() (posted to main via Intent)
 * - viewModelScope: Lifecycle-aware coroutine scope (cancelled in onCleared)
 *
 * Lifecycle:
 * - onCreate(): Register BroadcastReceiver
 * - onCleared(): Unregister BroadcastReceiver, cancel all coroutines
 * - Activity destroyed: ViewModel destroyed
 * - New chat opened with same contact: New ViewModel instance (key by contactId)
 *
 * Error Handling:
 * - Null packet after deserialize: Silently skip (invalid bytes)
 * - Wrong receiverId: Silently skip (not for me)
 * - Database write fails: Logged via AppLogger
 * - Broadcast delivery fails: Silently fails (no retry at ViewModel level)
 *
 * Performance:
 * - Lazy evaluation: messages Flow only emits on database change
 * - Coroutine scope: Expensive DB operations don't block UI thread
 * - Room transactions: Atomic updates prevent race conditions
 * - No in-memory cache: Room is source of truth
 *
 * Interactions:
 * - ChatScreen.kt: UI layer, calls sendMessage()
 * - ForegroundMeshService.kt: Broadcasts incoming packets via Intent
 * - BleAdvertiser.kt: Broadcasts serialized message (via broadcast() call)
 * - PacketSerializer.kt: Serialize/deserialize MeshPacket
 * - MessageRepository.kt: Room DAO wrapper, database persistence
 * - MessageEntity: Database model for messages
 * - NodeIdentity.kt: Get my own senderId for packet creation
 */
package com.example.ble

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

class ChatViewModel(
    app: Application,
    private val nodeIdentity: NodeIdentity,
    private val advertiser: BleAdvertiser,
    private val messageRepository: MessageRepository,
    private val contactId: String,
    private val contactSenderIdHex: String,
    private val contactPublicKeyB64: String = ""
) : AndroidViewModel(app) {

    // Derived once per session: first 16 bytes = AES key, next 12 bytes = nonce base.
    // Key derivation uses the two raw public keys in lexicographic order so both peers
    // compute identical HKDF output regardless of who is sender or receiver.
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

                viewModelScope.launch(Dispatchers.IO) {
                    val result = messageRepository.updateStatus(msgIdLong, MessageStatus.DELIVERED)
                    AppLogger.d("ChatVM", "ACK updateStatus msgId=$msgIdLong updated=$result")
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

                // Decrypt if session key is available; otherwise fall back to plaintext
                val text = sessionCrypto?.let { (aesKey, nonceBase) ->
                    try {
                        val aad = CryptoManager.buildAad(packet)
                        CryptoManager.decrypt(aesKey, nonceBase, msgIdLong, packet.payload, packet.authTag, aad)
                            ?.decodeToString()
                    } catch (_: Exception) { null }
                } ?: packet.payload.decodeToString()

                AppLogger.d("ChatVM", "CHAT storing: msgId=$msgIdLong text='${text.take(40)}' contactId=$contactId")

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

        val me         = nodeIdentity.getOrCreateIdentity().senderId
        val receiverId = contactSenderIdHex.hexToByteArray4()
        val msgIdBytes = Random.nextBytes(8)
        val msgIdLong  = msgIdBytes.toLongBE()

        // Build a skeleton packet for AAD computation, then encrypt payload
        val plainBytes = cleaned.encodeToByteArray()
        if (plainBytes.size > 209) return

        val skeleton = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = 6.toByte(),
            hopCount   = 0.toByte(),
            timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
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

        if (payload.size > 209) return

        val packet = MeshPacket(
            type       = PacketType.CHAT,
            msgId      = msgIdBytes,
            senderId   = me,
            receiverId = receiverId,
            ttl        = 6.toByte(),
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
                    contactId  = contactId,
                    text       = cleaned,
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

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray4(): ByteArray {
    val clean = trim().lowercase()
    require(clean.length == 8) { "Expected 8 hex chars, got ${clean.length}" }
    return ByteArray(4) { i ->
        val idx = i * 2
        clean.substring(idx, idx + 2).toInt(16).toByte()
    }
}
