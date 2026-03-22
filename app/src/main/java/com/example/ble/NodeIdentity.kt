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

data class NodeIdentityData(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val senderId: ByteArray
)

class NodeIdentity(private val context: Context) {

    companion object {
        private const val SECURE_PREFS_NAME = "node_identity_secure"
        private const val NICKNAME_PREFS_NAME = "node_identity_profile"

        private const val KEY_PUBLIC = "public_key_b64"
        private const val KEY_PRIVATE = "private_key_b64"
        private const val KEY_SENDER_ID = "sender_id_b64"
        private const val KEY_NICKNAME = "nickname"
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

    fun setNickname(nickname: String) {
        profilePrefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    fun getNickname(): String? = profilePrefs.getString(KEY_NICKNAME, null)

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

private fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)