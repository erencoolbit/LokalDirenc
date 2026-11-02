package org.lokaldirenc.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.lokaldirenc.core.Node
import java.io.File
import java.security.SecureRandom

/**
 * Düğüm işlem ömrü boyunca yaşar. Ön plan servisi prototip kapsamında yoktur:
 * sistem uygulamayı arka planda kapatırsa aktarım kesilir, sonra sürdürülebilir.
 */
class App : Application() {
    val node = mutableStateOf<Node?>(null)

    /** Düğümde her değişiklikte artar, arayüzü yeniden çizdirir. */
    val revision = mutableIntStateOf(0)

    private var multicast: WifiManager.MulticastLock? = null

    val bluetooth by lazy { BleTransport(this) { revision.intValue++ } }
    val lora by lazy { LoraLink(this, this) }
    val sosListener by lazy { SosListener(this) }

    /** Kullanıcı açtıysa başlatır. Onaysız ve izinsiz çalışmaz. */
    fun syncBluetooth() {
        val n = node.value ?: return
        if (n.snapshot().bluetoothEnabled) bluetooth.start(n) else bluetooth.stop()
        revision.intValue++
    }

    override fun onCreate() {
        super.onCreate()
        // Bu kilit olmadan birçok cihaz Wi-Fi yayın paketlerini uygulamaya iletmez.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("lokaldirenc-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        startNode()
    }

    private fun startNode() {
        val n = Node(File(noBackupFilesDir, "node"), KeystoreStateStore(File(noBackupFilesDir, "state.enc")))
        n.onChange = { revision.intValue++ }
        n.start()
        if (n.snapshot().nick.isEmpty()) {
            n.setNick("Cihaz-" + SecureRandom().nextInt(10000).toString().padStart(4, '0'))
        }
        node.value = n
        syncBluetooth()
    }

    fun panicWipe() {
        bluetooth.stop()
        lora.disconnect()
        sosListener.stop()
        stopService(Intent(this, SosService::class.java))
        node.value?.panicWipe()
        cacheDir.deleteRecursively()
        startNode()
        revision.intValue++
    }
}
