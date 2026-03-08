package com.example.ble

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class BleMessage(
    val messageId: UUID,
    val sourceId: String,
    val destinationId: String,
    val payload: String
) {
    fun toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        DataOutputStream(stream).use {
            it.writeLong(messageId.mostSignificantBits)
            it.writeLong(messageId.leastSignificantBits)
            it.writeUTF(sourceId)
            it.writeUTF(destinationId)
            it.writeUTF(payload)
        }
        return stream.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): BleMessage? {
            return try {
                DataInputStream(ByteArrayInputStream(bytes)).use {
                    val mostSigBits = it.readLong()
                    val leastSigBits = it.readLong()
                    val messageId = UUID(mostSigBits, leastSigBits)
                    val sourceId = it.readUTF()
                    val destinationId = it.readUTF()
                    val payload = it.readUTF()
                    BleMessage(messageId, sourceId, destinationId, payload)
                }
            } catch (e: Exception) {
                null // Or handle error appropriately
            }
        }

        /**
         * Alias for fromByteArray for consistency with common naming patterns
         */
        fun deserialize(bytes: ByteArray): BleMessage? = fromByteArray(bytes)
    }
}
