package com.example.ble

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class MessageDirection {
    INCOMING,
    OUTGOING
}

enum class MessageStatus {
    SENT,
    DELIVERED
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val contactId: String, // senderId hex
    val text: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val timestamp: Long,   // wall-clock for display
    val insertedAt: Long,  // monotonic ordering (System.currentTimeMillis at insert time)
    // Persisted transport bookkeeping (survives reboots)
    val ackBroadcastExhausted: Boolean = false,
    val deliveryCompleted: Boolean = false
)

data class ContactLastMessageRow(
    val senderId: String,
    val nickname: String,
    val publicKey: String,
    val dateAdded: Long,
    val lastText: String?,
    val lastTimestamp: Long?
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY insertedAt ASC")
    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId")
    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int

    @Query("UPDATE messages SET deliveryCompleted = 1, status = :status WHERE msgId = :msgId")
    suspend fun markDelivered(msgId: Long, status: MessageStatus = MessageStatus.DELIVERED): Int

    @Query("UPDATE messages SET ackBroadcastExhausted = 1 WHERE msgId = :msgId")
    suspend fun markAckBroadcastExhausted(msgId: Long): Int

    @Query("SELECT ackBroadcastExhausted FROM messages WHERE msgId = :msgId")
    suspend fun isAckBroadcastExhausted(msgId: Long): Boolean?

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: String)

    @Query("SELECT msgId FROM messages WHERE contactId = :contactId")
    suspend fun getMessageIdsForContact(contactId: String): List<Long>

    // WhatsApp-like conversation list query: one row per contact + latest message.
    @Query(
        """
        SELECT c.senderId, c.nickname, c.publicKey, c.dateAdded,
               m.text AS lastText, m.insertedAt AS lastTimestamp
        FROM contacts c
        LEFT JOIN messages m
          ON m.contactId = c.senderId
        WHERE m.insertedAt = (
            SELECT MAX(m2.insertedAt) FROM messages m2
            WHERE m2.contactId = c.senderId
        ) OR m.insertedAt IS NULL
        ORDER BY COALESCE(m.insertedAt, c.dateAdded) DESC
        """
    )
    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>>
}

class MessageRepository private constructor(private val db: AppDatabase) {
    private val dao = db.messageDao()

    companion object {
        @Volatile
        private var INSTANCE: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
    }

    suspend fun upsert(message: MessageEntity) {
        dao.upsert(message)
    }

    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>> =
        dao.observeMessagesForContact(contactId)

    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int {
        return dao.updateStatus(msgId, status)
    }

    suspend fun markDelivered(msgId: Long): Int = dao.markDelivered(msgId)

    suspend fun markAckBroadcastExhausted(msgId: Long): Int = dao.markAckBroadcastExhausted(msgId)

    suspend fun isAckBroadcastExhausted(msgId: Long): Boolean = dao.isAckBroadcastExhausted(msgId) == true

    suspend fun deleteHistory(contactId: String) {
        dao.deleteForContact(contactId)
    }

    suspend fun getMessageIdsForContact(contactId: String): List<Long> =
        db.messageDao().getMessageIdsForContact(contactId)

    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>> =
        dao.observeContactsWithLastMessage()
}
