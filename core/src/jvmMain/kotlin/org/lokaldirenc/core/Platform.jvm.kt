package org.lokaldirenc.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

internal actual val platformCrypto: CryptoProvider = BouncyCastleCrypto

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** Android ve masaüstü JVM için. */
internal object BouncyCastleCrypto : CryptoProvider {
    private const val TAG_BITS = 128
    private const val TAG = 16
    private val random = SecureRandom()

    override fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        parts.forEach { mac.update(it, 0, it.size) }
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    override fun hkdfSha256(ikm: ByteArray, info: ByteArray, size: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, null, info))
        return ByteArray(size).also { hkdf.generateBytes(it, 0, size) }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, ad: ByteArray) =
        ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, ad)) }

    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plain: ByteArray): ByteArray {
        val c = aead(true, key, nonce, ad)
        val out = ByteArray(plain.size + TAG)
        val len = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, len)
        return out
    }

    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, cipher: ByteArray): ByteArray? {
        if (cipher.size < TAG) return null
        val c = aead(false, key, nonce, ad)
        val out = ByteArray(cipher.size - TAG)
        return try {
            val len = c.processBytes(cipher, 0, cipher.size, out, 0)
            c.doFinal(out, len)
            out
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    override fun x25519GeneratePrivate(): ByteArray = X25519PrivateKeyParameters(random).encoded

    override fun x25519PublicOf(privateKey: ByteArray): ByteArray = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    override fun x25519Dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return try {
            X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(publicKey, 0), out, 0)
            out
        } catch (_: IllegalStateException) {
            null
        }
    }

    override fun ed25519GeneratePrivate(): ByteArray = Ed25519PrivateKeyParameters(random).encoded

    override fun ed25519PublicOf(privateKey: ByteArray): ByteArray = Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        signer.verifySignature(signature)
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IndexOutOfBoundsException) {
        false
    }
}
