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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
        const val ACTION_QUIT = "com.example.ble.ACTION_QUIT"

        private val _peerJoinedEvent = MutableSharedFlow<String>(extraBufferCapacity = 32)
        private val _peerLeftEvent = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val peerJoinedEvent: SharedFlow<String> = _peerJoinedEvent.asSharedFlow()
        val peerLeftEvent: SharedFlow<String> = _peerLeftEvent.asSharedFlow()

        @Volatile private var dedupeRef: PacketCache? = null
        @Volatile private var lastServiceCreatedAt: Long = 0L
        @Volatile private var lastScanEventAt: Long = System.currentTimeMillis()

        fun start(context: Context) {
            val intent = Intent(context, ForegroundMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun clearSeenMsgIds() { dedupeRef?.clear() }

        fun addSeenMsgIds(msgIds: List<Long>) {
            val ref = dedupeRef ?: return
            for (id in msgIds) {
                ref.isDuplicate(id)
            }
        }

        fun getDebugState(): String = "createdAt=$lastServiceCreatedAt lastScanEventAt=$lastScanEventAt"
    }

    private val packetCache = PacketCache()

    private var scanner: BleScanner? = null
    private var advertiser: BleAdvertiser? = null
    private var lastScannerRestartAt: Long = 0L
    private lateinit var nodeIdentity: NodeIdentity
    private var helloBeaconManager: HelloBeaconManager? = null
    private var myNodeIdHex: String = ""
    private val peerMap = ConcurrentHashMap<String, PeerEntry>()
    private val quitReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_QUIT) {
                broadcastLeaveAndStop()
            }
        }
    }

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
            val inTdmGap = advertiser?.isTdmGapActive ?: false
            if (isAppInForeground() && (s != null) && (!s.isScanning || ageMs > 60_000L)) {
                if (inTdmGap) {
                    AppLogger.d(DIAG_TAG, "scanHealth: skipping restart — TDM gap active, scanner has radio")
                } else {
                    val restartNow = System.currentTimeMillis()
                    val sinceLastRestart = restartNow - lastScannerRestartAt
                    if (sinceLastRestart < 30_000L) {
                        AppLogger.d(DIAG_TAG, "scanHealth: skipping restart — cooldown (${sinceLastRestart}ms since last)")
                        mainHandler.postDelayed(this, 10_000L)
                        return
                    }
                    lastScannerRestartAt = restartNow
                    AppLogger.w(DIAG_TAG, "scanHealth: restarting scanner (reason=${if (!s.isScanning) "notRunning" else "stalled"})")
                    s.stopScanning()
                    s.startScanning()
                }
            }
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastServiceCreatedAt = System.currentTimeMillis()
        dedupeRef = packetCache

        createNotificationChannel()
        startForeground(NOTIF_ID, buildServiceNotification())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter

        advertiser = BleAdvertiser(adapter)
        advertiser?.clearQueue()

        nodeIdentity = NodeIdentity(applicationContext)
        val myNodeIdBytes = nodeIdentity.getOrCreateIdentity().senderId
        myNodeIdHex = myNodeIdBytes.toHex()
        NeighborTable.clear()
        NeighborTable.setSelfNodeId(myNodeIdHex)

        runCatching {
            ContextCompat.registerReceiver(
                this,
                quitReceiver,
                IntentFilter(ACTION_QUIT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { AppLogger.w(DIAG_TAG, "Failed to register quitReceiver: ${it.message}") }

        serviceScope.launch {
            runCatching {
                val repo = ContactRepository.getInstance(applicationContext)
                repo.getAllContacts().forEach { c ->
                    contactNicknameCache[c.senderId.trim().lowercase()] = c.nickname
                }
            }.onFailure { AppLogger.w(DIAG_TAG, "contact cache load failed: ${it.message}") }
        }

        scanner = BleScanner(applicationContext, adapter) { packet: MeshPacket, rssi: Int ->
            lastScanEventAt = System.currentTimeMillis()
            Log.i(
                "MeshService",
                "RX msgId=${packet.msgId.toHex()} type=${packet.type} from=${packet.senderId.toHex()} ttl=${packet.ttl.toInt() and 0xFF} hop=${packet.hopCount.toInt() and 0xFF} rssi=$rssi"
            )

            val localSenderId = nodeIdentity.getOrCreateIdentity().senderId
            val senderIdHex = packet.senderId.toHex()
            if (!packet.senderId.contentEquals(localSenderId)) {
                val now = System.currentTimeMillis()
                val existing = peerMap.put(
                    senderIdHex,
                    PeerEntry(
                        lastSeen = now,
                        minHopCount = packet.hopCount.toInt() and 0xFF
                    )
                )
                if (existing == null) {
                    Log.i("MeshService", "PEER JOINED: $senderIdHex (via ${packet.type})")
                    _peerJoinedEvent.tryEmit(senderIdHex)
                    updateNotification()
                }
            }

            val msgIdLong = runCatching { packet.msgId.toLongBE() }.getOrNull()
            if (msgIdLong != null && packetCache.isDuplicate(msgIdLong)) {
                Log.v("MeshService", "DEDUP drop msgId=${packet.msgId.toHex()}")
                return@BleScanner
            }

            val isForMe = packet.receiverId.contentEquals(localSenderId)
            val isBroadcast = packet.receiverId.all { it == 0xFF.toByte() }
            val ttl = packet.ttl.toInt() and 0xFF
            val shouldRelay = ttl > 0 && !packet.receiverId.contentEquals(localSenderId)
            if (shouldRelay) {
                if (packet.type == PacketType.ACK) return@BleScanner  // never relay ACKs at the originating node

                // Skip relaying packets we originated locally
                if (advertiser?.wasLocallyOriginated(packet.msgId.toHex()) == true) {
                    AppLogger.d(TAG, "RELAY skip: locally originated msgId=${packet.msgId.toHex()}")
                    return@BleScanner
                }

                val relayTtl = if (packet.type == PacketType.ACK) {
                    minOf((ttl - 1), 2).toByte()
                } else {
                    (ttl - 1).toByte()
                }
                val relayPacket = packet.copy(
                    ttl = relayTtl,
                    hopCount = ((packet.hopCount.toInt() and 0xFF) + 1).toByte()
                )
                val jitterMs = Random.nextLong(10L, 51L)
                Log.i(
                    "MeshService",
                    "RELAY msgId=${packet.msgId.toHex()} newTtl=${relayPacket.ttl.toInt() and 0xFF} newHop=${relayPacket.hopCount.toInt() and 0xFF} jitter=${jitterMs}ms"
                )
                serviceScope.launch {
                    delay(jitterMs)
                    advertiser?.enqueue(relayPacket, isRelay = true)
                }
            }

            // Only process packets meant for me (or broadcast). Anything else is mesh noise.
            if (!isForMe && !isBroadcast) return@BleScanner
            if (isForMe) {
                Log.i("MeshService", "DELIVER msgId=${packet.msgId.toHex()} type=${packet.type} to self")
            }

             if (packet.type == PacketType.HELLO) {
                  if (packet.ttl != 1.toByte()) return@BleScanner

                  val senderId = senderIdHex
                  val now = System.currentTimeMillis()
                  if (senderId == myNodeIdHex) return@BleScanner

                 updatePeer(senderId, packet.hopCount.toInt())
                // Direct neighbor from scan RSSI (hop 0).
                NeighborTable.upsert(
                     NeighborEntry(
                         nodeId = senderId,
                         rssi = rssi,
                         lastSeen = now,
                         hopCount = 0,
                         seenVia = null
                     )
                 )

                val extended = parseHelloPayload(packet.payload, senderNodeId = senderId)
                val filtered = extended.filter { it.nodeId != myNodeIdHex && it.nodeId != senderId }
                if (filtered.isNotEmpty()) {
                    NeighborTable.upsertAll(filtered)
                }

                AppLogger.log("HELLO", "Neighbor: $senderId RSSI=${rssi} dBm, extended=${filtered.size} entries")
                return@BleScanner
            }

            if (packet.type == PacketType.LEAVE) {
                val departingSenderId = packet.senderId.toHex()
                AppLogger.i("MeshService", "LEAVE received from $departingSenderId - removing from peer map")

                peerMap.remove(departingSenderId)
                NeighborTable.removeNode(departingSenderId)
                updateNotification()
                _peerLeftEvent.tryEmit(departingSenderId)

                return@BleScanner
            }

            // If this is an ACK for me, cancel any pending retries for that msgId.
            if (packet.type == PacketType.ACK && isForMe) {
                val referencedMsgId = packet.payload
                    .takeIf { it.size >= 8 }
                    ?.copyOfRange(0, 8)

                if (referencedMsgId != null) {
                    Log.i(
                        "MeshService",
                        "ACK received for originalMsgId=${referencedMsgId.toHex()} from=${packet.senderId.toHex()}"
                    )
                    advertiser?.cancelRetries(referencedMsgId)
                    com.example.ble.debug.StressTestManager.onAckReceived(referencedMsgId.toHex())
                } else {
                    Log.w("MeshService", "ACK payload missing referenced msgId from=${packet.senderId.toHex()}")
                }
            }

            // ── 1.b ACK: service-level ACK for CHAT addressed to me ──
            if (packet.type == PacketType.CHAT && isForMe) {
                val ack = MeshPacket(
                    type       = PacketType.ACK,
                    msgId      = Random.nextBytes(8),
                    senderId   = localSenderId,
                    receiverId = packet.senderId,
                    ttl        = 6.toByte(),
                    hopCount   = 0.toByte(),
                    timestamp  = (System.currentTimeMillis() / 1000L).toInt(),
                    payloadLen = 8.toByte(),
                    authTag    = ByteArray(16),
                    payload    = packet.msgId.copyOf()
                )
                advertiser?.enqueue(ack)
                AppLogger.d(DIAG_TAG, "ACK sent for msgId=${ack.msgId.toHex()} original=${packet.msgId.toHex()} to ${packet.senderId.toHex()}")
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

            // ── 3. Forward packet bytes to the app layer ──
             val broadcastBytes = PacketSerializer.serialize(packet)
             val intent = Intent(ACTION_PACKET_RECEIVED).apply {
                 setPackage(packageName) // app-private broadcast
                 putExtra(EXTRA_PACKET_BYTES, broadcastBytes)
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

        helloBeaconManager = advertiser?.let { adv ->
            HelloBeaconManager(this, adv, myNodeIdBytes).also { it.start(serviceScope) }
        }
        AppLogger.d(TAG, "HelloBeaconManager started")

        serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                NeighborTable.evictStale()
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
                peerMap.entries.removeIf { it.value.lastSeen < cutoff }
                updateNotification()
            }
        }

        mainHandler.postDelayed(scanHealthRunnable, 10_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i("HELLO", "Service starting - will fire HELLO beacon to announce rejoin")
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
        scanner?.stopScanning(); scanner = null
        advertiser?.stopAll(); advertiser = null
        helloBeaconManager?.stop(); helloBeaconManager = null
        NeighborTable.clear()
        peerMap.clear()
        try {
            scanWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            scanWakeLock = null
        }
        runCatching { unregisterReceiver(quitReceiver) }
        runCatching { unregisterReceiver(scanResumeReceiver) }
        serviceScope.cancel()
        dedupeRef = null
        AppLogger.i("MeshService", "ForegroundMeshService destroyed cleanly after LEAVE")
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

    fun broadcastLeaveAndStop() {
        serviceScope.launch {
            AppLogger.i("HELLO", "Broadcasting LEAVE packet before shutdown")
            val sender = nodeIdentity.getOrCreateIdentity().senderId
            val leavePacket = MeshPacket(
                type = PacketType.LEAVE,
                msgId = Random.nextBytes(8),
                senderId = sender,
                receiverId = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                ttl = 3,
                hopCount = 0,
                timestamp = (System.currentTimeMillis() / 1000L).toInt(),
                payloadLen = 0,
                authTag = ByteArray(16),
                payload = byteArrayOf()
            )
            advertiser?.enqueue(leavePacket)
            delay(600L)
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    fun updatePeer(senderId: String, hopCount: Int) {
        val normalized = senderId.trim().lowercase()
        if (normalized == myNodeIdHex) return
        peerMap[normalized] = PeerEntry(System.currentTimeMillis(), hopCount)
        updateNotification()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val quitIntent = Intent(ACTION_QUIT).also { it.setPackage(packageName) }
        val quitPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val peerCount = peerMap.size
        val peerLabel = if (peerCount == 1) "peer" else "peers"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Peer Reach")
            .setContentText("Mesh is running • $peerCount $peerLabel")
            .setContentIntent(pending)
            .setOngoing(true)
            .addAction(0, "Quit Peer Reach", quitPendingIntent)
            .build()
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

    private fun parseHelloPayload(payload: ByteArray, senderNodeId: String): List<NeighborEntry> {
        if (payload.isEmpty()) return emptyList()

        val count = payload[0].toInt() and 0xFF
        if (count == 0) return emptyList()

        val requiredSize = 1 + (count * 5)
        if (requiredSize > payload.size) {
            AppLogger.w("HELLO", "Malformed HELLO payload from=$senderNodeId count=$count payloadLen=${payload.size}")
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val out = ArrayList<NeighborEntry>(count)
        var offset = 1
        repeat(count) {
            val nodeId = payload.copyOfRange(offset, offset + 4).toHex()
            val rssi = payload[offset + 4].toInt()
            out.add(
                NeighborEntry(
                    nodeId = nodeId,
                    rssi = rssi,
                    lastSeen = now,
                    hopCount = 1,
                    seenVia = senderNodeId
                )
            )
            offset += 5
        }
        return out
    }
}

private fun ByteArray.toLongBE(): Long {
    require(size == 8)
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private data class PeerEntry(
    val lastSeen: Long,
    val minHopCount: Int
)
