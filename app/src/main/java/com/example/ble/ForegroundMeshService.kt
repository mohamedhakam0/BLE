/**
 * Foreground mesh runtime service.
 *
 * This service keeps BLE scanning/advertising active in background, handles packet deduplication,
 * sends ACKs for inbound chat packets, and bridges packets to UI via local broadcasts.
 */
package com.example.ble

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.example.ble.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * Foreground service that keeps the mesh radio running indefinitely.
 *
 * Packet receive pipeline (exactly once, in order):
 *   BleScanner extracts serviceData
 *   → PacketSerializer.deserialize()          (in BleScanner — only place)
 *   → onPacketReceived lambda (here)
 *     → dedup check                           (FIRST thing, before any side-effects)
 *     → notification gate
 *     → local broadcast for ChatViewModel
 *
 * PacketSerializer.deserialize() is NEVER called inside this file.
 */
class ForegroundMeshService : Service() {

    companion object {
        private const val TAG = "ForegroundMeshService"
        private const val DIAG_TAG = "MeshService"
        private const val CHANNEL_ID = "mesh_service"
        private const val NOTIF_ID = 1001

        const val ACTION_PACKET_RECEIVED = "com.peerreach.PACKET_RECEIVED"
        const val EXTRA_PACKET_BYTES = "packet_bytes"

        @Volatile private var dedupeRef: LinkedHashMap<Long, Boolean>? = null
        @Volatile private var lastServiceCreatedAt: Long = 0L
        @Volatile private var lastScanEventAt: Long = 0L

        fun start(context: Context) {
            val intent = Intent(context, ForegroundMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun clearSeenMsgIds() { dedupeRef?.clear() }

        fun addSeenMsgIds(msgIds: List<Long>) {
            val ref = dedupeRef ?: return
            synchronized(ref) {
                for (id in msgIds) {
                    ref[id] = true
                }
            }
        }

        fun getDebugState(): String = "createdAt=$lastServiceCreatedAt lastScanEventAt=$lastScanEventAt"
    }

    // LRU dedupe cache — keeps last 200 msgIds in memory.
    private val seenPackets: LinkedHashMap<Long, Boolean> = object : LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>): Boolean = size > 200
    }

    private var scanner: BleScanner? = null
    private var advertiser: BleAdvertiser? = null
    private var helloBeaconManager: HelloBeaconManager? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contactNicknameCache: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private var scanWakeLock: PowerManager.WakeLock? = null

    // Detect screen-on/user-present transitions (Samsung background scan resets).
    private val scanResumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    AppLogger.d(DIAG_TAG, "scanResumeReceiver: ${intent.action}")
                    ensureScannerRunning(reason = intent.action ?: "resume")
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanHealthRunnable = object : Runnable {
        override fun run() {
            val s = scanner
            val now = System.currentTimeMillis()
            val ageMs = now - lastScanEventAt

            AppLogger.d(DIAG_TAG, "scanHealth: inFg=${isAppInForeground()} isScanning=${s?.isScanning} lastScanAgeMs=$ageMs")

            // If app is foreground and we haven't seen any scan results in a while,
            // restart scanning proactively.
            if (isAppInForeground() && (s != null) && (!s.isScanning || ageMs > 30_000L)) {
                AppLogger.w(DIAG_TAG, "scanHealth: restarting scanner (reason=${if (!s.isScanning) "notRunning" else "stalled"})")
                s.stopScanning()
                s.startScanning()
            }
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastServiceCreatedAt = System.currentTimeMillis()
        dedupeRef = seenPackets

        createNotificationChannel()
        startForeground(NOTIF_ID, buildServiceNotification())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter

        advertiser = BleAdvertiser(adapter)

        val nodeIdentity = NodeIdentity(applicationContext)
        val myIdHex = nodeIdentity.getOrCreateIdentity().senderId.toHex()
        NeighborTable.clear()
        NeighborTable.setSelfNodeId(myIdHex)
        helloBeaconManager = HelloBeaconManager(nodeIdentity) { advertiser }
        helloBeaconManager?.start(serviceScope)
        AppLogger.log("HELLO", "HelloBeaconManager started")

        serviceScope.launch {
            runCatching {
                val repo = ContactRepository.getInstance(applicationContext)
                repo.getAllContacts().forEach { c ->
                    contactNicknameCache[c.senderId.trim().lowercase()] = c.nickname
                }
            }.onFailure { AppLogger.w(DIAG_TAG, "contact cache load failed: ${it.message}") }
        }

        scanner = BleScanner(applicationContext, adapter) { packet, rawBytes, rssi ->
            lastScanEventAt = System.currentTimeMillis()

            // HELLO packets are strictly for neighbor presence/gossip; never dedupe or relay.
            if (packet.type == PacketType.HELLO) {
                val senderHex = packet.senderId.toHex()
                NeighborTable.upsertDirect(senderHex, rssi, System.currentTimeMillis())
                parseHelloPayload(packet.payload, senderHex)
                    .filter { it.nodeId != myIdHex }
                    .forEach { NeighborTable.upsertExtended(it.nodeId, it.rssi, it.seenVia) }
                AppLogger.log("HELLO", "Neighbor: $senderHex RSSI=$rssi")
                return@BleScanner
            }

            // Only process packets meant for me (or broadcast). Anything else is mesh noise.
            val myId = nodeIdentity.getOrCreateIdentity().senderId
            val isForMe = packet.receiverId.contentEquals(myId)
            val isBroadcast = packet.receiverId.all { it == 0xFF.toByte() }
            if (!isForMe && !isBroadcast) return@BleScanner

            // If this is an ACK for me, cancel any pending retries for that msgId.
            if (packet.type == PacketType.ACK && isForMe) {
                advertiser?.cancelRetries(packet.msgId)
            }

            // ── 1. DEDUP — only for CHAT packets (ACK skips; HELLO already returned above) ──
            if (packet.type == PacketType.CHAT) {
                val msgIdLong = runCatching { packet.msgId.toLongBE() }.getOrNull()
                if (msgIdLong != null) {
                    synchronized(seenPackets) {
                        if (seenPackets.containsKey(msgIdLong)) {
                            AppLogger.d("BLE", "BLE: Duplicate packet dropped msgId=$msgIdLong")
                            return@BleScanner
                        }
                        seenPackets[msgIdLong] = true
                    }
                }
            }

            // ── 1.b ACK: service-level ACK for CHAT addressed to me ──
            if (packet.type == PacketType.CHAT && isForMe) {
                val ack = MeshPacket(
                    type       = PacketType.ACK,
                    msgId      = packet.msgId,
                    senderId   = myId,
                    receiverId = packet.senderId,
                    ttl        = 7.toByte(),
                    hopCount   = 0.toByte(),
                    timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
                    payloadLen = 0.toByte(),
                    authTag    = ByteArray(16),
                    payload    = byteArrayOf()
                )
                advertiser?.broadcast(PacketSerializer.serialize(ack))
                AppLogger.d(DIAG_TAG, "ACK sent for msgId=${packet.msgId.toHex()} to ${packet.senderId.toHex()}")
            }

            // ── 2. Notification gate ──
            val appInForeground = isAppInForeground()
            AppLogger.d(DIAG_TAG, "notificationGate: receiverMatches=$isForMe appInForeground=$appInForeground")

            if (packet.type == PacketType.CHAT && isForMe && !appInForeground) {
                val senderHex = packet.senderId.joinToString("") { "%02x".format(it) }.lowercase()
                val preview = runCatching { packet.payload.decodeToString() }.getOrDefault("(message)")
                val senderNick = contactNicknameCache[senderHex] ?: "Peer-$senderHex"
                NotificationHelper.showMessageNotification(
                    context = applicationContext,
                    senderName = senderNick,
                    preview = preview.take(80),
                    contactId = senderHex
                )
            }

            // ── 3. Forward original wire bytes to ChatViewModel via local broadcast ──
             val intent = Intent(ACTION_PACKET_RECEIVED).apply {
                 setPackage(packageName) // app-private broadcast
                 putExtra(EXTRA_PACKET_BYTES, rawBytes)
             }
             sendBroadcast(intent)
         }
        
        // Register screen/user-present receiver to recover from vendor scan resets.
        runCatching {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(scanResumeReceiver, f)
        }.onFailure {
            AppLogger.w(DIAG_TAG, "Failed to register scanResumeReceiver: ${it.message}")
        }

        val started = scanner?.startScanning() == true
        AppLogger.d(TAG, "Scanner start requested, started=$started")
        mainHandler.postDelayed(scanHealthRunnable, 10_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-delivery happens when the process returns; also useful as a recovery hook.
        ensureScannerRunning(reason = "onStartCommand")

        if (scanWakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            scanWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeerReach:ScanWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.d(DIAG_TAG, "ScanWakeLock acquired")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanHealthRunnable)
        AppLogger.d(DIAG_TAG, "Service.onDestroy state=${getDebugState()}")
        runCatching { unregisterReceiver(scanResumeReceiver) }
        scanner?.stopScanning(); scanner = null
        advertiser?.stopAll(); advertiser = null
        helloBeaconManager?.stop(); helloBeaconManager = null
        try {
            scanWakeLock?.let { if (it.isHeld) it.release() }
            AppLogger.d(DIAG_TAG, "ScanWakeLock released")
        } catch (_: Exception) {
        } finally { scanWakeLock = null }
        serviceScope.cancel()
        dedupeRef = null
        NeighborTable.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == packageName
        } == true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mesh Service", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildServiceNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Peer Reach")
                .setContentText("Mesh is running")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Peer Reach")
                .setContentText("Mesh is running")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        }
    }

    private fun ensureScannerRunning(reason: String) {
        val s = scanner
        if (s == null) {
            AppLogger.w(DIAG_TAG, "ensureScannerRunning($reason): scanner=null")
            return
        }

        // Only attempt restart when app is in foreground — background scanning is
        // expected to be throttled/killed on some devices.
        val inFg = isAppInForeground()
        if (!inFg) {
            AppLogger.d(DIAG_TAG, "ensureScannerRunning($reason): app not in foreground; skip")
            return
        }

        if (!s.isScanning) {
            AppLogger.w(DIAG_TAG, "ensureScannerRunning($reason): scanner not running; restarting")
            val ok = s.startScanning()
            AppLogger.d(DIAG_TAG, "ensureScannerRunning($reason): restartResult=$ok")
        } else {
            AppLogger.d(DIAG_TAG, "ensureScannerRunning($reason): scanner already running")
        }
    }
}

private fun ByteArray.toLongBE(): Long {
    require(size == 8)
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

private fun parseHelloPayload(payload: ByteArray, seenVia: String): List<Neighbor.Extended> {
    val out = ArrayList<Neighbor.Extended>(payload.size / 5)
    var off = 0
    while (off + 5 <= payload.size) {
        val nodeId = payload.copyOfRange(off, off + 4).toHex()
        val rssi = payload[off + 4].toInt()
        out += Neighbor.Extended(nodeId = nodeId, rssi = rssi, seenVia = seenVia)
        off += 5
    }
    return out
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
