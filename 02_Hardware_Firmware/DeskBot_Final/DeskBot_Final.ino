#define MQTT_MAX_PACKET_SIZE 1024

#include <ESP8266WiFi.h>
#include <ESP8266WiFiMulti.h> 
#include <ESP8266mDNS.h>
#include <WiFiUdp.h>
#include <ArduinoOTA.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SH110X.h> 
#include <time.h>
#include "secrets.h"

// ==========================================
// --- HARDWARE & DISPLAY SETUP ---
// ==========================================
#define WHITE SH110X_WHITE
#define BLACK SH110X_BLACK
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1 
Adafruit_SH1106G display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

#define BUZZER_PIN 12 // D6
#define TOUCH_PIN 14  // D5

// ==========================================
// --- WI-FI & AWS CONFIG ---
// ==========================================
ESP8266WiFiMulti wifiMulti;
const char* aws_endpoint = "a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com";

// 1. Amazon Root CA 1
static const char AWS_CERT_CA[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
MIIDQTCCAimgAwIBAgITBmyfz5m/jAo54vB4ikPmljZbyjANBgkqhkiG9w0BAQsF
ADA5MQswCQYDVQQGEwJVUzEPMA0GA1UEChMGQW1hem9uMRkwFwYDVQQDExBBbWF6
b24gUm9vdCBDQSAxMB4XDTE1MDUyNjAwMDAwMFoXDTM4MDExNzAwMDAwMFowOTEL
MAkGA1UEBhMCVVMxDzANBgNVBAoTBkFtYXpvbjEZMBcGA1UEAxMQQW1hem9uIFJv
b3QgQ0EgMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALJ4gHHKeNXj
ca9HgFB0fW7Y14h29Jlo91ghYPl0hAEvrAIthtOgQ3pOsqTQNroBvo3bSMgHFzZM
9O6II8c+6zf1tRn4SWiw3te5djgdYZ6k/oI2peVKVuRF4fn9tBb6dNqcmzU5L/qw
IFAGbHrQgLKm+a/sRxmPUDgH3KKHOVj4utWp+UhnMJbulHheb4mjUcAwhmahRWa6
VOujw5H5SNz/0egwLX0tdHA114gk957EWW67c4cX8jJGKLhD+rcdqsq08p8kDi1L
93FcXmn/6pUCyziKrlA4b9v7LWIbxcceVOF34GfID5yHI9Y/QCB/IIDEgEw+OyQm
jgSubJrIqg0CAwEAAaNCMEAwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMC
AYYwHQYDVR0OBBYEFIQYzIU07LwMlJQuCFmcx7IQTgoIMA0GCSqGSIb3DQEBCwUA
A4IBAQCY8jdaQZChGsV2USggNiMOruYou6r4lK5IpDB/G/wkjUu0yKGX9rbxenDI
U5PMCCjjmCXPI6T53iHTfIUJrU6adTrCC2qJeHZERxhlbI1Bjjt/msv0tadQ1wUs
N+gDS63pYaACbvXy8MWy7Vu33PqUXHeeE6V/Uq2V8viTO96LXFvKWlJbYK8U90vv
o/ufQJVtMVT8QtPHRh8jrdkPSHCa2XV4cdFyQzR1bldZwgJcJmApzyMZFo6IQ6XU
5MsI+yMRQ+hDKXJioaldXgjUkK642M4UwtBV8ob2xJNDd2ZhwLnoQdeXeGADbkpy
rqXRfboQnoZsG4q5WTP468SQvvG5
-----END CERTIFICATE-----
)EOF";

// 2. Device Certificate
static const char AWS_CERT_CRT[] PROGMEM = R"EOF(
-----BEGIN CERTIFICATE-----
MIIDWTCCAkGgAwIBAgIUd7Il1ri49s3DU0diTW3JlvBqhJYwDQYJKoZIhvcNAQEL
BQAwTTFLMEkGA1UECwxCQW1hem9uIFdlYiBTZXJ2aWNlcyBPPUFtYXpvbi5jb20g
SW5jLiBMPVNlYXR0bGUgU1Q9V2FzaGluZ3RvbiBDPVVTMB4XDTI2MDcyNzA1NTIx
NVoXDTQ5MTIzMTIzNTk1OVowHjEcMBoGA1UEAwwTQVdTIElvVCBDZXJ0aWZpY2F0
ZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKInyu8CAyHUUhQYvpkF
gN2nuFXM+AcigxeCAgluktDaY1/K8UwYF6uwtpsW5S4GfZ9WUpQcpGdgQXiwwMQl
EmNqKiyEFGcXXUmBsVQN4UriJyami6R5YhHeSsou/4IvV5ejfbqVkmMrZIN8kaCj
u/q53k3nlKO3g2b3Y4alprCnRaBBYyKGJw0Se7qHFO4TMftSi9KXLP6JFXh65IUu
sK+fs9PB7SPb4nqo0GMaPW6O+6s9z0sHdhTLvX5dML5DQfHQ4//gLDeqNPVMbYMB
kYX6TpX7SoApC0HhSdZzMk6Io2lSc3/3/0BRpoXsenYh0uKlcvC9GOx7iUtlSYZi
+1sCAwEAAaNgMF4wHwYDVR0jBBgwFoAU8xlYUCAMKUtPOMRQUlVdYHqFxMcwHQYD
VR0OBBYEFPwcX1gxHD3E3wl7YCrN2T8TI72GMAwGA1UdEwEB/wQCMAAwDgYDVR0P
AQH/BAQDAgeAMA0GCSqGSIb3DQEBCwUAA4IBAQBdxGyiJCkp3xVE3gACIlokk/OJ
WqrXnh0GIOy/GY1yS/3pDorDZ5Yjrtq67FcZb04bCvCHa7udtWKfIFIrrGw60X6K
xcO8HH/W8/497il3BSOkaE+qKEs836gtfk4JjyUg7w9uunvzEg8DSYoIJp7ebNGc
Eake2hO/B51TGlvTD5xE2cylu8hlxu3CqS4lzzYikcJ6sdtRFblhNUlyYko3wvz2
rG5yhn7hZzwpJto83kZEEEHDC0l3Xe2Hx3vPcFUtCEyVEWbJ4E67NBv5tsSk64GL
21dTapsfGhd3ZcgKVQy3LqIglMaMe3vev28lhYgVeb3kyBQ8snl+B7ERllTP
-----END CERTIFICATE-----
)EOF";

// 3. Private Key
static const char AWS_CERT_PRIVATE[] PROGMEM = R"EOF(
-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEAoifK7wIDIdRSFBi+mQWA3ae4Vcz4ByKDF4ICCW6S0NpjX8rx
TBgXq7C2mxblLgZ9n1ZSlBykZ2BBeLDAxCUSY2oqLIQUZxddSYGxVA3hSuInJqaL
pHliEd5Kyi7/gi9Xl6N9upWSYytkg3yRoKO7+rneTeeUo7eDZvdjhqWmsKdFoEFj
IoYnDRJ7uocU7hMx+1KL0pcs/okVeHrkhS6wr5+z08HtI9vieqjQYxo9bo77qz3P
Swd2FMu9fl0wvkNB8dDj/+AsN6o09UxtgwGRhfpOlftKgCkLQeFJ1nMyToijaVJz
f/f/QFGmhex6diHS4qVy8L0Y7HuJS2VJhmL7WwIDAQABAoIBACOaIwco5PEngcHh
0O8d8zY3XdSQxSAj5q5hn/rcKSXr3QeqMiJn48KRcPZKJ5VcXXZ/aHAWfc4opxdN
j3VBQnFLXOkbMrOTwKqj7AAtb/qV/syQonZBJFQR/0+20+iMZmnnvnIZ5uMZIt9v
mTWw3VPmPTXTk6ICZ7pStKpacme0aK+ZedWxNC44ZwAlkcSpKoAwNAsv+pvXyKtq
Q/IGk2zqE+WgPTlN3ZJpXmzD6wtn0I/S3NbzL5753/2hNTUxmmkHXgAU7Scj0YYa
Hfem4DHFJrMp3d0j27NCb/4YGXT1abQWV5tBB1bP4d/RG5jreTKsMfLPYcONtPAl
db/u3LkCgYEAzV6w3mVV488wE6cK1u9VfY5jSixmwdX6SoLOhz9DksBYuQTFXsMj
cu34dru3LHMecYafWH6N0Zv49Pd5hCynIITMmFEhIQ15X+1XLm3vmvfZzhgDc5TJ
Mm87/oR7NjlsP4QgFpdWPzJ4FAO0nWH9mwB8ZNDQumde2Bu4WGc4y88CgYEAyiHA
LII1nsDD32SfIe8ahPzId5Xta3/oCwFKzs/86wZci9TNSYP7OgvGDv+3C4O0ybND
PGSb08tLmuafBSUV/Z5Nhj5psbn2m2NyldrcDtjBbyzrGbVGmpYnD1qT/MZ5sbSy
V3jEJe4o1qMdYfZALym+77ga1H7k/xRXRz4lfrUCgYEAluqezs9o7p+ylL6MGKaI
DFw2WquGV6sG4vp9CqZS4eS3Tj9TSu/bfPmjPJ11XhS6qnJjZoSZcw9LQ7kYcyjd
J+hu8P+teLyOFRA0oMno6dh5FhpPbwBHWeikDcUNRiusgIB3lCJiHs7Xrt2KPJ1l
4CHbR7qrqE+OQwEb1tsDwcECgYAguUoVapcntml/lxi0M7OFaC9EPP+M8c4tSe5E
gSOfIQ6GjNMXXcikYxeTgg6uTIpORTlTY65eoYEB+OgxuwlOX/yJCT2vSj4Edhss
65vIw4q9ZvZA2Tou+LQmJjHjMHavMGPXCXQn9+CgrxOosmc9u3Jv+MxPLqQAL9jZ
bdRAKQKBgC0WWdP9TrRJUQjYguG9H9aFAij+Zx6iTLk+uI+Y7E531TMJhSoNSD5P
DjCdYkd+M9xt+EJJbs/yVHEbZbhwvfaFhQdE3WDtCTrWfiVbbjQRXGNjr46Ah5/t
DSXQkUch52z9Jy2q1Zo8uX08YCZKqieF647ejf+jTWkKuim/irRs
-----END RSA PRIVATE KEY-----
)EOF";

WiFiClientSecure net;
BearSSL::X509List cert(AWS_CERT_CRT);
BearSSL::PrivateKey key(AWS_CERT_PRIVATE);
BearSSL::X509List rootCA(AWS_CERT_CA);
PubSubClient client(net);

// ==========================================
// --- CLOUD & SYSTEM VARIABLES ---
// ==========================================
String currentAIState = "Good Posture";
int currentFocusScore = 100;
bool stateChanged = false;
String nextEvent = "Syncing with clock...";

bool isDND = false; 
bool buzzerEnabled = true;

// Dynamic Wellness Timers
unsigned long eyeCareInterval = 20 * 60000UL;   
unsigned long hydrationInterval = 45 * 60000UL; 
const unsigned long TWENTY_SECONDS = 20000UL;    

// High-Priority Alert Flags
volatile bool pendingHydrationAlert = false;
volatile bool pendingNotificationAlert = false;

// Tasks Sync Struct
struct SyncedTask {
  String title;
  String category;
  bool isDone;
};
SyncedTask taskList[5];
int taskCount = 0;

// Notification Data Store
String notifSource = "WhatsApp";
String notifSender = "DeskBot";
String notifMsg = "Test Alert";

int phoneStrikes = 0;
unsigned long lastViolationTime = 0; 
String deviceIP = "";

// ==========================================
// --- TIMERS & OS STATES ---
// ==========================================
int uiMode = 0; 
int currentMode = 0; 
unsigned long currentMillis = 0;

long customTimerDuration = 0; 
long customTimerStart = 0; 
unsigned long lastEyeCareTime = 0; 
unsigned long lastHydrationTime = 0;

// ==========================================
// --- TOUCH & OVERRIDE VARIABLES ---
// ==========================================
int tapCount = 0;
unsigned long lastTapTime = 0;
unsigned long buttonPressTime = 0;
bool isButtonPressed = false;
bool longPressTriggered = false;

bool isOverridden = false;
int overrideType = 0; 
unsigned long overrideEndTime = 0;

// ==========================================
// --- ANIMATION VARIABLES ---
// ==========================================
float currentLookX = 0, targetLookX = 0;    
float currentSquish = 0, targetSquish = 0;  
unsigned long lastLookChange = 0;
unsigned long lastBlinkTime = 0;
unsigned long lastPhoneAlarmTime = 0;

// Forward Declarations
void connectAWS();
void messageReceived(char* topic, byte* payload, unsigned int length);
void handleTouchLogic();
void publishWaterAck();
void enterModalHydrationScreen();
void displayModalNotificationCard();
void drawTimetableMenu();
void drawTasksMenu();
void drawDNDMenu();
void drawAutoPagingMenu();
void animateDrowsyFace();
void drawFluidFace();
void animateAngryFace();
void animateJudgingFace();
void drawSleepyFace();
void drawShyFace();
void drawGlitchFace();
void drawDizzyFace();
void drawHourglass(int progressPercent);
void drawTimeDateMenu();

// ==========================================
// --- SOUND GENERATION UTILS ---
// ==========================================
void playBeep(int freq, int duration) {
  if (!buzzerEnabled || isDND) return;
  tone(BUZZER_PIN, freq);
  delay(duration);
  noTone(BUZZER_PIN); 
  digitalWrite(BUZZER_PIN, HIGH); 
}

void playBootSound() {
  playBeep(523, 100);
  playBeep(659, 100);
  playBeep(784, 100);
  playBeep(1046, 200);
}

void playNotificationChime() {
  if (!buzzerEnabled || isDND) return;
  tone(BUZZER_PIN, 987, 80);
  delay(90);
  tone(BUZZER_PIN, 1318, 140);
  delay(150);
  noTone(BUZZER_PIN);
  digitalWrite(BUZZER_PIN, HIGH);
}

void playHydrationChime() {
  if (!buzzerEnabled || isDND) return;
  tone(BUZZER_PIN, 1047, 150); // C6
  delay(180);
  tone(BUZZER_PIN, 1319, 250); // E6
  delay(260);
  noTone(BUZZER_PIN);
  digitalWrite(BUZZER_PIN, HIGH);
}

void playSuccessChirp() {
  if (!buzzerEnabled || isDND) return;
  tone(BUZZER_PIN, 1568, 120); // G6
  delay(140);
  tone(BUZZER_PIN, 2093, 160); // C7
  delay(170);
  noTone(BUZZER_PIN);
  digitalWrite(BUZZER_PIN, HIGH);
}

void playHappyTune() {
  playBeep(659, 100);
  playBeep(659, 100);
  delay(100);
  playBeep(659, 100);
  playBeep(523, 100);
  playBeep(659, 100);
  playBeep(784, 200);
}

void playDizzyTune() {
  for (int i = 1000; i > 400; i -= 100) {
    playBeep(i, 50);
  }
  playBeep(400, 200);
}

void publishWaterAck() {
  if (!client.connected()) return;
  time_t now = time(nullptr);

  JsonDocument doc;
  doc["ack"] = true;
  doc["event"] = "WATER_DRANK";
  doc["source"] = "capacitive_touch";
  doc["timestamp"] = (long)now;

  char jsonBuf[128];
  serializeJson(doc, jsonBuf);
  client.publish("deskbot/device/water_ack", jsonBuf);
  Serial.println(F("[WATER] Acknowledgment published to deskbot/device/water_ack"));
}

// --- 6EK2 MASTER TIMETABLE ---
struct ClassEvent { 
  int startH, startM, endH, endM; 
  const char* title; 
};

ClassEvent mon[] = { {7,30, 9,0, "AWT (MA115)"}, {9,0, 10,30, "AI Lab (MA112)"}, {11,0, 12,30, "CS Lab (MB306)"}, {12,30, 13,30, "CC Lab (MA115)"}, {14,0, 15,30, "AWT (MA113)"} };
ClassEvent tue[] = { {7,30, 9,0, "AI (MA106)"}, {9,0, 10,30, "SE (MB314)"}, {11,0, 12,30, "OT (MA101)"}, {12,30, 13,30, "PBL"}, {13,30, 14,30, "AWT (MA102)"} };
ClassEvent wed[] = { {7,30, 9,0, "OT (MA106)"}, {9,0, 10,30, "HCD (MA113)"}, {11,0, 12,30, "CC (MA113)"}, {12,30, 13,30, "CA-2 (MA105)"}, {14,0, 15,30, "CC (MA113)"} };
ClassEvent thu[] = { {7,30, 9,0, "CA-2 (MA111)"}, {9,0, 10,30, "SE (MA111)"}, {11,0, 13,30, "PBL"} };
ClassEvent fri[] = { {7,30, 9,0, "AI (MA101)"}, {9,0, 10,30, "CS (MA102)"}, {11,0, 12,30, "CS (MA102)"}, {12,30, 13,30, "CC (MA115)"} };

void updateTimetable() {
  time_t now = time(nullptr);
  struct tm* t = localtime(&now);
  if (t->tm_year < 100) return; 

  int currentMins = (t->tm_hour * 60) + t->tm_min;
  int wday = t->tm_wday; 
  ClassEvent* todayClasses = nullptr;
  int cCount = 0;

  if (wday == 1) { todayClasses = mon; cCount = 5; }
  else if (wday == 2) { todayClasses = tue; cCount = 5; }
  else if (wday == 3) { todayClasses = wed; cCount = 5; }
  else if (wday == 4) { todayClasses = thu; cCount = 3; }
  else if (wday == 5) { todayClasses = fri; cCount = 4; }

  if (cCount == 0) {
    nextEvent = "Weekend! Free Time";
    return;
  }

  bool foundNext = false;
  for (int i = 0; i < cCount; i++) {
    int endMins = (todayClasses[i].endH * 60) + todayClasses[i].endM;
    int startMins = (todayClasses[i].startH * 60) + todayClasses[i].startM;
    
    if (currentMins < endMins) {
      if (currentMins < startMins) nextEvent = String("NEXT: ") + todayClasses[i].title;
      else nextEvent = String("NOW: ") + todayClasses[i].title;
      foundNext = true;
      break;
    }
  }
  if (!foundNext) nextEvent = "Done for the day!";
}

void playBootSequence() {
  playBootSound();
  for (int radius = 2; radius <= 30; radius += 4) {
    display.clearDisplay();
    display.drawCircle(64, 32, radius, WHITE);
    display.display();
    delay(30);
  }
  display.clearDisplay();
  display.setTextSize(2);
  display.setTextColor(WHITE);
  display.setCursor(16, 24);
  display.print("DeskBot");
  display.display();
  delay(800);
}

void setup() {
  Serial.begin(115200);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, HIGH);
  pinMode(TOUCH_PIN, INPUT);
  
  Wire.begin();
  Wire.setClock(400000L); 
  display.begin(0x3C, true); 
  display.setTextWrap(true);
  display.setContrast(255);
  
  playBootSequence();

  display.clearDisplay(); 
  display.setTextSize(1); 
  display.setTextColor(WHITE);
  display.setCursor(10, 25); 
  display.print("Connecting Wi-Fi..."); 
  display.display();

  wifiMulti.addAP(WIFI_SSID_1, WIFI_PASS_1); 
  wifiMulti.addAP(WIFI_SSID_2, WIFI_PASS_2); 

  while (wifiMulti.run() != WL_CONNECTED) { 
    delay(500); 
    Serial.print("."); 
  }
  deviceIP = WiFi.localIP().toString(); 
  
  ArduinoOTA.begin();

  display.clearDisplay(); 
  display.setCursor(10, 25); 
  display.print("Syncing NTP Time..."); 
  display.display();
  
  configTime(19800, 0, "pool.ntp.org", "time.nist.gov"); 
  time_t now = time(nullptr);
  while (now < 100000) { delay(500); now = time(nullptr); }

  display.clearDisplay(); 
  display.setCursor(10, 25); 
  display.print("Attaching AWS IoT..."); 
  display.display();
  
  net.setTrustAnchors(&rootCA);
  net.setClientRSACert(&cert, &key);
  client.setServer(aws_endpoint, 8883);
  client.setBufferSize(1024); 
  client.setKeepAlive(60); 
  client.setCallback(messageReceived);

  connectAWS();

  lastEyeCareTime = millis(); 
  lastHydrationTime = millis();
  lastViolationTime = millis();
}
 
void connectAWS() {
  while (!client.connected()) {
    String clientId = "DeskBot_ESP8266_" + String(random(0xffff), HEX);
    if (client.connect(clientId.c_str())) {
      Serial.println(F("[AWS] MQTT Handshake established"));
      client.subscribe("deskbot/posture/live");
      client.subscribe("deskbot/notifications/stream");
      client.subscribe("deskbot/settings/dnd");
      client.subscribe("deskbot/settings/intervals");
      client.subscribe("deskbot/tasks/sync");
      client.subscribe("deskbot/device/cmd");
      playBeep(1500, 150);
    } else {
      Serial.print(F("[AWS] Connection failed, rc="));
      Serial.println(client.state());
      delay(3000);
    }
  }
}

void messageReceived(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }

  JsonDocument doc; 
  DeserializationError error = deserializeJson(doc, message);
  if (error) {
    Serial.print(F("[JSON Error] "));
    Serial.println(error.c_str());
    return;
  }

  String strTopic = String(topic);

  // 1. Remote Device Command Handler
  if (strTopic == "deskbot/device/cmd") {
    String cmd = doc["cmd"] | doc["action"] | "";
    cmd.trim();

    if (cmd == "water_reminder") {
      pendingHydrationAlert = true;
      Serial.println(F("[CMD] Flagged pendingHydrationAlert = true"));
    } else if (cmd == "reboot") {
      ESP.restart();
    } else if (cmd == "contrast_high") {
      display.setContrast(255);
    } else if (cmd == "contrast_low") {
      display.setContrast(15);
    }
    return;
  }

  // 2. Settings Intervals Sync
  if (strTopic == "deskbot/settings/intervals") {
    if (doc.containsKey("eyeIntervalMin")) {
      int eyeM = doc["eyeIntervalMin"].as<int>();
      if (eyeM > 0) eyeCareInterval = (unsigned long)eyeM * 60000UL;
    }
    if (doc.containsKey("waterIntervalMin")) {
      int waterM = doc["waterIntervalMin"].as<int>();
      if (waterM > 0) hydrationInterval = (unsigned long)waterM * 60000UL;
    }
    if (doc.containsKey("buzzerEnabled")) {
      buzzerEnabled = doc["buzzerEnabled"].as<bool>();
    }
    playBeep(1800, 80);
    return;
  }

  // 3. Task List Synchronization
  if (strTopic == "deskbot/tasks/sync") {
    if (doc.containsKey("tasks") && doc["tasks"].is<JsonArray>()) {
      JsonArray arr = doc["tasks"].as<JsonArray>();
      taskCount = 0;
      for (JsonObject tObj : arr) {
        if (taskCount >= 5) break;
        taskList[taskCount].title = tObj["title"] | "Task";
        taskList[taskCount].category = tObj["category"] | "FOCUS";
        taskList[taskCount].isDone = tObj["done"] | false;
        taskCount++;
      }
      playBeep(1600, 60);
    }
    return;
  }

  // 4. DND Handler
  if (strTopic == "deskbot/settings/dnd" || (doc.containsKey("dnd") && !doc.containsKey("source"))) {
    isDND = doc["dnd"].as<bool>();
    playBeep(isDND ? 2000 : 500, 100);
    return;
  }

  // 5. Notifications Stream: Parse and set pending flag to prevent overwrite
  if (strTopic == "deskbot/notifications/stream" && (doc.containsKey("source") || doc.containsKey("sender") || doc.containsKey("msg"))) {
    notifSource = doc["source"] | "WhatsApp";
    notifSender = doc["sender"] | "DeskBot";
    notifMsg    = doc["msg"]    | "";
    pendingNotificationAlert = true;
    Serial.printf("[NOTIF] Queued alert from %s: %s\n", notifSender.c_str(), notifMsg.c_str());
    return;
  }

  // 6. Posture Telemetry
  if (doc["posture"].is<String>()) {
    String newState = doc["posture"].as<String>();
    if (doc.containsKey("focusScore")) {
      currentFocusScore = doc["focusScore"].as<int>();
    }
    if (newState != currentAIState) {
      currentAIState = newState;
      // Do not allow posture transitions to trigger face redraws if alerts are pending
      if (!pendingNotificationAlert && !pendingHydrationAlert) {
        stateChanged = true;
      }
    }
  }
}

// ====================================================================
// --- MODAL NOTIFICATION CARD: DEDICATED 6-SECOND CARD PRESENTATION ---
// ====================================================================
void displayModalNotificationCard() {
  pendingNotificationAlert = false;
  playNotificationChime();

  unsigned long startT = millis();
  const unsigned long DISPLAY_MS = 6000;

  while (millis() - startT < DISPLAY_MS) {
    yield();
    ArduinoOTA.handle();
    client.loop();

    // Check tap on sensor to dismiss early
    if (digitalRead(TOUCH_PIN) == HIGH) {
      playBeep(2000, 80);
      while (digitalRead(TOUCH_PIN) == HIGH) {
        delay(10);
        yield();
      }
      break;
    }

    display.clearDisplay();

    // Outer cyber card border
    display.drawRoundRect(0, 0, 128, 64, 4, WHITE);
    
    // Inverted header
    display.fillRect(0, 0, 128, 14, WHITE);
    display.setTextColor(BLACK); 
    display.setTextSize(1);
    display.setCursor(4, 3);
    display.print("[!] " + notifSource);

    // Body content
    display.setTextColor(WHITE);
    display.setCursor(6, 18);
    display.print("FROM: ");
    display.print(notifSender.substring(0, 11));
    display.drawLine(4, 28, 124, 28, WHITE);
    
    display.setCursor(6, 33);
    display.print(notifMsg.substring(0, 18));
    if (notifMsg.length() > 18) {
      display.setCursor(6, 45);
      display.print(notifMsg.substring(18, 36));
    }

    // Countdown progress bar at card footer
    int progress = map(millis() - startT, 0, DISPLAY_MS, 120, 0);
    if (progress > 0) {
      display.fillRect(4, 58, progress, 2, WHITE);
    }

    display.display();
    delay(20);
  }
}

// ====================================================================
// --- MODAL HYDRATION SCREEN (BLOCKS FACE OVERWRITE & LOGS WATER) ---
// ====================================================================
void enterModalHydrationScreen() {
  pendingHydrationAlert = false; 
  unsigned long modalStart = millis();
  const unsigned long MODAL_TIMEOUT_MS = 45000;

  playHydrationChime();

  bool sensorAcknowledged = false;
  float waveOffset = 0.0;

  while (millis() - modalStart < MODAL_TIMEOUT_MS) {
    yield();
    ArduinoOTA.handle();
    client.loop();

    // Check Capacitive Touch Sensor (PIN 14 / D5)
    if (digitalRead(TOUCH_PIN) == HIGH) {
      delay(40); // Debounce
      if (digitalRead(TOUCH_PIN) == HIGH) {
        sensorAcknowledged = true;
        break;
      }
    }

    display.clearDisplay();
    display.setTextColor(WHITE);

    display.drawRoundRect(2, 0, 124, 15, 3, WHITE);
    display.setTextSize(1);
    display.setCursor(12, 4);
    display.print(F("DRINK WATER NOW!"));

    int cupX = 14;
    display.drawLine(cupX, 20, cupX + 5, 52, WHITE);      
    display.drawLine(cupX + 24, 20, cupX + 19, 52, WHITE); 
    display.drawLine(cupX + 5, 52, cupX + 19, 52, WHITE);  

    waveOffset += 0.35;
    for (int x = cupX + 6; x <= cupX + 18; x++) {
      int waveY = 36 + (int)(sin(waveOffset + (x * 0.6)) * 3.0);
      display.drawLine(x, waveY, x, 51, WHITE);
    }

    int bubbleY = 50 - ((millis() / 50) % 16);
    display.drawPixel(cupX + 12, bubbleY, BLACK);

    display.setCursor(48, 22);
    display.print(F("HYDRATE //"));
    display.setCursor(48, 33);
    display.print(F("LOG REFUEL"));

    if ((millis() / 350) % 2 == 0) {
      display.fillRect(45, 46, 80, 14, WHITE);
      display.setTextColor(BLACK);
      display.setCursor(49, 49);
      display.print(F("[TAP SENSOR]"));
    } else {
      display.drawRect(45, 46, 80, 14, WHITE);
      display.setTextColor(WHITE);
      display.setCursor(49, 49);
      display.print(F("[TAP SENSOR]"));
    }

    int barW = map(millis() - modalStart, 0, MODAL_TIMEOUT_MS, 124, 0);
    display.drawFastHLine(2, 63, barW, WHITE);

    display.display();
    delay(20);
  }

  if (sensorAcknowledged) {
    publishWaterAck();

    display.clearDisplay();
    display.drawRoundRect(4, 4, 120, 56, 4, WHITE);
    display.fillRoundRect(8, 8, 112, 14, 2, WHITE);
    display.setTextSize(1);
    display.setTextColor(BLACK);
    display.setCursor(18, 11);
    display.print(F("HYDRATION LOGGED"));

    display.setTextColor(WHITE);
    display.setTextSize(2);
    display.setCursor(16, 32);
    display.print(F("+1 STREAK"));
    display.display();

    playSuccessChirp();

    while (digitalRead(TOUCH_PIN) == HIGH) {
      delay(20);
      yield();
    }
    delay(1200);
  }

  lastHydrationTime = millis();
}

void loop() {
  ArduinoOTA.handle();
  currentMillis = millis();
  updateTimetable(); 

  if (!client.connected() && wifiMulti.run() == WL_CONNECTED) {
    connectAWS();
  }
  client.loop(); 

  // ==========================================================
  // 1. PRIORITY ALERT: INCOMING WHATSAPP NOTIFICATION
  // ==========================================================
  if (pendingNotificationAlert) {
    displayModalNotificationCard();
    return;
  }

  // ==========================================================
  // 2. PRIORITY ALERT: PENDING HYDRATION ALERT
  // ==========================================================
  if (pendingHydrationAlert) {
    enterModalHydrationScreen();
    return;
  }

  handleTouchLogic(); 

  // Posture alert sounds
  if (stateChanged) {
    if (currentAIState == "Slouching" || currentAIState == "Phone Distraction" || currentAIState == "Drowsy / Sleeping") {
      lastViolationTime = currentMillis; 
      if (currentAIState == "Phone Distraction") phoneStrikes++; 
    }

    if (!isDND && buzzerEnabled) {
      if (currentAIState == "Slouching") {
        playBeep(801, 150); playBeep(601, 300); 
      } else if (currentAIState == "Good Posture") {
        playHappyTune();
      } else if (currentAIState == "Drowsy / Sleeping") {
        playBeep(2500, 100); playBeep(2500, 100); playBeep(2500, 150); 
      }
    }
    stateChanged = false; 
    uiMode = 0; 
  }

  // Periodic phone distraction alert
  if (currentMode == 0 && currentAIState == "Phone Distraction") {
    if (!isDND && buzzerEnabled && (currentMillis - lastPhoneAlarmTime > 2000)) { 
      playBeep(2001, 100); delay(50); playBeep(1501, 250); 
      lastPhoneAlarmTime = currentMillis;
    }
    uiMode = 0; 
  }
  
  // Dynamic Wellness Timers check
  if (currentMode == 0 && (currentMillis - lastEyeCareTime > eyeCareInterval)) {
    currentMode = 3; 
    lastEyeCareTime = currentMillis; 
    playBeep(2000, 200); 
  }
  if (currentMode == 0 && (currentMillis - lastHydrationTime > hydrationInterval)) {
    pendingHydrationAlert = true;
  }

  // Eye care auto-dismiss
  if (currentMode == 3 && (currentMillis - lastEyeCareTime > TWENTY_SECONDS)) {
    currentMode = 0; 
    lastEyeCareTime = currentMillis; 
    playBeep(1500, 100); playBeep(2000, 150); 
  }

  display.clearDisplay();

  // Rendering Hierarchy
  if (currentMode == 3) {
    drawHourglass(100 - (((currentMillis - lastEyeCareTime) * 100) / TWENTY_SECONDS));
  } 
  else if (currentMode == 2) {
    long timeElapsed = currentMillis - customTimerStart;
    if (timeElapsed >= customTimerDuration) {
      currentMode = 0; 
      customTimerDuration = 0;
      playBeep(1000, 200); delay(100); playBeep(1000, 400); 
    } else {
      drawHourglass(100 - ((timeElapsed * 100) / customTimerDuration));
    }
  } 
  else if (currentMode == 1) {
    drawHourglass(100); 
  }
  else {
    if (isOverridden) {
      if (currentMillis < overrideEndTime) {
        if (overrideType == 1) drawShyFace();
        else if (overrideType == 2) drawGlitchFace();
        else if (overrideType == 3) drawDizzyFace();
      } else { 
        isOverridden = false; 
        targetSquish = 0; 
      }
    } 
    if (!isOverridden) {
      if (uiMode == 0) {
        if (isDND) {
          drawFluidFace();
        } else {
          if (currentAIState == "Good Posture") drawFluidFace();
          else if (currentAIState == "Slouching") animateAngryFace(); 
          else if (currentAIState == "Not At Desk") drawSleepyFace();
          else if (currentAIState == "Phone Distraction") animateJudgingFace(); 
          else if (currentAIState == "Drowsy / Sleeping") animateDrowsyFace();
          else drawFluidFace();
        }
      } 
      else if (uiMode == 1) drawTimeDateMenu();
      else if (uiMode == 2) drawAutoPagingMenu(); 
      else if (uiMode == 3) drawTasksMenu();
      else if (uiMode == 4) drawTimetableMenu(); 
      else if (uiMode == 5) drawDNDMenu();        
    }
  }
  display.display(); 
}

void handleTouchLogic() {
  int touchState = digitalRead(TOUCH_PIN);
  
  if (touchState == HIGH && !isButtonPressed) {
    isButtonPressed = true; 
    buttonPressTime = currentMillis; 
    longPressTriggered = false;
  } 
  else if (touchState == HIGH && isButtonPressed) {
    if (currentMillis - buttonPressTime > 1000 && !longPressTriggered) {
      longPressTriggered = true;
      
      if (currentMode == 0) {
        if (uiMode == 5) {
          isDND = !isDND; 
          playBeep(isDND ? 2000 : 500, 100); 
        } else {
          currentMode = 1; 
          customTimerDuration = 5 * 60000; 
          uiMode = 0; 
          playBeep(1000, 50); 
        }
      } 
      else if (currentMode == 1 && customTimerDuration > 0) { 
        currentMode = 2; 
        customTimerStart = currentMillis; 
        playBeep(1500, 100); playBeep(2000, 150); 
      } 
      else { 
        currentMode = 0; 
        customTimerDuration = 0; 
        playBeep(800, 200); 
      }
    }
  } 
  else if (touchState == LOW && isButtonPressed) {
    isButtonPressed = false;
    if (!longPressTriggered) {
      if (currentMillis - lastTapTime < 500) tapCount++; 
      else tapCount = 1; 
      lastTapTime = currentMillis;
    }
  }

  if (tapCount > 0 && (currentMillis - lastTapTime > 350) && !isButtonPressed) {
    if (currentMode == 3) {
      currentMode = 0; 
      lastEyeCareTime = currentMillis;
      playBeep(2000, 100);
    }
    else if (currentMode == 1) {
      if (tapCount == 1) { 
        customTimerDuration += 5 * 60000; 
        if (customTimerDuration > 60 * 60000) customTimerDuration = 5 * 60000; 
        playBeep(1200, 50); 
      }
    } 
    else if (currentMode == 0) {
      if (tapCount == 1) { 
        // Pure menu navigation: no spurious water acknowledgment
        uiMode = (uiMode + 1) % 6; 
        playBeep(2000, 50); 
      } 
      else if (tapCount == 2) { 
        uiMode = 0; isOverridden = true; overrideType = 1; 
        overrideEndTime = currentMillis + 3000; 
        playBeep(1500, 100); playBeep(2000, 150); 
      } 
      else if (tapCount == 3) { 
        uiMode = 0; isOverridden = true; overrideType = 2; 
        overrideEndTime = currentMillis + 1500; 
        playBeep(150, 200); 
      }
      else if (tapCount >= 4) { 
        uiMode = 0; isOverridden = true; overrideType = 3; 
        overrideEndTime = currentMillis + 2500; 
        playDizzyTune(); 
      }
    }
    tapCount = 0; 
  }
}

void drawTasksMenu() {
  display.setTextSize(1); 
  display.setTextColor(WHITE);
  display.setCursor(18, 0); 
  display.print("SYNCED TASKS"); 
  display.drawLine(0, 10, 128, 10, WHITE);

  if (taskCount == 0) {
    display.setCursor(10, 28);
    display.print("No tasks pending.");
    return;
  }

  int yPos = 16;
  for (int i = 0; i < taskCount && i < 3; i++) {
    display.setCursor(2, yPos);
    display.print(taskList[i].isDone ? "[x] " : "[ ] ");
    display.print(taskList[i].title.substring(0, 14));
    yPos += 16;
  }
}

void drawTimetableMenu() {
  display.setTextSize(1); display.setTextColor(WHITE);
  display.setCursor(20, 0); display.print("UPCOMING EVENT"); 
  display.drawLine(0, 10, 128, 10, WHITE);
  display.setCursor(0, 25); display.print(nextEvent); 
}

void drawDNDMenu() {
  display.drawLine(0, 10, 128, 10, WHITE);
  if (isDND) {
    display.setCursor(15, 0); display.setTextSize(1); display.print("STATUS: MEETING");
    display.setTextSize(2); display.setCursor(38, 25); display.print("BUSY");
    if ((currentMillis / 500) % 2 == 0) display.fillCircle(18, 32, 6, WHITE); 
    else display.drawCircle(18, 32, 6, WHITE);
    display.setTextSize(1); display.setCursor(12, 50); display.print("Audio Alerts Muted");
  } else {
    display.setCursor(15, 0); display.setTextSize(1); display.print("STATUS: DESK");
    display.setTextSize(2); display.setCursor(12, 25); display.print("AVAILABLE");
    display.drawLine(40, 50, 50, 60, WHITE); display.drawLine(50, 60, 75, 40, WHITE);
  }
}

void drawAutoPagingMenu() {
  display.setTextSize(1); display.setTextColor(WHITE); display.setCursor(12, 0); display.print("SYSTEM DIAGNOSTICS"); display.drawLine(0, 10, 128, 10, WHITE);
  int page = (currentMillis / 4000) % 3;
  if (page == 0) { 
    display.setCursor(0, 18); display.print("Wi-Fi: CONNECTED"); 
    display.setCursor(0, 32); display.print("IP: " + deviceIP); 
    display.setCursor(0, 46); display.print("AWS: ACTIVE SECURE"); 
  } 
  else if (page == 1) { 
    long streakMins = (currentMillis - lastViolationTime) / 60000; 
    display.setCursor(0, 18); display.print(String("AI: ") + currentAIState); 
    display.setCursor(0, 32); display.print(String("Focus: ") + currentFocusScore + "% Streak: " + streakMins + "m"); 
    display.setCursor(0, 46); display.print(String("Phone Strikes: ") + phoneStrikes); 
  } 
  else if (page == 2) { 
    long upMins = currentMillis / 60000; 
    long upHours = upMins / 60; 
    int nextEyeBreak = (eyeCareInterval - (currentMillis - lastEyeCareTime)) / 60000; 
    display.setCursor(0, 18); display.print(String("Uptime: ") + upHours + "h " + (upMins % 60) + "m"); 
    display.setCursor(0, 32); display.print(String("Eye Break In: ") + nextEyeBreak + "m"); 
    display.setCursor(0, 46); display.print("RAM: " + String(ESP.getFreeHeap() / 1024) + " KB Free"); 
  }
  for (int i = 0; i < 3; i++) { 
    if (i == page) display.fillCircle(110 + (i * 6), 58, 2, WHITE); 
    else display.drawCircle(110 + (i * 6), 58, 2, WHITE); 
  }
}

void animateDrowsyFace() {
  int eyelidDrop = 12 + (int)(sin(currentMillis / 600.0) * 10); 
  int y_shift = (int)(sin(currentMillis / 1200.0) * 3);         
  display.fillRoundRect(28, 12 + y_shift, 26, 30, 8, WHITE); display.fillRect(28, 12 + y_shift, 26, eyelidDrop, BLACK); display.drawLine(28, 12 + y_shift + eyelidDrop, 54, 12 + y_shift + eyelidDrop, WHITE); 
  display.fillRoundRect(76, 12 + y_shift, 26, 30, 8, WHITE); display.fillRect(76, 12 + y_shift, 26, eyelidDrop, BLACK); display.drawLine(76, 12 + y_shift + eyelidDrop, 102, 12 + y_shift + eyelidDrop, WHITE);
  if ((currentMillis / 800) % 2 == 0) { display.setCursor(105, 10 + y_shift); display.setTextSize(1); display.print("z"); }
  if ((currentMillis / 800) % 3 == 0) { display.setCursor(115, y_shift); display.setTextSize(1); display.print("Z"); }
}

void drawFluidFace() {
  if (currentMillis - lastLookChange > (unsigned long)random(3000, 6000)) { 
    targetLookX = random(-15, 16); 
    lastLookChange = currentMillis; 
  }
  if (currentMillis - lastBlinkTime > 4000) { 
    targetSquish = 38; 
    if (currentSquish > 36) { 
      targetSquish = 0; 
      lastBlinkTime = currentMillis; 
    } 
  } else { 
    targetSquish = 0; 
  }
  currentLookX += (targetLookX - currentLookX) * 0.15; 
  currentSquish += (targetSquish - currentSquish) * 0.35;
  int drawHeight = 40 + sin(currentMillis / 350.0) * 2.0 - currentSquish; 
  if (drawHeight < 4) drawHeight = 4; 
  display.fillRoundRect(28 + currentLookX, 12 + (40 - drawHeight) / 2, 26, drawHeight, 10, WHITE);
  display.fillRoundRect(76 + currentLookX, 12 + (40 - drawHeight) / 2, 26, drawHeight, 10, WHITE);
}

void animateAngryFace() {
  int x_shift = (int)(sin(currentMillis / 400.0) * 4); int y_shift = (int)(cos(currentMillis / 400.0 * 0.7) * 2); 
  int slant_depth = 16; if (currentMillis % 4000 < 300) slant_depth = 16 - (int)(sin((currentMillis % 4000) / 300.0 * 3.14159) * 16);
  display.fillRoundRect(30 + x_shift, 16 + y_shift, 18, 32, 6, WHITE); display.fillTriangle(34 + x_shift, 14 + y_shift, 50 + x_shift, 14 + y_shift, 50 + x_shift, 16 + y_shift + slant_depth, BLACK);
  display.fillRoundRect(80 + x_shift, 16 + y_shift, 18, 32, 6, WHITE); display.fillTriangle(94 + x_shift, 14 + y_shift, 78 + x_shift, 14 + y_shift, 78 + x_shift, 16 + y_shift + slant_depth, BLACK);
}

void animateJudgingFace() {
  int x_shift = (int)(sin(currentMillis / 500.0) * 8);
  display.fillRoundRect(28 + x_shift, 30, 24, 6, 2, WHITE); display.drawLine(26 + x_shift, 28, 54 + x_shift, 28, WHITE); 
  display.fillRoundRect(76 + x_shift, 18, 24, 18, 4, WHITE); display.fillTriangle(74 + x_shift, 16, 102 + x_shift, 16, 102 + x_shift, 24, BLACK);
}

void drawSleepyFace() { display.fillRoundRect(34, 32, 20, 4, 2, WHITE); display.fillRoundRect(74, 32, 20, 4, 2, WHITE); display.setCursor(95, 10); display.setTextSize(1); display.print("Zzz"); }
void drawShyFace() { display.fillRoundRect(30, 24, 20, 16, 4, WHITE); display.fillRoundRect(78, 24, 20, 16, 4, WHITE); display.drawLine(25, 45, 35, 45, WHITE); display.drawLine(93, 45, 103, 45, WHITE); }
void drawGlitchFace() { for(int i=0; i<6; i++) { display.fillRect(random(0, 128), random(0, 64), random(10, 50), random(2, 8), WHITE); } if ((currentMillis / 50) % 2 == 0) display.invertDisplay(true); else display.invertDisplay(false); }

void drawDizzyFace() {
  int spinOffset = (int)(sin(currentMillis / 100.0) * 4);
  display.drawCircle(40 + spinOffset, 32 - spinOffset, 12, WHITE); display.drawCircle(40 - spinOffset, 32 + spinOffset, 6, WHITE); display.fillCircle(40, 32, 2, WHITE);
  display.drawCircle(88 - spinOffset, 32 + spinOffset, 12, WHITE); display.drawCircle(88 + spinOffset, 32 - spinOffset, 6, WHITE); display.fillCircle(88, 32, 2, WHITE);
  display.drawLine(56, 48 + spinOffset, 72, 48 - spinOffset, WHITE);
}

void drawHourglass(int progressPercent) {
  display.drawTriangle(44, 4, 84, 4, 64, 30, WHITE); display.drawTriangle(44, 60, 84, 60, 64, 34, WHITE); 
  for(int y = 30 - map(progressPercent, 0, 100, 0, 26); y < 30; y++) { int w = map(y, 4, 30, 20, 0); display.drawLine(64 - w, y, 64 + w, y, WHITE); }
  for(int y = 60; y > 60 - map(progressPercent, 0, 100, 26, 0); y--) { int w = map(y, 60, 34, 20, 0); display.drawLine(64 - w, y, 64 + w, y, WHITE); }
  if (progressPercent > 0) display.drawPixel(64, 32 + ((millis() / 20) % 28), WHITE);
  if (currentMode == 1) { display.setCursor(95, 28); display.setTextSize(1); display.print(customTimerDuration / 60000); display.print("m"); }
}

void drawTimeDateMenu() {
  time_t now = time(nullptr); struct tm* timeinfo = localtime(&now); 
  display.setTextSize(1); display.setCursor(35, 0); display.print("LOCAL TIME"); display.drawLine(0, 10, 128, 10, WHITE);
  if (timeinfo->tm_year < 100) return;
  char timeStr[10]; int hour12 = timeinfo->tm_hour % 12; if (hour12 == 0) hour12 = 12; sprintf(timeStr, "%02d:%02d", hour12, timeinfo->tm_min);
  display.setTextSize(3); display.setCursor(20, 20); display.print(timeStr); 
  display.setTextSize(1); display.setCursor(110, 20); display.print(timeinfo->tm_hour >= 12 ? "PM" : "AM");
  char dateStr[20]; sprintf(dateStr, "%02d/%02d/%04d", timeinfo->tm_mday, timeinfo->tm_mon + 1, timeinfo->tm_year + 1900); display.setCursor(30, 50); display.print(dateStr);
}