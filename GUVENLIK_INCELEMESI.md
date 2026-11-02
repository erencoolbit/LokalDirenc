# Ağ saldırısı ve optimizasyon incelemesi

Bu inceleme kodu yazan tarafından yapıldı. **Bağımsız güvenlik denetimi değildir** ve onun yerini tutmaz. Kapsam: ağ yüzeyi (Wi-Fi/TCP, UDP keşif, Bluetooth, LoRa, yerel indirme) ve performans.

## Düzeltilenler

Hepsinin testi [HardeningTest.kt](core/src/test/kotlin/org/lokaldirenc/core/HardeningTest.kt) içinde.

| Saldırı | Kim yapabilir | Etki | Düzeltme |
|---|---|---|---|
| Boş bağlantılarla hizmet engelleme | Ağ kodunu bilmeyen, aynı ağdaki herkes | 8 bağlantı tüm oturumları kilitliyordu: aktarım ve mesaj durur | El sıkışma ayrı sayılır: kaynak başına 4, toplam 16 |
| Bayt bayt el sıkışma | Ağ kodunu bilmeyen | Okuma süresi her baytta yenilendiği için bağlantı dakikalarca tutulabiliyordu | Kesin süre sınırı |
| Keşif paketini kaydedip tekrarlama | Ağ kodunu bilmeyen | Üyenin adresi saldırgana geçiyor, mesaj ve yeni aktarımlar başarısız oluyordu. İçerik sızmıyordu | Doğrulanmış canlı adres korunur, yeni adres el sıkışmayı geçmeli |
| Büyük mesajla bellek tüketme | Üye | 8 oturum × 16 MB, telefonda bellek taşması | Mesaj sınırı 4 MB, parça sınırı 1 MB |
| İstem seli | Üye | Sonsuz doğrulama ve kabul penceresi | En çok 4 bekleyen istem |
| Sahte kimlik seli | Üye | Kişi listesi ve durum dosyası sınırsız büyüyordu | En çok 1000 kişi, kanıtlı kişiler korunur |
| Başarısız deneme seli | Üye | Geçmiş sınırsız büyüyordu | En çok 200 kayıt |
| Yavaş istemci (uygulama indirme) | Aynı ağdaki herkes | İndirme yuvası uzun süre tutuluyordu | Başlık okuma için kesin süre |

## Açık riskler

Düzeltilmedi. Çoğu tasarım kararı gerektiriyor. Önem sırası yazarın değerlendirmesidir.

### Yüksek

1. **Ağ kodu sızması geri alınamaz.** Bilen herkes üye olur, keşfi ve herkese açık mesajları okur. Yönetimsiz ağın doğası gereği; çözüm yeni ağ kurmaktır.
2. **Özel mesajda ileri gizlilik yok.** Alıcının anahtarı ele geçerse, kaydedilmiş bütün özel mesajlar açılır. Noise_X tek yönlü olduğu için böyledir. Aşama 5'te değişmeli.
3. **Noise elle yazıldı.** Vektörlerle sınandı, ama yan kanal ve yanlış kullanım açısından incelenmedi. Bu incelemede de bir şartname hatası çıkmıştı.
4. **Üye seviyesinde Sybil.** Yeni anahtar üretmek bedava: yazar kotası ve 500 zarflık depo sahte kimliklerle doldurulabilir. Bir üye kendi başına mesajları depodan düşürebilir. Yönetimsiz ağda çözümü yok, Aşama 2-3 sınırlar.
5. **Doğrulama kodu alışkanlığa bağlı.** Kullanıcı karşılaştırmadan "Kod aynı"ya basarsa, kodu bilen bir üye araya girebilir.

### Orta

6. **Trafikten tanınma.** Keşif paketleri şifreli ama sabit porttan (47474), sabit boyda (268 bayt) ve 3 saniyede bir gider. Ağı izleyen biri içeriği okuyamasa da uygulamanın çalıştığını anlayabilir. "Şifreli iletişim yazılımı kullanmanın" hukuki riski (Bölüm 18) düşünülünce önemli. BLE reklamının biçimi de benzer şekilde sabit.
7. **Mühürlü zarf seli.** Yazarı görünmediği için üye, 300 zarflık toplam kotayı doldurup başkalarının özel mesajlarını düşürebilir.
8. **İstasyon kimlik doğrulamaz.** Bluetooth ile bağlanan herkes çerçeve yazıp istasyonun yayın süresi hakkını tüketebilir; Wi-Fi ağı da açık. Anahtarsız tasarımın bedeli. Radyo karıştırma ise zaten engellenemez.
9. **Radyo görünürlüğü.** LoRa ve sabit istasyonun yönü bulunabilir, cihaz el konulabilir.
10. **SOS taklidi.** Bluetooth'tan ve LoRa'dan gelen çağrılar imzasızdır; kurtarma ekibinin zamanını çalabilir.
11. **Şifresiz uygulama indirme.** Aynı ağda ortadaki biri APK'yı değiştirebilir; ekrandaki parmak izi değiştirilmiş uygulamada kanıt değildir.
12. **Diskteki dosyalar şifresiz.** Telefonu ele geçiren, yüksek yetkiyle alınan dosyaları okuyabilir.

### Düşük

13. **BLE arama tıkanması.** Kaydedilen reklam tekrarlanırsa cihaz boş adresi arar ve 30 saniyelik zaman aşımı sırayı bekletir. Aramalar tek sıradadır.
14. **TCP tüm arayüzlerde dinler,** mobil veri dahil. Ağ kodu olmadan el sıkışma geçilemez, ama yüzey gereğinden geniş.
15. **Cihaz saatine bağımlılık.** 72 saatten eski zarflar reddedilir, BLE belirteci saate dayanır. Saati çok yanlış cihaz ağdan kopar; saati oynanan cihaz yönetim sürelerini kendi görünümünde değiştirir.
16. **Oturum anahtarı yenilenmiyor.** Uzun oturumda sorun değil (her aktarım yeni oturum), belgedeki hedef olduğu için not edildi.
17. **Adres değişimi testsiz.** Eşin gerçekten taşınıp yeni adresten doğrulanması kodda var, aynı makinede sınanamadı.

## Optimizasyon

### Yapılanlar

| Değişiklik | Kazanç | Kanıt |
|---|---|---|
| Boşta eşitlemede önce özet | 500 zarfta tur başına yaklaşık 33 KB yerine bir özet ve kimlik duyurusu | Hesap, ölçülmedi |
| Durum yazma aralığı 2 → 10 sn | Daha az şifreleme ve disk yazımı. Bedel: uygulama beklenmedik kapanırsa son 10 saniyenin mesaj kaydı gidebilir | Ölçülmedi |
| BLE belirteçlerinin önbelleği | Her tarama sonucunda 7 özet hesabı yerine dönemde bir | Ölçülmedi |
| G/Ç arabelleği 8 → 64 KB | Yerel döngüde aktarım 87,6 → 93,0 MB/s | Tek ölçüm, masaüstü, gürültü payı içinde. Telefonu temsil etmez |

Bu yol için bugünkü darboğaz kod değil, radyo. Gerçek hız telefonda ölçülmeli.

### Önerilenler

- **Pil, en önemli başlık:** Bluetooth mesh, düşük gecikmeli taramayı sürekli açık tutuyor. Aralıklı taramaya geçilmeli. Etkisi telefonda ölçülmeden karar verilmemeli. Wi-Fi keşfi ve SOS'taki uyandırma kilidi de ölçülmeli.
- **Tüm durumu her seferinde yeniden yazma:** Mesaj sayısı artınca pahalılaşır. Gerçek veritabanına geçilmeli (Aşama 5).
- **APK boyutu:** Yaklaşık 10 MB, kod küçültme kapalı. R8 açılabilir, ama serileştirme kurallarıyla sınanmalı.
- **Yavaş telefonda şifreleme:** BouncyCastle saf Java. Telefonda darboğaz çıkarsa platform şifrelemesi düşünülebilir.
- **Çok sayıda eş:** Her eşle ayrı eşitleme, büyük ağlarda ölçeklenmez. Üç cihazdan fazlası denenmedi.
