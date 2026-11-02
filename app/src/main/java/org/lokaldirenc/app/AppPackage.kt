package org.lokaldirenc.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.lokaldirenc.core.ShareServer
import java.io.File
import java.security.MessageDigest

/** Uygulamanın kendi kurulum paketine erişim (Bölüm 14.8). */
object AppPackage {
    private const val SHARE_DIR = "share"

    /** Paylaşılabilir tek parça APK; parçalı kurulumda (Android Studio'dan çalıştırma gibi) null. */
    fun sourceApk(context: Context): File? {
        val info = context.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return File(info.sourceDir).takeIf { it.exists() }
    }

    fun fileName(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        return "LokalDirenc-$version.apk"
    }

    /** İmzalayan sertifikanın SHA-256 özeti: dosyadan bağımsız olarak yayıncıyı tanımlar. */
    fun signatureFingerprint(context: Context): String {
        val pm = context.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        } ?: return "bilinmiyor"
        return group(MessageDigest.getInstance("SHA-256").digest(cert))
    }

    fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return group(digest.digest())
    }

    private fun group(bytes: ByteArray) =
        bytes.joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    /** Telefonun paylaşım menüsü: Quick Share, Bluetooth, e-posta vb. Hedefi kullanıcı seçer. */
    fun shareIntent(context: Context, apk: File): Intent {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val copy = File(dir, fileName(context))
        if (copy.length() != apk.length()) apk.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType(ShareServer.APK_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Lokal Direnç'i paylaş")
    }

    /** Yerel indirme sayfası. Şifresiz gider, bu yüzden güvenlik iddiası taşımaz. */
    fun landingPage(fileName: String): String = """
        <!doctype html><html lang="tr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Lokal Direnç</title>
        <style>
        body{font:16px/1.5 system-ui,sans-serif;margin:0 auto;max-width:34rem;padding:24px 16px;color:#1c1c1c;background:#fff}
        h1{font-size:1.5rem;margin:0 0 8px}
        a.b{display:block;background:#14523e;color:#fff;text-align:center;padding:14px;border-radius:24px;text-decoration:none;font-weight:600;margin:20px 0}
        li{margin-bottom:8px}.n{font-size:.9rem;color:#555}
        @media (prefers-color-scheme:dark){body{background:#141414;color:#eee}.n{color:#aaa}a.b{background:#8fd3b6;color:#0b2a20}}
        </style></head><body>
        <h1>Lokal Direnç</h1>
        <p>İnternet olmadan şifreli mesaj ve dosya paylaşımı. Yalnız Android 8 ve üstü içindir.</p>
        <a class="b" href="$fileName">Uygulamayı indir</a>
        <ol>
        <li>Tarayıcı, bağlantı şifresiz olduğu için güvenli indirilemedi uyarısı verebilir. Yalnız dosyayı güvendiğiniz kişiden alıyorsanız tutmayı seçin.</li>
        <li>İndirilen dosyayı açın.</li>
        <li>Telefon sorarsa bu kaynaktan yüklemeye izin verin.</li>
        <li>Kurduktan sonra paylaşan kişiden ağ kodunu alın.</li>
        </ol>
        <p class="n">Bu sayfa şifresiz gelir. Yalnız güvendiğiniz kişiden ve onun hotspot'una bağlıyken indirin. Prototiptir, bağımsız güvenlik incelemesinden geçmemiştir.</p>
        </body></html>
    """.trimIndent()
}
