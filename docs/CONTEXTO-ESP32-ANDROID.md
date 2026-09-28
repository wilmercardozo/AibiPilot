# Contexto técnico: AibiPilot (Android) + ESP32 "esp-spy" (puente de captura)

Fecha: 28 sep 2026. Objetivo de este archivo: que un agente externo entienda TODO el
sistema sin contexto previo: qué hace cada pieza, cómo se conectan entre sí, y cómo
reproducir el setup.

## Diagrama de conexiones

```
                        BLE (GATT ffe0/ffe1)          adb (USB)
   ROBOT AIBI  <---------------------------->  TABLET (AibiPilot)  <--------->  PC (workstation)
   (ESP32, fw 1.7.0)                                    |                          |
        |                                               |                          |
        |  WiFi 2.4 (AIBI-CAP, ABIERTA)                 |                          |
        v                                               |                          |
   ESP32 "esp-spy" (devkit + OLED)  <----  WiFi STA --->|--  iPhone hotspot ("<REDACTADO>")
        |                                               |     (internet)
        |  NAT + DHCP (DNS 8.8.8.8)                     v
        +--------  promiscuo: observa y loguea TODO el tráfico del robot ---------> Serial USB (CH340, /dev/ttyUSB0) --> PC
```

- El **robot** habla BLE con la app (comandos) y WiFi con el ESP32 (descargas de
  contenido/firmware).
- El **ESP32** es un puente TRANSPARENTE: el robot se conecta a su AP abierto
  `AIBI-CAP`; el ESP32 reenvía (NAT) ese tráfico a internet por el hotspot del iPhone.
  En paralelo, el ESP32 **observa en pasivo** (modo promiscuo) todo lo que el robot
  transmite y lo imprime por el puerto serial.
- La **PC** lee el serial del ESP32 (logger) y un "cosechador" extrae/descarga/valida
  cada URL HTTP que el robot pide.

---

## 1. La app Android: AibiPilot

Repo: `~/aibi/AibiPilot` (Kotlin + Compose, main branch).

### Qué es
App alternativa para el robot AIBI Pocket (el fabricante es Living.AI; la app oficial es
`ai.living.aibi` v1.7.0, decompilada por ingeniería inversa). Controla el robot por BLE.

### Cómo se conecta al robot (BLE)
- Servicio GATT `0000ffe0-0000-1000-8000-00805f9b34fb`, característica
  `0000ffe1-...` (passthrough serie, MTU 200).
- Frame TX: `BB AA` + len(2B little-endian) + JSON UTF-8. RX igual; `DD CC` = binario.
- Formato: `{"type":"<feature>_req","data":{"op":"..."}}` → el robot responde
  `<feature>_rsp` con `result:"<feature>_<op>_ok"`.
- **El robot exige entrar a modo primero** (`<feature>_in`) antes de comandos
  (show/light/alarm/setting/chess/snake/pirate/zero/photo). `ensureMode()` espera el ACK
  `*_in_ok` (timeout 4s + fallback 700ms).
- Handshake: `sta_req {"op":"query","list":[1,8,11,12]}` (version/property/features/battery).

### Funciones implementadas (todas verificadas en vivo)
- 5 destinos: Inicio (dashboard), Chat IA, Hablar (TTS+animaciones), Jugar, Herramientas.
- Tema "Tech limpio" (oscuro/claro/sistema). Toggle en header.
- Luces RGB, Alarmas (selector tag 0..6), Juegos (chess/snake/pirate/zero), Fotos (TCP 9090),
  Rutinas (WorkManager), Modo remoto (API HTTP puerto 8080 + token Bearer, foreground service),
  Laboratorio (consola JSON raw + barrido motion), Log BLE categorizado.
- **WiFi del robot** (pestaña Herramientas→WiFi): `setting_req op:"wifilist"` (el robot
  devuelve `data.list[]` con `ssid`+`rssi`), `op:"wifiset" {ssid,password}` (password vacía =
  red abierta), y estado con `sta_req query[4]` → `wifi:{connected,name}`.
- **Apagar robot**: `setting_req op:"off"`.
- **Ganchos por intent** (para automatizar sin UI): `am start -n com.wil.aibipilot/.MainActivity`
  con extras `--ei motion_cmd <n>` (envía 1 comando motion) o `--ei motion_from <a> --ei
  motion_to <b>` (barrido). Útil desde adb.

### Quirks del robot (documentados en AGENTS.md y docs/FIRMWARE-RESEARCH.md)
- `motion 0x18-0x1A` = **diskmode** (mata el BLE; sin salida por software). Botones físicos:
  2, bajo la tapa superior (power off + reset).
- `motion 0x61-0x64` (cmds 97-100) = **secuencia de fábrica**: motion tests → IR TEST →
  SD FORMAT OK → REBUILD SYSTEM → conectar WiFi → descarga de assets.
- El robot en modo update solo acepta redes seguras (el AP abierto lo rechaza en ese modo;
  en modo normal conecta sin problema).
- Stack WiFi del robot manda frames CCMP-PMF ("non-zero reserved bit") que rompen el
  handshake WPA2 con un AP ESP32.

### Servidores del robot descubiertos (28 sep)
- `api.aibipocket.com` (47.251.29.223): API de control, HTTPS.
- `api-guigu.aibipocket.com` (misma IP): CDN de contenido, **HTTP plano sin auth**,
  patrón `/<categoría>/dl/<id>` (vistas: `/poweron/`, `/tts/`). Todo descargable con curl.
- `res-us-east-1.living.ai` (47.253.30.157): bucket OSS Aliyun público por objeto, HTTPS,
  rutas desconocidas.

---

## 2. El ESP32 "esp-spy"

Sketch en el repo: `docs/esp32-spy/esp-spy.ino` (también vive en `/tmp/esp-spy/` — volátil).

### Hardware
- ESP32 clásico (devkit con pantalla OLED SSD1306 128x64 I2C, SDA=21 SCL=22) + CH340 USB
  (en la PC aparece como `/dev/ttyUSB0`).
- Flash 4MB. Toolchain: arduino-cli 1.5.2 + core `esp32:esp32` 3.3.11 (instalados en
  `~/.local/arduino` y `~/.arduino15`).

### Qué hace (setup)
1. `WiFi.mode(WIFI_AP_STA)`.
2. **AP abierto** `AIBI-CAP` (sin clave) en canal 6 — el robot se conecta acá. Abierto a
   propósito: (a) el robot conecta directo; (b) el tráfico va **en claro** → el promiscuo
   lee los payloads.
3. **NAT**: `esp_netif_napt_enable(ap_netif)` (disponible en el core 3.3.11) — el tráfico
   del AP sale por la STA.
4. **DHCP con DNS real**: `esp_netif_dhcps_option(... ESP_NETIF_DOMAIN_NAME_SERVER,
   8.8.8.8)` — el robot resuelve TODO en real a través del NAT. **No se bloquea nada**.
5. **STA** al hotspot del iPhone: `WiFi.begin("<REDACTADO>", "<REDACTADO>")` (SSID/contraseña
   hardcodeados en el sketch — editar si cambian).
6. **Modo promiscuo**: `esp_wifi_set_promiscuous(true)` + callback que parsea frames 802.11
   (data 24B / QoS 26B) → LLC/SNAP → IPv4 → TCP/UDP, y loguea por serial: destino IP +
   payload en claro (headers HTTP completos en puerto 80, hasta 512 chars; SNI de TLS;
   queries DNS en UDP 53). Solo frames con MAC del robot (`b4:3a:45:aa:bb:cc` — la WiFi;
   la BLE es `...:cf:6a`).
7. **Servidores TCP** en 80/443/8080/8883/1883/9090 (fallback: si el robot conectara a
   nosotros, se loguea el request).
8. **OLED** no bloqueante: alterna "AP/STA/Clientes" y "LOG" cada 4s.

### Cómo compilar y flashear
```bash
~/.local/arduino/arduino-cli compile --fqbn esp32:esp32:esp32 /tmp/esp-spy
BUILD=$(find ~/.cache/arduino -name "esp-spy.ino.bin" | head -1)
python3 -m esptool --port /dev/ttyUSB0 --baud 921600 write_flash 0x10000 "$BUILD"
# esptool: pip install --user esptool
# si el puerto da Permission denied: sudo chmod 666 /dev/ttyUSB0 (se pierde al re-pluggear)
```

---

## 3. Herramientas en la PC (workstation)

- **Logger** (`/tmp/esp-logger.py`): lee `/dev/ttyUSB0` 115200 y escribe `/tmp/esp-spy.log`.
  Arranque: `setsid python3 /tmp/esp-logger.py > /tmp/esp-spy.log 2>&1 < /dev/null & disown`
  (los procesos en background mueren si se lanzan sin setsid/disown).
- **Cosechador** (`/tmp/harvester.py`): vigila `/tmp/esp-spy.log`; por cada `GET /...` del
  robot: (a) agrega el curl a `/tmp/descargas-robot.sh`, (b) descarga el archivo a
  `/tmp/robot-assets/`, (c) registra estado HTTP + tamaño + tipo en
  `/tmp/respuestas-robot.txt`.
- Verificación del puerto: `ls -la /dev/ttyUSB0`, `pgrep -f esp-logger`.

---

## 4. Estado actual y cómo retomar

- El bridge está flasheado con el **AP abierto + NAT + DNS real + promiscuo**.
- El robot (fw 1.7.0) en modo normal conecta a `AIBI-CAP` sin problema; en modo update
  (secuencia 97-100) prefiere redes seguras y rechaza el AP abierto (comportamiento
  observado: vuelve a <REDACTADO>).
- Para capturar descargas: robot en AIBI-CAP + disparar la secuencia (barrido 27-100 en
  Laboratorio, o `--ei motion_from 97 --ei motion_to 100`) y mirar `/tmp/esp-spy.log` +
  `/tmp/respuestas-robot.txt`.
- Firmware binario del robot: NO disponible (el robot está en la última versión; el OTA
  nuevo solo llega cuando Living.AI publique otra — ver watcher en docs/FIRMWARE-RESEARCH.md).
- Aprendizaje clave del proceso: **no interceptar ni bloquear nada** — el bridge debe ser
  transparente; la captura es pasiva (promiscuo sobre el AP abierto).
