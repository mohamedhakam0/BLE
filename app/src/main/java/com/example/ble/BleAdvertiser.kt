/**
 * Broadcasts mesh packets via BLE 5.0 Extended Advertising with Coded PHY.
 *
 * This file is responsible for taking serialized mesh packets and transmitting them
 * over the Bluetooth radio using extended advertising for maximum range (Coded PHY).
 * No GATT connections are established - this is connectionless broadcasting.
 *
 * Main Classes:
 * - BleAdvertiser: Wrapper class handling API version compatibility (API 24+)
 * - BleAdvertiserApi26: Implementation for API 26+ (extended advertising support)
 *
 * Key Functions:
 * - broadcast(packetBytes): Broadcasts a serialized MeshPacket via BLE advertising
 *   - Handles retry queue: retries up to MAX_RETRIES times with random jitter
 *   - Auto-stops after ADVERT_DURATION_MS to allow other packets through
 *   - Serializes stop→start to ensure only one active advertising set at a time
 *   - Posts to worker thread to avoid blocking main thread
 *
 * - cancelRetries(msgId): Stops retrying a specific packet when ACK is received
 *   - Checks if currently advertising packet matches the msgId
 *   - Cuts the current window short and moves to next in queue
 *   - Saves battery by not continuing to broadcast already-delivered packets
 *
 * - stopAll(): Stops all advertising and clears the queue
 *   - Called from ForegroundMeshService.onDestroy()
 *
 * PHY Configuration:
 * - Primary PHY: LE_CODED (long-range, ~4x the distance of 1M)
 * - Secondary PHY: LE_CODED (extended advertising requires secondary PHY)
 * - Advertising Interval: INTERVAL_LOW (~500ms)
 * - Transmit Power: TX_POWER_HIGH (maximum output)
 * - Extended Mode: setLegacyMode(false) - mandatory for Coded PHY
 *
 * Retry Strategy:
 * - CHAT packets: Up to 5 retries, ~5 seconds per attempt (total ~25 seconds max)
 * - ACK packets: Up to 3 retries (fire-and-forget confirmations)
 * - Jitter: Random 500-2000ms between retries to avoid collision with other devices
 *
 * Interactions:
 * - MeshPacket.kt: Input is serialized MeshPacket as ByteArray
 * - PacketSerializer.kt: Packets already serialized before reaching this class
 * - ForegroundMeshService.kt: Calls broadcast() for outgoing CHAT/HELLO/ACK packets
 * - ForegroundMeshService.kt: Calls cancelRetries() when ACK is received
 * - ChatViewModel.kt: Creates and sends user messages through ForegroundMeshService
 * - BleScanner.kt: Receiver side (this is the sender side)
 *
 * Thread Safety:
 * - All work done on dedicated HandlerThread (workerThread)
 * - Queue (ArrayDeque) is thread-safe via Handler serialization
 * - isBusy flag uses AtomicBoolean for thread-safe state
 *
 * Android Permissions Required:
 * - android.permission.BLUETOOTH_ADVERTISE (Android 12+)
 * - android.permission.BLUETOOTH_CONNECT (Android 12+, for adapter access)
 *
 * Edge Cases Handled:
 * - API < 26: Graceful fallback to null (extended advertising not available)
 * - Adapter null: Graceful error logging
 * - Missing permissions: SecurityException caught and logged
 * - Interrupted operation: Retries continue after interruption
 * - Multiple rapid broadcasts: Queued sequentially, never interleaved
 */
package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wrapper around the API 26+ extended advertiser implementation.
 *
 * NOTE: The app's minSdk is 24, but extended advertising is API 26+. We keep the API 26+ code
 * isolated so the project compiles cleanly while still using extended advertising on supported devices.
 */
class BleAdvertiser(bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "BleAdvertiser"
    }

    private val impl: BleAdvertiserApi26? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BleAdvertiserApi26(bluetoothAdapter) else null
    init {
        // Ensure no stale jobs survive service/process lifecycle edges.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            impl?.clearQueue()
        }
    }
    val isTdmGapActive: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) impl?.isTdmGapActive ?: false else false

    fun broadcast(packetBytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLogger.e(TAG, "Extended advertising requires API 26+")
            return
        }
        impl?.broadcast(packetBytes)
    }

    fun enqueue(packet: MeshPacket, isRelay: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLogger.e(TAG, "Extended advertising requires API 26+")
            return
        }
        impl?.enqueue(packet, isRelay)
    }

    /** Cancels pending retry attempts for the given msgId (8 bytes). */
    fun cancelRetries(msgId: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.cancelRetries(msgId)
    }

    fun preemptHello(msgId: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.preemptHello(msgId)
    }

    fun stopAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.stopAll()
    }

    fun enqueueUrgent(packetBytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLogger.e(TAG, "Extended advertising requires API 26+")
            return
        }
        impl?.enqueueUrgent(packetBytes)
    }

    fun clearQueue() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.clearQueue()
    }

    fun wasLocallyOriginated(msgIdHex: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) impl?.wasLocallyOriginated(msgIdHex) ?: false else false
}

// Everything below here is strictly API 26+.
@SuppressLint("MissingPermission")
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private class BleAdvertiserApi26(bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG          = "BleAdvertiser"
        private const val DIAG_TAG     = "BLE"
        private const val MAX_PAYLOAD  = 250

        private const val STOP_TIMEOUT_MS    = 600L     // must be <= ADVERT_DURATION_MS to avoid stalling autoStopThread

        private const val MAX_RETRIES        = 2   // was 3
        // 3 retries × 600ms = 1.8s max ACK air time — was reduced to 2 when
        // ADVERT_DURATION_MS was 5s (15s total). At 600ms, 3 retries is fine.
        private const val ACK_MAX_RETRIES    = 2  // was 2, ACKs are tiny and always caught first try

        private const val ADVERT_DURATION_MS = 600L  // reduce back from 800ms, data shows delivery within 400ms
        private const val RETRY_JITTER_MS    = 100L     // was 200ms — reduced, jitter less important with TDM

        private const val SCAN_GAP_MS        = 900L     // was 0L — dedicated scan window between attempts

        private val SERVICE_UUID: java.util.UUID = BleConstants.MESH_SERVICE_UUID
    }

    @Volatile var isTdmGapActive: Boolean = false
        private set

    private data class QueuedPacket(
        val packet: MeshPacket,
        val packetBytes: ByteArray,
        val msgIdPreview: String,
        val tier: QueueTier,
        val isRelay: Boolean,
        val attempt: Int,
        val maxAttempts: Int,
        val isAck: Boolean           // true → ACK packet, queued at head for priority
    )

    private enum class QueueTier { URGENT, NORMAL, BACKGROUND }

    private val bleAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser? =
        bluetoothAdapter?.bluetoothLeAdvertiser

    @Volatile private var activeSet: android.bluetooth.le.AdvertisingSet? = null
    @Volatile private var activeCallback: android.bluetooth.le.AdvertisingSetCallback? = null
    @Volatile private var stopDeferred: CompletableDeferred<Unit>? = null

    private val advertiserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workerThread   = HandlerThread("BleAdvertiser").also { it.start() }
    private val workerHandler  = android.os.Handler(workerThread.looper)

    // autoStopHandler runs on its OWN dedicated thread, NOT on workerThread.looper.
    //
    // Why this matters: both handlers previously shared workerThread.looper, so
    // calling autoStopHandler.removeCallbacksAndMessages(null) inside cancelRetries()
    // also removed any workerHandler.post{} runnables that had just been enqueued on
    // the same looper queue — including the preemption runnable itself. The preempt
    // check would then never run, leaving isAdvertising=true permanently, and every
    // subsequent drainQueue() call would return immediately doing nothing.
    //
    // With a separate looper: removeCallbacksAndMessages(null) on autoStopHandler
    // only cancels the auto-stop timer. The workerHandler queue is untouched.
    private val autoStopThread  = HandlerThread("BleAdvertiser-timer").also { it.start() }
    private val autoStopHandler = android.os.Handler(autoStopThread.looper)

    // Retry cancellation: tracks msgIds that have been ACKed.
    private val canceledMsgIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // Track locally originated msgIds to prevent them from being relayed back.
    // Uses LinkedHashMap to auto-remove oldest entries when cap is exceeded.
    private val originatedMsgIds: MutableMap<String, Boolean> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean = size > 500
        }
    )

    // ── Tiered queues + state (ALL accessed only on workerHandler) ───────────
    private val urgentQueue: ArrayDeque<QueuedPacket> = ArrayDeque()
    private val normalQueue: ArrayDeque<QueuedPacket> = ArrayDeque()
    private val backgroundQueue: ArrayDeque<QueuedPacket> = ArrayDeque()
    private var currentTier: QueueTier? = null
    private var isAdvertising: Boolean = false
    private var currentJob: QueuedPacket? = null

    /** Worker-thread hook to complete the current attempt immediately on preemption. */
    private var currentAttemptStop: (() -> Unit)? = null

    init {
        if (bleAdvertiser == null) {
            AppLogger.w(TAG, "BluetoothLeAdvertiser not available on this device")
        }
    }

    fun cancelRetries(msgId: ByteArray) {
        if (msgId.size != 8) return
        val msgIdHex = msgId.toHex()
        // Prune canceledMsgIds if it grows large (defensive cap for long sessions).
        if (canceledMsgIds.size > 1000) canceledMsgIds.clear()
        canceledMsgIds.add(msgIdHex)
        AppLogger.d(DIAG_TAG, "Advertiser.cancelRetries(): msgId=$msgIdHex")

        // Step 1: cancel the auto-stop timer from the calling thread.
        // autoStopHandler now has its own dedicated looper (autoStopThread),
        // so this removeCallbacksAndMessages(null) ONLY removes the timer
        // runnable — it cannot touch anything on workerHandler's queue.
        autoStopHandler.removeCallbacksAndMessages(null)

        // Step 2: preempt on the worker thread.
        workerHandler.post {
            val removed = removeQueuedByMsgId(msgIdHex)
            if (removed > 0) {
                AppLogger.d(DIAG_TAG, "Advertiser.preempt(): removed $removed queued copies of msgId=$msgIdHex")
            }

            val cj = currentJob
            if (isAdvertising && cj != null && cj.msgIdPreview == msgIdHex) {
                // Active set is ours — stop it and advance the queue immediately.
                AppLogger.d(DIAG_TAG, "Advertiser.preempt(): stopping active set early msgId=$msgIdHex")
                val stop = currentAttemptStop
                currentAttemptStop = null
                advertiserScope.launch {
                    stopActiveSetAsync()
                    workerHandler.post {
                        stop?.invoke() // queue advances with 0 delay, skips via canceledMsgIds
                    }
                }
                return@post
            }
            // Active set has already moved on — just drain so next job starts.
            AppLogger.d(DIAG_TAG, "Advertiser.preempt(): msgId=$msgIdHex not active, draining queue")
            drainQueue()
        }
    }

    fun preemptHello(msgId: ByteArray) {
        if (msgId.size != 8) return
        val msgIdHex = msgId.toHex()
        workerHandler.post {
            val removed = backgroundQueue.removeAll { it.msgIdPreview == msgIdHex && it.packet.type == PacketType.HELLO }
            if (removed) {
                AppLogger.d(DIAG_TAG, "Advertiser.preempt(): removed queued HELLO msgId=$msgIdHex from background")
            }

            val active = currentJob
            if (isAdvertising && active != null && active.msgIdPreview == msgIdHex && active.packet.type == PacketType.HELLO) {
                val stop = currentAttemptStop
                currentAttemptStop = null
                advertiserScope.launch {
                    autoStopHandler.removeCallbacksAndMessages(null)
                    stopActiveSetAsync()
                    workerHandler.post {
                        AppLogger.d(DIAG_TAG, "Advertiser.preempt(): cancelled active HELLO msgId=$msgIdHex")
                        stop?.invoke()
                    }
                }
            }
        }
    }

    fun wasLocallyOriginated(msgIdHex: String): Boolean = originatedMsgIds.containsKey(msgIdHex)

    fun broadcast(packetBytes: ByteArray) {
        val packet = PacketSerializer.deserialize(packetBytes)
        if (packet == null) {
            AppLogger.w(DIAG_TAG, "Advertiser.broadcast(): dropped non-mesh payload len=${packetBytes.size}")
            return
        }
        enqueue(packet, isRelay = false)
    }

    fun enqueueUrgent(packetBytes: ByteArray) {
        val packet = PacketSerializer.deserialize(packetBytes)
        if (packet == null) {
            AppLogger.w(DIAG_TAG, "Advertiser.enqueueUrgent(): dropped non-mesh payload len=${packetBytes.size}")
            return
        }
        enqueueInternal(packet, isRelay = false, forceUrgent = true, forceSingleAttempt = true)
    }

    fun enqueue(packet: MeshPacket, isRelay: Boolean) {
        enqueueInternal(packet, isRelay = isRelay, forceUrgent = false, forceSingleAttempt = false)
    }

    fun clearQueue() {
        autoStopHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            val cleared = urgentQueue.size + normalQueue.size + backgroundQueue.size
            urgentQueue.clear()
            normalQueue.clear()
            backgroundQueue.clear()
            canceledMsgIds.clear()
            isAdvertising = false
            currentJob = null
            currentTier = null
            currentAttemptStop = null
            advertiserScope.launch {
                stopActiveSetAsync()
                workerHandler.post {
                    Log.i("BLE", "Advertiser: queue flushed on init ($cleared items cleared)")
                }
            }
        }
    }

    private fun enqueueInternal(packet: MeshPacket, isRelay: Boolean, forceUrgent: Boolean, forceSingleAttempt: Boolean) {
        if (bleAdvertiser == null) return

        val packetBytes = PacketSerializer.serialize(packet)
        if (packetBytes.size > MAX_PAYLOAD) {
            AppLogger.e(TAG, "Packet too large: ${packetBytes.size} bytes (max $MAX_PAYLOAD)")
            return
        }

        val msgIdPreview = packet.msgId.toHex()
        val maxAttempts = when {
            forceSingleAttempt -> 1
            packet.type == PacketType.ACK -> ACK_MAX_RETRIES
            else -> MAX_RETRIES
        }

        val tier = when {
            forceUrgent -> QueueTier.URGENT
            packet.type == PacketType.LEAVE -> QueueTier.URGENT
            packet.type == PacketType.ACK && !isRelay -> QueueTier.URGENT
            packet.type == PacketType.CHAT && !isRelay -> QueueTier.NORMAL
            else -> QueueTier.BACKGROUND
        }

        val job = QueuedPacket(
            packet = packet,
            packetBytes = packetBytes,
            msgIdPreview = msgIdPreview,
            tier = tier,
            isRelay = isRelay,
            attempt = 1,
            maxAttempts = maxAttempts,
            isAck = packet.type == PacketType.ACK
        )

        workerHandler.post {
            // Track locally originated packets (non-relay) to prevent self-relay
            if (!isRelay) {
                originatedMsgIds[msgIdPreview] = true
            }

            if (canceledMsgIds.contains(msgIdPreview)) {
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): msgId=$msgIdPreview already delivered — skipped")
                return@post
            }

            if (containsQueuedMsgId(msgIdPreview)) {
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): msgId=$msgIdPreview already in queue — skipped")
                return@post
            }

            when (tier) {
                QueueTier.URGENT -> {
                    if (packet.type == PacketType.LEAVE || forceUrgent) urgentQueue.addFirst(job)
                    else urgentQueue.addLast(job)
                    Log.i("BLE", "Advertiser.enqueue(): URGENT(${packet.type}) msgId=$msgIdPreview")
                }

                QueueTier.NORMAL -> {
                    normalQueue.addLast(job)
                    Log.i("BLE", "Advertiser.enqueue(): NORMAL(${packet.type}) msgId=$msgIdPreview")
                }

                QueueTier.BACKGROUND -> {
                    if (backgroundQueue.size >= 8) {
                        val dropped = backgroundQueue.removeFirst()
                        Log.w("BLE", "Advertiser: background queue cap — dropped msgId=${dropped.msgIdPreview} type=${dropped.packet.type}")
                    }
                    backgroundQueue.addLast(job)
                    Log.d("BLE", "Advertiser.enqueue(): BACKGROUND msgId=$msgIdPreview type=${packet.type} relay=$isRelay queueSize=${backgroundQueue.size}")
                }
            }
            drainQueue()
        }
    }

    fun stopAll() {
        autoStopHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            urgentQueue.clear()
            normalQueue.clear()
            backgroundQueue.clear()
            canceledMsgIds.clear()
            isAdvertising      = false
            currentJob         = null
            currentTier        = null
            currentAttemptStop = null
            advertiserScope.launch {
                stopActiveSetAsync()
                workerHandler.post {
                    AppLogger.d(DIAG_TAG, "BLE: Advertiser all sets cleared")
                }
            }
        }
    }

    // ── Queue drain (worker thread only) ─────────────────────────────────────

    private fun drainQueue() {
        if (isAdvertising) return

        val job = takeNextJob() ?: return
        isAdvertising  = true
        currentJob     = job
        currentTier    = job.tier

        // Build the shared cleanup closure.  Both the normal auto-stop path and
        // the preemption path in cancelRetries() invoke this exact same lambda,
        // guaranteeing identical state transitions regardless of which path runs.
        currentAttemptStop = {
            // Always called on workerHandler.
            isAdvertising      = false
            currentJob         = null
            currentAttemptStop = null

            // Re-enqueue next attempt. ACK retries also go to the front so they
            // stay ahead of any new original messages added during the window.
            // Bug fix: skip re-enqueue if already canceled OR if a copy is already
            // in the queue (prevents the accumulation that drove 2→3→6 ACKs/event).
            if (job.attempt < job.maxAttempts
                && !canceledMsgIds.contains(job.msgIdPreview)
                && !containsQueuedMsgId(job.msgIdPreview)
            ) {
                val next = job.copy(attempt = job.attempt + 1)
                enqueueRetry(next)
            }

            // ACKed jobs advance immediately; others wait a small random jitter
            // to reduce collisions between nearby senders.
            // Bug fix (scanner starvation): when the queue has more jobs waiting,
            // add an extra SCAN_GAP_MS on top of the jitter so the BLE scanner
            // gets a guaranteed window to receive ACKs before the next burst.
            val isCanceled = canceledMsgIds.contains(job.msgIdPreview)
            if (isCanceled) {
                isTdmGapActive = false
                workerHandler.post { drainQueue() }
            } else {
                val jitter = (0L..RETRY_JITTER_MS).random()
                val gap = if (hasPendingJobs()) SCAN_GAP_MS else SCAN_GAP_MS / 2
                isTdmGapActive = true
                AppLogger.d("BLE", "Advertiser: TDM scan window ${gap}ms after attempt ${job.attempt}/${job.maxAttempts} msgId=${job.msgIdPreview}")
                workerHandler.postDelayed({
                    isTdmGapActive = false
                    // Re-check cancellation at gap expiry — ACK may have arrived
                    // during the gap window. If canceled, skip re-enqueue entirely.
                    if (canceledMsgIds.contains(job.msgIdPreview)) {
                        AppLogger.d("BLE", "Advertiser: TDM gap expired but msgId=${job.msgIdPreview} already ACKed — skipping attempt ${job.attempt + 1}")
                        drainQueue()
                        return@postDelayed
                    }
                    drainQueue()
                }, jitter + gap)
            }
        }

        broadcastInternalOnce(job) {
            // Normal (auto-stop) path: delegate to the shared cleanup closure.
            currentAttemptStop?.invoke()
        }
    }

    // ── One attempt: start advertising, auto-stop after ADVERT_DURATION_MS ──

    private fun broadcastInternalOnce(job: QueuedPacket, onStopped: () -> Unit) {
        // Cancel any stale auto-stop timer from a previous attempt.
        autoStopHandler.removeCallbacksAndMessages(null)
        // Stop any currently active set before starting the next one.
        advertiserScope.launch {
            stopActiveSetAsync()
            workerHandler.post {
                startAdvertisingSet(job, onStopped)
            }
        }
    }

    private fun startAdvertisingSet(job: QueuedPacket, onStopped: () -> Unit) {
        val params = android.bluetooth.le.AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .build()

        val serviceParcelUuid = android.os.ParcelUuid(SERVICE_UUID)
        val data = android.bluetooth.le.AdvertiseData.Builder()
            .addServiceUuid(serviceParcelUuid)
            .addServiceData(serviceParcelUuid, job.packetBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val callback = object : android.bluetooth.le.AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: android.bluetooth.le.AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    activeSet = advertisingSet
                    stopDeferred = CompletableDeferred()
                    AppLogger.d(DIAG_TAG,
                        "Advertiser.onAdvertisingSetStarted(): SUCCESS " +
                                "txPower=$txPower msgId=${job.msgIdPreview} " +
                                "attempt=${job.attempt}/${job.maxAttempts}")

                    autoStopHandler.postDelayed({
                        advertiserScope.launch {
                            stopActiveSetAsync()
                            AppLogger.d(DIAG_TAG,
                                "Advertiser: auto-stopped after ${ADVERT_DURATION_MS}ms " +
                                        "msgId=${job.msgIdPreview} attempt=${job.attempt}/${job.maxAttempts}")
                            workerHandler.post { onStopped() }
                        }
                    }, ADVERT_DURATION_MS)

                } else {
                    AppLogger.e(DIAG_TAG,
                        "Advertiser.onAdvertisingSetStarted(): FAILED " +
                                "status=$status txPower=$txPower msgId=${job.msgIdPreview} " +
                                "attempt=${job.attempt}/${job.maxAttempts}")
                    activeSet      = null
                    activeCallback = null
                    // Failed to start — still need to advance the queue.
                    // Post to workerHandler because the BLE callback fires on an
                    // arbitrary thread.
                    workerHandler.post { onStopped() }
                }
            }

            override fun onAdvertisingSetStopped(
                advertisingSet: android.bluetooth.le.AdvertisingSet?
            ) {
                AppLogger.d(DIAG_TAG, "BLE: Advertiser stopped previous set")
                stopDeferred?.complete(Unit)
                activeSet = null
            }
        }

        activeCallback = callback

        try {
            bleAdvertiser?.startAdvertisingSet(params, data, null, null, null, callback)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error starting advertising: ${e.message}", e)
            activeSet      = null
            activeCallback = null
            workerHandler.post { onStopped() }
        }
    }

    private suspend fun stopActiveSetAsync() {
        val callbackToStop = activeCallback ?: return
        val deferred = stopDeferred
        if (deferred == null || deferred.isCompleted) {
            return
        }

        try {
            bleAdvertiser?.stopAdvertisingSet(callbackToStop)
            val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                deferred.await()
                true
            } ?: false
            if (!stopped) {
                AppLogger.w(DIAG_TAG, "Advertiser.stopActiveSetAsync(): timeout waiting for stop")
            }
        } catch (e: Exception) {
            AppLogger.w(DIAG_TAG, "Advertiser.stopActiveSetAsync(): ${e.message}")
        } finally {
            if (stopDeferred === deferred) {
                stopDeferred = null
            }
            activeSet      = null
            activeCallback = null
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hasPendingJobs(): Boolean =
        urgentQueue.isNotEmpty() || normalQueue.isNotEmpty() || backgroundQueue.isNotEmpty()

    private fun containsQueuedMsgId(msgIdHex: String): Boolean =
        urgentQueue.any { it.msgIdPreview == msgIdHex } ||
            normalQueue.any { it.msgIdPreview == msgIdHex } ||
            backgroundQueue.any { it.msgIdPreview == msgIdHex }

    private fun removeQueuedByMsgId(msgIdHex: String): Int {
        var removed = 0
        removed += removeByMsgId(urgentQueue, msgIdHex)
        removed += removeByMsgId(normalQueue, msgIdHex)
        removed += removeByMsgId(backgroundQueue, msgIdHex)
        return removed
    }

    private fun removeByMsgId(queue: ArrayDeque<QueuedPacket>, msgIdHex: String): Int {
        var removed = 0
        val it = queue.iterator()
        while (it.hasNext()) {
            if (it.next().msgIdPreview == msgIdHex) {
                it.remove()
                removed += 1
            }
        }
        return removed
    }

    private fun takeNextJob(): QueuedPacket? {
        while (urgentQueue.isNotEmpty()) {
            val next = urgentQueue.removeFirst()
            if (!canceledMsgIds.contains(next.msgIdPreview)) return next
        }
        while (normalQueue.isNotEmpty()) {
            val next = normalQueue.removeFirst()
            if (!canceledMsgIds.contains(next.msgIdPreview)) return next
        }
        while (backgroundQueue.isNotEmpty()) {
            val next = backgroundQueue.removeFirst()
            if (!canceledMsgIds.contains(next.msgIdPreview)) return next
        }
        return null
    }

    private fun enqueueRetry(job: QueuedPacket) {
        when (job.tier) {
            QueueTier.URGENT -> urgentQueue.addFirst(job)
            QueueTier.NORMAL -> normalQueue.addLast(job)
            QueueTier.BACKGROUND -> {
                if (backgroundQueue.size >= 8) {
                    val dropped = backgroundQueue.removeFirst()
                    Log.w("BLE", "Advertiser: background queue cap — dropped msgId=${dropped.msgIdPreview} type=${dropped.packet.type}")
                }
                backgroundQueue.addLast(job)
            }
        }
    }
}

private class HandlerThread(name: String) : Thread(name) {
    val looper: android.os.Looper get() = _looper!!
    private var _looper: android.os.Looper? = null
    private val ready = java.util.concurrent.CountDownLatch(1)

    override fun run() {
        android.os.Looper.prepare()
        _looper = android.os.Looper.myLooper()
        ready.countDown()
        android.os.Looper.loop()
    }

    override fun start() {
        super.start()
        ready.await()
    }
}