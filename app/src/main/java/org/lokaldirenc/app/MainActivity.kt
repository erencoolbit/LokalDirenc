package org.lokaldirenc.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import org.lokaldirenc.core.MeshContact
import org.lokaldirenc.core.MeshEngine
import org.lokaldirenc.core.MeshMessage
import org.lokaldirenc.core.NetCode
import org.lokaldirenc.core.NetworkInfo
import org.lokaldirenc.core.Node
import org.lokaldirenc.core.Peer
import org.lokaldirenc.core.ShareServer
import org.lokaldirenc.core.Transfer
import org.lokaldirenc.core.TransferStatus
import org.lokaldirenc.core.isFinal
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import kotlin.concurrent.thread

private val Warn = Color(0xFFB3261E)
private val Caution = Color(0xFF9A6700)
private val Ok = Color(0xFF1B6E4F)

/**
 * Düğüm durumu Compose dışında yaşar. Bunu okuyan her ekran bu değeri okuyarak
 * düğüm değiştikçe ve eş listesinin süresi için saniyede bir yeniden çizilir.
 */
private val LocalTick = compositionLocalOf { 0 }

private sealed class Screen {
    data object Home : Screen()
    data class Network(val id: String) : Screen()
    data class Messages(val id: String) : Screen()
    data object Settings : Screen()
    data object ShareApp : Screen()
    data object Emergency : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(primary = Color(0xFF8FD3B6), onPrimary = Color(0xFF0B2A20), secondary = Color(0xFFF2C14E))
                } else {
                    lightColorScheme(primary = Color(0xFF14523E), onPrimary = Color.White, secondary = Color(0xFF8A6A00), surfaceVariant = Color(0xFFEDEFEA))
                },
            ) {
                val node = app.node.value
                val revision = app.revision.intValue
                var clock by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        clock++
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    if (node != null) {
                        CompositionLocalProvider(LocalTick provides revision + clock) { Root(app, node) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Root(app: App, node: Node) {
    LocalTick.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Messages -> Screen.Network(s.id)
            Screen.ShareApp -> Screen.Settings
            else -> Screen.Home
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(node, onOpen = { screen = Screen.Network(it) }, onSettings = { screen = Screen.Settings }, onEmergency = { screen = Screen.Emergency })
            Screen.Emergency -> EmergencyScreen(app) { screen = Screen.Home }
            is Screen.Network -> {
                val net = node.networks().firstOrNull { it.id == s.id }
                if (net == null) screen = Screen.Home else NetworkScreen(node, net, onMessages = { screen = Screen.Messages(s.id) }) { screen = Screen.Home }
            }
            is Screen.Messages -> {
                val net = node.networks().firstOrNull { it.id == s.id }
                if (net == null) screen = Screen.Home else MessagesScreen(app, node, net) { screen = Screen.Network(s.id) }
            }
            Screen.Settings -> SettingsScreen(app, node, onShare = { screen = Screen.ShareApp }) { screen = Screen.Home }
            Screen.ShareApp -> ShareAppScreen(node) { screen = Screen.Settings }
        }
    }
    node.transfers().firstOrNull { it.needsPairing }?.let { PairingDialog(node, it) }
        ?: node.transfers().firstOrNull { it.needsAccept }?.let { AcceptDialog(node, it) }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹ Geri") } else Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1)
        action?.invoke()
    }
    HorizontalDivider()
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// ---- Ana ekran ----

@Composable
private fun HomeScreen(node: Node, onOpen: (String) -> Unit, onSettings: () -> Unit, onEmergency: () -> Unit) {
    LocalTick.current
    val networks = node.networks().map { it to node.peers(it.id).size }
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { joining = it } }

    Header("Lokal Direnç", action = { TextButton(onClick = onSettings) { Text("Ayarlar") } })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("İnternet olmadan, aynı Wi-Fi veya hotspot üzerindeki cihazlarla şifreli dosya paylaşın.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Yeni ağ kur") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Ağ kodunun QR'ını okutun").setBeepEnabled(false).setOrientationLocked(false))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("QR ile katıl") }
                OutlinedButton(onClick = { joining = "" }, modifier = Modifier.weight(1f)) { Text("Kodla katıl") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onEmergency, colors = ButtonDefaults.buttonColors(containerColor = Warn), modifier = Modifier.fillMaxWidth()) {
                Text(if (SosState.active) "Yardım sinyali açık" else "Acil durum")
            }
            SectionTitle("Ağlarım")
            if (networks.isEmpty()) Muted("Henüz bir ağa katılmadınız.")
        }
        networks.forEach { (net, count) ->
            item(key = net.id) {
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpen(net.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(net.name, style = MaterialTheme.typography.titleMedium)
                        Muted(if (count == 0) "Yakında cihaz görünmüyor" else "Yakında $count cihaz")
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog("Yeni ağ", "Ağ adı (yalnız sizde görünür)", onDismiss = { creating = false }) { name ->
            val net = node.createNetwork(name)
            creating = false
            onOpen(net.id)
        }
    }
    joining?.let { prefill -> JoinDialog(node, prefill, onDismiss = { joining = null }, onJoined = { joining = null; onOpen(it) }) }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(onClick = { onDone(name) }) { Text("Tamam") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun JoinDialog(node: Node, prefill: String, onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    var code by remember { mutableStateOf(prefill) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ağa katıl") },
        text = {
            Column {
                OutlinedTextField(code, { code = it; error = null }, label = { Text("Ağ kodu") }, isError = error != null, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Ağ adı (yalnız sizde görünür)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    onJoined(node.joinNetwork(code, name).id)
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }) { Text("Katıl") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

// ---- Ağ ekranı ----

@Composable
private fun NetworkScreen(node: Node, net: NetworkInfo, onMessages: () -> Unit, onBack: () -> Unit) {
    LocalTick.current
    val context = LocalContext.current
    var showCode by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Triple<String, Int, String?>?>(null) }
    var manualHost by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf<Transfer?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = target
        if (uri != null && t != null) {
            thread(name = "ld-copy") {
                val result = runCatching { startSend(context, node, net.id, t, uri) }
                result.exceptionOrNull()?.let { e ->
                    (context as ComponentActivity).runOnUiThread { Toast.makeText(context, e.message ?: "Dosya okunamadı", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val t = saving
        val path = t?.storedPath
        if (uri != null && path != null) {
            thread(name = "ld-export") {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
                }.isSuccess
                (context as ComponentActivity).runOnUiThread { Toast.makeText(context, if (ok) "Kaydedildi" else "Kaydedilemedi", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Header(net.name, onBack, action = { TextButton(onClick = { showCode = true }) { Text("Ağ kodu") } })
    val peers = node.peers(net.id)
    val transfers = node.transfers().filter { it.networkId == net.id && it.fileName.isNotEmpty() }
    val messageCount = node.meshMessages(net.id).size
    val carried = node.meshCarried(net.id)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth().clickable(onClick = onMessages)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mesajlar", style = MaterialTheme.typography.titleMedium)
                    Muted("$messageCount mesaj · bu cihaz $carried zarf taşıyor")
                }
            }
            SectionTitle("Yakındaki cihazlar")
            if (peers.isEmpty()) Muted("Aynı Wi-Fi veya hotspot'a bağlı, bu ağın kodunu bilen cihazlar burada görünür.")
        }
        peers.forEach { peer ->
            item(key = "p" + peer.fingerprint) {
                PeerRow(peer, onSend = {
                    target = Triple(peer.host, peer.port, peer.fingerprint)
                    picker.launch(arrayOf("*/*"))
                }, onBlock = { peer.fingerprint?.let(node::blockPeer) })
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            ManualAddress(net, node, manualHost, { manualHost = it }) { host, port ->
                target = Triple(host, port, null)
                picker.launch(arrayOf("*/*"))
            }
            SectionTitle("Aktarımlar")
            if (transfers.isEmpty()) Muted("Henüz aktarım yok.")
        }
        transfers.forEach { t ->
            item(key = t.id) {
                TransferCard(node, t, onSave = { saving = t; exporter.launch(t.fileName) })
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            Muted("Kimliğiniz bu ağda: ${shortFp(net.fingerprint)}")
            TextButton(onClick = { confirmLeave = true }) { Text("Bu ağdan ayrıl", color = Warn) }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCode) CodeDialog(net) { showCode = false }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Ağdan ayrıl?") },
            text = { Text("Bu ağdaki kimliğiniz ve kayıtlarınız silinir. Aynı kodla yeniden katılırsanız yeni bir kimlikle görünürsünüz.") },
            confirmButton = { Button(onClick = { node.leaveNetwork(net.id); confirmLeave = false; onBack() }, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text("Ayrıl") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Vazgeç") } },
        )
    }
}

// ---- Mesajlar ----

@Composable
private fun MessagesScreen(app: App, node: Node, net: NetworkInfo, onBack: () -> Unit) {
    LocalTick.current
    val ownId = node.meshOwnId(net.id)
    val contacts = node.meshContacts(net.id)
    val messages = node.meshMessages(net.id).sortedByDescending { it.at }
    var recipient by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var blocking by remember { mutableStateOf<MeshContact?>(null) }
    var remote by remember { mutableStateOf(false) }
    val selected = contacts.firstOrNull { it.id == recipient }
    val context = LocalContext.current
    val loraReady = node.loraSink != null

    Column(Modifier.fillMaxSize()) {
    Header("Mesajlar", onBack)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), reverseLayout = true) {
        if (messages.isEmpty()) {
            item {
                Muted("Henüz mesaj yok. Mesajlar yakındaki üyeler üzerinden yayılır; bir cihaz alıp başka üyelere taşıyabilir.", Modifier.padding(vertical = 16.dp))
            }
        }
        messages.forEach { m ->
            item(key = m.id) {
                val contact = contacts.firstOrNull { it.id == m.fromId }
                MessageBubble(m, ownId, contact, contacts, verified = contact?.let { node.isContactVerified(net.id, it) } == true)
            }
        }
    }
    HorizontalDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SendMethods(app, node, net)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RecipientChip("Herkes", selected == null) { recipient = null }
            contacts.forEach { c ->
                RecipientChip(c.nick.ifEmpty { shortFp(c.id) }, selected?.id == c.id) { recipient = c.id }
            }
        }
        Spacer(Modifier.height(4.dp))
        val note = when {
            selected == null -> "Ağ kodunu bilen herkes okuyabilir ve imzanızı görür."
            node.isContactVerified(net.id, selected) -> "Yalnız ${selected.nick} açabilir. Kimliği kodla doğrulandı."
            else -> "Yalnız bu anahtarın sahibi açabilir. Kimliği doğrulanmadı: ${shortFp(selected.id)}"
        }
        Muted(note)
        if (loraReady) {
            Row(Modifier.fillMaxWidth().clickable { remote = !remote }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remote, { remote = it })
                Column {
                    Text("LoRa ile uzağa da gönder", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Muted("${node.loraBudgetLeft()} paket hakkı kaldı · özel mesaj çok daha fazla paket tutar")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(MeshEngine.MAX_TEXT) }, placeholder = { Text("Mesaj") }, modifier = Modifier.weight(1f), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button(enabled = text.isNotBlank(), onClick = {
                val viaLora = remote && loraReady
                val sent = if (selected == null) node.sendPublicMessage(net.id, text, viaLora) else node.sendDirectMessage(net.id, selected.id, text, viaLora)
                if (sent) text = ""
                if (sent && viaLora && node.lastLoraFailed) {
                    Toast.makeText(context, "LoRa'ya çıkmadı: hak doldu veya mesaj uzun. Yerel ağda gönderildi.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Yolla") }
        }
        if (selected != null) {
            TextButton(onClick = { blocking = selected }) { Text("Bu kişiyi engelle", color = Warn) }
        }
    }
    }
    blocking?.let { c ->
        AlertDialog(
            onDismissRequest = { blocking = null },
            title = { Text("Bu kişi engellensin mi?") },
            text = { Text("Mesajları bu cihazda gizlenir ve herkese açık olanları başkasına iletilmez. Mühürlü özel zarfların yazarı görünmediği için onlar taşınmaya devam edebilir. Yasaklanan biri yeni bir anahtarla geri dönebilir; sistem bunu engellemez.") },
            confirmButton = {
                Button(onClick = {
                    node.blockContact(c)
                    recipient = null
                    blocking = null
                }) { Text("Engelle") }
            },
            dismissButton = { TextButton(onClick = { blocking = null }) { Text("Vazgeç") } },
        )
    }
}

private enum class Method { WIFI, BLUETOOTH, LORA }

/** Mesajın hangi yollardan gidebileceği. Menzil ve hız ölçüm değil, tipik değerlerdir. */
@Composable
private fun SendMethods(app: App, node: Node, net: NetworkInfo) {
    var open by remember { mutableStateOf<Method?>(null) }
    val wifiOn = node.localAddresses().isNotEmpty()
    val bluetoothOn = app.bluetooth.status == BleTransport.Status.RUNNING
    val loraOn = app.lora.state == LoraLink.State.CONNECTED || node.loraSink != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MethodChip(R.drawable.ic_wifi, "Wi-Fi", "30-50 m · hızlı", wifiOn, Modifier.weight(1f)) { open = Method.WIFI }
        MethodChip(R.drawable.ic_bluetooth, "Bluetooth", "10-50 m · orta", bluetoothOn, Modifier.weight(1f)) { open = Method.BLUETOOTH }
        MethodChip(R.drawable.ic_antenna, "LoRa", "km'ler · çok yavaş", loraOn, Modifier.weight(1f)) { open = Method.LORA }
    }
    when (open) {
        Method.WIFI -> MethodDialog(R.drawable.ic_wifi, "Wi-Fi / hotspot", { open = null }) {
            Text(if (wifiOn) "Açık · ${node.peers(net.id).size} üye görünüyor" else "Kapalı: Wi-Fi'ye bağlanın veya hotspot açın", fontWeight = FontWeight.Medium)
            MethodFacts("Tipik 30-50 m", "Hızlı", "Mesaj ve dosya")
        }
        Method.BLUETOOTH -> MethodDialog(R.drawable.ic_bluetooth, "Bluetooth", { open = null }) {
            BluetoothSection(app, node)
            MethodFacts("Tipik 10-50 m", "Orta, kısa mesaj için yeterli", "Yalnız mesaj")
        }
        Method.LORA -> MethodDialog(R.drawable.ic_antenna, "LoRa", { open = null }) {
            LoraSection(app)
            MethodFacts("Şehirde 1-5 km, açık görüşte daha fazla", "Çok yavaş, saatte sınırlı yayın", "Yalnız kısa mesaj")
        }
        null -> Unit
    }
}

@Composable
private fun MethodChip(icon: Int, name: String, facts: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (active) Ok else MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(20.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(facts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(if (active) "Açık" else "Kapalı", style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MethodDialog(icon: Int, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(icon), null) },
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun MethodFacts(range: String, speed: String, carries: String) {
    Spacer(Modifier.height(12.dp))
    Text("Menzil: $range", style = MaterialTheme.typography.bodySmall)
    Text("Hız: $speed", style = MaterialTheme.typography.bodySmall)
    Text("Taşır: $carries", style = MaterialTheme.typography.bodySmall)
    Muted("Tipik değerlerdir, ölçüm değil. Ortama göre çok değişir.", Modifier.padding(top = 4.dp))
}

@Composable
private fun LoraSection(app: App) {
    LocalTick.current
    val lora = app.lora
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.bluetooth.missingPermissions().isEmpty()) lora.scan()
    }
    Muted("Telefonda LoRa radyosu yoktur. ESP32 + LoRa cihazına Bluetooth ile bağlanır. Cihaz ağ kodunuzu bilmez, içeriği okuyamaz.")
    Spacer(Modifier.height(8.dp))
    when (lora.state) {
        LoraLink.State.IDLE -> Button(onClick = {
            val missing = app.bluetooth.missingPermissions()
            if (missing.isEmpty()) lora.scan() else permissions.launch(missing.toTypedArray())
        }, modifier = Modifier.fillMaxWidth()) { Text("LoRa cihazı ara") }
        LoraLink.State.SCANNING -> {
            Text("Aranıyor…", style = MaterialTheme.typography.bodySmall)
            lora.devices().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("İstasyon ${d.device.address.takeLast(5)} (${d.rssi} dBm)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { lora.connect(d) }) { Text("Bağlan") }
                }
            }
            TextButton(onClick = { lora.disconnect() }) { Text("Vazgeç") }
        }
        LoraLink.State.CONNECTING -> Text("Bağlanıyor…", style = MaterialTheme.typography.bodySmall)
        LoraLink.State.CONNECTED -> {
            Text("Bağlı: ${lora.connectedName}", fontWeight = FontWeight.Medium, color = Ok)
            TextButton(onClick = { lora.disconnect() }) { Text("Bağlantıyı kes") }
        }
    }
}

// ---- Acil durum ----

@Composable
private fun EmergencyScreen(app: App, onBack: () -> Unit) {
    LocalTick.current
    val context = LocalContext.current
    var whistle by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }

    fun startSignal() {
        val intent = Intent(context, SosService::class.java).putExtra(SosService.EXTRA_WHISTLE, whistle)
        runCatching { context.startForegroundService(intent) }
            .onFailure { Toast.makeText(context, "Yardım sinyali başlatılamadı", Toast.LENGTH_LONG).show() }
    }
    val signalPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSignal() }
    val listenPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (app.bluetooth.missingPermissions().isEmpty()) {
            app.sosListener.start()
            listening = true
        }
    }
    DisposableEffect(Unit) {
        if (app.bluetooth.missingPermissions().isEmpty()) {
            app.sosListener.start()
            listening = true
        }
        onDispose { app.sosListener.stop() }
    }

    Header("Acil durum", onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
        }, colors = ButtonDefaults.buttonColors(containerColor = Warn), modifier = Modifier.fillMaxWidth()) { Text("112'yi ara") }
        Muted("Şebeke varsa önce bunu deneyin.", Modifier.padding(top = 4.dp))

        SectionTitle("Yardım sinyali")
        if (SosState.active) {
            Text("Yayında", color = Warn, fontWeight = FontWeight.Bold)
            val minutes = (System.currentTimeMillis() - SosState.startedAt) / 60_000
            Muted("$minutes dakikadır açık")
            Muted(
                when {
                    SosState.advertising && SosState.longRange -> "Bluetooth: normal ve uzun menzil yayın"
                    SosState.advertising -> "Bluetooth: normal yayın. Bu telefon uzun menzili desteklemiyor."
                    else -> "Bluetooth yayını yok: Bluetooth kapalı veya izin verilmedi"
                },
            )
            Muted(if (SosState.whistling) "Düdük: 30 saniyede bir üç kez" else "Düdük kapalı")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { context.stopService(Intent(context, SosService::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Yardım sinyalini durdur") }
        } else {
            Row(Modifier.fillMaxWidth().clickable { whistle = !whistle }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(whistle, { whistle = it })
                Text("Hoparlörden düdük de çalsın", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = {
                val missing = app.bluetooth.requiredPermissions().toMutableList()
                if (Build.VERSION.SDK_INT >= 33) missing += Manifest.permission.POST_NOTIFICATIONS
                val needed = missing.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (needed.isEmpty()) startSignal() else signalPermissions.launch(needed.toTypedArray())
            }, colors = ButtonDefaults.buttonColors(containerColor = Warn), modifier = Modifier.fillMaxWidth()) { Text("Yardım sinyali yayınla") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Bu özellik kurtarılacağınızı garanti etmez. Beton sinyali çok zayıflatır, kurtarma ekipleri bu sinyali dinliyor olmayabilir, pil ömrü de ölçülmedi. Yayın herkese açıktır ve kim olduğunuzu veya konumunuzu içermez. Düdük bulunduğunuz yeri belli eder.", color = Caution, style = MaterialTheme.typography.bodySmall)

        SectionTitle("Yakındaki yardım çağrıları")
        if (!listening) {
            OutlinedButton(onClick = { listenPermissions.launch(app.bluetooth.requiredPermissions().toTypedArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Dinlemeyi başlat") }
        }
        val heard = app.sosListener.current()
        if (listening && heard.isEmpty()) Muted("Dinleniyor. Bu ekran açıkken duyulan çağrılar burada görünür.")
        heard.forEach { h ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Warn.copy(alpha = 0.10f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Yardım çağrısı #${(h.session and 0xFFFF).toString(16).uppercase()}", fontWeight = FontWeight.SemiBold)
                    val strength = when {
                        h.rssi > -60 -> "güçlü"
                        h.rssi > -80 -> "orta"
                        else -> "zayıf"
                    }
                    Muted("Sinyal $strength (${h.rssi} dBm) · pil %${h.battery} · ${(System.currentTimeMillis() - h.lastSeen) / 1000} sn önce" + if (h.viaLora) " · LoRa istasyonundan" else "")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Muted("Çağrılar doğrulanamaz, herkes taklit edebilir. Sinyal gücü yalnız kaba bir ipucudur: yaklaşınca artması beklenir, kesin mesafe vermez. Bulursanız 112'ye bildirin; enkaza kendiniz girmeyin.")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecipientChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label, maxLines = 1) } else OutlinedButton(onClick = onClick) { Text(label, maxLines = 1) }
}

@Composable
private fun MessageBubble(m: MeshMessage, ownId: String?, contact: MeshContact?, contacts: List<MeshContact>, verified: Boolean) {
    val mine = m.outgoing || m.fromId == ownId
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            Modifier.fillMaxWidth(0.86f),
            colors = if (mine) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) else CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val who = if (mine) "Siz" else m.fromNick.ifEmpty { "Adsız" }
                val target = if (m.direct) {
                    if (mine) " → " + (contacts.firstOrNull { it.id == m.toId }?.nick ?: "özel") else " (size özel)"
                } else {
                    ""
                }
                Text(who + target, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                val meta = if (mine) {
                    "Yola çıktı · ulaştığı doğrulanamaz"
                } else {
                    val identity = if (verified) "Doğrulandı" else "Doğrulanmadı · ${shortFp(contact?.id ?: m.fromId)}"
                    "$identity · ${m.hops} atlama"
                }
                Muted(meta)
            }
        }
    }
}

private fun startSend(context: android.content.Context, node: Node, networkId: String, target: Triple<String, Int, String?>, uri: Uri) {
    var name = "dosya"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
        }
    }
    if (size > node.config.maxFileSize) throw IllegalArgumentException("Dosya 2 GB sınırını aşıyor")
    node.outbox.mkdirs()
    val copy = File.createTempFile("send", ".tmp", node.outbox)
    context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
    node.send(networkId, target.first, target.second, target.third, copy, name)
}

@Composable
private fun PeerRow(peer: Peer, onSend: () -> Unit, onBlock: () -> Unit) {
    var confirmBlock by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.nick.ifEmpty { "Adsız cihaz" }, style = MaterialTheme.typography.titleMedium)
                Muted((if (peer.trusted) "Doğrulandı · " else "Doğrulanmadı · ") + shortFp(peer.fingerprint ?: ""))
            }
            TextButton(onClick = { confirmBlock = true }) { Text("Engelle", color = Warn) }
            Button(onClick = onSend) { Text("Gönder") }
        }
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Bu kişi engellensin mi?") },
            text = { Text("Engel yalnız bu cihazda geçerlidir. Yasaklanan biri yeni bir anahtarla geri dönebilir; sistem bunu engellemez.") },
            confirmButton = { Button(onClick = { onBlock(); confirmBlock = false }) { Text("Engelle") } },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun ManualAddress(net: NetworkInfo, node: Node, value: String, onChange: (String) -> Unit, onSend: (String, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Elle adres ▲" else "Cihaz görünmüyor mu? Elle adres ▼") }
    if (open) {
        val own = node.localAddresses().joinToString(", ") { "$it:${net.port}" }.ifEmpty { "ağ bağlantısı yok" }
        Muted("Bu cihazın adresi: $own")
        Spacer(Modifier.height(4.dp))
        val parsed = value.trim().split(":").takeIf { it.size == 2 }?.let { p -> p[1].toIntOrNull()?.takeIf { it in 1..65535 }?.let { p[0] to it } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, onChange, label = { Text("adres:port") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = parsed != null, onClick = { parsed?.let { onSend(it.first, it.second) } }) { Text("Gönder") }
        }
    }
}

@Composable
private fun CodeDialog(net: NetworkInfo, onDismiss: () -> Unit) {
    val qr = remember(net.code) { qrBitmap(NetCode.qrPayload(net.code), 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ağ kodu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Image(qr.asImageBitmap(), "Ağ kodu QR", Modifier.size(232.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(NetCode.format(net.code), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text("Bu kod ortak sırdır. Bilen herkes eşit üye olur; sızarsa geri alınamaz, yeni ağ kurmanız gerekir.", color = Caution, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// ---- Aktarımlar ----

private fun statusText(t: Transfer) = when (t.status) {
    TransferStatus.PREPARING -> "Hazırlanıyor"
    TransferStatus.CONNECTING -> "Bağlanıyor"
    TransferStatus.PAIRING -> "Doğrulama bekleniyor"
    TransferStatus.AWAIT_ACCEPT -> "Kararınız bekleniyor"
    TransferStatus.WAITING_PEER -> "Alıcının kararı bekleniyor"
    TransferStatus.TRANSFERRING -> "Aktarılıyor"
    TransferStatus.VERIFYING -> "Doğrulanıyor"
    TransferStatus.DONE -> if (t.outgoing) "Gönderildi" else "Alındı, özet doğrulandı"
    TransferStatus.REJECTED -> t.error ?: "Reddedildi"
    TransferStatus.FAILED -> t.error ?: "Başarısız"
}

@Composable
private fun TransferCard(node: Node, t: Transfer, onSave: () -> Unit) {
    var confirmRelease by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = if (t.quarantined) CardDefaults.cardColors(containerColor = Warn.copy(alpha = 0.10f)) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text((if (t.outgoing) "↑ " else "↓ ") + t.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Muted("${sizeText(t.size)} · ${t.peerNick.ifEmpty { shortFp(t.peerFp) }}")
            val color = when (t.status) {
                TransferStatus.DONE -> Ok
                TransferStatus.FAILED, TransferStatus.REJECTED -> Warn
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(statusText(t), color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (!t.status.isFinal && t.chunksTotal > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { t.chunksDone.toFloat() / t.chunksTotal }, modifier = Modifier.fillMaxWidth())
                if (t.resumedChunks > 0) Muted("Kaldığı yerden sürüyor (${t.resumedChunks} parça zaten vardı)")
            }
            if (t.status == TransferStatus.DONE && t.resumedChunks > 0) Muted("Kaldığı yerden sürdürüldü")
            if (t.quarantined) Text("KARANTİNADA: açılmadı", color = Warn, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            t.warnings.forEach { Text("• $it", color = Caution, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (t.outgoing && t.status.isFinal && t.status != TransferStatus.DONE && t.sourcePath?.let { File(it).exists() } == true) {
                    TextButton(onClick = { node.retry(t.id) }) { Text("Yeniden dene") }
                }
                if (t.quarantined) TextButton(onClick = { confirmRelease = true }) { Text("Karantinadan çıkar") }
                if (!t.outgoing && t.status == TransferStatus.DONE && !t.quarantined && t.storedPath != null) {
                    TextButton(onClick = onSave) { Text("Cihaza kaydet") }
                }
                if (t.status.isFinal) TextButton(onClick = { node.deleteTransfer(t.id) }) { Text("Sil") }
            }
        }
    }
    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text("Karantinadan çıkarılsın mı?") },
            text = {
                Column {
                    Text("Bu dosya riskli görünüyor. Özetinin doğru olması zararsız olduğunu göstermez.")
                    Spacer(Modifier.height(8.dp))
                    t.warnings.forEach { Text("• $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Text("Yalnız gönderene güveniyorsanız devam edin. Uygulama dosyayı yine de açmaz.")
                }
            },
            confirmButton = { Button(onClick = { node.releaseFromQuarantine(t.id); confirmRelease = false }, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text("Riski kabul ediyorum") } },
            dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun PairingDialog(node: Node, t: Transfer) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Bu cihazı doğrulayın") },
        text = {
            Column {
                Text("${t.peerNick.ifEmpty { "Karşı cihaz" }} ile ilk kez eşleşiyorsunuz. Karşı ekrandaki kodun aynısını görüyor musunuz?")
                Spacer(Modifier.height(16.dp))
                Text(t.sas ?: "", fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                Muted("Kod farklıysa arada başka biri olabilir: reddedin.")
            }
        },
        confirmButton = { Button(onClick = { node.confirmPairing(t.id, true) }) { Text("Kod aynı") } },
        dismissButton = { TextButton(onClick = { node.confirmPairing(t.id, false) }) { Text("Kod farklı", color = Warn) } },
    )
}

@Composable
private fun AcceptDialog(node: Node, t: Transfer) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Dosya almak istiyor musunuz?") },
        text = {
            Column {
                Text(t.fileName, fontWeight = FontWeight.SemiBold)
                Muted("${sizeText(t.size)} · ${t.peerNick.ifEmpty { "Adsız" }} · ${shortFp(t.peerFp)}")
                t.warnings.forEach { Text("• $it", color = Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(8.dp))
                Muted("Dosya otomatik açılmaz. Riskli türler karantinaya alınır.")
            }
        },
        confirmButton = { Button(onClick = { node.respondOffer(t.id, true) }) { Text("Kabul et") } },
        dismissButton = { TextButton(onClick = { node.respondOffer(t.id, false) }) { Text("Reddet") } },
    )
}

// ---- Ayarlar ----

@Composable
private fun SettingsScreen(app: App, node: Node, onShare: () -> Unit, onBack: () -> Unit) {
    LocalTick.current
    var nick by remember { mutableStateOf(node.snapshot().nick) }
    var panic by remember { mutableStateOf(false) }
    val state = node.snapshot()
    val context = LocalContext.current
    Header("Ayarlar", onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Uygulamayı paylaş")
        Muted("Uygulaması olmayan birine kurulum paketini gönderin.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Uygulamayı paylaş") }

        SectionTitle("Takma ad")
        OutlinedTextField(nick, { nick = it.take(32); node.setNick(nick) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Muted("Ağ üyelerine görünür. Gerçek adınız olması gerekmez. Doğrulanmaz, herkes aynı adı seçebilir.")

        SectionTitle("Kayıt saklama süresi")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24 to "24 saat", 24 * 7 to "7 gün").forEach { (hours, label) ->
                if (state.retentionHours == hours) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { node.setRetentionHours(hours) }) { Text(label) }
            }
        }
        Muted("Süresi dolan geçmiş ile alınmış ve cihaza kaydedilmemiş dosyalar silinir.")

        SectionTitle("Engel listesi")
        if (state.blockedPeers.isEmpty() && state.blockedHashes.isEmpty()) Muted("Engellenen kişi veya dosya yok.")
        state.blockedPeers.forEach { fp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kişi ${shortFp(fp)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { node.unblockPeer(fp) }) { Text("Kaldır") }
            }
        }
        state.blockedHashes.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dosya ${h.take(12)}…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { node.unblockHash(h) }) { Text("Kaldır") }
            }
        }

        SectionTitle("Bluetooth mesh")
        BluetoothSection(app, node)

        SectionTitle("Paket kimliği")
        val signature = remember { AppPackage.signatureFingerprint(context) }
        Text(signature, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Muted("Uygulamayı aldığınız kişinin ekranındakiyle aynı olmalı.")

        SectionTitle("Koruma durumu")
        Muted("Yerel anahtar: ${node.protection}")
        Muted("Şifreleme: Noise XXpsk0 (kapatılamaz)")
        Muted("Bu prototip bağımsız güvenlik incelemesinden geçmedi. Hassas durumlarda güvenmeyin.")

        SectionTitle("Panik silme")
        Muted("Bu cihazdaki tüm ağları, kimlikleri, kayıtları ve alınan dosyaları siler. Başka cihazlardaki kopyalara ve cihaza kaydettiğiniz dosyalara dokunmaz.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { panic = true }, colors = ButtonDefaults.buttonColors(containerColor = Warn), modifier = Modifier.fillMaxWidth()) { Text("Her şeyi sil") }
        Spacer(Modifier.height(24.dp))
    }
    if (panic) {
        AlertDialog(
            onDismissRequest = { panic = false },
            title = { Text("Her şey silinsin mi?") },
            text = { Text("Bu işlem geri alınamaz.") },
            confirmButton = {
                Button(onClick = {
                    panic = false
                    app.panicWipe()
                    Toast.makeText(context, "Silindi", Toast.LENGTH_SHORT).show()
                    onBack()
                }, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text("Evet, sil") }
            },
            dismissButton = { TextButton(onClick = { panic = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun BluetoothSection(app: App, node: Node) {
    LocalTick.current
    val enabled = node.snapshot().bluetoothEnabled
    val ble = app.bluetooth
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        node.setBluetoothEnabled(ble.missingPermissions().isEmpty())
        app.syncBluetooth()
    }
    Muted("Wi-Fi olmadan, yakındaki üyelerle mesajları Bluetooth üzerinden eşitler. Dosyalar bu yoldan gitmez. Pil tüketir ve yalnız uygulama açıkken çalışır.")
    Spacer(Modifier.height(8.dp))
    if (enabled) {
        OutlinedButton(onClick = { node.setBluetoothEnabled(false); app.syncBluetooth() }, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth mesh'i kapat") }
    } else {
        Button(onClick = {
            val missing = ble.missingPermissions()
            if (missing.isEmpty()) {
                node.setBluetoothEnabled(true)
                app.syncBluetooth()
            } else {
                permissions.launch(missing.toTypedArray())
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth mesh'i aç") }
    }
    val text = when (ble.status) {
        BleTransport.Status.OFF -> "Kapalı"
        BleTransport.Status.RUNNING -> "Açık · yakında ${ble.nearby} üye · ${ble.completedSyncs} eşitleme"
        BleTransport.Status.UNSUPPORTED_ANDROID -> "Bu cihazda çalışmaz: Android 10 veya üstü gerekir"
        BleTransport.Status.NO_ADAPTER -> "Bu cihazda Bluetooth yok"
        BleTransport.Status.ADAPTER_OFF -> "Telefonun Bluetooth'u kapalı. Açılınca kendiliğinden devam eder"
        BleTransport.Status.NO_PERMISSION -> "Bluetooth izni verilmedi"
        BleTransport.Status.NO_ADVERTISING -> "Bu cihaz Bluetooth reklamını desteklemiyor"
    }
    Spacer(Modifier.height(4.dp))
    Muted("Durum: $text")
}

// ---- Uygulamayı paylaş ----

@Composable
private fun ShareAppScreen(node: Node, onBack: () -> Unit) {
    LocalTick.current
    val context = LocalContext.current
    val apk = remember { AppPackage.sourceApk(context) }
    val fingerprint = remember { AppPackage.signatureFingerprint(context) }
    val fileHash = remember(apk) { apk?.let { AppPackage.fileHash(it) } }
    val addresses = node.localAddresses()
    var address by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<ShareServer?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun stopServer() {
        server?.stop()
        server = null
    }

    fun startServer(host: String) {
        stopServer()
        val file = apk ?: return
        failure = null
        try {
            val name = AppPackage.fileName(context)
            // Süre dolunca ekran bir sonraki çizimde kapalı durumu gösterir.
            val created = ShareServer(file, name, AppPackage.landingPage(name), InetAddress.getByName(host))
            created.start()
            server = created
            address = host
        } catch (_: IOException) {
            failure = "İndirme adresi açılamadı. Bağlantınızı kontrol edin."
        }
    }

    // Ekrandan çıkınca sunucu açık kalmasın.
    DisposableEffect(Unit) { onDispose { server?.stop() } }
    if (server != null && server?.remainingMs == 0L) server = null

    Header("Uygulamayı paylaş", onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (apk == null) {
            Spacer(Modifier.height(16.dp))
            Text("Bu kurulum tek parça değil, paylaşılamıyor. APK dosyasından kurulmuş sürümü kullanın.", color = Warn)
        } else {
            SectionTitle("İnternet varsa veya yakın paylaşım açıksa")
            Muted("Quick Share, e-posta veya başka bir uygulamayla gönderin. Bazı telefonlar APK'yı Bluetooth'tan kabul etmeyebilir.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                runCatching { context.startActivity(AppPackage.shareIntent(context, apk)) }
                    .onFailure { Toast.makeText(context, "Paylaşım başlatılamadı", Toast.LENGTH_LONG).show() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Paylaşım menüsünü aç") }

            SectionTitle("İnternet yoksa: QR ile indirme")
            Muted("Telefonunuzun hotspot'unu açın. Karşı taraf ona bağlanıp QR'ı kamerayla okutur ve tarayıcıdan indirir.")
            Spacer(Modifier.height(8.dp))
            val current = server
            if (current == null) {
                if (addresses.isEmpty()) {
                    Text("Önce hotspot açın veya bir Wi-Fi'ye bağlanın.", color = Caution, style = MaterialTheme.typography.bodySmall)
                }
                addresses.forEach { host ->
                    Button(onClick = { startServer(host) }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) { Text("$host üzerinden aç") }
                }
                failure?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
            } else {
                val qr = remember(current) { qrBitmap(current.url, 640) }
                Box(Modifier.align(Alignment.CenterHorizontally).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Image(qr.asImageBitmap(), "İndirme adresi QR", Modifier.size(232.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(current.url, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(4.dp))
                val minutes = current.remainingMs / 60_000
                val seconds = current.remainingMs / 1000 % 60
                Muted("%d:%02d sonra kapanır · %d indirme başladı".format(minutes, seconds, current.downloads), Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { stopServer() }, modifier = Modifier.fillMaxWidth()) { Text("İndirmeyi kapat") }
                Spacer(Modifier.height(4.dp))
                Muted("Bu ekrandan çıkınca indirme de kapanır.")
            }
            Spacer(Modifier.height(8.dp))
            Text("Bu indirme şifresizdir. Aynı ağdaki biri dosyayı değiştirebilir. En güvenlisi, kendi hotspot'unuza yalnız tanıdığınız kişiyi almaktır. Bu yol yalnız uygulama içindir, kişisel dosya için kullanılamaz.", color = Caution, style = MaterialTheme.typography.bodySmall)

            SectionTitle("Paket kimliği")
            Muted("Karşı tarafa kurulumdan sonra Ayarlar > Paket kimliği'ni bununla karşılaştırmasını söyleyin. Değiştirilmiş bir uygulama yalan söyleyebileceği için bu tek başına kanıt değildir.")
            Spacer(Modifier.height(4.dp))
            Text("İmza", style = MaterialTheme.typography.labelMedium)
            Text(fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text("Dosya SHA-256", style = MaterialTheme.typography.labelMedium)
            Text(fileHash ?: "", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Muted("Bu sürüm geliştirici imzalıdır, resmi yayıncı kimliği taşımaz. Karşı taraf bilinmeyen kaynaktan yüklemeye kendisi izin vermelidir.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun shortFp(fp: String) = if (fp.length < 12) fp else fp.take(12).chunked(4).joinToString(" ")

private fun sizeText(size: Long): String = when {
    size >= 1024L * 1024 * 1024 -> String.format(Locale("tr"), "%.1f GB", size / (1024.0 * 1024 * 1024))
    size >= 1024L * 1024 -> String.format(Locale("tr"), "%.1f MB", size / (1024.0 * 1024))
    size >= 1024 -> "${size / 1024} KB"
    else -> "$size B"
}
