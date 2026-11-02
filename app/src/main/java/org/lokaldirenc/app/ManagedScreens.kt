package org.lokaldirenc.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.lokaldirenc.core.EntryMode
import org.lokaldirenc.core.GovernanceLog
import org.lokaldirenc.core.GovernanceReject
import org.lokaldirenc.core.GovernanceTypes
import org.lokaldirenc.core.ManagedLinks
import org.lokaldirenc.core.ManagementMode
import org.lokaldirenc.core.NetworkInfo
import org.lokaldirenc.core.Node
import org.lokaldirenc.core.Proposal
import org.lokaldirenc.core.RetirePayload
import org.lokaldirenc.core.RevokePayload
import org.lokaldirenc.core.TermPayload
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun modeName(mode: EntryMode) = when (mode) {
    EntryMode.OPEN -> "Açık: bağlantıyı alan herkes girer"
    EntryMode.MIXED -> "Karma: herkes okur, onaylı üyeler yazar"
    EntryMode.INVITE_ONLY -> "Davetli: yalnız davetle girilir"
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lokal Direnç", text))
    Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
}

/** Telefonlar arasında elden geçen bilgi: QR, kopyalama ve istenirse düz metin. */
@Composable
private fun ShareBlock(text: String, warning: String) {
    val context = LocalContext.current
    val qr = remember(text) { qrBitmap(text, 800) }
    var showText by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
            Image(qr.asImageBitmap(), "QR", Modifier.size(248.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy(context, text) }) { Text("Kopyala") }
            TextButton(onClick = { showText = !showText }) { Text(if (showText) "Metni gizle" else "Metni göster") }
        }
        if (showText) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(warning, color = Caution, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Klavye kodları düzeltmeye kalkmasın. */
internal val CodeKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)

private fun scanOptions(prompt: String) = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false)

/** Yönetilen ağ kurulumu: tek cihaz eşiği dolduramaz, en az iki telefon gerekir (Bölüm 11.3). */
@Composable
internal fun ManagedSetupScreen(node: Node, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(EntryMode.OPEN) }
    var others by remember { mutableStateOf("") }
    var outgoing by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf<NetworkInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val code = remember { node.adminCode() }

    fun openPackage(text: String) {
        error = null
        val bundle = ManagedLinks.parseBundle(text)
        if (bundle == null) {
            error = "Paket okunamadı"
            return
        }
        try {
            val result = node.signBundle(bundle)
            outgoing = ManagedLinks.encode(result.bundle)
            finished = result.network
            incoming = ""
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }
    val othersScanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { others = it } }
    val packageScanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { openPackage(it) } }

    Header("Yönetilen ağ", onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Muted("Kararlar en az iki yöneticinin imzasıyla geçer. Tek kişi ağı kuramaz ve yönetemez. Kurmak için iki telefon gerekir. Yöneticiler kimsenin verisini silemez, mesajlarını okuyamaz.")

        var tab by remember { mutableStateOf(0) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Yeni ağ", "Paket imzala", "Kodum").forEachIndexed { i, label ->
                if (tab == i) {
                    Button(onClick = {}, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(label, maxLines = 1, fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { tab = i }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(label, maxLines = 1, fontSize = 13.sp) }
                }
            }
        }
        if (tab == 2) {
        SectionTitle("Yönetici kodunuz")
        Muted("Kurucu değilseniz bu kodu kurucuya verin. Sır değildir.")
        Spacer(Modifier.height(4.dp))
        ShareBlock(code, "Bu kod kimliğinizi söyler, ağa yetki vermez.")

        }
        if (tab == 1) {
        SectionTitle("Gelen paketi imzala")
        Muted("Diğer yöneticiden gelen paketi okutun veya yapıştırın.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { packageScanner.launch(scanOptions("İmza paketini okutun")) }) { Text("QR okut") }
        }
        OutlinedTextField(incoming, { incoming = it }, label = { Text("Paket") }, maxLines = 3, keyboardOptions = CodeKeyboard, modifier = Modifier.fillMaxWidth())
        Button(enabled = incoming.isNotBlank(), onClick = { openPackage(incoming) }, modifier = Modifier.fillMaxWidth()) { Text("Paketi imzala") }

        }
        if (tab == 0) {
        SectionTitle("Yeni ağ başlat")
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Ağ adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        EntryMode.entries.forEach { m ->
            if (m == mode) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(modeName(m), fontSize = 12.sp) } else OutlinedButton(onClick = { mode = m }, modifier = Modifier.fillMaxWidth()) { Text(modeName(m), fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { othersScanner.launch(scanOptions("Diğer yöneticinin kodunu okutun")) }) { Text("Yönetici kodu okut") }
        OutlinedTextField(others, { others = it }, label = { Text("Diğer yönetici kodları (virgülle)") }, maxLines = 3, keyboardOptions = CodeKeyboard, modifier = Modifier.fillMaxWidth())
        Muted("Üç yönetici önerilir: biri telefonunu kaybederse diğer ikisi karar almaya devam eder. İki yöneticiyle biri kaybolursa ağ yönetilemez.")
        Button(enabled = name.isNotBlank() && others.isNotBlank(), onClick = {
            error = null
            try {
                outgoing = ManagedLinks.encode(node.startManagedNetwork(name, mode, others.split(",").filter { it.isNotBlank() }))
                finished = null
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Paket oluştur") }
        }
        error?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }

        outgoing?.let { text ->
            SectionTitle(if (finished != null) "Ağ kuruldu" else "Paketi diğer yöneticiye verin")
            finished?.let { net ->
                Button(onClick = { onOpen(net.id) }, modifier = Modifier.fillMaxWidth()) { Text("Ağı aç") }
                Spacer(Modifier.height(4.dp))
                Muted("Diğer yönetici de paketi henüz imzalamadıysa bu paketi ona geri verin:")
                Spacer(Modifier.height(4.dp))
            }
            ShareBlock(text, "Bu paket ağ sırrını içerir. Yalnız diğer yöneticiye gösterin.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun describe(p: Proposal): String {
    p.ticket?.let { return "Yeni davet (${it.maxUses} kullanım)" }
    val e = p.event ?: return "Bilinmeyen"
    return when (e.type) {
        GovernanceTypes.DEFINITION -> GovernanceLog.decodeDefinition(e.payload)?.let { "Ağ ayarı: ${it.name}, ${modeName(it.entryMode)}" } ?: "Ağ ayarı"
        GovernanceTypes.TERM_RENEW -> "Yönetici süresini yenile"
        GovernanceTypes.RETIRE_ANNOUNCE -> "Ağı 72 saat sonra sonlandır"
        GovernanceTypes.RETIRE_CANCEL -> "Sonlandırmayı iptal et"
        GovernanceTypes.RETIRE_NOW -> "Ağı HEMEN sonlandır"
        GovernanceTypes.MEMBER_REVOKE -> "Üyeliği iptal et: ${GovernanceLog.decodeRevoke(e.payload)?.value?.take(12)}"
        GovernanceTypes.INVITE_REVOKE -> "Daveti iptal et"
        GovernanceTypes.KEY_ROTATION -> GovernanceLog.decodeKeyRotation(e.payload)?.keys?.let { "Yöneticileri değiştir: ${it.threshold}/${it.keys.size} yeni set" } ?: "Yöneticileri değiştir"
        GovernanceTypes.SECRET_ROTATE -> GovernanceLog.decodeRotation(e.payload)?.let { "Ağ sırrını yenile: ${it.sealed.size} kişi yeni sırrı alacak" } ?: "Ağ sırrını yenile"
        else -> e.type
    }
}

@Composable
internal fun ManageScreen(node: Node, net: NetworkInfo, onBack: () -> Unit) {
    LocalTick.current
    val context = LocalContext.current
    val log = node.governance(net.id)
    val definition = log?.definition
    val admin = node.isAdmin(net.id)
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun act(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            Toast.makeText(context, e.message ?: "Yapılamadı", Toast.LENGTH_LONG).show()
        }
    }

    Header("Yönetim", onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Ağ")
        if (log == null || definition == null) {
            Muted("Ağın kuralları henüz alınmadı. Bir üyeyle karşılaşınca gelir ve imzaları doğrulanır.")
        } else {
            Text(definition.name, style = MaterialTheme.typography.titleMedium)
            Muted(modeName(definition.entryMode))
            Muted(
                when (log.mode) {
                    ManagementMode.ACTIVE -> "Yönetici yetkisi: ${DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("tr")).format(Date(definition.adminTermExpiry))} tarihine kadar"
                    ManagementMode.SUSPENDED -> "Yönetici yetkisi doldu: ayarlar duruyor, aktarım ve mesajlar sürüyor"
                    ManagementMode.RETIRED -> "Bu ağ sonlandı"
                },
            )
            log.keys?.let { Muted("Kararlar için ${it.threshold}/${it.keys.size} yönetici imzası gerekir") }
            if (log.secretEpoch > 0) Muted("Ağ sırrı ${log.secretEpoch} kez yenilendi")
            log.retireEffectiveAt?.let { Text("Sonlanma duyuruldu: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale("tr")).format(Date(it))}", color = Warn, style = MaterialTheme.typography.bodySmall) }
        }
        node.governanceAlert(net.id)?.let { reason ->
            Spacer(Modifier.height(4.dp))
            Text(
                if (reason == GovernanceReject.FORK) "Uyarı: Yöneticilerin çelişen kararları görüldü. Yöneticilerle görüşün." else "Uyarı: Bir eşten geçersiz yönetim olayı geldi ve reddedildi.",
                color = Warn, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (node.secretMissed(net.id)) {
            Spacer(Modifier.height(4.dp))
            Text("Ağ sırrı yenilendi ama bu cihaz alamadı. Devam etmek için yöneticiden yeni davet isteyin.", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
        if (!admin) {
            Spacer(Modifier.height(8.dp))
            Muted("Bu cihaz yönetici değil. Yöneticiler mesajlarınızı okuyamaz, verinizi silemez.")
        }
        if (admin && log != null && definition != null) {
            val threshold = log.keys?.threshold ?: 0
            // İmzası tamamlanan davet onay beklemez, aşağıda bağlantı olarak görünür.
            val pending = node.pendingProposals(net.id).filter { it.signatures.size < threshold }
            SectionTitle("Onay bekleyenler (${pending.size})")
            if (pending.isEmpty()) Muted("Onay bekleyen karar yok.")
            pending.forEach { p ->
                Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(describe(p), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Muted("${p.signatures.size}/$threshold imza")
                        Row {
                            if (!node.hasSigned(net.id, p)) Button(onClick = { act { node.approveProposal(net.id, p) } }) { Text("İmzala") }
                            TextButton(onClick = { node.rejectProposal(net.id, p) }) { Text("Reddet") }
                        }
                    }
                }
            }

            SectionTitle("Davetler")
            Muted("Davet 24 saat geçerlidir, ikinci yönetici imzalayınca hazır olur.")
            OutlinedButton(onClick = { act { node.proposeInvite(net.id) } }, modifier = Modifier.fillMaxWidth()) { Text("Davet oluştur") }
            node.readyInvites(net.id).forEach { link ->
                Spacer(Modifier.height(8.dp))
                ShareBlock(link, "Bu davet ağ sırrını içerir. Tek kişiye, doğrudan verin. Sızarsa ilk kullanan üye olur.")
            }

            SectionTitle("Giriş modu")
            EntryMode.entries.filter { it != definition.entryMode }.forEach { m ->
                OutlinedButton(onClick = {
                    confirm = "Mod değiştirme önerilsin mi? Daha önce bağlantıyı alanlar ağ sırrını biliyor; yeni kural onların yeni oturumlarını sınırlar, sırrı geri almaz." to {
                        act { node.proposeEvent(net.id, GovernanceTypes.DEFINITION, GovernanceLog.encode(definition.copy(entryMode = m))) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(modeName(m), fontSize = 12.sp) }
            }

            SectionTitle("Üyeler")
            Muted("Üyeliği iptal etmek yeni oturumları keser, kişinin elindeki veriye dokunmaz. LoRa'yı okumasını da kesmek için sonra ağ sırrını yenileyin. Tanık sayısı, kaç üyenin bu kişinin katılımını gördüğünü söyler; sahte kimliklere karşı kanıt değildir.")
            node.meshContacts(net.id).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val witnesses = node.witnessSummary(net.id, c.id)
                    Column(Modifier.weight(1f)) {
                        Text(c.nick.ifEmpty { "Adsız" }, style = MaterialTheme.typography.bodyMedium)
                        Muted("${witnesses.witnesses} tanık" + (witnesses.medianObservedAt?.let { " · ${DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("tr")).format(Date(it))} tarihinden beri" } ?: ""))
                    }
                    TextButton(onClick = {
                        confirm = "${c.nick} için iptal önerilsin mi?" to {
                            act { node.proposeEvent(net.id, GovernanceTypes.MEMBER_REVOKE, GovernanceLog.encode(RevokePayload(node.contactFingerprint(c)))) }
                        }
                    }) { Text("İptal öner", color = Warn) }
                }
            }

            SectionTitle("Ağ sırrı")
            Muted("Sır sızdıysa veya birini çıkardıysanız yenileyin. Yeni sır, tanınan üyelere ayrı ayrı mühürlenir. Hiç görmediğiniz üyeler yeniden davet ister. Eski sır 72 saat yalnız yenisini almak için geçerli kalır.")
            OutlinedButton(onClick = {
                confirm = "Ağ sırrının yenilenmesi önerilsin mi? Şu an ${node.meshContacts(net.id).size} kişiyi tanıyorsunuz; yalnız onlar ve siz yeni sırrı alırsınız." to {
                    act { node.proposeSecretRotation(net.id) }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Ağ sırrını yenile") }

            SectionTitle("Yöneticiler")
            Muted("Anahtar çalındıysa veya bir yönetici ayrılıyorsa yeni seti yazın. Mevcut yöneticilerin eşiği onaylar. Davetli ağda tanınan üyeler kalır, kullanılmamış eski davetler geçersiz olur.")
            var codes by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kodunuz: ${node.ownAdminCode(net.id)?.take(12)}…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { node.ownAdminCode(net.id)?.let { copy(context, it) } }) { Text("Kopyala") }
            }
            OutlinedTextField(codes, { codes = it }, label = { Text("Yeni yönetici kodları (virgülle, kendiniz dahil)") }, maxLines = 3, keyboardOptions = CodeKeyboard, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = codes.isNotBlank(), onClick = {
                try {
                    node.proposeKeyRotation(net.id, codes.split(","))
                    codes = ""
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(context, e.message ?: "Kodlar geçersiz", Toast.LENGTH_LONG).show()
                } catch (e: IllegalStateException) {
                    Toast.makeText(context, e.message ?: "Yapılamadı", Toast.LENGTH_LONG).show()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Yönetici değişimini öner") }

            SectionTitle("Yetki ve sonlandırma")
            OutlinedButton(onClick = {
                act { node.proposeEvent(net.id, GovernanceTypes.TERM_RENEW, GovernanceLog.encode(TermPayload(System.currentTimeMillis() + GovernanceLog.DEFAULT_TERM_MS))) }
            }, modifier = Modifier.fillMaxWidth()) { Text("Yönetici süresini 6 ay yenile") }
            if (log.retireEffectiveAt != null) {
                OutlinedButton(onClick = { act { node.proposeEvent(net.id, GovernanceTypes.RETIRE_CANCEL, "{}") } }, modifier = Modifier.fillMaxWidth()) { Text("Sonlandırmayı iptal et") }
            } else {
                OutlinedButton(onClick = {
                    confirm = "Ağın 72 saat sonra sonlanması önerilsin mi? Kimsenin verisi silinmez, üyeler kendisi karar verir." to {
                        act { node.proposeEvent(net.id, GovernanceTypes.RETIRE_ANNOUNCE, GovernanceLog.encode(RetirePayload())) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Ağı 72 saat sonra sonlandır") }
            }
            Button(onClick = {
                confirm = "Acil durum: anahtarlar çalındıysa ağ hemen sonlandırılsın mı? Bu geri alınamaz." to {
                    act { node.proposeEvent(net.id, GovernanceTypes.RETIRE_NOW, GovernanceLog.encode(RetirePayload("acil"))) }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = OnDanger), modifier = Modifier.fillMaxWidth()) { Text("Hemen sonlandır") }
        }
        Spacer(Modifier.height(24.dp))
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Emin misiniz?") },
            text = { Text(text) },
            confirmButton = { Button(onClick = { action(); confirm = null }) { Text("Öner") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}

/** Bölüm 11.6: "Bu ağ sonlandı." Silme kararı kullanıcıdadır; emir değil davettir. */
@Composable
internal fun RetiredDialog(node: Node, net: NetworkInfo, onDismiss: () -> Unit, onLeft: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bu ağ sonlandı") },
        text = { Text("Yöneticiler bu ağı sonlandırdı. Bu cihazdaki verilerinizi silmek veya saklamak size kalmış. Alınan dosyaları önce Aktarımlar'dan kaydedebilirsiniz.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sakla") } },
        dismissButton = { TextButton(onClick = { node.leaveNetwork(net.id); onLeft() }) { Text("Verileri sil", color = Warn) } },
    )
}
