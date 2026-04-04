/**
 * Room persistence layer for chat messages and conversation list projections.
 *
 * This file defines message table schema, DAO queries for chat history and delivery updates,
 * plus a repository wrapper used by chat UI and mesh service logic.
 */
package com.example.ble

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Direction marker for message ownership in the UI. */
enum class MessageDirection {
    INCOMING,
    OUTGOING
}

/** Delivery state stored in Room and rendered as checkmarks in chat. */
enum class MessageStatus {
    SENT,
    DELIVERED
}

/**
 * Persisted message row for one chat event.
 *
 * @property msgId protocol-level message identifier.
 * @property contactId sender/peer ID in hex string format.
 * @property insertedAt insertion timestamp used for stable ordering.
 */
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

/** Projection row for contacts list with latest message preview. */
data class ContactLastMessageRow(
    val senderId: String,
    val nickname: String,
    val publicKey: String,
    val dateAdded: Long,
    val lastText: String?,
    val lastTimestamp: Long?
)

/** DAO for message insert/query/update operations. */
@Dao
interface MessageDao {
    /** Inserts or replaces message row by msgId primary key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    /** Streams one conversation ordered by insertion time. */
    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY insertedAt ASC")
    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>>

    /** Updates delivery status and returns affected row count. */
    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId")
    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int

    /** Marks message delivered and returns affected row count. */
    @Query("UPDATE messages SET deliveryCompleted = 1, status = :status WHERE msgId = :msgId")
    suspend fun markDelivered(msgId: Long, status: MessageStatus = MessageStatus.DELIVERED): Int

    /** Marks ACK retry exhaustion and returns affected row count. */
    @Query("UPDATE messages SET ackBroadcastExhausted = 1 WHERE msgId = :msgId")
    suspend fun markAckBroadcastExhausted(msgId: Long): Int

    /** Returns whether ACK retries were exhausted for given message. */
    @Query("SELECT ackBroadcastExhausted FROM messages WHERE msgId = :msgId")
    suspend fun isAckBroadcastExhausted(msgId: Long): Boolean?

    /** Deletes all conversation rows for contact. */
    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: String)

    /** Returns all msgIds for one contact; used for dedup priming after delete. */
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

/** Repository wrapper around MessageDao used by ViewModels and service layer. */
class MessageRepository private constructor(private val db: AppDatabase) {
    private val dao = db.messageDao()

    companion object {
        @Volatile
        private var INSTANCE: MessageRepository? = null

        /** Returns process-wide singleton repository instance. */
        fun getInstance(context: Context): MessageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
    }

    /** Upserts one message row. */
    suspend fun upsert(message: MessageEntity) {
        dao.upsert(message)
    }

    /** Observes one contact conversation as Flow. */
    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>> =
        dao.observeMessagesForContact(contactId)

    /** Updates status and returns updated row count. */
    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int {
        return dao.updateStatus(msgId, status)
    }

    /** Marks message delivered and returns updated row count. */
    suspend fun markDelivered(msgId: Long): Int = dao.markDelivered(msgId)

    /** Marks ACK retries exhausted and returns updated row count. */
    suspend fun markAckBroadcastExhausted(msgId: Long): Int = dao.markAckBroadcastExhausted(msgId)

    /** True when ACK retry exhaustion flag is set for this message. */
    suspend fun isAckBroadcastExhausted(msgId: Long): Boolean = dao.isAckBroadcastExhausted(msgId) == true

    /** Deletes local message history for a contact. */
    suspend fun deleteHistory(contactId: String) {
        dao.deleteForContact(contactId)
    }

    /** Returns all message IDs for a contact. */
    suspend fun getMessageIdsForContact(contactId: String): List<Long> =
        db.messageDao().getMessageIdsForContact(contactId)

    /** Streams contact list with latest message preview rows. */
    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>> =
        dao.observeContactsWithLastMessage()
}
