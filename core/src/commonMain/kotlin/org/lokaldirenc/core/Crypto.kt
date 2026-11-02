package org.lokaldirenc.core

/**
 * Protokolün kullandığı şifreleme ilkelleri. Her platform kendi uygulamasını verir; ortak kod
 * belirli bir kitaplığa bağlı değildir. Yeni bir uygulama, resources altındaki protokol
 * vektörlerinden bayt bayt geçmelidir.
 */
interface CryptoProvider {
    fun randomBytes(size: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray

    /** RFC 5869, tuz boş. */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, size: Int): ByteArray

    /** ChaCha20-Poly1305. Sonuç: şifreli metin + 16 bayt etiket. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plain: ByteArray): ByteArray

    /** Doğrulama başarısızsa null. */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, cipher: ByteArray): ByteArray?

    fun x25519GeneratePrivate(): ByteArray
    fun x25519PublicOf(privateKey: ByteArray): ByteArray

    /** Geçersiz açık anahtarda null. */
    fun x25519Dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray?

    fun ed25519GeneratePrivate(): ByteArray
    fun ed25519PublicOf(privateKey: ByteArray): ByteArray
    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /** Bozuk anahtar veya imzada false. */
    fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean
}

internal expect val platformCrypto: CryptoProvider

internal expect fun currentTimeMillis(): Long

internal fun sha256(data: ByteArray): ByteArray = platformCrypto.sha256(data)
