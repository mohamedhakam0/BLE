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
import com.example.ble.AppLogger

/**
 * Wrapper around the API 26+ extended advertiser implementation.
 *
 * NOTE: The app's minSdk is 24, but extended advertising is API 26+. We keep the API 26+ code
 * isolated so the project compiles cleanly while still using extended advertising on supported devices.
 */
class BleAdvertiser(private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "BleAdvertiser"
    }

    private val impl: BleAdvertiserApi26? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BleAdvertiserApi26(bluetoothAdapter) else null

    fun broadcast(packetBytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AppLogger.e(TAG, "Extended advertising requires API 26+")
            return
        }
        impl?.broadcast(packetBytes)
    }

    /** Cancels pending retry attempts for the given msgId (8 bytes). */
    fun cancelRetries(msgId: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.cancelRetries(msgId)
    }

    fun stopAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        impl?.stopAll()
    }
}

// Everything below here is strictly API 26+.
@SuppressLint("MissingPermission")
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private class BleAdvertiserApi26(private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG          = "BleAdvertiser"
        private const val DIAG_TAG     = "BLE"
        private const val MAX_PAYLOAD  = 250

        private const val STOP_TIMEOUT_MS    = 600L     // must be <= ADVERT_DURATION_MS to avoid stalling autoStopThread

        private const val MAX_RETRIES        = 5
        // 3 retries × 600ms = 1.8s max ACK air time — was reduced to 2 when
        // ADVERT_DURATION_MS was 5s (15s total). At 600ms, 3 retries is fine.
        private const val ACK_MAX_RETRIES    = 3

        private const val ADVERT_DURATION_MS = 600L     // 6× INTERVAL_LOW events — enough for reliable reception
        private const val RETRY_JITTER_MS    = 200L     // small collision avoidance, doesn't dominate latency

        // SCAN_GAP_MS removed: with ADVERT_DURATION_MS=600ms the advertiser cycles
        // fast enough that the scanner gets natural duty cycle between bursts.
        // The 800ms gap was only necessary with 5000ms windows; at 600ms it just
        // added latency without helping scanner coverage.
        private const val SCAN_GAP_MS        = 0L

        private val SERVICE_UUID: java.util.UUID = BleConstants.MESH_SERVICE_UUID
    }

    private data class BroadcastJob(
        val packetBytes: ByteArray,
        val msgIdPreview: String,
        val attempt: Int,
        val maxAttempts: Int,
        val isAck: Boolean           // true → ACK packet, queued at head for priority
    )

    private val bleAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser? =
        bluetoothAdapter?.bluetoothLeAdvertiser

    private var activeSet: android.bluetooth.le.AdvertisingSet? = null
    private var activeCallback: android.bluetooth.le.AdvertisingSetCallback? = null

    @Volatile private var stopLatch: java.util.concurrent.CountDownLatch? = null

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

    // ── Queue + state (ALL accessed only on workerHandler) ───────────────────
    private val queue: ArrayDeque<BroadcastJob> = ArrayDeque()
    private var isAdvertising: Boolean = false
    private var currentJob: BroadcastJob? = null

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
            // Always evict any queued copies for this msgId regardless of whether
            // it is the active set — fixes the case where multiple copies were
            // enqueued (the accumulation bug) and only the active one was stopped.
            val before = queue.size
            queue.removeAll { it.msgIdPreview == msgIdHex }
            if (queue.size < before) {
                AppLogger.d(DIAG_TAG, "Advertiser.preempt(): removed ${before - queue.size} queued copies of msgId=$msgIdHex")
            }

            val cj = currentJob
            if (isAdvertising && cj != null && cj.msgIdPreview == msgIdHex) {
                // Active set is ours — stop it and advance the queue immediately.
                // stopActiveSetBlocking() is safe here: onAdvertisingSetStopped
                // fires on a BLE callback thread and calls stopLatch.countDown(),
                // which is a different thread from workerHandler — no deadlock.
                AppLogger.d(DIAG_TAG, "Advertiser.preempt(): stopping active set early msgId=$msgIdHex")
                stopActiveSetBlocking()
                val stop = currentAttemptStop
                currentAttemptStop = null
                stop?.invoke()  // queue advances with 0 delay, skips via canceledMsgIds
                return@post
            }
            // Active set has already moved on — just drain so next job starts.
            AppLogger.d(DIAG_TAG, "Advertiser.preempt(): msgId=$msgIdHex not active, draining queue")
            drainQueue()
        }
    }

    fun broadcast(packetBytes: ByteArray) {
        if (bleAdvertiser == null) return
        if (packetBytes.size > MAX_PAYLOAD) {
            AppLogger.e(TAG, "Packet too large: ${packetBytes.size} bytes (max $MAX_PAYLOAD)")
            return
        }

        // FIX (issue 1): msgId offset fix.
        // PacketSerializer wire format: byte 0 = version/flags, byte 1 = type,
        // bytes 2..9 = msgId (8 bytes).  The old take(8) captured bytes 0..7,
        // which included the header prefix and never matched the raw msgId
        // passed into cancelRetries() — breaking preemption every single time.
        val msgIdPreview = packetBytes.drop(2).take(8).toByteArray().toHex()
        val isAck = packetBytes.size == PacketSerializer.FIXED_HEADER_SIZE
        val maxAttempts = if (isAck) ACK_MAX_RETRIES else MAX_RETRIES

        AppLogger.d(DIAG_TAG, "Advertiser.broadcast(): len=${packetBytes.size}, msgId=$msgIdPreview")

        workerHandler.post {
            // Bug fix: if this msgId is already in canceledMsgIds (ACK was delivered
            // in a prior session and survived in persistent state), do not re-enqueue.
            // This prevents the updated=0 zombie flood observed in session 3 where
            // the DB row was already marked delivered but the ACK kept being re-queued.
            if (canceledMsgIds.contains(msgIdPreview)) {
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): msgId=$msgIdPreview already delivered — skipped")
                return@post
            }

            // Bug fix: also guard against duplicate queue entries for the same msgId.
            // The queue accumulation bug (2→3→6 ACKs per event) was caused by the same
            // ACK being enqueued multiple times across sessions without deduplication.
            if (queue.any { it.msgIdPreview == msgIdPreview }) {
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): msgId=$msgIdPreview already in queue — skipped")
                return@post
            }

            val job = BroadcastJob(
                packetBytes  = packetBytes,
                msgIdPreview = msgIdPreview,
                attempt      = 1,
                maxAttempts  = maxAttempts,
                isAck        = isAck
            )
            // ACKs jump to the head of the queue — they are small and time-critical.
            // Every ms an ACK waits behind an original message costs the remote
            // device a full wasted retry slot (~5s of air time).
            if (isAck) {
                queue.addFirst(job)
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): ACK msgId=$msgIdPreview → head (queueSize=${queue.size})")
            } else {
                queue.addLast(job)
                AppLogger.d(DIAG_TAG, "Advertiser.enqueue(): MSG msgId=$msgIdPreview → tail (queueSize=${queue.size})")
            }
            drainQueue()
        }
    }

    fun stopAll() {
        autoStopHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            queue.clear()
            canceledMsgIds.clear()
            isAdvertising      = false
            currentJob         = null
            currentAttemptStop = null
            stopActiveSetBlocking()
            AppLogger.d(DIAG_TAG, "BLE: Advertiser all sets cleared")
        }
    }

    // ── Queue drain (worker thread only) ─────────────────────────────────────

    private fun drainQueue() {
        // Drop any head jobs whose msgId has already been ACKed.
        while (queue.isNotEmpty() && canceledMsgIds.contains(queue.first().msgIdPreview)) {
            queue.removeFirst()
        }

        if (queue.isEmpty() || isAdvertising) return

        val job = queue.first()   // peek — do not remove until attempt is done
        isAdvertising  = true
        currentJob     = job

        // Build the shared cleanup closure.  Both the normal auto-stop path and
        // the preemption path in cancelRetries() invoke this exact same lambda,
        // guaranteeing identical state transitions regardless of which path runs.
        currentAttemptStop = {
            // Always called on workerHandler.
            isAdvertising      = false
            currentJob         = null
            currentAttemptStop = null

            // Dequeue the job that just finished.
            if (queue.isNotEmpty()
                && queue.first().msgIdPreview == job.msgIdPreview
                && queue.first().attempt      == job.attempt) {
                queue.removeFirst()
            } else {
                val it = queue.iterator()
                while (it.hasNext()) {
                    val j = it.next()
                    if (j.msgIdPreview == job.msgIdPreview && j.attempt == job.attempt) {
                        it.remove(); break
                    }
                }
            }

            // Re-enqueue next attempt. ACK retries also go to the front so they
            // stay ahead of any new original messages added during the window.
            // Bug fix: skip re-enqueue if already canceled OR if a copy is already
            // in the queue (prevents the accumulation that drove 2→3→6 ACKs/event).
            if (job.attempt < job.maxAttempts
                && !canceledMsgIds.contains(job.msgIdPreview)
                && queue.none { it.msgIdPreview == job.msgIdPreview }
            ) {
                val next = job.copy(attempt = job.attempt + 1)
                if (job.isAck) queue.addFirst(next) else queue.addLast(next)
            }

            // ACKed jobs advance immediately; others wait a small random jitter
            // to reduce collisions between nearby senders.
            // Bug fix (scanner starvation): when the queue has more jobs waiting,
            // add an extra SCAN_GAP_MS on top of the jitter so the BLE scanner
            // gets a guaranteed window to receive ACKs before the next burst.
            val baseDelay = if (canceledMsgIds.contains(job.msgIdPreview)) 0L
            else (0L..RETRY_JITTER_MS).random()
            val scanGap = if (queue.isNotEmpty() && !canceledMsgIds.contains(job.msgIdPreview)) SCAN_GAP_MS else 0L
            workerHandler.postDelayed({ drainQueue() }, baseDelay + scanGap)
        }

        broadcastInternalOnce(job) {
            // Normal (auto-stop) path: delegate to the shared cleanup closure.
            currentAttemptStop?.invoke()
        }
    }

    // ── One attempt: start advertising, auto-stop after ADVERT_DURATION_MS ──

    private fun broadcastInternalOnce(job: BroadcastJob, onStopped: () -> Unit) {
        // Cancel any stale auto-stop timer from a previous attempt.
        autoStopHandler.removeCallbacksAndMessages(null)
        // Stop any currently active set before starting the next one.
        stopActiveSetBlocking()

        val params = android.bluetooth.le.AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_CODED)
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
                    AppLogger.d(DIAG_TAG,
                        "Advertiser.onAdvertisingSetStarted(): SUCCESS " +
                                "txPower=$txPower msgId=${job.msgIdPreview} " +
                                "attempt=${job.attempt}/${job.maxAttempts}")

                    // autoStopHandler is on its own dedicated thread (autoStopThread).
                    // stopActiveSetBlocking() is safe there — it just calls the BLE API
                    // and waits on a latch that is released by a BLE callback thread.
                    // onStopped() modifies queue state (owned by workerHandler), so it
                    // must be dispatched back to workerHandler after the stop completes.
                    autoStopHandler.postDelayed({
                        stopActiveSetBlocking()
                        AppLogger.d(DIAG_TAG,
                            "Advertiser: auto-stopped after ${ADVERT_DURATION_MS}ms " +
                                    "msgId=${job.msgIdPreview} attempt=${job.attempt}/${job.maxAttempts}")
                        workerHandler.post { onStopped() }
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
                activeSet      = null
                activeCallback = null
                stopLatch?.countDown()
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

    private fun stopActiveSetBlocking() {
        val callbackToStop = activeCallback ?: return

        val latch = java.util.concurrent.CountDownLatch(1)
        stopLatch = latch

        try {
            bleAdvertiser?.stopAdvertisingSet(callbackToStop)
            val stopped = latch.await(STOP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!stopped) {
                AppLogger.w(DIAG_TAG, "Advertiser.stopActiveSetBlocking(): timeout waiting for stop")
            }
        } catch (e: Exception) {
            AppLogger.w(DIAG_TAG, "Advertiser.stopActiveSetBlocking(): ${e.message}")
        } finally {
            stopLatch      = null
            activeSet      = null
            activeCallback = null
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
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