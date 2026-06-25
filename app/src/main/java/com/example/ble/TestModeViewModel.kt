package com.example.ble

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class TestUiState(
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val ackedCount: Int = 0,
    val liveMdr: Float = 0f,
    val meanLatencyMs: Long = 0L,
    val isRunning: Boolean = false,
    val isComplete: Boolean = false
)

class TestModeViewModel(
    app: Application,
    private val nodeIdentity: NodeIdentity,
    private val advertiser: BleAdvertiser,
    private val contactRepository: ContactRepository
) : AndroidViewModel(app) {

    companion object {
        private const val ACK_TIMEOUT_MS = 30_000L
    }

    private val _uiState = MutableStateFlow(TestUiState())
    val uiState: StateFlow<TestUiState> = _uiState.asStateFlow()

    private var activeConfig: TestConfig? = null
    private var logger: TestSessionLogger? = null
    private var sendJob: Job? = null

    @Volatile private var sessionCrypto: Pair<ByteArray, ByteArray>? = null

    private val sentTimesMs = ConcurrentHashMap<String, Long>()
    private val ackedMsgIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val seenRxMsgIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val latenciesMs = mutableListOf<Long>()
    private val lock = Any()
    private var sentCount = 0
    private var receivedCount = 0
    private var ackedCount = 0

    private var exportUri: Uri? = null

    private val myNodeId: ByteArray by lazy {
        nodeIdentity.getOrCreateIdentity().senderId
    }
    private val myNodeIdHex: String by lazy {
        myNodeId.joinToString("") { "%02x".format(it) }
    }

    init {
        // Subscribe to scan observer for the lifetime of this ViewModel.
        // All packet handling is gated on activeConfig so it is free when idle.
        viewModelScope.launch {
            TestScanObserver.packetScanned.collect { (packet, meta) ->
                handleScannedPacket(packet, meta)
            }
        }
    }

    // ── Scan-observer handler ─────────────────────────────────────────────────

    private fun handleScannedPacket(packet: MeshPacket, meta: ScanMeta) {
        val cfg = activeConfig ?: return
        when (packet.type) {
            PacketType.ACK -> {
                if (cfg.role != TestRole.SENDER) return
                if (!packet.receiverId.contentEquals(myNodeId)) return
                val msgIdBytes = packet.payload.takeIf { it.size >= 8 }
                    ?.copyOfRange(0, 8) ?: return
                val msgIdHex = msgIdBytes.joinToString("") { "%02x".format(it) }
                onAckScanned(msgIdHex, meta, packet, cfg)
            }
            PacketType.CHAT -> {
                if (cfg.role != TestRole.RECEIVER) return
                onChatScanned(packet, meta, cfg)
            }
            else -> Unit
        }
    }

    private fun onAckScanned(msgIdHex: String, meta: ScanMeta, packet: MeshPacket, cfg: TestConfig) {
        if (!ackedMsgIds.add(msgIdHex)) return
        val sentTs = sentTimesMs[msgIdHex] ?: return
        val now = System.currentTimeMillis()
        val ackLatency = (now - sentTs).coerceAtLeast(0L)
        val originSenderHex = packet.senderId.joinToString("") { "%02x".format(it) }

        logger?.log(TestEvent.AckReceived(
            msgId            = msgIdHex,
            ackTs            = now,
            ackRssiDbm       = meta.rssiDbm,
            immediateSenderId = meta.deviceAddress,
            originSenderId   = originSenderHex,
            ackWithinTimeout = ackLatency <= ACK_TIMEOUT_MS
        ))
        synchronized(lock) { ackedCount++; latenciesMs += ackLatency }
        refreshState()
        AppLogger.d("TestMode", "ACK msgId=$msgIdHex latency=${ackLatency}ms rssi=${meta.rssiDbm}")
    }

    private fun onChatScanned(packet: MeshPacket, meta: ScanMeta, cfg: TestConfig) {
        val senderHex = packet.senderId.joinToString("") { "%02x".format(it) }
        val target = cfg.targetPeerId?.trim()?.lowercase()
        if (target != null && senderHex != target) return

        val msgIdHex     = packet.msgId.joinToString("") { "%02x".format(it) }
        val now          = System.currentTimeMillis()
        val hopCount     = (PacketSerializer.DEFAULT_INITIAL_TTL.toInt() and 0xFF) -
                           (packet.ttl.toInt() and 0xFF)
        val packetSentTs = packet.timestamp.toLong() * 1000L

        if (!seenRxMsgIds.add(msgIdHex)) {
            logger?.log(TestEvent.DuplicateDropped(
                msgId             = msgIdHex,
                receivedTs        = now,
                packetSentTs      = packetSentTs,
                hopCount          = hopCount,
                rssiDbm           = meta.rssiDbm,
                immediateSenderId = meta.deviceAddress,
                originSenderId    = senderHex
            ))
            AppLogger.d("TestMode", "DUP msgId=$msgIdHex")
            return
        }

        synchronized(lock) { receivedCount++ }

        val crypto = sessionCrypto
        if (crypto != null) {
            val ok = try {
                val (aesKey, nonceBase) = crypto
                val msgIdLong = packet.msgId.testToLongBE()
                val aad = CryptoManager.buildAad(packet)
                CryptoManager.decrypt(aesKey, nonceBase, msgIdLong, packet.payload, packet.authTag, aad)
                true
            } catch (_: Exception) { false }

            if (!ok) {
                logger?.log(TestEvent.DecryptionFailed(
                    msgId             = msgIdHex,
                    receivedTs        = now,
                    packetSentTs      = packetSentTs,
                    hopCount          = hopCount,
                    rssiDbm           = meta.rssiDbm,
                    immediateSenderId = meta.deviceAddress,
                    originSenderId    = senderHex
                ))
                refreshState()
                AppLogger.d("TestMode", "DECRYPT_FAIL msgId=$msgIdHex")
                return
            }
        }

        logger?.log(TestEvent.Received(
            msgId             = msgIdHex,
            receivedTs        = now,
            packetSentTs      = packetSentTs,
            hopCount          = hopCount,
            rssiDbm           = meta.rssiDbm,
            immediateSenderId = meta.deviceAddress,
            originSenderId    = senderHex,
            decryptionSuccess = if (crypto != null) true else null,
            payloadVerified   = if (crypto != null) true else null
        ))
        refreshState()
        AppLogger.d("TestMode", "RX msgId=$msgIdHex hop=$hopCount rssi=${meta.rssiDbm}")
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    fun startSession(config: TestConfig) {
        stopSession()
        activeConfig = config
        sessionCrypto = null
        exportUri = null

        synchronized(lock) {
            sentCount = 0; receivedCount = 0; ackedCount = 0
            latenciesMs.clear()
        }
        sentTimesMs.clear()
        ackedMsgIds.clear()
        seenRxMsgIds.clear()

        logger = TestSessionLogger(getApplication(), config, myNodeIdHex)
        _uiState.value = TestUiState(isRunning = true)

        sendJob = viewModelScope.launch(Dispatchers.IO) {
            // Derive session key for both SENDER (encrypt) and RECEIVER (decrypt).
            if (config.targetPeerId != null) {
                sessionCrypto = deriveSessionCrypto(config.targetPeerId)
            }
            if (config.role == TestRole.SENDER) {
                runSendLoop(config)
            }
            // RECEIVER: crypto derivation done; handleScannedPacket handles the rest.
        }
    }

    private suspend fun runSendLoop(config: TestConfig) {
        try {
            val identity = nodeIdentity.getOrCreateIdentity()
            val me = identity.senderId
            val receiverId = config.targetPeerId
                ?.takeIf { it.length == 8 }
                ?.let { testHexToByteArray4(it) }
                ?: byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

            val aesKey    = sessionCrypto?.first
            val nonceBase = sessionCrypto?.second

            val total = config.messageCount
            var msgCounter = 0L

            for (i in 1..total) {
                if (!coroutineContext.isActive) break
                msgCounter++

                val text = "[TEST ${config.experimentId} msg $i/$total]"
                val plainBytes = text.encodeToByteArray()
                if (plainBytes.size > 208) continue

                val msgIdBytes = Random.nextBytes(8)
                val msgIdLong  = msgIdBytes.testToLongBE()
                val msgIdHex   = msgIdBytes.joinToString("") { "%02x".format(it) }
                val now = System.currentTimeMillis()

                val loraFlags = if (config.loraEligible) MeshPacket.FLAG_LORA_ELIGIBLE else 0x00.toByte()
                val skeleton = MeshPacket(
                    type       = PacketType.CHAT,
                    msgId      = msgIdBytes,
                    senderId   = me,
                    receiverId = receiverId,
                    ttl        = PacketSerializer.DEFAULT_INITIAL_TTL,
                    hopCount   = 0.toByte(),
                    timestamp  = (now / 1000L).toInt(),
                    flags      = loraFlags,
                    payloadLen = plainBytes.size.toByte(),
                    authTag    = ByteArray(16),
                    payload    = plainBytes
                )

                val (payload, authTag) = if (aesKey != null && nonceBase != null) {
                    try {
                        val aad    = CryptoManager.buildAad(skeleton)
                        val result = CryptoManager.encrypt(aesKey, nonceBase, msgIdLong, plainBytes, aad)
                        Pair(result.ciphertext, result.authTag)
                    } catch (_: Exception) { Pair(plainBytes, Random.nextBytes(16)) }
                } else Pair(plainBytes, Random.nextBytes(16))

                if (payload.size > 208) continue

                val packet = skeleton.copy(
                    payloadLen = payload.size.toByte(),
                    authTag    = authTag,
                    payload    = payload
                )

                sentTimesMs[msgIdHex] = now
                logger?.log(TestEvent.Sent(msgIdHex, msgCounter, now))
                synchronized(lock) { sentCount++ }
                refreshState()

                advertiser.enqueue(packet)
                AppLogger.d("TestMode", "TX seq=$i/$total msgId=$msgIdHex")

                if (config.intervalMs > 0L) delay(config.intervalMs)
            }

            awaitAckSettling()
            emitAckTimeouts(config)
        } catch (_: CancellationException) {
            // normal stop
        } finally {
            if (activeConfig != null) {
                _uiState.value = _uiState.value.copy(isRunning = false, isComplete = true)
            }
            captureExportUri()
            logger?.close()
            logger = null
        }
    }

    private suspend fun awaitAckSettling() {
        var prev = -1; var stable = 0
        repeat(60) {
            val cur = synchronized(lock) { ackedCount }
            if (cur == prev) stable++ else stable = 0
            prev = cur
            if (stable >= 3) return
            delay(500L)
        }
    }

    private fun emitAckTimeouts(config: TestConfig) {
        val now = System.currentTimeMillis()
        for ((msgId, _) in sentTimesMs) {
            if (!ackedMsgIds.contains(msgId)) {
                logger?.log(TestEvent.AckTimeout(msgId = msgId, timeoutTs = now))
            }
        }
    }

    private suspend fun deriveSessionCrypto(targetPeerId: String): Pair<ByteArray, ByteArray>? {
        val contact = contactRepository.getContact(targetPeerId.trim().lowercase()) ?: return null
        return try {
            val identity = nodeIdentity.getOrCreateIdentity()
            val peerPub  = Base64.decode(contact.publicKey, Base64.NO_WRAP)
            val shared   = CryptoManager.computeSharedSecret(identity.privateKey, peerPub)
            val myKey4   = identity.publicKey.copyOfRange(0, 4)
            val peerKey4 = peerPub.copyOfRange(0, 4)
            val (lo, hi) = if (myKey4.testUnsignedLex4(peerKey4) <= 0)
                Pair(myKey4, peerKey4) else Pair(peerKey4, myKey4)
            val full = CryptoManager.deriveSessionKey(shared, lo, hi)
            Pair(full.copyOfRange(0, 16), full.copyOfRange(16, 28))
        } catch (_: Exception) { null }
    }

    fun stopSession() {
        sendJob?.cancel()
        sendJob = null
        captureExportUri()
        logger?.close()
        logger = null
        if (activeConfig != null) {
            _uiState.value = _uiState.value.copy(isRunning = false, isComplete = true)
        }
        activeConfig = null
        sessionCrypto = null
    }

    private fun captureExportUri() {
        if (exportUri == null) exportUri = logger?.getShareUri()
    }

    fun buildExportIntent(): Intent? {
        val uri = exportUri ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun refreshState() {
        val sent: Int; val received: Int; val acked: Int; val lats: List<Long>
        synchronized(lock) {
            sent     = sentCount
            received = receivedCount
            acked    = ackedCount
            lats     = latenciesMs.toList()
        }
        val cfg = activeConfig
        val mdr = when {
            cfg?.role == TestRole.SENDER   && sent > 0              -> acked.toFloat() / sent.toFloat()
            cfg?.role == TestRole.RECEIVER && cfg.messageCount > 0  -> received.toFloat() / cfg.messageCount.toFloat()
            else -> 0f
        }
        val mean = if (lats.isNotEmpty()) lats.average().toLong() else 0L
        _uiState.value = _uiState.value.copy(
            sentCount     = sent,
            receivedCount = received,
            ackedCount    = acked,
            liveMdr       = mdr,
            meanLatencyMs = mean
        )
    }

    override fun onCleared() {
        logger?.close()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val nodeIdentity: NodeIdentity,
        private val advertiser: BleAdvertiser,
        private val contactRepository: ContactRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TestModeViewModel(application, nodeIdentity, advertiser, contactRepository) as T
    }
}

// ── Private extension helpers ─────────────────────────────────────────────────

private fun ByteArray.testToLongBE(): Long {
    require(size == 8)
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

private fun testHexToByteArray4(hex: String): ByteArray {
    val clean = hex.trim().lowercase()
    return ByteArray(4) { i ->
        val idx = i * 2
        clean.substring(idx, idx + 2).toInt(16).toByte()
    }
}

private fun ByteArray.testUnsignedLex4(other: ByteArray): Int {
    for (i in 0 until 4) {
        val diff = (this[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
        if (diff != 0) return diff
    }
    return 0
}
