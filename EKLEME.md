# Eklenecekler

Fikirlerin durumu ve kalan işler. Ayrıntılar [TEKNIK.md](TEKNIK.md) içinde.

## Tamamlandı: Mesaj gönderirken gönderim yöntemleri

Mesaj ekranında Wi-Fi, Bluetooth ve LoRa simgeleri, tipik menzil ve hız, açık/kapalı durumu. Dokununca ayrıntı ve açma düğmesi çıkar.

**Kalan:** Menzil ve hız değerleri tipik değerdir; gerçek ölçümlerle güncellenmeli.

## Kısmen: ESP32 + LoRa ile menzil

Telefon protokolü, Bluetooth köprüsü ve ESP32 yazılımı yazıldı. Yazılım derleniyor, ama **gerçek kartta ve radyoda hiç çalıştırılmadı.**

**Kalan:**

- Türkiye'deki frekans, güç ve yayın süresi kurallarını (BTK) doğrula, `firmware/ld_station/src/config.h` dosyasını doldur.
- İki kartla gerçek menzil, kayıp oranı ve yayın süresini ölç.
- Topluluktaki başka bir telefonun uzak mesajını bağlı telefon üzerinden LoRa'ya çıkarma. Şu an gönderenin telefonu cihaza bağlı olmalı.
- Kayıp parça için yeniden gönderim yok; özel mesajlar (yaklaşık 4 paket) bundan daha çok etkilenir.

## Kısmen: LoRa + ESP32 topluluk istasyonları

İstasyon yazılımı aktarma, tekrar engelleme, yayın süresi sınırı, son çerçeveleri saklama ve kendi Wi-Fi ağını içeriyor. Ağ kodu taşımaz. **Radyoda ve Wi-Fi'de denenmedi.**

**Kalan:**

- Topluluk etiketiyle seçici yönlendirme ve mesaj önceliği. Şu an her uzak mesaj her istasyondan yayılır.
- Saklanan çerçeveler yalnız bellekte: elektrik kesilince silinir.
- İstasyon, üye olmayanların sahte çerçevelerini ayıramaz; kanal doldurulabilir.
- Farklı ağ kodlu toplulukları bağlama: yönetilen ağ + federasyon (Aşama 2 ve 4).
- Wi-Fi ağı üzerinden telefonların birbirini bulduğunu gerçek kartta doğrula.
- Üzerinden Wi-Fi'ye bağlanan telefonun "internet yok" uyarısıyla nasıl davrandığını doğrula.
- Sahada yedekli istasyon düzeni ve işleten kişinin hukuki riski (Bölüm 18).

## Kısmen: Afet SOS modu

Telefon tarafı yazıldı: Bluetooth yayını, düdük, 112 yönlendirmesi, yakındaki çağrıları dinleme, ekran kapalıyken sürme. **Saha ölçümü yok; kurtarma aracı olarak sunulmamalı.**

**Sırada ölçülecek:**

- Betonarme bodrum, kolon arkası ve kapalı merdiven boşluğunda sinyalin ve düdüğün nerede kesildiği.
- SOS modunun pil ömrü. Hedef saatler değil, günler.
- Düdüğün gerçek ses düzeyi. Emülatör sessiz çalıştırıldı.

**Çözülecek konular:**

- Tetikleme: Baygın kişi elle başlatamaz, sarsıntıyla otomatik başlatmak yanlış alarm üretir.
- Sahte alarm: Herkes taklit edebilir, ekiplerin zamanını çalabilir.
- Dinleme yalnız Acil durum ekranı açıkken çalışır.
- İstasyon telefonların uzun menzil yayınını duymaz, yalnız normal yayını duyar.
- iPhone: Arka planda Bluetooth yayını çok sınırlı.
- Kurtarma ekiplerinin katılımı: AFAD ve arama kurtarma ekiplerinin görüşü alınmalı. Ekipler bu sinyali dinlemiyorsa kimse duymaz.
- Sabit SOS kimliği, "sabit kimlik yayınlanmaz" kuralının bilinçli istisnasıdır; mimari belgeye yazılmalı.
