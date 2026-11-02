package org.lokaldirenc.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

class NoiseException(message: String) : Exception(message)

/**
 * Noise şartnamesinin (rev. 34) birebir uygulaması, yeni bir protokol değil. Bağımsız bir
 * uygulamadan üretilmiş vektörlerle bayt bayt karşılaştırılır: tools/gen_noise_vectors.py.
 *
 * Bağlantılar için Noise_XXpsk0: kodu bilmeyen düğüm ilk mesajda elenir. Yanıtlayan hiçbir
 * şey göndermez, dolayısıyla statik anahtarı ve varlığı yoklamayla öğrenilemez.
 *
 * Mesh'teki özel mesajlar için Noise_X: tek yönlü, yalnız alıcının açabildiği mühür.
 * Gönderen şifreli içeride kalır, ara cihazlar kimden kime gittiğini göremez.
 */
internal object Noise {
    const val PROTOCOL = "Noise_XXpsk0_25519_ChaChaPoly_SHA256"
    const val SEAL_PROTOCOL = "Noise_X_25519_ChaChaPoly_SHA256"
    private const val TAG = 16
    private const val DH = 32

    class Cipher(private val key: ByteArray) {
        private var nonce = 0L

        private fun aead(encrypt: Boolean, ad: ByteArray): ChaCha20Poly1305 {
            val n = ByteArray(12)
            for (i in 0 until 8) n[4 + i] = (nonce ushr (8 * i)).toByte()
            return ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), TAG * 8, n, ad)) }
        }

        fun encrypt(plain: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
            val c = aead(true, ad)
            val out = ByteArray(plain.size + TAG)
            val len = c.processBytes(plain, 0, plain.size, out, 0)
            c.doFinal(out, len)
            nonce++
            return out
        }

        fun decrypt(cipher: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
            if (cipher.size < TAG) throw NoiseException("kısa şifreli metin")
            val c = aead(false, ad)
            val out = ByteArray(cipher.size - TAG)
            try {
                val len = c.processBytes(cipher, 0, cipher.size, out, 0)
                c.doFinal(out, len)
            } catch (_: InvalidCipherTextException) {
                throw NoiseException("kimlik doğrulama başarısız")
            }
            nonce++
            return out
        }
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        parts.forEach { mac.update(it, 0, it.size) }
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    private fun hkdf(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val temp = hmac(ck, ikm)
        val o1 = hmac(temp, byteArrayOf(1))
        val o2 = hmac(temp, o1, byteArrayOf(2))
        val o3 = hmac(temp, o2, byteArrayOf(3))
        return Triple(o1, o2, o3)
    }

    private fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(DH)
        try {
            X25519PrivateKeyParameters(priv, 0).generateSecret(X25519PublicKeyParameters(pub, 0), out, 0)
        } catch (_: IllegalStateException) {
            throw NoiseException("geçersiz açık anahtar")
        }
        return out
    }

    /** Noise SymmetricState. */
    private class Symmetric(protocol: String) {
        // Şartname: 32 bayta kadar ad özetlenmez, sıfırlarla doldurulur.
        var ck = protocol.toByteArray().let { if (it.size <= 32) it.copyOf(32) else sha256(it) }
        var h = ck.copyOf()
        var k: Cipher? = null

        fun mixHash(data: ByteArray) {
            h = sha256(h + data)
        }

        fun mixKey(ikm: ByteArray) {
            val (newCk, tempK, _) = hkdf(ck, ikm)
            ck = newCk
            k = Cipher(tempK)
        }

        fun mixKeyAndHash(ikm: ByteArray) {
            val (newCk, temp, tempK) = hkdf(ck, ikm)
            ck = newCk
            mixHash(temp)
            k = Cipher(tempK)
        }

        fun encryptAndHash(plain: ByteArray): ByteArray {
            val out = k?.encrypt(plain, h) ?: plain
            mixHash(out)
            return out
        }

        fun decryptAndHash(cipher: ByteArray): ByteArray {
            val out = k?.decrypt(cipher, h) ?: cipher
            mixHash(cipher)
            return out
        }
    }

    class Result(val send: Cipher, val recv: Cipher, val handshakeHash: ByteArray, val remoteStatic: ByteArray)

    fun generatePrivate(): ByteArray = X25519PrivateKeyParameters(SecureRandom()).encoded

    fun publicOf(priv: ByteArray): ByteArray = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    /**
     * XXpsk0:  -> psk, e    <- e, ee, s, es    -> s, se
     * [ephemeral] yalnız test vektörleri içindir.
     */
    class Handshake(
        private val initiator: Boolean,
        private val staticPriv: ByteArray,
        psk: ByteArray,
        prologue: ByteArray,
        ephemeral: ByteArray? = null,
    ) {
        private val st = Symmetric(PROTOCOL)
        private val ePriv = ephemeral ?: generatePrivate()
        private val sPub = publicOf(staticPriv)
        private var re: ByteArray? = null
        private var rs: ByteArray? = null
        private var step = 0

        init {
            require(psk.size == 32) { "PSK 32 bayt olmalı" }
            st.mixHash(prologue)
            st.mixKeyAndHash(psk)
        }

        val finished get() = step == 3

        fun write(payload: ByteArray = ByteArray(0)): ByteArray {
            check((step % 2 == 0) == initiator) { "sıra karşı tarafta" }
            val out = when (step) {
                0 -> {
                    val e = publicOf(ePriv)
                    st.mixHash(e)
                    st.mixKey(e)
                    e + st.encryptAndHash(payload)
                }
                1 -> {
                    val e = publicOf(ePriv)
                    st.mixHash(e)
                    st.mixKey(e)
                    st.mixKey(dh(ePriv, re!!))
                    val s = st.encryptAndHash(sPub)
                    st.mixKey(dh(staticPriv, re!!))
                    e + s + st.encryptAndHash(payload)
                }
                2 -> {
                    val s = st.encryptAndHash(sPub)
                    st.mixKey(dh(staticPriv, re!!))
                    s + st.encryptAndHash(payload)
                }
                else -> error("el sıkışma bitti")
            }
            step++
            return out
        }

        fun read(message: ByteArray): ByteArray {
            check((step % 2 == 0) != initiator) { "sıra bizde" }
            val payload = when (step) {
                0 -> {
                    need(message, DH + TAG)
                    val e = message.copyOfRange(0, DH)
                    re = e
                    st.mixHash(e)
                    st.mixKey(e)
                    st.decryptAndHash(message.copyOfRange(DH, message.size))
                }
                1 -> {
                    need(message, DH + DH + TAG + TAG)
                    val e = message.copyOfRange(0, DH)
                    re = e
                    st.mixHash(e)
                    st.mixKey(e)
                    st.mixKey(dh(ePriv, e))
                    val s = st.decryptAndHash(message.copyOfRange(DH, DH + DH + TAG))
                    rs = s
                    st.mixKey(dh(ePriv, s))
                    st.decryptAndHash(message.copyOfRange(DH + DH + TAG, message.size))
                }
                2 -> {
                    need(message, DH + TAG + TAG)
                    val s = st.decryptAndHash(message.copyOfRange(0, DH + TAG))
                    rs = s
                    st.mixKey(dh(ePriv, s))
                    st.decryptAndHash(message.copyOfRange(DH + TAG, message.size))
                }
                else -> error("el sıkışma bitti")
            }
            step++
            return payload
        }

        private fun need(message: ByteArray, min: Int) {
            if (message.size < min) throw NoiseException("kısa el sıkışma mesajı")
        }

        fun split(): Result {
            check(finished) { "el sıkışma bitmedi" }
            val (k1, k2, _) = hkdf(st.ck, ByteArray(0))
            val first = Cipher(k1)
            val second = Cipher(k2)
            return if (initiator) Result(first, second, st.h, rs!!) else Result(second, first, st.h, rs!!)
        }
    }

    /** X:  <- s   ...   -> e, es, s, ss. [ephemeral] yalnız test vektörleri içindir. */
    fun seal(recipientPub: ByteArray, senderPriv: ByteArray, prologue: ByteArray, plain: ByteArray, ephemeral: ByteArray? = null): ByteArray {
        val st = Symmetric(SEAL_PROTOCOL)
        st.mixHash(prologue)
        st.mixHash(recipientPub)
        val ePriv = ephemeral ?: generatePrivate()
        val e = publicOf(ePriv)
        st.mixHash(e)
        st.mixKey(dh(ePriv, recipientPub))
        val s = st.encryptAndHash(publicOf(senderPriv))
        st.mixKey(dh(senderPriv, recipientPub))
        return e + s + st.encryptAndHash(plain)
    }

    class Opened(val senderPub: ByteArray, val plain: ByteArray)

    /** Mühür bu alıcıya ait değilse [NoiseException] atar. */
    fun open(recipientPriv: ByteArray, prologue: ByteArray, sealed: ByteArray): Opened {
        if (sealed.size < DH + DH + TAG + TAG) throw NoiseException("kısa mühür")
        val st = Symmetric(SEAL_PROTOCOL)
        st.mixHash(prologue)
        st.mixHash(publicOf(recipientPriv))
        val e = sealed.copyOfRange(0, DH)
        st.mixHash(e)
        st.mixKey(dh(recipientPriv, e))
        val s = st.decryptAndHash(sealed.copyOfRange(DH, DH + DH + TAG))
        st.mixKey(dh(recipientPriv, s))
        return Opened(s, st.decryptAndHash(sealed.copyOfRange(DH + DH + TAG, sealed.size)))
    }
}
