// Lokal Direnç LoRa istasyonu.
//
// Ağ kodu ve anahtar taşımaz: çerçeveler şifrelidir, istasyon okuyamaz. Yalnız aktarır:
//   telefon -> Bluetooth -> istasyon -> LoRa
//   LoRa -> istasyon -> yeniden yayın (TTL > 0) ve bağlı telefonlar
// Bu yüzden cihaz çalınsa bile içinden ağ kodu çıkmaz. Bedeli: üye olmayanların sahte
// çerçevelerini de ayıramaz; kanalı bunlar da doldurabilir. Yayın süresi sınırı yine geçerlidir.
//
// Çerçeve biçimi telefonla ortaktır (core/.../Lora.kt): [ttl][tür][veri]

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <RadioLib.h>
#include <SPI.h>
#include <WiFi.h>

#include "config.h"

static const char* SERVICE_UUID = "4c442d4c-4f52-4100-8000-000000000001";
static const char* TX_UUID = "4c442d4c-4f52-4100-8000-000000000002";
static const char* RX_UUID = "4c442d4c-4f52-4100-8000-000000000003";
static const char* SOS_UUID = "4c442d53-4f53-4000-8000-000000000001";

static const uint8_t KIND_SOS = 2;
static const uint8_t SOS_TTL = 2;
static const size_t SOS_SIZE = 6;
static const size_t MAX_FRAME = 200;
static const size_t TX_QUEUE = 16;
static const size_t SEEN = 128;
static const size_t RETAINED = 32;
static const uint32_t SOS_REPEAT_MS = 5UL * 60UL * 1000UL;
static const uint32_t DUTY_WINDOW_MS = 60UL * 60UL * 1000UL;

struct Frame {
    uint8_t len;
    uint8_t data[MAX_FRAME];
};

static SX1262 radio = new Module(LD_PIN_NSS, LD_PIN_DIO1, LD_PIN_RST, LD_PIN_BUSY);

static NimBLECharacteristic* rxCharacteristic = nullptr;
static volatile bool radioEvent = false;

static portMUX_TYPE queueLock = portMUX_INITIALIZER_UNLOCKED;
static Frame txQueue[TX_QUEUE];
static size_t txHead = 0, txCount = 0;

static uint32_t seen[SEEN];
static size_t seenNext = 0;

// Telefon bağlanınca verilmek üzere son çerçeveler: taşı-ve-ilet. Elektrik kesilince silinir.
static Frame retained[RETAINED];
static size_t retainedNext = 0, retainedCount = 0;

// Yayın süresi defteri: her dakikalık dilimde harcanan ms. Toplam, pencerede sınırı aşamaz.
static uint32_t airtimeBuckets[60];
static uint32_t lastBucketMinute = 0;

#if LD_SOS_RELAY
struct SosSeen {
    uint32_t session;
    uint32_t at;
};
static SosSeen sosSeen[16];
#endif

// TTL hariç özet: aynı çerçeve TTL azalmış halde dönünce de tanınsın.
static uint32_t frameHash(const uint8_t* data, size_t len) {
    uint32_t h = 2166136261u;
    for (size_t i = 1; i < len; i++) {
        h ^= data[i];
        h *= 16777619u;
    }
    return h;
}

static bool alreadySeen(uint32_t h) {
    for (size_t i = 0; i < SEEN; i++) {
        if (seen[i] == h) return true;
    }
    seen[seenNext] = h;
    seenNext = (seenNext + 1) % SEEN;
    return false;
}

static bool enqueue(const uint8_t* data, size_t len) {
    if (len < 2 || len > MAX_FRAME) return false;
    bool ok = false;
    portENTER_CRITICAL(&queueLock);
    if (txCount < TX_QUEUE) {
        Frame& f = txQueue[(txHead + txCount) % TX_QUEUE];
        f.len = len;
        memcpy(f.data, data, len);
        txCount++;
        ok = true;
    }
    portEXIT_CRITICAL(&queueLock);
    return ok;
}

static void retain(const uint8_t* data, size_t len) {
    Frame& f = retained[retainedNext];
    f.len = len;
    memcpy(f.data, data, len);
    retainedNext = (retainedNext + 1) % RETAINED;
    if (retainedCount < RETAINED) retainedCount++;
}

static void notifyPhones(const uint8_t* data, size_t len) {
    if (rxCharacteristic == nullptr) return;
    rxCharacteristic->setValue(data, len);
    rxCharacteristic->notify();
}

static void rollAirtime() {
    uint32_t minute = millis() / 60000UL;
    while (lastBucketMinute != minute) {
        lastBucketMinute++;
        airtimeBuckets[lastBucketMinute % 60] = 0;
    }
}

static uint32_t airtimeUsedMs() {
    rollAirtime();
    uint32_t total = 0;
    for (uint32_t b : airtimeBuckets) total += b;
    return total;
}

static bool airtimeAllows(uint32_t needMs) {
    uint32_t limit = (uint32_t)(DUTY_WINDOW_MS * (LD_DUTY_PERCENT / 100.0));
    return airtimeUsedMs() + needMs <= limit;
}

class TxCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c) override {
        NimBLEAttValue v = c->getValue();
        if (v.size() < 2 || v.size() > MAX_FRAME) return;
        // Kendi gönderdiğimiz LoRa'dan geri gelirse aktarılmasın.
        alreadySeen(frameHash(v.data(), v.size()));
        enqueue(v.data(), v.size());
    }
};

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*) override {
        // Bağlantı başına bir tane daha bağlantıya izin vermek için reklam sürer.
        NimBLEDevice::startAdvertising();
    }
    void onDisconnect(NimBLEServer*) override {
        NimBLEDevice::startAdvertising();
    }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
    // Telefon bildirime abone olunca saklanan çerçeveleri verir: uzaktayken gelenler böyle ulaşır.
    void onSubscribe(NimBLECharacteristic* c, ble_gap_conn_desc*, uint16_t subValue) override {
        if (subValue == 0) return;
        for (size_t i = 0; i < retainedCount; i++) {
            size_t index = (retainedNext + RETAINED - retainedCount + i) % RETAINED;
            c->setValue(retained[index].data, retained[index].len);
            c->notify();
            delay(20);
        }
    }
};

#if LD_SOS_RELAY
// Telefon SOS yayını duyulunca LoRa'ya aktarılır. Oturum başına aralıklı: kanalı doldurmasın.
class SosScanCallbacks : public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice* device) override {
        NimBLEUUID uuid(SOS_UUID);
        std::string data = device->getServiceData(uuid);
        if (data.size() != SOS_SIZE || data[0] != 1) return;
        uint32_t session;
        memcpy(&session, data.data() + 1, 4);
        uint32_t now = millis();
        size_t oldest = 0;
        for (size_t i = 0; i < 16; i++) {
            if (sosSeen[i].at != 0 && sosSeen[i].session == session) {
                if (now - sosSeen[i].at < SOS_REPEAT_MS) return;
                oldest = i;
                break;
            }
            if (sosSeen[i].at < sosSeen[oldest].at) oldest = i;
        }
        sosSeen[oldest] = {session, now == 0 ? 1 : now};
        uint8_t frame[2 + SOS_SIZE + 1];
        frame[0] = SOS_TTL;
        frame[1] = KIND_SOS;
        memcpy(frame + 2, data.data(), SOS_SIZE);
        frame[2 + SOS_SIZE] = (int8_t)device->getRSSI();
        alreadySeen(frameHash(frame, sizeof(frame)));
        enqueue(frame, sizeof(frame));
        notifyPhones(frame, sizeof(frame));
    }
};
#endif

static void setupBle() {
    NimBLEDevice::init("");
    NimBLEDevice::setMTU(247);
    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    NimBLEService* service = server->createService(SERVICE_UUID);
    NimBLECharacteristic* tx = service->createCharacteristic(TX_UUID, NIMBLE_PROPERTY::WRITE);
    tx->setCallbacks(new TxCallbacks());
    rxCharacteristic = service->createCharacteristic(RX_UUID, NIMBLE_PROPERTY::NOTIFY);
    rxCharacteristic->setCallbacks(new RxCallbacks());
    service->start();
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->start();
#if LD_SOS_RELAY
    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(new SosScanCallbacks(), true);
    scan->setActiveScan(false);
    scan->setInterval(160);
    scan->setWindow(80);
    scan->start(0, nullptr, false);
#endif
}

static void onRadioEvent() {
    radioEvent = true;
}

static void startListening() {
    radio.startReceive();
}

static void handleReceived() {
    size_t len = radio.getPacketLength();
    if (len < 2 || len > MAX_FRAME) return;
    uint8_t data[MAX_FRAME];
    if (radio.readData(data, len) != RADIOLIB_ERR_NONE) return;
    if (alreadySeen(frameHash(data, len))) return;
    retain(data, len);
    notifyPhones(data, len);
    if (data[0] > 0) {
        data[0]--;
        enqueue(data, len);
    }
}

static void transmitNext() {
    Frame f;
    portENTER_CRITICAL(&queueLock);
    if (txCount == 0) {
        portEXIT_CRITICAL(&queueLock);
        return;
    }
    f = txQueue[txHead];
    portEXIT_CRITICAL(&queueLock);

    uint32_t need = radio.getTimeOnAir(f.len) / 1000;
    // Hak yoksa kuyrukta bekler; kuyruk dolarsa yeni çerçeveler düşer.
    if (!airtimeAllows(need)) return;

    portENTER_CRITICAL(&queueLock);
    txHead = (txHead + 1) % TX_QUEUE;
    txCount--;
    portEXIT_CRITICAL(&queueLock);

    int state = radio.transmit(f.data, f.len);
    if (state == RADIOLIB_ERR_NONE) {
        rollAirtime();
        airtimeBuckets[lastBucketMinute % 60] += need;
    }
    radioEvent = false;
    startListening();
}

void setup() {
    Serial.begin(115200);
    SPI.begin(LD_PIN_SCK, LD_PIN_MISO, LD_PIN_MOSI, LD_PIN_NSS);
    int state = radio.begin(LD_FREQ_MHZ, LD_BANDWIDTH_KHZ, LD_SPREADING, LD_CODING_RATE, LD_SYNC_WORD, LD_TX_DBM, LD_PREAMBLE);
    if (state != RADIOLIB_ERR_NONE) {
        Serial.printf("Radyo baslatilamadi: %d\n", state);
        while (true) delay(1000);
    }
    radio.setDio1Action(onRadioEvent);
    startListening();
#if LD_WIFI_AP
    WiFi.softAP(LD_WIFI_SSID, nullptr, 1, 0, LD_WIFI_MAX_CLIENTS);
#endif
    setupBle();
    Serial.println("Lokal Direnc istasyonu hazir");
}

void loop() {
    if (radioEvent) {
        radioEvent = false;
        handleReceived();
        startListening();
    }
    transmitNext();
    delay(5);
}
