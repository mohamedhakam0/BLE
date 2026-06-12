package com.example.ble

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val senderId: String,
    val nickname: String,
    val publicKey: String,
    val dateAdded: Long,
    val gradientSeed: String = ""
)

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact)

    @Query("SELECT * FROM contacts")
    suspend fun getAll(): List<Contact>

    @Query("SELECT * FROM contacts WHERE senderId = :senderId LIMIT 1")
    suspend fun getBySenderId(senderId: String): Contact?

    @Query("UPDATE contacts SET nickname = :newName WHERE senderId = :senderId")
    suspend fun rename(senderId: String, newName: String): Int

    @Query("DELETE FROM contacts WHERE senderId = :senderId")
    suspend fun delete(senderId: String): Int
}

@Database(
    entities = [Contact::class, MessageEntity::class, ReactionEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = Migration(5, 6) { database ->
            database.execSQL("ALTER TABLE contacts ADD COLUMN gradientSeed TEXT NOT NULL DEFAULT ''")
        }

        private val MIGRATION_6_7 = Migration(6, 7) { database ->
            database.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE messages ADD COLUMN hopCount INTEGER NOT NULL DEFAULT -1")
            database.execSQL("ALTER TABLE messages ADD COLUMN ackTimestamp INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE messages ADD COLUMN ackRttMs INTEGER NOT NULL DEFAULT -1")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reactions (
                    msgId INTEGER NOT NULL,
                    reactorId TEXT NOT NULL,
                    emoji TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    PRIMARY KEY (msgId, reactorId, emoji)
                )
                """.trimIndent()
            )
        }

        private val MIGRATION_7_8 = Migration(7, 8) { database ->
            database.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE messages ADD COLUMN deletedForEveryone INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE messages ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peerreach.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}

class ContactRepository private constructor(private val db: AppDatabase) {
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()
    private val reactionDao = db.reactionDao()

    companion object {
        @Volatile private var INSTANCE: ContactRepository? = null

        fun getInstance(context: Context): ContactRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContactRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
            }
    }

    suspend fun upsertContact(contact: Contact) = contactDao.upsert(contact)

    suspend fun getAllContacts(): List<Contact> = contactDao.getAll()

    suspend fun getContact(senderId: String): Contact? = contactDao.getBySenderId(senderId)

    fun observeContactsWithLastMessage(): Flow<List<ContactLastMessageRow>> =
        messageDao.observeContactsWithLastMessage()

    suspend fun renameContact(senderId: String, newName: String): Int =
        contactDao.rename(senderId, newName)

    suspend fun deleteContactAndChat(senderId: String) {
        db.withTransaction {
            reactionDao.deleteForContact(senderId)
            messageDao.deleteForContact(senderId)
            contactDao.delete(senderId)
        }
    }

    suspend fun deleteChat(senderId: String) {
        db.withTransaction {
            reactionDao.deleteForContact(senderId)
            messageDao.deleteForContact(senderId)
        }
    }
}
