# AIBI Pilot — App alternativa para el robot AIBI Pocket (Living.AI)

App Android en Kotlin + Jetpack Compose que controla el robot AIBI Pocket por BLE.
Protocolo recuperado por ingeniería inversa de la app oficial (ai.living.aibi v1.7.0).

## Build
```bash
cd ~/aibi/AibiPilot
JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- Android SDK: `~/android-sdk` (platform-35, build-tools 35.0.0) — ver `local.properties`
- JDK 17: `~/jdk17` (Gradle 8.13, AGP 8.9.2, Kotlin 2.1.20)
- Dispositivo de prueba: Redmi Pad SE (adb, ID XXXXXXXX), robot "AIBI-CF6A" MAC B4:3A:45:AA:BB:CC

## Datos técnicos clave (NO romper)
- BLE: servicio `0000ffe0-...`, característica `0000ffe1-...` (serial passthrough, MTU 200)
- Frame TX: `BB AA` + len(2B little-endian) + JSON UTF-8. Frame RX igual; `DD CC` = binario
- Formato: `{"type":"<feature>_req","data":{"op":"...",...}}` → respuestas `<feature>_rsp` con `result:"<feature>_<op>_ok"`
- **El robot exige entrar a modo primero** (`<feature>_in`) antes de aceptar comandos de cada feature
  (show/light/alarm/setting/chess/snake/pirate/zero/photo). Implementado en `RobotViewModel.ensureMode()`
  que espera el ACK `*_in_ok` (timeout 4s) antes del comando.
- Handshake: `sta_req {"op":"query","list":[1,8,11,12]}` (version/property/features/battery)
- Batería: nivel 1=Baja 2=Media 3=Alta 4=Llena
- Volumen: `setting_req {"op":"volume","volume":"mute|low|high"}` (String)
- Evento robot: `aibi_event` con `event:"modeout"` al salir de modo
- OTA: robot se auto-actualiza por WiFi (`setting_req op:"update"`), API `https://api.aibipocket.com/`
- Fotos: robot conecta TCP al teléfono puerto 9090; **header real por archivo: `name=x;filesize=N;delimited=------#`**
  (SIN prefijo `finish;` — `finish` solo = 6 bytes que marcan fin de la sync), datos binarios,
  cierre `------------------` (18 guiones), ACK `ok`. Antes del sync hay que entrar a modo photo
  (`photo_in` → `photo_in_ok`). El robot necesita WiFi propio: si no, responde
  `photo_sync_no "Failed to connect to App through Wi-Fi"`. Ver `PhotoTcpServer.kt`
- Alarma add: campo `tag` (tipo 0..6), NO `index`; `index` solo en `del` y `list`
- IP WiFi local de la tablet: usar ConnectivityManager (WifiManager.connectionInfo.ipAddress
  devuelve 0 en MIUI; fix ya aplicado en `RobotViewModel.getWifiIp()`)

## Verificado en vivo (22 sep 2026)
- Volumen mute/low/high → `setting_volume_ok` · Alarmas list/add(tag)/del → `*_ok` ·
  Juegos chess/snake/pirate/zero in/start/play → `*_ok` (play va sin parámetros extra)
- Fotos: `photo_in_ok` + `photo_sync` OK hasta la red (transferencia TCP pendiente de robot con WiFi)
- Reconexión automática (backoff 1-30s, 10 intentos), keep-alive (ping sta query[12] a los 20s
  idle, solo sin modo activo) y diagnóstico de fallo (re-scan del MAC → hint "otra app" vs "dormido")

## Quirks del robot (observados en vivo)
- Los ACK de modo (`*_in_ok`) tardan 3-5s; `ensureMode()` tiene timeout 4s + fallback 700ms
- `aibi_event modeout` llega por el modo ANTERIOR al cambiar de modo (el VM limpia currentMode
  y se reenvía un "in" redundante — inofensivo)
- Tras force-stop de la app, el primer reconnect falla con status 133; reintentar funciona
- MIUI no entrega callback GATT al apagar el adaptador BT: el keep-alive fuerza la reconexión
  sin depender del callback
- Log BLE de la app: categorías TX/RX/EVT/ERR/SYS con filtros en la pestaña Log BLE

## Quirks del entorno
- MIUI/HyperOS exige ACCESS_FINE_LOCATION para entregar resultados de scan BLE incluso en Android 12+
- El scan se registra pero no entrega resultados si falta ese permiso
- Robot "AIBI-CF6A": el nombre no es exactamente "AIBI" (el filtro por nombre exacto NO matchea)
- La app oficial loguea su tráfico BLE en logcat (tag "BleUtils", LOG_LEVEL=6) → se puede
  capturar su tráfico real: `adb logcat | grep BleUtils`

## Estructura
- `ble/BleClient.kt` — scan/connect/notify/write + reensamblado de frames
- `ble/PhotoTcpServer.kt` — servidor TCP fotos (protocolo reconstruido del smali oficial)
- `protocol/Protocol.kt` — builders de comandos + catálogo de animaciones
- `RobotViewModel.kt` — estado, ensureMode (modos), chat LLM (OpenAI-compatible), escenas
- `ui/Screens.kt` — Compose, layout adaptativo (NavigationRail >= 840dp), tabs, Chat IA

## Referencias de decompilación (persistidas en /tmp/aibi — se pierden al reiniciar)
- `jadx_out/sources/ai/living/aibi/` — Java decompilado de la app oficial
- Comandos útiles:
  - jadx: `/tmp/jadx/bin/jadx`
  - apktool: `/tmp/bin/apktool`
  - APK oficial: `~/aibi/AIBI+Pocket_1.7.0_APKPure.xapk` (extraer el .apk interno)
