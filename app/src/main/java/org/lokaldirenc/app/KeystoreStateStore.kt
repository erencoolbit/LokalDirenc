package org.lokaldirenc.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import org.lokaldirenc.core.StateStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Durum (kimlik anahtarları, ağ kodları, geçmiş, engel listesi) AES-256-GCM ile şifreli
 * tek dosyadır; anahtar Android Keystore'dan çıkamaz. Anahtar silinince dosya okunamaz
 * hale gelir (kriptografik silme). Prototipte SQLCipher yerine bu yeterlidir.
 */
class KeystoreStateStore(private val file: File) : StateStore {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun load(): ByteArray? {
        if (!file.exists()) return null
        return runCatching {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV))
            cipher.doFinal(blob, IV, blob.size - IV)
        }.getOrNull()
    }

    override fun save(data: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV) { "beklenmeyen IV" }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(iv + cipher.doFinal(data))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "durum yazılamadı" }
        }
    }

    override fun destroy() {
        keyStore.deleteEntry(ALIAS)
        file.delete()
    }

    override val protection: String
        get() = runCatching {
            val factory = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            val info = factory.getKeySpec(key(), KeyInfo::class.java) as KeyInfo
            val hardware = if (Build.VERSION.SDK_INT >= 31) {
                info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
            if (hardware) "Android Keystore (donanım destekli)" else "Android Keystore (yalnız yazılım)"
        }.getOrDefault("Android Keystore")

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "lokaldirenc_state_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
