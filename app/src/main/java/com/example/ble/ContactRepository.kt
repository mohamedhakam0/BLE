/**
 * Room persistence layer for contacts and shared app database construction.
 *
 * This file defines the `contacts` table schema, DAO operations, the `AppDatabase` singleton,
 * and repository methods used by UI and services to read/update contacts and chat metadata.
 */
package com.example.ble

import android.content.Context
import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Saved contact record exchanged through QR onboarding.
 *
 * @property senderId peer node ID in lowercase hex format.
 * @property nickname user-defined display name.
 * @property publicKey peer public key in Base64 form.
 * @property dateAdded local insertion timestamp.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val senderId: String, // hex (lowercase)
    val nickname: String,
    val publicKey: String,
    val dateAdded: Long
)

/** Data access contract for contact CRUD operations. */
@Dao
interface ContactDao {
    /** Inserts or replaces a contact row by senderId primary key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact)

    /** Returns all contacts without ordering guarantees. */
    @Query("SELECT * FROM contacts")
    suspend fun getAll(): List<Contact>

    /** Returns a single contact by senderId, or null if missing. */
    @Query("SELECT * FROM contacts WHERE senderId = :senderId LIMIT 1")
    suspend fun getBySenderId(senderId: String): Contact?

    /** Renames a contact and returns number of updated rows. */
    @Query("UPDATE contacts SET nickname = :newName WHERE senderId = :senderId")
    suspend fun rename(senderId: String, newName: String): Int

    /** Deletes one contact row and returns number of removed rows. */
    @Query("DELETE FROM contacts WHERE senderId = :senderId")
    suspend fun delete(senderId: String): Int
}

/**
 * Main Room database for Peer Reach.
 *
 * Includes both `Contact` and `MessageEntity` tables and exposes their DAOs.
 */
@Database(entities = [Contact::class, MessageEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Returns process-wide singleton database instance. */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peerreach.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

/** Repository wrapper around contact and message DAOs. */
class ContactRepository private constructor(private val db: AppDatabase) {
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()

    companion object {
        @Volatile private var INSTANCE: ContactRepository? = null

        /** Returns process-wide repository singleton. */
        fun getInstance(context: Context): ContactRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContactRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
    }

    /** Inserts or updates a contact row. */
    suspend fun upsertContact(contact: Contact) {
        contactDao.upsert(contact)
    }

    /** Returns all contacts. */
    suspend fun getAllContacts(): List<Contact> = contactDao.getAll()

    /** Returns one contact by senderId or null. */
    suspend fun getContact(senderId: String): Contact? = contactDao.getBySenderId(senderId)

    /** Streams contacts joined with their latest message preview for home screen. */
    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>> =
        messageDao.observeContactsWithLastMessage()

    /** Updates the contact nickname and returns affected row count. */
    suspend fun renameContact(senderId: String, newName: String): Int =
        contactDao.rename(senderId, newName)

    /** Deletes the contact row only. */
    suspend fun deleteContact(senderId: String): Int =
        contactDao.delete(senderId)

    /** Deletes all messages linked to a contact but preserves the contact entry. */
    suspend fun deleteChat(senderId: String) {
        messageDao.deleteForContact(senderId)
    }

    /** Deletes both chat history and contact in a single Room transaction. */
    suspend fun deleteContactAndChat(senderId: String) {
        db.withTransaction {
            messageDao.deleteForContact(senderId)
            contactDao.delete(senderId)
        }
    }
}
