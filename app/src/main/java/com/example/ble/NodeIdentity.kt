/**
 * Manages local node cryptographic identity and user profile nickname.
 *
 * Identity keys are generated with Bouncy Castle X25519 and persisted in
 * `EncryptedSharedPreferences`. A 4-byte sender ID is derived from SHA-256(publicKey)
 * and used as the routing identity in mesh packets.
 */
package com.example.ble

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/** Immutable snapshot of node key material and derived senderId. */
data class NodeIdentityData(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val senderId: ByteArray
)

/** Provides get-or-create identity lifecycle and nickname persistence. */
class NodeIdentity(private val context: Context) {

    companion object {
        private const val SECURE_PREFS_NAME = "node_identity_secure"
        private const val NICKNAME_PREFS_NAME = "node_identity_profile"

        private const val KEY_PUBLIC = "public_key_b64"
        private const val KEY_PRIVATE = "private_key_b64"
        private const val KEY_SENDER_ID = "sender_id_b64"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LOCAL_AVATAR = "local_avatar_jpeg"

        /**
         * Persists [jpeg] bytes as Base64 in EncryptedSharedPreferences so the
         * running GATT server can serve them after a process restart.
         */
        fun saveLocalAvatar(context: Context, jpeg: ByteArray) {
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            openSecurePrefs(context).edit().putString(KEY_LOCAL_AVATAR, b64).apply()
        }

        /**
         * Returns the stored avatar JPEG bytes, or null if none have been saved.
         */
        fun loadLocalAvatar(context: Context): ByteArray? {
            val b64 = openSecurePrefs(context).getString(KEY_LOCAL_AVATAR, null) ?: return null
            return try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { null }
        }

        /** Opens (or re-uses the cached) EncryptedSharedPreferences for the secure store. */
        private fun openSecurePrefs(context: Context): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context.applicationContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val profilePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(NICKNAME_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns persisted identity if present; otherwise generates and stores a new one.
     */
    fun getOrCreateIdentity(): NodeIdentityData {
        getIdentityOrNull()?.let { return it }

        val keyPair = generateX25519KeyPair()
        val publicKey = keyPair.publicKey
        val privateKey = keyPair.privateKey
        val senderId = deriveSenderId(publicKey)

        securePrefs.edit()
            .putString(KEY_PUBLIC, publicKey.toB64())
            .putString(KEY_PRIVATE, privateKey.toB64())
            .putString(KEY_SENDER_ID, senderId.toB64())
            .apply()

        return NodeIdentityData(publicKey, privateKey, senderId)
    }

    /** Returns persisted identity, or null when the app has not generated one yet. */
    fun getIdentityOrNull(): NodeIdentityData? {
        val pub = securePrefs.getString(KEY_PUBLIC, null) ?: return null
        val priv = securePrefs.getString(KEY_PRIVATE, null) ?: return null
        val sid = securePrefs.getString(KEY_SENDER_ID, null) ?: return null

        return NodeIdentityData(
            publicKey = pub.fromB64(),
            privateKey = priv.fromB64(),
            senderId = sid.fromB64()
        )
    }

    /** Saves user-facing nickname used in QR sharing and contact UI. */
    fun setNickname(nickname: String) {
        profilePrefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    /** Returns the stored nickname, or null if not set. */
    fun getNickname(): String? = profilePrefs.getString(KEY_NICKNAME, null)

    /** Derives 4-byte senderId from SHA-256(publicKey). */
    private fun deriveSenderId(publicKey: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return hash.copyOfRange(0, 4)
    }

    /**
     * Generate X25519 keypair using Bouncy Castle's native API.
     * Returns raw 32-byte keys (not ASN.1 encoded).
     */
    private fun generateX25519KeyPair(): X25519KeyPair {
        val random = SecureRandom()
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(random))

        val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()

        val privateKeyParams = keyPair.private as X25519PrivateKeyParameters
        val publicKeyParams = keyPair.public as X25519PublicKeyParameters

        return X25519KeyPair(
            publicKey = publicKeyParams.encoded,  // 32 bytes
            privateKey = privateKeyParams.encoded // 32 bytes
        )
    }

    private data class X25519KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    )
}

/** Encodes raw bytes to compact Base64 string for preference storage. */
private fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

/** Decodes Base64 preference string to raw bytes. */
private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)