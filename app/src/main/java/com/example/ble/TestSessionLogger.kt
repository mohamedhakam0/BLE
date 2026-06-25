package com.example.ble

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one CSV row per TestEvent to Downloads/PeerReach/.
 *
 * Column order (28 columns):
 *   session_id, experiment_id, trial_num, environment, distance_label, device_role,
 *   my_device_id, origin_sender_id, target_peer_id, msg_id, msg_counter, attempt_number,
 *   sent_ts_ms, received_ts_ms, latency_ms, hop_count, rssi_dbm, immediate_sender_id,
 *   is_duplicate, decryption_success, payload_verified, ack_received, ack_ts_ms,
 *   ack_latency_ms, ack_within_timeout, ack_rssi_dbm, event_type, notes
 */
class TestSessionLogger(
    private val context: Context,
    private val config: TestConfig,
    private val myDeviceId: String
) {

    val sessionId: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val fileName = "PR_${config.experimentId}_${config.role.name}_$sessionId.csv"

    private var writer: BufferedWriter? = null
    private var outputStream: OutputStream? = null
    private var legacyFile: File? = null
    private var mediaStoreUri: Uri? = null

    private val sentTimesMs = mutableMapOf<String, Long>()

    init { open() }

    private fun open() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/PeerReach")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    mediaStoreUri = uri
                    val os = context.contentResolver.openOutputStream(uri)!!
                    outputStream = os
                    writer = BufferedWriter(OutputStreamWriter(os))
                    writeHeader()
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PeerReach"
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                legacyFile = file
                writer = BufferedWriter(FileWriter(file, false))
                writeHeader()
            }
        } catch (e: Exception) {
            AppLogger.e("TestLogger", "Failed to open log file: ${e.message}")
        }
    }

    private fun writeHeader() {
        writer?.write(
            "session_id,experiment_id,trial_num,environment,distance_label,device_role," +
            "my_device_id,origin_sender_id,target_peer_id,msg_id,msg_counter,attempt_number," +
            "sent_ts_ms,received_ts_ms,latency_ms,hop_count,rssi_dbm,immediate_sender_id," +
            "is_duplicate,decryption_success,payload_verified,ack_received,ack_ts_ms," +
            "ack_latency_ms,ack_within_timeout,ack_rssi_dbm,event_type,notes"
        )
        writer?.newLine()
        writer?.flush()
    }

    fun log(event: TestEvent) {
        if (event is TestEvent.Sent) sentTimesMs[event.msgId] = event.sentTs
        try {
            writer?.write(buildRow(event))
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            AppLogger.e("TestLogger", "Write failed: ${e.message}")
        }
    }

    private fun buildRow(event: TestEvent): String {
        val sid    = sessionId
        val exp    = config.experimentId
        val trial  = config.trialNum.toString()
        val env    = config.environment.safe()
        val dist   = config.distanceLabel.safe()
        val role   = config.role.name
        val target = (config.targetPeerId ?: "broadcast").safe()
        val notes  = config.notes.safe()

        return when (event) {
            is TestEvent.Sent -> csv28(
                sid, exp, trial, env, dist, role,
                myDeviceId, myDeviceId, target,
                event.msgId, event.msgCounter.toString(), event.attemptNumber.toString(),
                event.sentTs.toString(), "", "", "", "", "",
                "false", "", "",
                "false", "", "", "", "",
                "SENT", notes
            )

            is TestEvent.Received -> {
                val latMs = (event.receivedTs - event.packetSentTs).toString()
                csv28(
                    sid, exp, trial, env, dist, role,
                    myDeviceId, event.originSenderId.safe(), target,
                    event.msgId, "", "",
                    "", event.receivedTs.toString(), latMs,
                    event.hopCount.toString(), event.rssiDbm.toString(),
                    event.immediateSenderId.safe(),
                    "false",
                    event.decryptionSuccess?.toString() ?: "",
                    event.payloadVerified?.toString() ?: "",
                    "false", "", "", "", "",
                    "RECEIVED", notes
                )
            }

            is TestEvent.AckReceived -> {
                val sentTs    = sentTimesMs[event.msgId] ?: 0L
                val sentTsStr = if (sentTs > 0) sentTs.toString() else ""
                val ackLat    = if (sentTs > 0) (event.ackTs - sentTs).coerceAtLeast(0).toString() else ""
                csv28(
                    sid, exp, trial, env, dist, role,
                    myDeviceId, event.originSenderId.safe(), target,
                    event.msgId, "", "",
                    sentTsStr, "", "",
                    "", event.ackRssiDbm.toString(), event.immediateSenderId.safe(),
                    "false", "true", "true",
                    "true", event.ackTs.toString(), ackLat,
                    event.ackWithinTimeout.toString(), event.ackRssiDbm.toString(),
                    "ACK_RECEIVED", notes
                )
            }

            is TestEvent.AckTimeout -> {
                val sentTs    = sentTimesMs[event.msgId] ?: 0L
                val sentTsStr = if (sentTs > 0) sentTs.toString() else ""
                csv28(
                    sid, exp, trial, env, dist, role,
                    myDeviceId, "", target,
                    event.msgId, "", "",
                    sentTsStr, "", "", "", "", "",
                    "false", "", "",
                    "false", "", "", "false", "",
                    "ACK_TIMEOUT", notes
                )
            }

            is TestEvent.DecryptionFailed -> {
                val latMs = (event.receivedTs - event.packetSentTs).toString()
                csv28(
                    sid, exp, trial, env, dist, role,
                    myDeviceId, event.originSenderId.safe(), target,
                    event.msgId, "", "",
                    "", event.receivedTs.toString(), latMs,
                    event.hopCount.toString(), event.rssiDbm.toString(),
                    event.immediateSenderId.safe(),
                    "false", "false", "false",
                    "false", "", "", "", "",
                    "DECRYPTION_FAILED", notes
                )
            }

            is TestEvent.DuplicateDropped -> {
                val latMs = (event.receivedTs - event.packetSentTs).toString()
                csv28(
                    sid, exp, trial, env, dist, role,
                    myDeviceId, event.originSenderId.safe(), target,
                    event.msgId, "", "",
                    "", event.receivedTs.toString(), latMs,
                    event.hopCount.toString(), event.rssiDbm.toString(),
                    event.immediateSenderId.safe(),
                    "true", "", "",
                    "false", "", "", "", "",
                    "DUPLICATE_DROPPED", notes
                )
            }
        }
    }

    /** Exactly 28 positional parameters, one per CSV column. */
    private fun csv28(
        c01: String, c02: String, c03: String, c04: String, c05: String, c06: String,
        c07: String, c08: String, c09: String, c10: String, c11: String, c12: String,
        c13: String, c14: String, c15: String, c16: String, c17: String, c18: String,
        c19: String, c20: String, c21: String, c22: String, c23: String, c24: String,
        c25: String, c26: String, c27: String, c28: String
    ): String = "$c01,$c02,$c03,$c04,$c05,$c06,$c07,$c08,$c09,$c10," +
                "$c11,$c12,$c13,$c14,$c15,$c16,$c17,$c18,$c19,$c20," +
                "$c21,$c22,$c23,$c24,$c25,$c26,$c27,$c28"

    private fun String.safe(): String =
        if (contains(',') || contains('"') || contains('\n'))
            "\"${replace("\"", "\"\"")}\"" else this

    fun getShareUri(): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return mediaStoreUri
        val file = legacyFile ?: return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) { null }
    }

    fun close() {
        try { writer?.flush(); writer?.close(); outputStream?.close() } catch (_: Exception) {}
        writer = null
        outputStream = null
    }
}
