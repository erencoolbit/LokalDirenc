#pragma once

// ---- BÖLGE AYARLARI: YÜKLEMEDEN ÖNCE DOLDURUN ----
//
// Frekans, güç ve yayın süresi sınırları ülkeye göre değişir. Varsayılan bilerek yoktur:
// yanlış değerle yayın yapmak yasadışı olabilir. Türkiye için güncel kuralları BTK'dan
// doğrulayın ve kartınızın anten ve bandına uygun değerleri girin. Sonra aşağıdaki
// satırların başındaki // işaretini kaldırın.
//
// #define LD_FREQ_MHZ 000.000      // Yasal ve kartınıza uygun frekans
// #define LD_TX_DBM 14             // Yasal güç sınırı (anten kazancı dahil)
// #define LD_DUTY_PERCENT 1.0      // Yasal yayın süresi sınırı (%)

#if !defined(LD_FREQ_MHZ) || !defined(LD_TX_DBM) || !defined(LD_DUTY_PERCENT)
#error "src/config.h: bolge ayarlarini doldurun. Yasal frekans, guc ve yayin suresi sinirlari ulkeye gore degisir."
#endif

// LoRa radyo ayarları. Bütün istasyonlarda aynı olmalı.
#define LD_BANDWIDTH_KHZ 125.0
#define LD_SPREADING 9
#define LD_CODING_RATE 7
#define LD_SYNC_WORD 0x4C
#define LD_PREAMBLE 8

// Kendi Wi-Fi ağı: telefonlar uygulamanın mevcut keşif ve eşitleme yolunu kullanabilir.
// Şifre yoktur: içerik uygulamada zaten Noise ile şifrelidir. Ağ adı sabittir, görünürlüğü artırır.
#define LD_WIFI_AP 1
#define LD_WIFI_SSID "LokalDirenc"
#define LD_WIFI_MAX_CLIENTS 8

// Yakındaki telefonların SOS yayınını dinleyip LoRa'ya aktarır.
#define LD_SOS_RELAY 1

// Heltec WiFi LoRa 32 V3 pinleri (SX1262).
#define LD_PIN_NSS 8
#define LD_PIN_DIO1 14
#define LD_PIN_RST 12
#define LD_PIN_BUSY 13
#define LD_PIN_SCK 9
#define LD_PIN_MISO 11
#define LD_PIN_MOSI 10
