# Protokol notları

Başka bir platformda (iOS, masaüstü) yazılacak uygulamanın Android ile konuşması için gereken bayt düzeyi tanım. Şartname taslağıdır, bağımsız incelemeden geçmedi.

**Sınav:** Yeni uygulama [protocol_vectors.json](../core/src/jvmTest/resources/protocol_vectors.json) ve Noise vektörlerinden bayt bayt geçmelidir. Bu dosyaları Kotlin kodundan bağımsız Python betikleri üretir: [gen_protocol_vectors.py](../tools/gen_protocol_vectors.py), [gen_noise_vectors.py](../tools/gen_noise_vectors.py). Karşılaştırma testleri: `ProtocolVectorTest`, `NoiseVectorTest`.

Tamsayılar büyük uçludur. Metinler UTF-8'dir.

## Ağ kodu ve anahtarlar

| Değer | Tanım |
|---|---|
| Ağ kodu | 26 karakter Base32 (A-Z, 2-7) + 2 karakter sağlama |
| Sağlama | `d = SHA256("LD-CHK-v1" + gövde)`, `bit = (d[0] << 2) \| (d[1] >> 6)`, 10 biti iki karakter |
| Network ID | `SHA256("LD-NET-v1-U" + gövde)` |
| PSK, keşif, LoRa anahtarı | HKDF-SHA256, girdi gövde, tuz yok, bilgi `LD-PSK-v1`, `LD-DISC-v1`, `LD-LORA-v1`, 32 bayt |
| Yönetilen ağ kimliği | `SHA256("LD-NET-v1" + Canon(eşik, sıralı anahtarlar...))` |

`Canon(a, b, ...)`: her alan için 4 baytlık uzunluk + bayt.

## Oturum

`Noise_XXpsk0_25519_ChaChaPoly_SHA256`, prologue `"LD-PROTO-1|" + Network ID`. TCP veya L2CAP üzerinde: el sıkışma mesajları 2 bayt uzunluk öneklidir.

Oturumda her parça: 2 bayt uzunluk + Noise şifreli `[bitti bayrağı 1][veri]`. Birleşen mesajın ilk baytı türdür: `1` JSON denetim mesajı, `2` dosya parçası `[sıra 4][veri]`. Birleşmiş mesaj en çok 4 MB.

Denetim mesajları: [Messages.kt](../core/src/commonMain/kotlin/org/lokaldirenc/core/Messages.kt).

Doğrulama kodu: `Base32(SHA256("LD-SAS-v1" + el sıkışma özeti))` ilk 8 karakter. Parmak izi: `SHA256(statik açık anahtar)`.

## Yerel ağ keşfi

UDP 47474, 268 baytlık yayın: `[nonce 12][ChaCha20-Poly1305: uzunluk 2 + JSON + sıfır dolgu, toplam 240][etiket 16]`. Anahtar keşif anahtarı. JSON: `{"port","fp","nick"}`.

## Bluetooth reklamı

Tek bir 128 bitlik hizmet kimliği. iOS uygulamaları reklamda "service data" yayınlayamaz, hizmet kimliği yayınlayabilir.

```text
[belirteç 10][L2CAP kanalı 2][rol etiketi 4]
```

- Belirteç: `HMAC-SHA256(keşif anahtarı, "LD-BLE-v1|" + dönem)` ilk 10 bayt. Dönem: Unix ms / 600000. Alıcı ±3 dönemi kabul eder.
- Rol etiketi: rastgele. Karşılaşan iki cihazdan etiketi büyük olan arar.

L2CAP eşleştirmesiz kanal; güvenlik Noise'tan gelir.

## SOS

Tek 128 bitlik hizmet kimliği, ağ kodundan bağımsız, imzasız, herkese açık:

```text
[4c442d534f5340008000][sürüm 1 = 01][oturum 4][pil yüzdesi 1]
```

## Mesh zarfı

JSON `MeshWire`. İmza `Ed25519(yazar, "LD-MESH-1|" + Network ID + "|tür|zaman|gövde_base64")`. Kimlik: `SHA256(imzalanan bayt + yazar hex + imza hex)`. Atlama sayısı imzaya girmez.

- `presence`: `{"box","nick"}`, `public`: `{"text"}`.
- `direct`: `Noise_X_25519_ChaChaPoly_SHA256`, prologue `"LD-MESH-1|" + Network ID`. Yazar ve imza boştur. İçerik 512 baytın katına doldurulur: `[uzunluk 2][JSON]`.

## LoRa çerçevesi

En çok 200 bayt: `[ttl 1][tür 1][veri]`. Tür `1`: `[nonce 12][ChaCha20-Poly1305: mesaj no 4, parça 1, toplam 1, veri][etiket 16]`, anahtar LoRa anahtarı. Tür `2`: SOS, `[sos verisi 6][RSSI 1]`. TTL kimlik doğrulamaya girmez, istasyon azaltır.

Sıkı zarf: `[tür 1: 0 presence, 1 public, 2 direct][zaman 8][atlama 1][yazar 32 + imza 64, direct'te yok][gövde]`.

## Yönetim zinciri

İmzalanan: `Canon("LD-GOV-v1", ağ, tür, sıra, önceki özet, zaman, içerik)`. Olay özeti bunun SHA-256'sıdır. Kurallar: [Governance.kt](../core/src/commonMain/kotlin/org/lokaldirenc/core/Governance.kt).

## Yönetilen ağda oturum ve üyelik

Network ID kamuya açıktır ve yönetici kanıtı değildir. Bağlantı anahtarları rastgele 32 baytlık **ağ sırrından** türer: `HKDF(ağ sırrı, "LD-MANAGED-v1|psk")`, aynı şekilde `disc` ve `lora`. Sırrı bilmeyen el sıkışamaz, keşif paketlerini ve LoRa çerçevelerini açamaz.

Sır, `ld://managed?d=` bağlantısıyla gider (Base64Url JSON: kimlik, sır, ad, mod ipucu, varsa bilet ve davet sırrı). Açık ve karma ağda QR ile paylaşılır, davetli ağda yalnız davetin içinde gider. Kurulum paketi: `ld://sign?d=`.

Eşitlemede önce `gov_have` / `gov_events` ile zincir değişilir, sonra başlatan `mesh_end` ile onaylar (Bluetooth'ta erken kapanış son paketi düşürür). Ardından giriş modu uygulanır: açık herkese tam, karma onaysıza salt okunur, davetli onaysıza kapalı. Sonra `gov_proposals` ile onay bekleyen kararlar değişilir; geçersiz imzalar atılır.

Yönetici kanıtı: yönetici anahtarıyla `Canon("LD-ADMIN-MEMBER-v1", ağ, parmak izi)`.

**Ağ sırrı yenileme:** `secret_rotate` olayı `{epoch, sealed[]}` taşır. Her mühür `Noise_X` ile bir üyeye yazılır, prologue `"LD-SECRET-1|ağ|dönem"`, içerik yeni sır. Üye hepsini dener. Eski sır 72 saat daha kabul edilir: yanıtlayan iki anahtarı da dener, keşif iki anahtarla yapılır, LoRa iki anahtarla açılır ama yalnız yeniyle yazılır. Bluetooth'ta reklam yeni anahtarladır; eski belirteci gören cihaz, rol etiketine bakmadan eski anahtarla arar ki yeni sır karşıya ulaşsın.

**Tanıklık:** Yönetilen ağda bir üye ilk görüldüğünde `witness` zarfı imzalanır: `{subject}`, zaman zarfın kendisinden. Tanık başına ilk gözlem geçerlidir. Süre, en az K (3) tanığın medyanıdır.

- Davet bileti: yöneticiler eşik kadar `Canon("LD-INVITE-v1", ağ, davet no, davet anahtarı, bitiş, en çok kullanım)` imzalar.
- Üyelik kanıtı: sır olan davet anahtarıyla `Canon("LD-MEMBER-v1", ağ, davet no, parmak izi, zaman)` imzalanır.

## Ortak kod ve platforma özgü kod

| Yer | İçerik |
|---|---|
| `core/src/commonMain` | Bu belgedeki biçimler, Noise, mesh, LoRa, yönetim. Her platformda aynı |
| `core/src/jvmMain` | `Node` (soket, iş parçacığı, dosya), oturum çerçeveleme, dosya güvenliği, yerel indirme, BouncyCastle |
| `core/src/nativeMain` | Yer tutucu: şifreleme uygulaması henüz yok |
| `app` | Android arayüzü, Bluetooth, Keystore |

Yeni platform için: `CryptoProvider` uygulaması, `jvmMain`'deki düğüm mantığının o platforma taşınması, kendi Bluetooth ve arayüz katmanı.
