# Lokal Direnç: Aşama 1 prototipi

Mimari belge v1.5, Bölüm 19'daki ilk prototip: **yönetimsiz ağda yerel, şifreli dosya aktarımı (Android)**.

> Bu prototip bağımsız güvenlik incelemesinden geçmedi. Gerçek riskli durumlarda kullanmayın.

## Kurulum

Hazır APK: `LokalDirenc.apk` (Android 8 ve üstü). İki telefona kurun, ikisini aynı Wi-Fi'ye veya birinin hotspot'una bağlayın. Önceki `LokalDirenc-debug.apk` kuruluysa önce onu kaldırın: imzası farklı olduğu için üzerine kurulmaz, kaldırınca ağlar ve mesajlar silinir.

**Telefon virüs veya zararlı uygulama uyarısı verirse:** Bu uygulama mağaza dışından geliyor ve tanınmış bir yayıncı imzası taşımıyor, bu yüzden uyarı beklenebilir. Bkz. [Kurulum uyarıları](#kurulum-uyarıları).

1. Birinci telefon: **Yeni ağ kur**, sonra **Ağ kodu**.
2. İkinci telefon: **QR ile katıl** veya **Kodla katıl**.
3. Cihaz listede görününce **Gönder**. İlk eşleşmede iki ekrandaki kodu karşılaştırın.

Kaynaktan derleme (JDK 17 veya üstü, Android SDK; `local.properties` içinde `sdk.dir`):

```bash
./gradlew :core:test :app:assembleDebug
```

Derleme çıktıları OneDrive kilitlerine takılmasın diye `%LOCALAPPDATA%\LokalDirenc\build` altına yazılır.

## Kurulum uyarıları

Bir Samsung Galaxy, indirilen APK'yı virüs diye reddetti. Tam metin elimizde olmadığı için kesin neden bilinmiyor. Olası kaynaklar:

- **Google Play Protect:** Bilinmeyen geliştiriciden gelen yeni bir uygulamada uyarır. "Ayrıntılar" altında yine de yükleme seçeneği sunabilir; ciddi tehdit saydığı uygulamaları engeller.
- **Samsung Otomatik Engelleyici (Auto Blocker):** Yeni Galaxy'lerde mağaza dışı kurulumu engeller. Ayarlar > Güvenlik ve gizlilik altından yönetilir.
- **Tarayıcı:** Chrome, şifresiz bağlantıdan inen dosyayı uyarır.

Bu uyarıları yalnız dosyanın kaynağına güveniyorsanız geçin. Yayıcı bir yöntemle değil, doğrudan güvendiğiniz kişiden alın.

Ne yapıldı: Dağıtılan APK önceden debug imzalı ve `debuggable` idi; artık kendi anahtarıyla imzalı ve `debuggable` kapalı. Bunun uyarıyı kaldıracağı **doğrulanmadı**. Kalıcı çözüm için imzalı resmi sürüm, kod kanıtı (kaynak kod ve yeniden üretilebilir derleme) ve mağaza dağıtımı gerekir ([TAM_SURUM.md](TAM_SURUM.md), Aşama 6-7).

İmza anahtarı `~/.lokaldirenc/` altında, proje dışındadır. **Kaybolursa aynı uygulamaya güncelleme yayımlanamaz; yedekleyin.** Prototip anahtarıdır, belgedeki kök ve alt anahtar yapısının yerini tutmaz.

## Yapı

| Yer | İçerik |
|---|---|
| `core/` | Android'e bağımlı olmayan çekirdek: ağ kodu, Noise, keşif, aktarım, dosya güvenliği |
| `app/` | Compose arayüzü, Keystore deposu, QR |
| `core/.../cli/Peer.kt` | Geliştirici aracı: emülatörle denemede karşı cihaz rolü. Kodları otomatik onaylar, durumu şifresiz yazar |
| `tools/gen_noise_vectors.py` | Bağımsız Noise uygulamasından test vektörü üretir |

## Bölüm 19 kapsamı

| # | Madde | Durum | Nasıl doğrulandı |
|---|---|---|---|
| 1 | Ağ kodu, Network ID | Var | Birim test, uygulama |
| 2 | QR gösterme / okutma | Var | Gösterme uygulamada görüldü. **Kamerayla okutma denenmedi** |
| 3 | Aynı Wi-Fi/hotspot keşfi | Var | Alma uygulamada, yönlendirilmiş UDP ile. **Gerçek yayın adresi yolu denenmedi** |
| 4 | Noise + PSK, parmak izi | Var | Bağımsız uygulamayla bayt bayt vektör testi, uygulamada eşleşme |
| 5 | Alıcı kabulü | Var | Birim test, uygulama |
| 6 | Parçalı, özetli, sürdürülebilir aktarım | Var | Birim test (kopma, bozuk parça, sahte manifest); iki yönlü aktarım uygulamada, özet eşleşti |
| 7 | Dosya güvenliği, karantina | Var | Birim test; `.jpg` kılığında exe uygulamada karantinaya alındı |
| 8 | Şifreli yerel durum, Keystore | Kısmen | Ağ kodu ve ad `state.enc` içinde düz metin olarak bulunmadı. Bkz. sapmalar |
| 9 | Panik silme, kısa saklama | Var | Panik silme birim test ve uygulamada. **Süre dolunca silme test edilmedi** |
| 10 | Geçmiş, hata yönetimi | Var | Birim test, uygulama |
| 11 | Kişisel engel listesi | Var | Birim test. **Arayüzdeki düğme denenmedi** |

"Cihaza kaydet" düğmesi de uygulamada denenmedi. **İki gerçek telefon arasında hiç deneme yapılmadı.** Uçtan uca denemeler bir emülatör ile masaüstündeki `Peer` aracı arasında yapıldı.

## Mesh mesajlaşma

Belgede mesajlaşma ve çok atlamalı senkron Aşama 5'teydi. İstek üzerine öne alındı, belgeye henüz işlenmedi.

Her cihaz gördüğü zarfları saklar ve karşılaştığı üyelere iletir (taşı-ve-ilet, en çok 8 atlama). Yönlendirme tablosu ve teslim onayı yoktur. Zarf en çok 72 saat yaşar. Ekranda görünen mesajlar saklama süresine uyar. **Yalnız kısa mesajlar yayılır; dosyalar hâlâ doğrudan bağlantı ister.**

| | Herkese açık | Özel |
|---|---|---|
| Koruma | Ed25519 imzası | Noise_X mührü |
| Kim okur | Ağ kodunu bilen herkes | Yalnız alıcı |
| Ara cihaz ne görür | İçerik ve yazar | Yazar ve alıcı görünmez; zaman, boyut grubu ve komşusunun adresi görünür |

Doğrulananlar:

- **Birim test:** üç düğümlü hat (A ile C birbirini görmez), yazar ayrıldıktan sonra taşıyıcının teslimi, sahte imza, başka ağdan zarf, atlama sınırı, süre sınırı, yazar kotası, engelleme, yeniden başlatma, panik silme. Noise_X bağımsız uygulamayla bayt bayt karşılaştırıldı; bu test gerçek bir şartname hatasını yakaladı.
- **Uygulamada:** emülatör ↔ masaüstü ↔ masaüstü hattında iki yönlü, 2 atlamalı herkese açık ve özel mesaj. Ortadaki düğümün durum dosyasında özel metinler yoktu.

Sınırlar:

- **Üçten çok düğüm ve gerçek telefon denenmedi.** Salgın yayılım büyük ağlarda ölçeklenmez; pil etkisi ölçülmedi.
- **Arka planda çalışmaz.** Ön plan servisi olmadığı için uygulama kapanınca taşıma da durur. Taşıyıcı senaryosu için bu çözülmeli.
- **Özel mesajda ileri gizlilik yok.** Noise_X tek yönlüdür: alıcının anahtarı sonradan ele geçerse saklanmış mühürler de açılır. Belgedeki ileri gizlilik hedefini karşılamaz (Bölüm 8).
- **Mühürlü zarf seli sınırlı.** Yazarı görünmediği için kişi başı kota uygulanamaz, yalnız toplam kota (300) vardır. Kodu bilen bir üye bu kotayı doldurup başkalarının özel zarflarını düşürebilir. Engelleme de bu zarfları durdurmaz.
- **Kimlik:** Çok atlamadan öğrenilen kişi "Doğrulanmadı" görünür. "Doğrulandı" için dosya aktarımındaki kod onayı ve doğrudan bağlantı gerekir.
- **Saat:** Saati 72 saatten çok geride veya 24 saatten çok ileride olan cihazın zarfları reddedilir.
- **Tasarım incelenmedi.** Zarf biçimi ve mühür kullanımı bağımsız güvenlik incelemesinden geçmeli.

## Bluetooth taşıması

Ayarlar > Bluetooth mesh ile açılır, varsayılan kapalıdır. Ortak Wi-Fi veya hotspot olmadan yakın üyeler mesaj eşitler. Android 10 ve üstü gerekir. **Yalnız mesh mesajları geçer; dosyalar hâlâ IP bağlantısı ister.**

- **Keşif:** BLE reklamı. 128 bitlik belirteç ağ anahtarından türer, 10 dakikada bir döner ve üye olmayana rastgele görünür. Reklamda ayrıca düz metin L2CAP kanal numarası ve rastgele 4 baytlık rol etiketi vardır (etiketi büyük olan arar).
- **Bağlantı:** Bluetooth eşleştirmesi istemeyen L2CAP kanalı. Bluetooth katmanı bu yüzden güvenlik sağlamaz; üyelik ve gizlilik üstteki Noise oturumundan gelir.
- **Gecikme:** Yeni mesaj, arayan taraftan hemen, bekleyen taraftan en geç 30 saniyede geçer.

Doğrulananlar (iki emülatör, sanal Bluetooth, aralarında IP yolu yok):

- Birbirini bulma, iki yönde herkese açık ve özel mesaj (1 atlama), art arda yeniden bağlanma.
- Uygulama yeniden başlayınca bağlantının dönmesi, 10 dakikalık belirteç dönüşünden sonra yeniden bağlanma.
- Panik silmenin yayını durdurması: karşı cihazda "yakında 0 üye".
- Birim test: TCP olmayan akışta eşitleme, iki bağlantı üzerinden 2 atlamalı mühürlü mesaj, yanlış ağ kodu, sessiz eşte zaman aşımı.

Sınırlar:

- **Gerçek radyo denenmedi.** Emülatör menzil, parazit, üretici farkları ve eşzamanlı reklam sınırlarını taklit etmez. L2CAP üretici yazılımlarında sorunlu olabilir.
- **Çözülmemiş bir olay:** Bir denemede bağlantılar arka arkaya 30 saniyelik zaman aşımına düştü. Sanal Bluetooth'u sıfırlamak düzeltti, sonra tekrar üretilemedi. Nedeni bilinmiyor.
- **Bluetooth üzerinde 2 atlama gerçek uygulamada denenmedi.** Bunun için üç cihaz gerekir; yalnız birim testte var.
- **Ekran kapalıyken büyük olasılıkla çalışmaz.** Android filtresiz BLE taramasını ekran kapalıyken durdurur. Ön plan servisi de yok. Taşıyıcı senaryosu için ikisi de çözülmeli.
- **İz:** Belirteç saate bağlıdır. Saat farkı yaklaşık 30 dakikayı aşan cihazlar birbirini bulamaz. Reklam biçimi sabit olduğu için, belirteç okunamasa da dikkatli bir gözlemci uygulamanın izini sezebilir; ayrıca telefonun Bluetooth radyosu açık kalır.
- **Birden çok ağ:** Her ağ için ayrı reklam gerekir; telefonların reklam yuvası sayısı sınırlıdır ve tek ağın ötesi denenmedi.
- **Wi-Fi Direct yok.** Her bağlantıda kullanıcı onayı gerektirdiği için bu aşamada eklenmedi.

## Gönderim yöntemleri

Mesaj ekranında Wi-Fi, Bluetooth ve LoRa simgeleri, tipik menzil, hız ve açık/kapalı durumu görünür. Dokununca ayrıntı ve açma düğmesi çıkar. Değerler ölçüm değil, tipik değerlerdir.

## LoRa köprüsü ve topluluk istasyonu

Telefonda LoRa radyosu yoktur: telefon → Bluetooth → ESP32 + LoRa → radyo. Yazılım `firmware/ld_station`, hedef kart Heltec WiFi LoRa 32 V3.

```bash
pio run -d firmware/ld_station -t upload
```

**Yüklemeden önce** `src/config.h` içindeki frekans, güç ve yayın süresi değerleri elle doldurulmalıdır. Yasal değerler ülkeye göre değişir; varsayılan bilerek yoktur ve doldurulmazsa derleme durur.

- **İstasyon ağ kodu bilmez.** Çerçeveler şifrelidir, istasyon okuyamaz. Yalnız aktarır, tekrarları eler, yayın süresi sınırını uygular, son 32 çerçeveyi bellekte tutup bağlanan telefona verir. Çalınırsa içinden anahtar çıkmaz. Bedeli: sahte çerçeveleri ayıramaz.
- **Yerel sohbet LoRa'ya çıkmaz.** Mesaj ekranında "LoRa ile uzağa da gönder" seçilmelidir. Telefon başına 10 dakikada 20 paket hakkı vardır.
- **Boyut:** Çerçeve en çok 200 bayt, mesaj en çok 8 çerçeve. Denemede bir özel mesaj 4, bir herkese açık mesaj 1-2 çerçeve tuttu.
- **Onay yok:** Teslim onayı ve yeniden gönderim yoktur. Tek parça kaybolursa mesaj düşer.

Doğrulananlar:

- Birim test: sıkı kodlamanın imzayı bozmaması, yalnız üyelerin açabilmesi, sıra dışı ve yinelenen parçalar, kayıp parça, hak sınırı.
- İki emülatör: telefondaki Bluetooth köprüsü, ESP32 ile aynı hizmeti sunan **sahte istasyon** (`assembleSim`, dağıtılan APK'da yok) üzerinden herkese açık mesaj, özel mesaj ve SOS çerçevesi.
- ESP32 yazılımı derleniyor; bölge ayarı boşsa derleme reddediliyor.

**Denenmedi:** Yazılım gerçek kartta hiç çalıştırılmadı. Radyo, gerçek menzil, istasyonun yeniden yayını, yayın süresi sayacı, saklanan çerçeveler, kendi Wi-Fi ağı ve telefonun gerçek ESP32'ye bağlanması doğrulanmadı. Pinler Heltec V3 için yazıldı, kartta sınanmadı.

## Afet SOS modu

Ana ekran > Acil durum. Yayını yalnız kullanıcı başlatır.

- **Yardım sinyali:** Herkese açık Bluetooth yayını, destekleyen telefonda uzun menzil yayını da. Ağ kodu, kimlik ve konum taşımaz; oturum numarası ve pil yüzdesi içerir. Ekran kapalıyken de sürer.
- **Düdük:** 30 saniyede bir üçlü ses. Kapatılabilir. Çalarken alarm sesini en yükseğe alır, durunca eski düzeye getirir.
- **112:** Arama ekranını açar, kullanıcı arar.
- **Dinleme:** Yakındaki çağrılar sinyal gücü ve pille listelenir. Yalnız bu ekran açıkken çalışır.

Doğrulananlar (iki emülatör): yayını duyma, ekran kapalıyken sürme, durdurma. **Sinyalin duvardan geçmesi, pil ömrü ve düdük sesi ölçülmedi;** emülatör sessiz çalıştırıldı ve sanal Bluetooth'ta uzun menzilin gerçek menzili görülemez.

Bu özellik kurtarma garantisi vermez ve saha denemesi olmadan kurtarma aracı olarak sunulmamalıdır. Çağrılar doğrulanamaz, herkes taklit edebilir.

## Uygulamayı paylaşma

Ayarlar > Uygulamayı paylaş (Bölüm 14.8). Yalnız kurulum paketi içindir. Uygulaması olmayan birine kullanıcı dosyası gönderme bilerek yoktur, çünkü bu yol şifresizdir.

- **Paylaşım menüsü:** Quick Share, e-posta vb. Hedefi kullanıcı seçer.
- **QR ile yerel indirme:** Telefon, seçilen ağ arayüzünde küçük bir HTTP sunucusu açar. Yalnız bu APK'yı ve bir sayfayı verir; adres rastgeledir, 10 dakikada veya ekrandan çıkınca kapanır, kayıt tutmaz.
- **Paket kimliği:** İmza parmak izi ve dosya SHA-256 özeti gösterilir.

Doğrulananlar:

- Birim test: tam içerik, rastgele adres, dizin kaçışı ve başka dosya isteklerinin reddi, yalnız GET/HEAD, başlık sınırı, süre dolunca kapanma.
- Emülatörde: paylaşım menüsü doğru dosya adıyla açıldı; Chrome ile indirilen dosyanın özeti APK ile aynı çıktı; ekrandaki özet derlenen APK ile aynı çıktı.

Sınırlar:

- **Şifresizdir.** Aynı ağdaki biri dosyayı değiştirebilir. En güvenlisi paylaşanın kendi hotspot'udur. Chrome bu yüzden "güvenli indirilemedi" uyarısı verir ve onay ister.
- **Parmak izi tek başına kanıt değildir.** Değiştirilmiş bir uygulama ekranda doğru parmak izini gösterebilir. Telefonda gerçek karşılaştırma için dış bir araç gerekir. APK şu an prototip anahtarıyla imzalıdır; resmi sürüm imzası ve yayımlanmış parmak izi olmadan yayıncı kimliği kanıtlanamaz.
- **Denenmedi:** gerçek telefon hotspot'u, QR'ın kamerayla okutulması, bilinmeyen kaynak izniyle kurulum ekranı, Quick Share ile gerçek gönderim.
- iOS'a kurulamaz.

## Belgeden bilinçli sapmalar

Bunlar belgeye işlenmeli veya güvenlik incelemesinde tartışılmalı:

1. **Sıra:** Belgede eşleşme doğrulaması (adım 2) ve kabul (adım 3), şifreli oturumdan (adım 5) önce. Prototipte önce oturum kurulur, çünkü doğrulama kodu el sıkışmadan türer ve dosya adı düz metin gitmemelidir.
2. **psk0:** Kodu bilmeyen düğüm ilk mesajda elenir, yanıt alamaz, statik anahtarı da öğrenemez. XXpsk3'te anahtar kodu bilmeyene de sızardı. (Açık konu 8.)
3. **Noise uygulaması:** Şartname BouncyCastle ilkelleri üzerine elle yazıldı. Hazır JVM kitaplıkları ayrıntılı taranmadı; incelemeden önce yerine bakımı süren bir kitaplık konup konamayacağına bakılmalı. Protokol yeni değil, ama bu kod da incelenmeli.
4. **Doğrulama kodu 40 bit:** 6 haneli kod (20 bit), kodu bilen bir saldırganın el sıkışma sırasında anahtar deneyerek tutturabileceği kadar kısadır.
5. **Ağ kodu 28 karakter:** 26 karakter + 2 sağlama. Yazım hatası sessizce başka ağa götürmez. Sağlama anahtar türetmeye girmez.
6. **SQLCipher yok:** Durum, Keystore anahtarıyla AES-GCM şifreli tek dosyadır.
7. **Elle adres:** Belgede yok. Yayını engelleyen ağlar ve deneme için eklendi.

## Bilinen sınırlar

- **Alınan dosyalar diskte şifresiz.** Uygulamanın özel alanında dururlar. Panik silme onları normal silmeyle siler, kriptografik silme yalnız `state.enc` için geçerlidir. Silinen verinin bellekten geri getirilemeyeceği garanti edilmez.
- **Ön plan servisi yok.** Sistem uygulamayı arka planda kapatırsa aktarım kesilir; sonra kaldığı yerden sürdürülebilir.
- **Ağ kodu sızarsa geri alınamaz**, yeni ağ kurulur. Kodu bilen herkes keşif paketlerini okuyabilir.
- **Gönderen imzası yok** (Bölüm 9, adım 10, isteğe bağlı). Kaynak, oturumdaki statik anahtarla doğrulanır.
- **Takma ad doğrulanmaz.** Kimlik parmak izidir.
- **Oturum içi anahtar yenileme, mesaj dolgusu ve kötü davranan düğüm puanlaması yok.**
- **Prototip imzalı.** Resmi sürüm imzası ve lisans dosyası yok (belgedeki AGPL-3.0 önerisi henüz uygulanmadı).
- `Peer` geliştirici aracı şimdilik APK içinde de duruyor; uygulama onu çalıştırmaz.
