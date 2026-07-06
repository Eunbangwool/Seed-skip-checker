/*
 * 콩 결주감지기 개조 펌웨어 (ESP32)
 * -------------------------------------------------------------
 * 기존 4채널 광센서의 "씨앗 통과" 원신호를 병렬로 읽어,
 * "간격 비율" 기반으로 진짜 결주만 판단하고 BLE로 폰에 알린다.
 *
 * 기존 장치의 고정시간(watchdog) 판단(=속도변하면 오탐)을 버리고,
 * 최근 씨앗간격의 이동중앙값 대비 이번 간격이 몇 배인지로 판단한다.
 * 땅바퀴 구동이라 씨앗 간격(cm)이 일정 → 배율이 아주 잘 맞는다.
 *   배율 ≈ 2 → 1개 빠짐(결주), ≈3 → 2개 빠짐, 아주 크면 선회·정지로 보고 무시.
 *
 * 보드: ESP32-WROOM-32 DevKitC (Arduino-ESP32 코어)
 * 라이브러리: 코어 내장 BLE (BLEDevice)  ── 별도 설치 불필요
 *
 * ★측정 후 확정할 것 2가지만 아래 CONFIG에서 바꾸면 됨:
 *   1) ACTIVE_EDGE  (씨앗 통과 시 신호가 오르면 RISING, 떨어지면 FALLING)
 *   2) USE_PULLUP   (오픈컬렉터 센서면 true)
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ===================== CONFIG (측정 후 여기만 손봄) =====================
#define NCH            4                 // 채널 수
const int PIN[NCH]   = {25, 26, 32, 33}; // 채널1~4 GPIO (docs/02 참고)

#define ACTIVE_EDGE    FALLING           // 씨앗 통과 엣지: RISING 또는 FALLING  ★측정 후 확정
#define USE_PULLUP     true              // 오픈컬렉터 센서면 true               ★측정 후 확정

// 판단 파라미터 (캡처 데이터로 미세조정 가능)
#define DEBOUNCE_US    2000              // 펄스 채터링 무시(µs). 씨앗 최소간격보다 작게
#define HIST_N         8                 // 이동중앙값 표본 수
#define WARMUP_N       5                 // 이만큼 정상펄스 모여야 판단 시작
#define R_NORMAL_MAX   1.6f              // 이 배율 이하 = 정상(중앙값에 반영)
#define R_MULTI_MAX    6.5f              // 이 배율 이하까지 결주로 인정(최대 ~5개 빠짐)
                                         // 초과하면 선회/정지/지두회전으로 보고 무시
#define STAT_MS        400               // 상태 전송 주기(ms)

#define DEV_NAME       "KongGyeolju"     // BLE 장치명
// Nordic UART Service (Web Bluetooth 호환)
#define NUS_SVC  "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX   "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // 폰→ESP32 (write)
#define NUS_TX   "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32→폰 (notify)
// =====================================================================

// ---- 채널별 판단 상태 (Arduino 자동 프로토타입보다 먼저 정의되어야 함) ----
struct Chan {
  uint16_t proc = 0;          // 처리한 엣지 수
  uint32_t lastSeedUs = 0;
  uint32_t hist[HIST_N];      // 최근 정상 간격(µs)
  int      histLen = 0;
  int      histPos = 0;
  uint32_t seedCount = 0;     // 감지한 씨앗 수
  uint32_t skipEvents = 0;    // 결주 이벤트 수
  uint32_t missedTotal = 0;   // 누락 추정 씨앗 총합
  uint32_t lastSeedForRate = 0;
} ch[NCH];

// ---- ISR 공유 상태 ----
volatile uint32_t g_lastEdgeUs[NCH] = {0};
volatile uint32_t g_edgeTimeUs[NCH] = {0};
volatile uint16_t g_edgeCount[NCH]  = {0};

void IRAM_ATTR onEdge(int ch) {   // inline 금지: IRAM 리터럴 재배치 오류(dangerous relocation) 방지
  uint32_t now = micros();
  if (now - g_lastEdgeUs[ch] < DEBOUNCE_US) return; // 디바운스
  g_lastEdgeUs[ch] = now;
  g_edgeTimeUs[ch] = now;
  g_edgeCount[ch]++;
}
void IRAM_ATTR isr0() { onEdge(0); }
void IRAM_ATTR isr1() { onEdge(1); }
void IRAM_ATTR isr2() { onEdge(2); }
void IRAM_ATTR isr3() { onEdge(3); }
void (*ISRS[NCH])() = {isr0, isr1, isr2, isr3};

// ---- 채널별 판단 상태 (Chan 구조체는 파일 상단 CONFIG 아래로 이동) ----

// ---- BLE ----
BLECharacteristic* txChar = nullptr;
volatile bool bleConnected = false;

class SrvCB : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { bleConnected = true; }
  void onDisconnect(BLEServer* s) override { bleConnected = false; s->getAdvertising()->start(); }
};

void resetCounts() {
  for (int i = 0; i < NCH; i++) {
    ch[i].seedCount = 0; ch[i].skipEvents = 0; ch[i].missedTotal = 0;
    ch[i].histLen = 0; ch[i].histPos = 0; ch[i].lastSeedUs = 0;
  }
}

class RxCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue().c_str();
    v.trim();
    if (v == "reset") resetCounts();
  }
};

void bleSend(const String& line) {
  if (!bleConnected || !txChar) return;
  String s = line + "\n";
  txChar->setValue((uint8_t*)s.c_str(), s.length());
  txChar->notify();
}

uint32_t medianOf(uint32_t* a, int n) {
  uint32_t t[HIST_N];
  for (int i = 0; i < n; i++) t[i] = a[i];
  for (int i = 1; i < n; i++) {          // 삽입정렬
    uint32_t k = t[i]; int j = i - 1;
    while (j >= 0 && t[j] > k) { t[j + 1] = t[j]; j--; }
    t[j + 1] = k;
  }
  return (n & 1) ? t[n / 2] : (t[n / 2 - 1] + t[n / 2]) / 2;
}

void pushHist(Chan& c, uint32_t gap) {
  c.hist[c.histPos] = gap;
  c.histPos = (c.histPos + 1) % HIST_N;
  if (c.histLen < HIST_N) c.histLen++;
}

// 씨앗 1개 처리(타이밍 판단). now = 이번 씨앗 시각(µs)
void processSeed(int idx, uint32_t now) {
  Chan& c = ch[idx];
  if (c.lastSeedUs != 0 && c.histLen >= WARMUP_N) {
    uint32_t gap = now - c.lastSeedUs;
    uint32_t med = medianOf(c.hist, c.histLen);
    if (med == 0) med = 1;
    float r = (float)gap / (float)med;

    if (r <= R_NORMAL_MAX) {
      pushHist(c, gap);                              // 정상 간격
    } else if (r <= R_MULTI_MAX) {
      int miss = (int)lroundf(r) - 1;               // ≈2배→1개, ≈3배→2개
      if (miss < 1) miss = 1;
      c.skipEvents++;
      c.missedTotal += miss;
      bleSend(String("{\"t\":\"skip\",\"ch\":") + (idx + 1) +
              ",\"miss\":" + miss +
              ",\"tot\":" + c.missedTotal + "}");
      pushHist(c, gap / lroundf(r));                 // 씨앗당 간격 추정치로 속도 추종
    }
    // r > R_MULTI_MAX : 선회/정지 → 무시(중앙값에도 반영 안 함)
  } else if (c.lastSeedUs != 0) {
    pushHist(c, now - c.lastSeedUs);                 // 워밍업: 표본만 수집
  }
  c.lastSeedUs = now;
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[콩결주] 부팅");

  for (int i = 0; i < NCH; i++) {
    pinMode(PIN[i], USE_PULLUP ? INPUT_PULLUP : INPUT);
    attachInterrupt(digitalPinToInterrupt(PIN[i]), ISRS[i], ACTIVE_EDGE);
  }

  BLEDevice::init(DEV_NAME);
  BLEServer* srv = BLEDevice::createServer();
  srv->setCallbacks(new SrvCB());
  BLEService* svc = srv->createService(NUS_SVC);
  txChar = svc->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());
  BLECharacteristic* rx = svc->createCharacteristic(NUS_RX, BLECharacteristic::PROPERTY_WRITE);
  rx->setCallbacks(new RxCB());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SVC);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[콩결주] BLE 광고 시작: " DEV_NAME);
}

uint32_t lastStat = 0;

void loop() {
  // 1) 새 엣지 처리
  for (int i = 0; i < NCH; i++) {
    uint16_t cnt = g_edgeCount[i];
    uint32_t t   = g_edgeTimeUs[i];
    if (cnt != ch[i].proc) {
      uint16_t delta = (uint16_t)(cnt - ch[i].proc);
      ch[i].seedCount += delta;      // 계수 정확도 유지
      processSeed(i, t);             // 타이밍 판단(대표시각 1회)
      ch[i].proc = cnt;
    }
  }

  // 2) 주기적 상태 전송
  uint32_t nowMs = millis();
  if (nowMs - lastStat >= STAT_MS) {
    lastStat = nowMs;
    String s = "{\"t\":\"stat\",\"s\":[";
    for (int i = 0; i < NCH; i++) { s += ch[i].seedCount; if (i < NCH - 1) s += ","; }
    s += "],\"k\":[";
    for (int i = 0; i < NCH; i++) { s += ch[i].skipEvents; if (i < NCH - 1) s += ","; }
    s += "],\"m\":[";
    for (int i = 0; i < NCH; i++) { s += ch[i].missedTotal; if (i < NCH - 1) s += ","; }
    s += "]}";
    bleSend(s);
  }
}
