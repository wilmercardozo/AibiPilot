#include <WiFi.h>
#include <esp_wifi.h>
#include <esp_netif.h>
#include <Wire.h>
#include <Adafruit_SSD1306.h>

const char* AP_SSID = "AIBI-CAP";
const char* AP_PASS = "<REDACTADO>";
const char* STA_SSID = "<REDACTADO>";
const char* STA_PASS = "<REDACTADO>";

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_SDA 21
#define OLED_SCL 22
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

WiFiServer servers[] = {
  WiFiServer(80), WiFiServer(443), WiFiServer(8080),
  WiFiServer(8883), WiFiServer(1883), WiFiServer(9090)
};
const char* portNames[] = {"http80", "https443", "8080", "mqtts8883", "mqtt1883", "9090"};
const int NUM = sizeof(servers) / sizeof(servers[0]);

// DNS por host: el bridge es 100% transparente: el DHCP del AP entrega
// DNS=8.8.8.8 (vía dhcps_option en setup) y el robot resuelve TODO en real
// a través del NAT. Nosotros solo observamos en pasivo (promiscuo).
#include <WiFiUdp.h>
WiFiUDP dnsUdp;   // escucha DNS del robot en 192.168.4.1:53
WiFiUDP dnsFwd;   // reenvia al upstream real
// DNS del bridge. Por defecto TRANSPARENTE: reenvia todo a 8.8.8.8 (el robot
// resuelve de verdad y sale por el NAT). Para MITM de la OTA, poner
// MITM_HOST="api.aibipocket.com" y MITM_IP = IP de la PC en el hotspot: el robot
// resolvera ese host a la PC, donde corre el server falso que fuerza el update
// (downgrade) y captura la URL/binario del firmware.
// MITM DESACTIVADO (transparente). Para re-capturar/inyectar firmware, poner
// MITM_HOST="res-us-east-1.living.ai" (el robot no valida el cert: el MITM funciona)
// y levantar mitmproxy en la PC (172.20.10.10:443) — ver docs/FIRMWARE-RESEARCH.md.
const char* MITM_HOST = "";
IPAddress MITM_IP(192, 168, 4, 1);    // el robot pega al ESP mismo...
IPAddress RELAY_TO(172, 20, 10, 10);  // ...y el ESP relaya ese TCP a la PC (mitmproxy)
IPAddress DNS_UP(8, 8, 8, 8);

String stations[4][2];
int stationCount = 0;
String screenLog[5];
int screenLogIdx = 0;

void logLine(const String& line) {
  Serial.println(line);
  screenLog[screenLogIdx % 5] = line;
  screenLogIdx++;
}

void addStation(String mac, String ip) {
  if (stationCount < 4) {
    stations[stationCount][0] = mac;
    stations[stationCount][1] = ip;
    stationCount++;
    logLine("STATION+ " + mac + " " + ip);
  }
}

void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  switch (event) {
    case ARDUINO_EVENT_WIFI_AP_STACONNECTED: {
      char mac[18];
      snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x",
               info.wifi_ap_staconnected.mac[0], info.wifi_ap_staconnected.mac[1],
               info.wifi_ap_staconnected.mac[2], info.wifi_ap_staconnected.mac[3],
               info.wifi_ap_staconnected.mac[4], info.wifi_ap_staconnected.mac[5]);
      logLine("STATION-conn " + String(mac));
      break;
    }
    case ARDUINO_EVENT_WIFI_AP_STAIPASSIGNED: {
      String ip = IPAddress(info.wifi_ap_staipassigned.ip.addr).toString();
      addStation("x", ip);
      break;
    }
    case ARDUINO_EVENT_WIFI_AP_STADISCONNECTED:
      logLine("STATION-disconn");
      break;
    default: break;
  }
}

const uint8_t robotMac[6] = {0xb4, 0x3a, 0x45, 0xaa, 0xbb, 0xcc};
String lastDst = "";
unsigned long lastDstT = 0;
unsigned long lastPayloadT = 0;

// vuelca bytes imprimibles de un payload (primeros maxLen)
String printable(const uint8_t* p, int len, int maxLen) {
  String s = "";
  for (int i = 0; i < len && i < maxLen; i++) {
    char c = p[i];
    if (c >= 32 && c < 127) s += c;
    else if (c == '\n' || c == '\r' || c == '\t') s += ' ';
    else s += '.';
  }
  return s;
}

void promiscCb(void* buf, wifi_promiscuous_pkt_type_t type) {
  if (type != WIFI_PKT_DATA) return;
  wifi_promiscuous_pkt_t* pkt = (wifi_promiscuous_pkt_t*)buf;
  uint8_t* f = pkt->payload;
  if (pkt->rx_ctrl.sig_len < 60) return;
  uint8_t fc0 = f[0];
  uint8_t subtype = (fc0 >> 4) & 0x0F;
  int hdr = 24;
  if (subtype == 8) hdr = 26;          // QoS Data
  if (memcmp(f + 10, robotMac, 6) != 0) return;  // solo frames DEL robot
  if (pkt->rx_ctrl.sig_len < hdr + 8 + 20) return;
  uint8_t* llc = f + hdr;
  if (llc[0] != 0xAA) return;          // esperamos LLC/SNAP
  uint8_t* ip = llc + 8;
  if ((ip[0] >> 4) != 4) return;
  int ipHdr = (ip[0] & 0x0F) * 4;
  uint8_t proto = ip[9];
  String dst = String(ip[16]) + "." + String(ip[17]) + "." + String(ip[18]) + "." + String(ip[19]);
  unsigned long now = millis();
  if (dst != lastDst || now - lastDstT > 1500) {
    logLine("ROBOT->" + dst + " (proto " + String(proto) + ")");
    lastDst = dst;
    lastDstT = now;
  }
  int totalLen = ((ip[2] << 8) | ip[3]) - ipHdr;
  uint8_t* l4 = ip + ipHdr;
  if (proto == 6 && totalLen >= 20) {           // TCP
    int tcpHdr = ((l4[12] >> 4) & 0x0F) * 4;
    int dport = (l4[2] << 8) | l4[3];
    int payLen = totalLen - tcpHdr;
    if (payLen > 0) {
      int maxDump = (dport == 80) ? 512 : 180;   // HTTP: headers completos
      String p = printable(l4 + tcpHdr, payLen, maxDump);
      bool isReq = p.startsWith("GET ") || p.startsWith("POST ") ||
                   p.indexOf("GET /") >= 0 || p.indexOf("POST /") >= 0;
      if (isReq) {
        // toda peticion HTTP se loguea SIN throttle: no perder GETs en rafaga
        // (mapeo de catalogo / OTA). El throttle solo aplica al resto de payloads.
        logLine("  TCP:" + String(dport) + " | " + p);
      } else if (now - lastPayloadT > 400 && (p.indexOf("Host:") >= 0 || p.length() > 10)) {
        logLine("  TCP:" + String(dport) + " | " + p);   // resto (incl. SNI de 443)
        lastPayloadT = now;
      }
    }
  } else if (proto == 17 && totalLen >= 8) {    // UDP
    int dport = (l4[2] << 8) | l4[3];
    int payLen = totalLen - 8;
    if (payLen > 0 && (dport == 53 || dport == 123)) {
      logLine("  UDP:" + String(dport) + " | " + printable(l4 + 8, payLen, 80));
    }
  }
}

void dumpClient(WiFiClient client, const char* name) {
  unsigned long t0 = millis();
  logLine(String("=== [") + name + "] " + client.remoteIP().toString() + " ===");
  int total = 0;
  while (client.connected() && millis() - t0 < 4000 && total < 4096) {
    int avail = client.available();
    if (avail <= 0) { delay(5); continue; }
    char buf[256];
    int n = client.read((uint8_t*)buf, avail > 255 ? 255 : avail);
    Serial.write((uint8_t*)buf, n);
    total += n;
  }
  logLine("=== fin ===");
  client.stop();
}

// extrae el qname (dotted) de un paquete DNS
String dnsQName(uint8_t* p, int len) {
  String n = "";
  int i = 12;
  while (i < len && p[i] != 0) {
    int l = p[i++];
    if (l <= 0 || i + l > len) break;
    if (n.length()) n += ".";
    for (int j = 0; j < l; j++) n += (char)p[i++];
  }
  return n;
}

// DNS del bridge: MITM_HOST -> MITM_IP; el resto se reenvia real a 8.8.8.8.
void handleDns() {
  int sz = dnsUdp.parsePacket();
  if (sz <= 0) return;
  uint8_t q[512];
  int n = dnsUdp.read(q, sizeof(q));
  if (n < 12) return;
  IPAddress cip = dnsUdp.remoteIP();
  uint16_t cport = dnsUdp.remotePort();
  String name = dnsQName(q, n);

  if (MITM_HOST[0] && name.equalsIgnoreCase(MITM_HOST) && n + 16 <= 512) {
    uint8_t r[512];
    memcpy(r, q, n);
    r[2] = 0x81; r[3] = 0x80;                 // QR=1, RD, RA
    r[6] = 0; r[7] = 1;                        // ANCOUNT=1
    int o = n;
    r[o++] = 0xC0; r[o++] = 0x0C;             // puntero al qname
    r[o++] = 0; r[o++] = 1;                   // type A
    r[o++] = 0; r[o++] = 1;                   // class IN
    r[o++] = 0; r[o++] = 0; r[o++] = 0; r[o++] = 60;   // TTL
    r[o++] = 0; r[o++] = 4;                   // RDLENGTH
    r[o++] = MITM_IP[0]; r[o++] = MITM_IP[1]; r[o++] = MITM_IP[2]; r[o++] = MITM_IP[3];
    dnsUdp.beginPacket(cip, cport); dnsUdp.write(r, o); dnsUdp.endPacket();
    logLine("DNS-MITM " + name + " -> " + MITM_IP.toString());
    return;
  }

  // transparente: reenviar tal cual a 8.8.8.8 y devolver la respuesta al robot
  dnsFwd.beginPacket(DNS_UP, 53); dnsFwd.write(q, n); dnsFwd.endPacket();
  unsigned long t0 = millis();
  while (millis() - t0 < 300) {
    int rs = dnsFwd.parsePacket();
    if (rs > 0) {
      uint8_t rb[512];
      int rn = dnsFwd.read(rb, sizeof(rb));
      dnsUdp.beginPacket(cip, cport); dnsUdp.write(rb, rn); dnsUdp.endPacket();
      if (name.length()) logLine("DNS " + name);
      return;
    }
    delay(2);
  }
}

// Relay TCP: robot -> ESP:443 -> PC:443 (mitmproxy). El TLS es end-to-end
// robot<->mitmproxy; el ESP solo bombea bytes en ambos sentidos (transparente).
void relay443(WiFiClient robot) {
  WiFiClient up;
  if (!up.connect(RELAY_TO, 443)) {
    logLine("relay443: no conecto a PC");
    robot.stop();
    return;
  }
  logLine("relay443 abierto robot<->PC");
  uint8_t buf[512];
  unsigned long last = millis();
  while ((robot.connected() || up.available()) && millis() - last < 20000) {
    bool moved = false;
    int n = robot.available();
    if (n > 0) { int r = robot.read(buf, n > 512 ? 512 : n); if (r > 0) { up.write(buf, r); moved = true; } }
    n = up.available();
    if (n > 0) { int r = up.read(buf, n > 512 ? 512 : n); if (r > 0) { robot.write(buf, r); moved = true; } }
    if (moved) last = millis(); else delay(1);
  }
  robot.stop();
  up.stop();
  logLine("relay443 cerrado");
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Wire.begin(OLED_SDA, OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) Serial.println("[!] OLED no encontrada");
  display.clearDisplay(); display.display();

  WiFi.mode(WIFI_AP_STA);
  // AP ABIERTO (sin clave): el robot conecta directo; el trafico va en claro
  // y lo observamos en pasivo. NAT + DNS real para no bloquear nada.
  WiFi.softAP(AP_SSID, NULL, 6);
  delay(100);
  esp_netif_t* ap_netif = esp_netif_get_handle_from_ifkey("WIFI_AP_DEF");
  if (ap_netif != NULL) {
    esp_err_t e = esp_netif_napt_enable(ap_netif);
    Serial.printf("[NAT] enable: %d\n", (int)e);
    // DNS REAL para los clientes del AP via DHCP (8.8.8.8), para que el robot
    // resuelva api.aibipocket.com de verdad y descargue por el NAT.
    esp_netif_dns_info_t dnsInfo;
    dnsInfo.ip.type = ESP_IPADDR_TYPE_V4;
    dnsInfo.ip.u_addr.ip4.addr = esp_ip4addr_aton("192.168.4.1");  // el bridge ES el DNS
    esp_netif_set_dns_info(ap_netif, ESP_NETIF_DNS_MAIN, &dnsInfo);
    esp_netif_dhcps_option(ap_netif, ESP_NETIF_OP_SET, ESP_NETIF_DOMAIN_NAME_SERVER,
                           &dnsInfo, sizeof(dnsInfo));
  }
  WiFi.begin(STA_SSID, STA_PASS);
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) delay(500);
  if (WiFi.status() == WL_CONNECTED) {
    logLine(String("[+] STA ") + STA_SSID + " ok " + WiFi.localIP().toString());
  } else {
    logLine("[!] STA sin conexion");
  }
  WiFi.onEvent(onWifiEvent);
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_promiscuous_rx_cb(promiscCb);
  for (int i = 0; i < NUM; i++) servers[i].begin();
  dnsUdp.begin(53);
  dnsFwd.begin(0);
  logLine(String("[+] AP ") + AP_SSID + " 192.168.4.1");
  logLine("[+] bridge transparente: DNS real (8.8.8.8) por NAT, solo observamos");
}

void loop() {
  handleDns();
  for (int i = 0; i < NUM; i++) {
    WiFiClient c = servers[i].available();
    if (c) {
      if (i == 1) relay443(c);        // 443: relay TLS transparente a la PC
      else dumpClient(c, portNames[i]);
    }
  }
  // display NO bloqueante: 4s por pantalla alternando
  static unsigned long lastRender = 0;
  static int screenIdx = 0;
  unsigned long now = millis();
  if (now - lastRender > 4000) {
    lastRender = now;
    if (screenIdx == 0) {
      display.clearDisplay();
      display.setTextSize(1);
      display.setTextColor(SSD1306_WHITE);
      display.setCursor(0, 0);
      display.print("AP: "); display.println(AP_SSID);
      display.print("STA: "); display.println(WiFi.localIP().toString());
      display.print("Clientes: "); display.println(stationCount);
      for (int i = 0; i < stationCount && i < 3; i++) display.println("  " + stations[i][1]);
      display.display();
    } else {
      display.clearDisplay();
      display.setCursor(0, 0);
      display.println("LOG:");
      for (int i = 0; i < 5; i++) {
        String l = screenLog[(screenLogIdx - 5 + i + 100) % 5];
        if (l.length() > 21) l = l.substring(0, 21);
        display.println(l);
      }
      display.display();
    }
    screenIdx = 1 - screenIdx;
  }
}
