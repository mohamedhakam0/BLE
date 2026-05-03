package com.example.ble.debug

import android.content.Context
import android.os.Environment
import com.example.ble.AppLogger
import com.example.ble.BleAdvertiser
import com.example.ble.MeshPacket
import com.example.ble.MessageDirection
import com.example.ble.MessageEntity
import com.example.ble.MessageRepository
import com.example.ble.MessageStatus
import com.example.ble.NodeIdentity
import com.example.ble.PacketSerializer
import com.example.ble.PacketType
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS
)
annotation class DebugOnly

@DebugOnly
data class StressTestResults(
    val totalSent: Int,
    val totalAcked: Int,
    val attempt1Delivered: Int,
    val meanLatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val mdr: Float,
    val durationMs: Long,
    val rssiReadings: List<Int>,
    val rssiMean: Float,
    val rssiMin: Int,
    val rssiMax: Int,
    val packetLossRate: Float
)

@DebugOnly
object StressTestManager {
    private const val TAG = "STRESS"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private val sessionCounter = AtomicLong(0)
    @Volatile private var activeSessionId = 0L
    @Volatile private var finalizedSessionId = -1L

    private val lock = Any()
    private val sendTimesMs = ConcurrentHashMap<String, Long>()
    private val latencyReadingsMs = mutableListOf<Long>()
    private val rssiReadings = mutableListOf<Int>()
    private var currentStartTimeMs: Long = 0L
    private var totalSentCount: Int = 0
    private var totalAckedCount: Int = 0

    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _results = MutableStateFlow<StressTestResults?>(null)
    val results: StateFlow<StressTestResults?> = _results

    private val _currentLabel = MutableStateFlow("stress")
    val currentLabel: StateFlow<String> = _currentLabel

    init {
        AppLogger.d(TAG, "StressTestManager initialized - debug build confirmed")
    }

    fun start(
        nodeIdentity: NodeIdentity,
        advertiser: BleAdvertiser,
        messageRepository: MessageRepository,
        contactId: String,
        contactSenderIdHex: String,
        messageCount: Int = 500,
        intervalMs: Long = 2000L,
        testLabel: String = "stress",
        context: Context
    ) {
        runJob?.cancel()

        val safeCount = messageCount.coerceAtLeast(0)
        val safeIntervalMs = intervalMs.coerceAtLeast(0L)
        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId
        finalizedSessionId = -1L

        synchronized(lock) {
            sendTimesMs.clear()
            latencyReadingsMs.clear()
            rssiReadings.clear()
            totalSentCount = 0
            totalAckedCount = 0
            currentStartTimeMs = System.currentTimeMillis()
        }

        _progress.value = 0
        _results.value = null
        _currentLabel.value = testLabel
        _isRunning.value = true

        openLogFile(context, testLabel)

        runJob = scope.launch {
            try {
                val me = nodeIdentity.getOrCreateIdentity().senderId
                val receiverId = contactSenderIdHex.hexToByteArray4()

                for (i in 1..safeCount) {
                    if (!isActive) break

                    val text = "[$testLabel] #$i"
                    val payloadBytes = text.encodeToByteArray()
                    if (payloadBytes.size > 209) continue

                    val msgIdBytes = Random.nextBytes(8)
                    val msgIdHex = msgIdBytes.toHex()
                    val now = System.currentTimeMillis()

                    val packet = MeshPacket(
                        type = PacketType.CHAT,
                        msgId = msgIdBytes,
                        senderId = me,
                        receiverId = receiverId,
                        ttl = 6.toByte(),
                        hopCount = 0.toByte(),
                        timestamp = (now / 1000L).toInt(),
                        payloadLen = payloadBytes.size.toByte(),
                        authTag = Random.nextBytes(16),
                        payload = payloadBytes
                    )

                    sendTimesMs[msgIdHex] = now
                    advertiser.broadcast(PacketSerializer.serialize(packet))

                    messageRepository.upsert(
                        MessageEntity(
                            msgId = msgIdBytes.toLongBE(),
                            contactId = contactId,
                            text = text,
                            direction = MessageDirection.OUTGOING,
                            status = MessageStatus.SENT,
                            timestamp = now,
                            insertedAt = now
                        )
                    )

                    AppLogger.d(
                        TAG,
                        "STRESS_TX msgId=$msgIdHex seq=$i/$safeCount label=$testLabel ts=${System.currentTimeMillis()}"
                    )
                    writeLog("TX", i.toString(), msgIdHex, System.currentTimeMillis().toString(), "", testLabel, "")

                    synchronized(lock) { totalSentCount = i }
                    _progress.value = i

                    delay(safeIntervalMs)
                }

                awaitAckSettlingWindow()
            } catch (_: CancellationException) {
                // Partial results are emitted in finally.
            } finally {
                emitFinalResultsIfActive(sessionId)
            }
        }
    }

    fun onAckReceived(msgIdHex: String) {
        AppLogger.d(TAG, "STRESS_ACK_HOOK called msgId=$msgIdHex isRunning=${_isRunning.value}")
        if (!_isRunning.value) return

        val normalized = msgIdHex.trim().lowercase(Locale.US)
        val sendTimeMs = sendTimesMs.remove(normalized) ?: return
        val latencyMs = (System.currentTimeMillis() - sendTimeMs).coerceAtLeast(0L)

        synchronized(lock) {
            totalAckedCount += 1
            latencyReadingsMs += latencyMs
        }

        AppLogger.d(TAG, "STRESS_ACK msgId=$normalized latency=${latencyMs}ms label=${_currentLabel.value}")
        writeLog("ACK", "", normalized, System.currentTimeMillis().toString(), latencyMs.toString(), _currentLabel.value, "")
    }

    fun onRssiObserved(rssi: Int) {
        if (!_isRunning.value) return
        synchronized(lock) { rssiReadings += rssi }
        writeLog("RSSI", "", "", System.currentTimeMillis().toString(), "", _currentLabel.value, rssi.toString())
    }

    fun stop() {
        val sessionId = activeSessionId
        runJob?.cancel()
        emitFinalResultsIfActive(sessionId)
    }

    private suspend fun awaitAckSettlingWindow() {
        var previousAckCount = -1
        var stableTicks = 0

        repeat(60) {
            val ackCount = synchronized(lock) { totalAckedCount }
            if (ackCount == previousAckCount) stableTicks += 1 else stableTicks = 0
            previousAckCount = ackCount
            if (stableTicks >= 2) return
            delay(500)
        }
    }

    private fun emitFinalResultsIfActive(sessionId: Long) {
        if (activeSessionId != sessionId || finalizedSessionId == sessionId) return
        finalizedSessionId = sessionId

        val now = System.currentTimeMillis()
        val snapshot = synchronized(lock) {
            val sent = totalSentCount
            val acked = totalAckedCount.coerceAtMost(sent)
            val latencyMean = if (latencyReadingsMs.isEmpty()) 0L else latencyReadingsMs.average().toLong()
            val latencyMin = latencyReadingsMs.minOrNull() ?: 0L
            val latencyMax = latencyReadingsMs.maxOrNull() ?: 0L
            val rssiMean = if (rssiReadings.isEmpty()) 0f else rssiReadings.average().toFloat()
            val rssiMin = rssiReadings.minOrNull() ?: 0
            val rssiMax = rssiReadings.maxOrNull() ?: 0
            val mdr = if (sent == 0) 0f else acked.toFloat() / sent.toFloat()

            StressTestResults(
                totalSent = sent,
                totalAcked = acked,
                attempt1Delivered = 0,
                meanLatencyMs = latencyMean,
                minLatencyMs = latencyMin,
                maxLatencyMs = latencyMax,
                mdr = mdr,
                durationMs = (now - currentStartTimeMs).coerceAtLeast(0L),
                rssiReadings = rssiReadings.toList(),
                rssiMean = rssiMean,
                rssiMin = rssiMin,
                rssiMax = rssiMax,
                packetLossRate = 1f - mdr
            )
        }

        _results.value = snapshot
        _isRunning.value = false
        _progress.value = snapshot.totalSent
        runJob = null

        writeLog(
            "SUMMARY",
            snapshot.totalSent.toString(),
            "",
            System.currentTimeMillis().toString(),
            snapshot.meanLatencyMs.toString(),
            _currentLabel.value,
            snapshot.rssiMean.toString()
        )
        writeLog(
            "SUMMARY_DETAIL",
            snapshot.totalAcked.toString(),
            "",
            "",
            "",
            _currentLabel.value,
            "sent=${snapshot.totalSent} acked=${snapshot.totalAcked} mdr=${snapshot.mdr}"
        )
        closeLogFile()

        AppLogger.d(
            TAG,
            "STRESS_SUMMARY label=${_currentLabel.value} sent=${snapshot.totalSent} acked=${snapshot.totalAcked} mdr=${snapshot.mdr} meanLatency=${snapshot.meanLatencyMs}ms"
        )
    }

    private fun openLogFile(context: Context, label: String) {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = sdf.format(Date())
            val fileName = "stress_${label}_${timestamp}.csv"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            logFile = file
            logWriter = BufferedWriter(FileWriter(file, false))
            logWriter?.write("event,seq,msgId,timestampMs,latencyMs,label,rssi")
            logWriter?.newLine()
            logWriter?.flush()
            AppLogger.d(TAG, "Log file opened: ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to open log file: ${e.message}")
            logWriter = null
        }
    }

    private fun writeLog(vararg fields: String) {
        try {
            logWriter?.write(fields.joinToString(","))
            logWriter?.newLine()
            logWriter?.flush()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write log: ${e.message}")
        }
    }

    private fun closeLogFile() {
        try {
            logWriter?.flush()
            logWriter?.close()
            logWriter = null
            AppLogger.d(TAG, "Log file closed: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to close log file: ${e.message}")
        }
    }
}

private fun ByteArray.toLongBE(): Long {
    require(size == 8)
    var result = 0L
    for (b in this) {
        result = (result shl 8) or (b.toLong() and 0xFF)
    }
    return result
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray4(): ByteArray {
    val clean = trim().lowercase(Locale.US)
    require(clean.length == 8) { "Expected 8 hex chars, got ${clean.length}" }
    return ByteArray(4) { i ->
        val index = i * 2
        clean.substring(index, index + 2).toInt(16).toByte()
    }
}




