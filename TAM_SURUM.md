# Tam sürüm gereklilikleri

Kaynak: *Lokal Direnç Sistem ve Mimari* belgesi. Parantez içindeki numaralar belgenin bölümleridir. Bu liste belgeden çıkarılan iş listesidir, belgenin yerini tutmaz.

**Durum:** ✅ var · 🟡 kısmen veya prototip düzeyinde · ⬜ yok

Bugünkü sürüm yalnız Aşama 1 prototipi ile belgenin dışında öne alınan birkaç özellikten oluşur. Hiçbir madde bağımsız güvenlik incelemesinden geçmedi; "var" işareti "çalışıyor ve test edildi" demektir, "güvenli" demek değildir.

## Aşama 1: Yönetimsiz ağda dosya aktarımı (19)

- ✅ Ağ kodu, Network ID, QR gösterme (6.3)
- 🟡 QR okutma: kod var, kamerayla denenmedi
- 🟡 Wi-Fi/hotspot keşfi: gerçek ağda denenmedi (10)
- ✅ Noise el sıkışması, ağ kodu PSK olarak (8). Güvenlik incelemesi bekliyor (20, açık konu 8)
- ✅ Parçalı, özetli, sürdürülebilir aktarım (9)
- ✅ Dosya güvenliği, karantina, otomatik açmama (9.1)
- 🟡 Şifreli yerel veri: durum şifreli, ama SQLCipher yok ve alınan dosyalar diskte şifresiz (8)
- ✅ Panik silme (11.7)
- 🟡 Kısa saklama süresi: kod var, süre dolunca silme test edilmedi
- ✅ Aktarım geçmişi, kişisel engel listesi
- ⬜ Gönderen imzası (9, adım 10)
- ⬜ İki gerçek telefonda saha denemesi

## Aşama 2: Yönetilen ağ (5, 6, 11)

**İlk dilim çekirdekte (`Governance.kt`) yazıldı ve test edildi; henüz ağa ve arayüze bağlı değil, kullanıcı göremez.** Aşağıda 🟡 olanlar bu nedenle kısmen sayılır.

- 🟡 M-of-N çoklu imzalı yönetim anahtarı, en az 2 imza (11.3)
- 🟡 Network ID'nin yönetim anahtar setinden türemesi, imzalı rotasyon zinciri (6.1)
- 🟡 İmzalı ağ tanımı (6.1). QR ve katılım akışı yok (10.1)
- ⬜ Giriş modları: açık, karma, davetli; katılmadan önce görünür (5.5)
- ⬜ Davet kodu: 24 saat, 1 kullanım (6.2); üyelik sertifikası ve iptali (5.5)
- 🟡 Süreli yönetici yetkisi, en çok 1 yıl; süresi dolunca askı (5.1, 11.1). Askıdayken yenileme ve anahtar değişimi açık bırakıldı: belgeye işlenecek karar
- 🟡 Eylem izin listesi: yönetici eylemleri için var (11.4). Moderatör ve üye rolleri Aşama 3'te
- 🟡 Eşik sağlanırsa anahtar değiştirme (11.3). Eşik kaybında yönetimsiz kipe düşme yok
- 🟡 Ağ kimliğini emekli etme: 72 saat bekleme, iptal, acil sonlandırma (11.6). "Sil / dışa aktar / sakla" sorusu yok
- ⬜ Olay zincirinin üyeler arasında dağıtılması ve Node'a bağlanması
- ⬜ Yönetilen ağ kurma ve yönetme arayüzü
- ⬜ Tanıklık kayıtları, medyan zaman, nedensel sıra (13.4)
- ⬜ Takma kimliğin ağlar arası isteğe bağlı taşınması (7.2)

## Aşama 3: Moderasyon (13)

- 🟡 Katman 1 kişisel engel: var, yazar anahtarı ve içerik özeti ile (13.1)
- ⬜ İmzalı öneri listeleri ve abonelik (13.1)
- ⬜ Hatalı veri gönderen düğümlerin yerelde puan kaybetmesi (13.1)
- ⬜ Katman 2 modülü, ağ bilgisinde görünür (13.2)
- ⬜ Üyelerce seçilen, süreli moderatör; işaret ve teklif (11.1)
- ⬜ Oy hakkı: tanıklık, bekleme süresi, katılım geçmişi (13.4)
- ⬜ Açık oylama ve önceden risk uyarısı (13.3.1)
- ⬜ Quorum %20, alt sınır 5; basit çoğunluk (13.2)
- ⬜ Olağan dışı hesap kümelerini işaretleme (7.5)
- ⬜ İmzalı karar olayı, gerekçe, itiraz, ikinci inceleme (13.3, 13.6)
- ⬜ Geçici karantina işareti, en çok 48 saat (13.5)
- ⬜ Yaptırım seviyeleri (13.6); kaldırma önerisi/tombstone (13.8)

## Aşama 4: Federasyon (5)

- ⬜ El sıkışma protokolü, iki yönlü doğrulama (5.6)
- ⬜ İki yöneticinin imzaladığı anlaşma, açılacak hizmetler (5.4, 5.6)
- ⬜ Sonlandırma: imzalı olay, 24-72 saat bildirim (5.6, 11.5)
- ⬜ Ağlar arası aktarım; moderasyon bildirimleri (13.7)
- ⬜ Süreli yetki devri (11.1)

## Aşama 5: Veri katmanı, arşiv ve yayın (9.6, 17, 21)

- 🟡 Mesajlaşma: mesh üzerinde kısa mesaj var (belgeden önce eklendi)
- ⬜ Özel mesajda ileri gizlilik ve inkâr edilebilir kimlik doğrulama (8). Bugünkü Noise_X mührü bunu karşılamaz
- ⬜ Yazar başına append-only log, sürüm vektörü, CRDT birleştirme (17)
- ⬜ Çoklu bağımsız arşiv düğümü, imzalı Merkle snapshot (9.6)
- ⬜ Arşivler arası karşılaştırma ve eksik kayıt tespiti (9.6)
- ⬜ Yayın kanalları: yayıncı anahtarı, düzeltme kaydı (21.1)
- ⬜ Görsellerde EXIF/konum temizleme (21.1)
- ⬜ Kişisel güven ağı, kanıt etiketleri; oylama yok, gizleme yok (21.2)
- ⬜ Evrensel yasadışı içerik filtresi, herkese açık kanal ve arşivde. **Hukuk ve güvenlik incelemesi olmadan yazılmayacak** (12)

## Aşama 6: Çok platform ve güncelleme (4, 14, 15)

- ⬜ Windows, Linux, iOS istemcileri (4)
- 🟡 Ortak çekirdek (3): protokol kodu Kotlin Multiplatform `commonMain` altında ve Windows yerel hedefine derleniyor; biçimler Python vektörleriyle sınanıyor; Bluetooth reklamı iOS'a uygun. **iOS için kalan:** `Node`'un (soket, iş parçacığı, dosya) taşınması, şifreleme uygulaması, iOS Bluetooth ve arayüzü, Mac ve Apple geliştirici hesabı. Hiçbir iOS kodu derlenmedi
- ⬜ Module Manager (3)
- ⬜ Çevrimdışı kök anahtar, süreli alt anahtarlar, çok parçalı yedek (14.2)
- ⬜ İmzalı güncelleme, iptal listesi, asgari sürüm, geri düşürme koruması (14.3, 14.4)
- 🟡 Yakındaki cihaza uygulama aktarımı: var, parmak izi resmi imzaya dayanmıyor (14.8)
- ⬜ Sürüm etiketleri: resmi, topluluk, imzasız (14.6); topluluk sürümü bildirimi (11.4, 14.5)
- ⬜ Relay/NAT geçişi (10, 20)
- ⬜ DPI bağlantı yardımcısı modülü (15)
- ⬜ Wi-Fi Direct (10)
- 🟡 Bluetooth: mesaj için var (belgeden önce eklendi); dosya yok
- ⬜ Arka planda çalışma: ön plan servisi yalnız SOS'ta var

## Aşama 7: Güvenlik ve yayın (18, 19)

- ⬜ Bağımsız güvenlik incelemesi ve testi
- ⬜ Protokol dokümantasyonu
- ⬜ Hukuk incelemesi: lisanslar, gizlilik, KVKK/GDPR, mağaza kuralları, yönetici ve arşiv sorumlulukları, kural 12 (18)
- ⬜ Lisans dosyası, öneri AGPL-3.0 ve protokol için ayrı serbest lisans (20)
- ⬜ Kademeli beta
- ⬜ Dağıtım güveni: resmi imza, yeniden üretilebilir derleme, mağaza. Galaxy'de virüs uyarısı bu başlığa girer; bkz. TEKNIK.md "Kurulum uyarıları"

## Karar bekleyen konular (20)

1. Topluluk temsilcilerinin seçim yöntemi.
2. Kök güncelleme anahtarı kaybolursa yeniden güven kurulumu.
3. Tanıklık için K değeri, küçük ağlarda davranış.
4. Gizli oy: ne zaman, hangi yöntemle.
5. Kural 12'nin teknik uygulaması.
6. İnternet üzerinden P2P için NAT/relay yöntemi.
7. Resmi ve özel sürümlerin uyumluluk testi.
8. Ağ kodunun Noise PSK olarak kullanımının güvenlik incelemesi.
9. Yayıncı doğrulama göstergesinin biçimi.

Ayrıca varsayılan değerler onay bekliyor: 2-of-3, yetki süreleri, quorum, bekleme süreleri.

## Belgeye işlenmesi gerekenler

Belgede olmayan veya ondan ayrılan kararlar. Belgenin yeni sürümüne yazılmalı:

- Şifreli oturumun doğrulamadan önce kurulması, psk0 ve diğer sapmalar (TEKNIK.md).
- Mesh mesajlaşma ve Bluetooth'un öne alınması; ara cihazların gördüğü metadata.
- LoRa köprüsü ve anahtarsız topluluk istasyonu.
- Afet SOS modu ve sabit kimlik istisnası.

Donanım ve saha işleri [EKLEME.md](EKLEME.md) içinde.
