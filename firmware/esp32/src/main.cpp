#include <Arduino.h>
#include <BLEDevice.h>
#include <BLE2902.h>
#include <DHT.h>
#include <atomic>

constexpr char SERVICE[] = "e89ba400-b5f4-49bc-a83c-76c54ae84001";
constexpr char METRIC[] = "e89ba400-b5f4-49bc-a83c-76c54ae84002";
DHT dht(4, DHT11);
BLECharacteristic* telemetry;
BLE2902* subscription;
std::atomic<bool> advertiseAgain{false};
uint32_t sampled;

class Connections : public BLEServerCallbacks {
    void onConnect(BLEServer*) override { Serial.println("Cliente conectado"); }
    void onDisconnect(BLEServer*) override {
        subscription->setNotifications(false);
        advertiseAgain = true;
    }
};

void setup() {
    Serial.begin(115200);
    dht.begin();
    BLEDevice::init("SensorHub-ESP32");
    auto server = BLEDevice::createServer();
    server->setCallbacks(new Connections());
    auto service = server->createService(SERVICE);
    telemetry = service->createCharacteristic(METRIC, BLECharacteristic::PROPERTY_NOTIFY);
    subscription = new BLE2902();
    telemetry->addDescriptor(subscription);
    service->start();
    auto advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE);
    advertising->setScanResponse(true);
    advertising->start();
    Serial.println("SensorHub-ESP32 anunciando");
}

void loop() {
    if (advertiseAgain.exchange(false)) BLEDevice::startAdvertising();
    if (millis() - sampled < 2000) { delay(5); return; }
    sampled = millis();
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (!isfinite(t) || !isfinite(h) || t < 0 || t > 50 || h < 0 || h > 100) {
        Serial.println("Lectura DHT11 descartada");
        return;
    }
    uint16_t temp = lroundf(t*10), humidity = lroundf(h*10);
    uint8_t packet[] = {1, 1, uint8_t(temp), uint8_t(temp >> 8), uint8_t(humidity), uint8_t(humidity >> 8)};
    Serial.printf("%.1f C, %.1f %%\n", t, h);
    if (subscription->getNotifications()) {
        telemetry->setValue(packet,sizeof(packet));
        telemetry->notify();
    }
}
