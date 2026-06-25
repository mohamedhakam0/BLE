package com.example.ble

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
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
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val contactId: String,
    val text: String,
    val direction: MessageDirection,
    val status: MessageStatus,
    val timestamp: Long,
    val insertedAt: Long,
    // Legacy columns kept only for schema compatibility; no code reads or updates them.
    val ackBroadcastExhausted: Boolean = false,
    val deliveryCompleted: Boolean = false,
    // Added in v7
    val starred: Boolean = false,
    val hopCount: Int = -1,
    val ackTimestamp: Long = 0L,
    val ackRttMs: Long = -1L,
    // Added in v8
    val deleted: Boolean = false,
    val deletedForEveryone: Boolean = false,
    val deletedAt: Long = 0L
)

/** Persisted emoji reaction row. Composite PK prevents duplicate reactions per (msg, reactor, emoji). */
@Entity(tableName = "reactions", primaryKeys = ["msgId", "reactorId", "emoji"])
data class ReactionEntity(
    val msgId: Long,
    val reactorId: String,
    val emoji: String,
    val timestamp: Long
)

/** Projection row for contacts list with latest message preview. */
data class ContactLastMessageRow(
    val senderId: String,
    val nickname: String,
    val publicKey: String,
    val dateAdded: Long,
    val gradientSeed: String = "",
    val lastText: String?,
    val lastTimestamp: Long?
)

/** DAO for message insert/query/update operations. */
@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(message: MessageEntity)

    // Excludes "delete for me" records (deleted=1 but deletedForEveryone=0).
    // Tombstones (deleted=1 AND deletedForEveryone=1) are included so the UI can render a placeholder.
    @Query("SELECT * FROM messages WHERE contactId = :contactId AND (deleted = 0 OR deletedForEveryone = 1) ORDER BY insertedAt ASC")
    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId")
    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int

    @Query("SELECT msgId FROM messages WHERE contactId = :contactId")
    suspend fun getMessageIdsForContact(contactId: String): List<Long>

    @Query("SELECT * FROM messages WHERE msgId = :msgId LIMIT 1")
    suspend fun getById(msgId: Long): MessageEntity?

    @Query("UPDATE messages SET starred = :starred WHERE msgId = :msgId")
    suspend fun updateStarred(msgId: Long, starred: Boolean): Int

    @Query("UPDATE messages SET hopCount = :hopCount, ackTimestamp = :ackTimestamp, ackRttMs = :ackRttMs WHERE msgId = :msgId")
    suspend fun updateDeliveryInfo(msgId: Long, hopCount: Int, ackTimestamp: Long, ackRttMs: Long): Int

    @Query("UPDATE messages SET deleted = 1, deletedAt = :deletedAt WHERE msgId = :msgId")
    suspend fun softDeleteForMe(msgId: Long, deletedAt: Long): Int

    @Query("UPDATE messages SET deleted = 1, deletedForEveryone = 1, deletedAt = :deletedAt WHERE msgId = :msgId")
    suspend fun softDeleteForEveryone(msgId: Long, deletedAt: Long): Int

    @Query("SELECT * FROM messages WHERE contactId = :contactId AND starred = 1 AND deleted = 0 ORDER BY insertedAt ASC")
    fun observeStarredForContact(contactId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE starred = 1 AND deleted = 0 ORDER BY insertedAt ASC")
    fun observeAllStarred(): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT c.senderId, c.nickname, c.publicKey, c.dateAdded, c.gradientSeed,
               m.text AS lastText, m.insertedAt AS lastTimestamp
        FROM contacts c
        LEFT JOIN messages m
          ON m.contactId = c.senderId AND m.deleted = 0
        WHERE m.insertedAt = (
            SELECT MAX(m2.insertedAt) FROM messages m2
            WHERE m2.contactId = c.senderId AND m2.deleted = 0
        ) OR m.insertedAt IS NULL
        ORDER BY COALESCE(m.insertedAt, c.dateAdded) DESC
        """
    )
    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>>
}

/** DAO for emoji reactions. */
@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("SELECT * FROM reactions WHERE msgId IN (SELECT msgId FROM messages WHERE contactId = :contactId)")
    fun observeForContact(contactId: String): Flow<List<ReactionEntity>>

    @Query("DELETE FROM reactions WHERE msgId IN (SELECT msgId FROM messages WHERE contactId = :contactId)")
    suspend fun deleteForContact(contactId: String)

    @Query("DELETE FROM reactions")
    suspend fun deleteAll(): Int
}

/** Repository wrapper around MessageDao and ReactionDao used by ViewModels and service layer. */
class MessageRepository private constructor(private val db: AppDatabase) {
    private val dao = db.messageDao()
    private val reactionDao = db.reactionDao()

    companion object {
        @Volatile
        private var INSTANCE: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
    }

    suspend fun upsert(message: MessageEntity) = dao.upsert(message)

    fun observeMessagesForContact(contactId: String): Flow<List<MessageEntity>> =
        dao.observeMessagesForContact(contactId)

    suspend fun updateStatus(msgId: Long, status: MessageStatus): Int =
        dao.updateStatus(msgId, status)

    suspend fun deleteHistory(contactId: String) {
        db.withTransaction {
            reactionDao.deleteForContact(contactId)
            dao.deleteForContact(contactId)
        }
    }

    suspend fun getMessageIdsForContact(contactId: String): List<Long> =
        dao.getMessageIdsForContact(contactId)

    suspend fun getById(msgId: Long): MessageEntity? = dao.getById(msgId)

    suspend fun updateStarred(msgId: Long, starred: Boolean): Int =
        dao.updateStarred(msgId, starred)

    suspend fun updateDeliveryInfo(msgId: Long, hopCount: Int, ackTimestamp: Long, ackRttMs: Long): Int =
        dao.updateDeliveryInfo(msgId, hopCount, ackTimestamp, ackRttMs)

    suspend fun softDeleteForMe(msgId: Long, deletedAt: Long): Int =
        dao.softDeleteForMe(msgId, deletedAt)

    suspend fun softDeleteForEveryone(msgId: Long, deletedAt: Long): Int =
        dao.softDeleteForEveryone(msgId, deletedAt)

    suspend fun insertTombstone(message: MessageEntity) =
        dao.insertTombstone(message)

    fun observeStarredForContact(contactId: String): Flow<List<MessageEntity>> =
        dao.observeStarredForContact(contactId)

    fun observeAllStarred(): Flow<List<MessageEntity>> =
        dao.observeAllStarred()

    suspend fun upsertReaction(reaction: ReactionEntity) = reactionDao.upsert(reaction)

    fun observeReactionsForContact(contactId: String): Flow<List<ReactionEntity>> =
        reactionDao.observeForContact(contactId)

    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>> =
        dao.observeContactsWithLastMessage()
}
