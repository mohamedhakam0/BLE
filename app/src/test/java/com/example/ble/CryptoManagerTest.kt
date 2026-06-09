package com.example.ble

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

class CryptoManagerTest {

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = (kp.private as X25519PrivateKeyParameters).encoded
        val pub = (kp.public as X25519PublicKeyParameters).encoded
        return priv to pub
    }

    private val senderIdA = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val senderIdB = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

    private fun buildTestPacket(
        senderId: ByteArray,
        receiverId: ByteArray,
        msgId: ByteArray,
        payload: ByteArray,
        authTag: ByteArray
    ) = MeshPacket(
        version    = 0x01,
        type       = PacketType.CHAT,
        msgId      = msgId,
        senderId   = senderId,
        receiverId = receiverId,
        ttl        = 6,
        hopCount   = 0,
        timestamp  = 1000,
        payloadLen = payload.size.toByte(),
        authTag    = authTag,
        payload    = payload
    )

    @Test
    fun testRoundTrip() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()

        val secretAB = CryptoManager.computeSharedSecret(privA, pubB)
        val secretBA = CryptoManager.computeSharedSecret(privB, pubA)
        assertArrayEquals("ECDH shared secrets must match", secretAB, secretBA)

        val keyMatA = CryptoManager.deriveSessionKey(secretAB, senderIdA, senderIdB)
        val keyMatB = CryptoManager.deriveSessionKey(secretBA, senderIdA, senderIdB)
        assertArrayEquals("Derived key material must match", keyMatA, keyMatB)

        val aesKey    = keyMatA.copyOfRange(0, 16)
        val nonceBase = keyMatA.copyOfRange(16, 28)
        val plaintext = "Hello from A to B".toByteArray(Charsets.UTF_8)
        val msgCounter = 42L
        val msgId = ByteArray(8).also {
            var v = msgCounter
            for (i in 7 downTo 0) { it[i] = (v and 0xFF).toByte(); v = v shr 8 }
        }

        val packet = buildTestPacket(senderIdA, senderIdB, msgId, plaintext, ByteArray(16))
        val aad = CryptoManager.buildAad(packet)

        val encrypted = CryptoManager.encrypt(aesKey, nonceBase, msgCounter, plaintext, aad)
        assertEquals("Auth tag must be 16 bytes", 16, encrypted.authTag.size)

        val decrypted = CryptoManager.decrypt(aesKey, nonceBase, msgCounter, encrypted.ciphertext, encrypted.authTag, aad)
        assertNotNull("Decryption must succeed", decrypted)
        assertArrayEquals("Decrypted text must match original", plaintext, decrypted)
    }

    @Test
    fun testTamperDetection() {
        val (privA, pubA) = generateKeyPair()
        val (_, pubB) = generateKeyPair()

        val secret = CryptoManager.computeSharedSecret(privA, pubB)
        val keyMat = CryptoManager.deriveSessionKey(secret, senderIdA, senderIdB)
        val aesKey    = keyMat.copyOfRange(0, 16)
        val nonceBase = keyMat.copyOfRange(16, 28)

        val plaintext = "secret message".toByteArray(Charsets.UTF_8)
        val msgCounter = 99L
        val msgId = ByteArray(8).also {
            var v = msgCounter
            for (i in 7 downTo 0) { it[i] = (v and 0xFF).toByte(); v = v shr 8 }
        }

        val packet = buildTestPacket(senderIdA, senderIdB, msgId, plaintext, ByteArray(16))
        val aad = CryptoManager.buildAad(packet)

        val encrypted = CryptoManager.encrypt(aesKey, nonceBase, msgCounter, plaintext, aad)

        val tampered = encrypted.ciphertext.clone()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        val result = CryptoManager.decrypt(aesKey, nonceBase, msgCounter, tampered, encrypted.authTag, aad)
        assertNull("Tampered ciphertext must fail decryption", result)
    }

    @Test
    fun testAadTamperDetection() {
        val (privA, _) = generateKeyPair()
        val (_, pubB) = generateKeyPair()

        val secret = CryptoManager.computeSharedSecret(privA, pubB)
        val keyMat = CryptoManager.deriveSessionKey(secret, senderIdA, senderIdB)
        val aesKey    = keyMat.copyOfRange(0, 16)
        val nonceBase = keyMat.copyOfRange(16, 28)

        val plaintext = "authenticated data test".toByteArray(Charsets.UTF_8)
        val msgCounter = 7L
        val msgId = ByteArray(8).also {
            var v = msgCounter
            for (i in 7 downTo 0) { it[i] = (v and 0xFF).toByte(); v = v shr 8 }
        }

        val packet = buildTestPacket(senderIdA, senderIdB, msgId, plaintext, ByteArray(16))
        val aad = CryptoManager.buildAad(packet)

        val encrypted = CryptoManager.encrypt(aesKey, nonceBase, msgCounter, plaintext, aad)

        val badAad = aad.clone()
        badAad[0] = (badAad[0].toInt() xor 0xFF).toByte()

        val result = CryptoManager.decrypt(aesKey, nonceBase, msgCounter, encrypted.ciphertext, encrypted.authTag, badAad)
        assertNull("Modified AAD must fail decryption", result)
    }

    @Test
    fun testRelayedPacketDecrypts() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()

        val secret = CryptoManager.computeSharedSecret(privA, pubB)
        val keyMat = CryptoManager.deriveSessionKey(secret, senderIdA, senderIdB)
        val aesKey    = keyMat.copyOfRange(0, 16)
        val nonceBase = keyMat.copyOfRange(16, 28)

        val plaintext = "relayed message".toByteArray(Charsets.UTF_8)
        val msgCounter = 55L
        val msgId = ByteArray(8).also {
            var v = msgCounter
            for (i in 7 downTo 0) { it[i] = (v and 0xFF).toByte(); v = v shr 8 }
        }

        val original = buildTestPacket(senderIdA, senderIdB, msgId, plaintext, ByteArray(16))
        val aadSend = CryptoManager.buildAad(original)
        val encrypted = CryptoManager.encrypt(aesKey, nonceBase, msgCounter, plaintext, aadSend)

        val relayed = original.copy(ttl = 4, hopCount = 2)
        val aadRecv = CryptoManager.buildAad(relayed)

        val secretRecv = CryptoManager.computeSharedSecret(privB, pubA)
        val keyMatRecv = CryptoManager.deriveSessionKey(secretRecv, senderIdA, senderIdB)
        val aesKeyRecv    = keyMatRecv.copyOfRange(0, 16)
        val nonceBaseRecv = keyMatRecv.copyOfRange(16, 28)

        val decrypted = CryptoManager.decrypt(aesKeyRecv, nonceBaseRecv, msgCounter, encrypted.ciphertext, encrypted.authTag, aadRecv)
        assertNotNull("Relayed packet (modified ttl/hopCount) must still decrypt", decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testNonceUniqueness() {
        val (privA, _) = generateKeyPair()
        val (_, pubB) = generateKeyPair()

        val secret = CryptoManager.computeSharedSecret(privA, pubB)
        val keyMat = CryptoManager.deriveSessionKey(secret, senderIdA, senderIdB)
        val aesKey    = keyMat.copyOfRange(0, 16)
        val nonceBase = keyMat.copyOfRange(16, 28)

        val plaintext = "same message both times".toByteArray(Charsets.UTF_8)
        val aad = ByteArray(24)

        val enc1 = CryptoManager.encrypt(aesKey, nonceBase, 1L, plaintext, aad)
        val enc2 = CryptoManager.encrypt(aesKey, nonceBase, 2L, plaintext, aad)

        assertFalse(
            "Different msgCounters must produce different ciphertexts",
            enc1.ciphertext.contentEquals(enc2.ciphertext)
        )
    }
}
