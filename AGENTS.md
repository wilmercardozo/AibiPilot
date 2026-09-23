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
- `RobotViewModel.kt` — estado, ensureMode (modos), chat LLM (OpenAI-compatible), escenas,
  máquina de reconexión, keep-alive, snackbar, tema
- `ui/Screens.kt` — scaffold: Destination enum (5 destinos), AibiPilotApp, ConnectedHeader
- `ui/ConnectScreen.kt` — wizard de conexión (permisos → despertar → escanear + reconexión rápida)
- `ui/HomeScreen.kt` (dashboard) · `ChatScreen.kt` · `TalkScreen.kt` · `GamesScreen.kt` ·
  `ToolsScreen.kt` (Luces/Alarmas/Fotos/Log BLE)
- `ui/theme/Theme.kt` (tema "Tech limpio" oscuro/claro/sistema) · `ui/components/Components.kt`
- `ble/RemoteController.kt` — conexión BLE standalone para el modo remoto (ensureMode-lite,
  un request en vuelo, keep-alive) · `ble/RemoteApiServer.kt` — API HTTP local (token Bearer,
  puerto 8080, endpoints /status /speak /play /light /volume /alarms /game /scene)
- `ble/RemoteService.kt` — foreground service (connectedDevice) + RemoteStateBus +
  ACTION_RUN_ROUTINES · `routines/Routines.kt` + `RoutineWorker.kt` — rutinas programadas
  (WorkManager 15 min, ventana [ahora-14min, ahora] con wrap de medianoche)
- `DESIGN.md` — diseño del rediseño UX (sistema visual, pantallas, copy) · `docs/HERMES.md` —
  guía de integración con Hermes Agent (modo remoto)

## Datos del modo remoto y rutinas (subproyecto C, verificado en vivo 22 sep)
- Modo remoto: toggle en Herramientas → servicio foreground toma LA conexión BLE (una a la
  vez: la UI no conecta con remoto activo). Token Bearer obligatorio en prefs `remote_token`.
- Los ACK de comandos remotos tardan: TTS hasta ~11s → timeout de comandos del controller 15s.
- Rutinas: ejecución sin remoto vía notificación → intent `run_routine_id` → MainActivity
  (singleTop + onNewIntent) ejecuta con la máquina normal.
- Prueba con curl desde la workstation: `adb forward tcp:8080 tcp:8080` + curl a
  localhost:8080 (el server bindea 0.0.0.0; la seguridad es el token).

## Datos del rediseño UX (subproyecto B, verificado en vivo 22 sep)
- Tema: oscuro por defecto + toggle Sistema/Claro/Oscuro (diálogo Apariencia en el header)
- Alarmas: selector de tag 0..6 con etiquetas reales del oficial: 0 Alarma, 1 Medicamento,
  2 Agua, 3 Deporte, 4 Levantarse, 5 Comida, 6 Reunión
- Verificación visual con adb: uiautomator dump para layout (mi modelo no lee imágenes);
  pixel-check del tema con PIL (`adb exec-out screencap -p` + `Image.getpixel`)
- Layout teléfono para pruebas: `adb shell wm density 400` (+ `wm size`), restaurar con
  `adb shell wm density reset && adb shell wm size reset`

## Referencias de decompilación (persistidas en /tmp/aibi — se pierden al reiniciar)
- `jadx_out/sources/ai/living/aibi/` — Java decompilado de la app oficial
- Comandos útiles:
  - jadx: `/tmp/jadx/bin/jadx`
  - apktool: `/tmp/bin/apktool`
  - APK oficial: `~/aibi/AIBI+Pocket_1.7.0_APKPure.xapk` (extraer el .apk interno)

## Investigación de firmware (subproyecto D, 22 sep — ver docs/FIRMWARE-RESEARCH.md)
- SoC del robot: **Espressif ESP32** (OUI MAC B4:3A:45 + EspRFTestTool_v2.6 en reporte FCC)
- OTA: `api.aibipocket.com` sin auth; la app solo manda `setting_req op:"update"` y el robot
  descarga por SU WiFi. El binario del firmware NO es accesible públicamente.
- Canal BLE binario `DD CC`: sin consumidor en la app oficial; sondeable desde AibiPilot
  (ya loguea "RX binario"). El comando `motion 55 AA 55 AA 21 <cmd>...ED` es la única primitiva
  binaria TX (pantalla de debug oculta, `POST /aibiapp/support/testpage` code==200, no
  allowlisteada para nuestro robot).
- **⚠ NO barrer `motion` a ciegas**: cmds 0x18-0x1A activan "diskmode" (modo fábrica que mata
  el BLE; sin salida por software).
- **Botones físicos: 2, bajo la tapa superior** (removible): uno = power off, otro = reset.
  La reconexión automática de la app sobrevive un reset real (~8s).
- Incógnitas que bloquean firmware propio: binario del firmware + estado de eFuses del ESP32
  (secure boot/flash encryption). Vías: captura WiFi de OTA, o UART (abrir el robot →
  espefuse.py summary).
