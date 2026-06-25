package com.example.ble

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import java.nio.ByteBuffer

data class EncryptedResult(
    val ciphertext: ByteArray,
    val authTag: ByteArray
)

object CryptoManager {

    private const val AES_KEY_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_LEN_BITS = 128
    private const val TAG_LEN_BYTES = 16
    private const val SESSION_KEY_LEN = 28  // 16 AES key + 12 nonce base, no discarded bytes
    private const val X25519_KEY_LEN = 32
    private const val AAD_LEN = 22

    fun computeSharedSecret(
        localPrivateKeyBytes: ByteArray,
        peerPublicKeyBytes: ByteArray
    ): ByteArray {
        require(localPrivateKeyBytes.size == X25519_KEY_LEN) {
            "Private key must be $X25519_KEY_LEN bytes, got ${localPrivateKeyBytes.size}"
        }
        require(peerPublicKeyBytes.size == X25519_KEY_LEN) {
            "Public key must be $X25519_KEY_LEN bytes, got ${peerPublicKeyBytes.size}"
        }

        val privateKey = X25519PrivateKeyParameters(localPrivateKeyBytes, 0)
        val publicKey = X25519PublicKeyParameters(peerPublicKeyBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(privateKey)

        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKey, secret, 0)
        return secret
    }

    fun deriveSessionKey(
        sharedSecret: ByteArray,
        senderId: ByteArray,
        receiverId: ByteArray
    ): ByteArray {
        require(sharedSecret.size == X25519_KEY_LEN) {
            "Shared secret must be $X25519_KEY_LEN bytes"
        }
        require(senderId.size == 4) { "Sender ID must be 4 bytes" }
        require(receiverId.size == 4) { "Receiver ID must be 4 bytes" }

        val infoPrefix = "peer-reach-v1".toByteArray(Charsets.UTF_8)
        val info = ByteArray(infoPrefix.size + senderId.size + receiverId.size)
        System.arraycopy(infoPrefix, 0, info, 0, infoPrefix.size)
        System.arraycopy(senderId, 0, info, infoPrefix.size, senderId.size)
        System.arraycopy(receiverId, 0, info, infoPrefix.size + senderId.size, receiverId.size)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, info))

        val output = ByteArray(SESSION_KEY_LEN)
        hkdf.generateBytes(output, 0, SESSION_KEY_LEN)
        return output
    }

    fun encrypt(
        aesKey: ByteArray,
        nonceBase: ByteArray,
        msgCounter: Long,
        plaintext: ByteArray,
        aad: ByteArray
    ): EncryptedResult {
        require(aesKey.size == AES_KEY_LEN) { "AES key must be $AES_KEY_LEN bytes" }
        require(nonceBase.size == NONCE_LEN) { "Nonce base must be $NONCE_LEN bytes" }

        val nonce = deriveNonce(nonceBase, msgCounter)

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(aesKey), TAG_LEN_BITS, nonce, aad)
        cipher.init(true, params)

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var off = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        off += cipher.doFinal(output, off)

        val ciphertext = output.copyOfRange(0, off - TAG_LEN_BYTES)
        val authTag = output.copyOfRange(off - TAG_LEN_BYTES, off)

        return EncryptedResult(ciphertext, authTag)
    }

    fun decrypt(
        aesKey: ByteArray,
        nonceBase: ByteArray,
        msgCounter: Long,
        ciphertext: ByteArray,
        authTag: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        require(aesKey.size == AES_KEY_LEN) { "AES key must be $AES_KEY_LEN bytes" }
        require(nonceBase.size == NONCE_LEN) { "Nonce base must be $NONCE_LEN bytes" }
        require(authTag.size == TAG_LEN_BYTES) { "Auth tag must be $TAG_LEN_BYTES bytes" }

        val nonce = deriveNonce(nonceBase, msgCounter)
        val input = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, input, 0, ciphertext.size)
        System.arraycopy(authTag, 0, input, ciphertext.size, authTag.size)

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(aesKey), TAG_LEN_BITS, nonce, aad)
        cipher.init(false, params)

        return try {
            val output = ByteArray(cipher.getOutputSize(input.size))
            var off = cipher.processBytes(input, 0, input.size, output, 0)
            off += cipher.doFinal(output, off)
            output.copyOfRange(0, off)
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    fun buildAad(packet: MeshPacket): ByteArray {
        // flags is intentionally excluded: FLAG_IS_GATEWAY is set by LoRa relays on every
        // packet they re-broadcast, which would change the AAD and break decryption.
        val buf = ByteBuffer.allocate(AAD_LEN)
        buf.put(packet.version)
        buf.put(packet.type.value)
        buf.put(packet.msgId)
        buf.put(packet.senderId)
        buf.put(packet.receiverId)
        buf.putInt(packet.timestamp)
        return buf.array()
    }

    private fun deriveNonce(nonceBase: ByteArray, msgCounter: Long): ByteArray {
        val counterBytes = ByteBuffer.allocate(NONCE_LEN).apply {
            put(ByteArray(NONCE_LEN - 8))
            putLong(msgCounter)
        }.array()

        val nonce = ByteArray(NONCE_LEN)
        for (i in 0 until NONCE_LEN) {
            nonce[i] = (nonceBase[i].toInt() xor counterBytes[i].toInt()).toByte()
        }
        return nonce
    }
}
