# Spec: Subproyecto C — Features nuevas de AibiPilot

Fecha: 2026-09-22
Estado: aprobado en brainstorming (pendiente revisión final del usuario)
Proyecto: AibiPilot (app alternativa para el robot AIBI Pocket)
Precede a: Subproyecto D — Investigación firmware (paralelo, sin código de app)

## Objetivo

Agregar cuatro features que la app oficial no tiene, en orden de valor:
C1 Modo remoto (API HTTP + Hermes Agent), C2 Chat IA con contexto del robot,
C3 Rutinas programadas + notificaciones, C4 Laboratorio (consola JSON raw).

## C1 — Modo remoto (API HTTP local)

- **Toggle "Modo remoto"** en Herramientas → levanta un servidor HTTP propio
  (ServerSocket, sin dependencias nuevas — mismo patrón que `PhotoTcpServer`),
  puerto configurable (default **8080**).
- **Foreground service** de Android (type `connectedDevice`, Android 14+) con
  notificación persistente ("Modo remoto activo") y partial wakelock → MIUI no
  mata la app en background. El servicio solo vive mientras el toggle está
  activo; se apaga al desactivar o al desconectar.
- **Auth**: token estático configurable (prefs `"remote_token"`; si está vacío
  el servidor rechaza todo con 401 y pide configurarlo). Header
  `Authorization: Bearer <token>`.
- **Endpoints JSON** (todas las respuestas `{"ok":true,...}` o
  `{"ok":false,"error":"..."}`; si el robot no está conectado → error claro
  "robot no conectado"):
  - `GET /status` → conn, batería, pasos, monedas, comida, versión, modo actual.
  - `POST /speak {"text":"..."}` → TTS del robot.
  - `POST /play {"animation":"dance_ai1"}` → animación.
  - `POST /light {"mode":"color|breath|flow|default","color":[r,g,b],"brightness":0-100}`
    y `POST /light/on` / `POST /light/off`.
  - `POST /volume {"level":"mute|low|high"}`.
  - `GET /alarms` (list) · `POST /alarms {"time":"HH:mm","tag":0-6}` (add) ·
    `DELETE /alarms {"index":N}` (del).
  - `POST /game {"type":"chess|snake|pirate|zero","op":"in|start|play|out"}`.
  - `POST /scene {"id":"fiesta|despertar|relax|noche"}`.
- **Seguridad**: bind a la interfaz WiFi local, solo LAN; timeout de read;
  límite de tamaño de request (64KB); loguear solo IP+endpoint, nunca el token.
- **Guía Hermes**: documentar en el repo cómo darle a Hermes Agent una tool
  HTTP (ejemplo de script/curl) para controlar el robot desde Telegram/HA/cron.

## C2 — Chat IA con contexto del robot

- `askLlm()` arma el system prompt con **plantilla editable** (campo nuevo en la
  configuración del chat; default: el prompt actual) + **bloque de contexto
  automático** inyectado al final: batería (label 1-4), pasos, monedas, comida,
  hora actual del dispositivo. Se refresca en cada mensaje.
- El diálogo de configuración (Task 5 de B) gana el campo de plantilla.

## C3 — Rutinas programadas + notificaciones

- Dependencia nueva: `androidx.work:work-runtime-ktx`.
- **CRUD de rutinas** (pestaña en Herramientas o destino propio): hora "HH:mm",
  días (todos o L-D), acción (hablar texto / animación / escena / luz on-off),
  toggle activo. Persistencia: JSON en las prefs existentes.
- **Ejecución**: `PeriodicWorkRequest` (15 min) que chequea si alguna rutina
  toca en la ventana y, si el robot no está conectado, intenta reconectar
  (misma máquina de reconexión) y ejecuta la acción. Si falla (robot dormido),
  notifica "Rutina no ejecutada".
- **Notificaciones** (canal propio):
  - Batería baja (nivel 1) detectada en handshake/keep-alive (máx 1 por carga).
  - Desconexión prolongada: al agotar la reconexión automática.

## C4 — Laboratorio (consola JSON raw)

- Pestaña en Herramientas: editor JSON libre (mono), historial de comandos
  enviados (persistido, máx ~50), plantillas precargadas (sta query, show
  speak/play, light set, alarm ops, game ops, photo ops).
- Enviar: envuelve con `Protocol.frame()` y muestra la respuesta JSON o hex
  (RX). Errores de parseo visibles.
- Guía en la pestaña: cómo capturar el tráfico de la app oficial con
  `adb logcat | grep BleUtils` (link con la investigación de firmware D).

## Fuera de alcance (C5, plan futuro)

Gamificación (rachas/historial), editor de escenas propio, widget de escritorio,
UI completa de juegos (tableros). Sniffer BLE real dentro de la app (requiere
root; se cubre con adb logcat). Transferencia real de fotos (sigue dependiendo
del WiFi del robot). Botón "Abrir" en galería (FileProvider — se incluye si
queda espacio en C, si no pasa a C5).

## Arquitectura

- `ble/RemoteController.kt` — clase standalone (no depende del ViewModel) que
  maneja SU PROPIA conexión BLE (reusa `BleClient` + `Protocol` + una versión
  liviana del patrón ensureMode/reconexión). La usa el servicio de fondo.
- **Una sola conexión a la vez**: cuando el modo remoto está activo, la
  conexión la maneja `RemoteController` (en el servicio); la UI en foreground
  muestra el estado del servicio y NO inicia su propia conexión BLE. Al apagar
  el modo remoto, la UI retoma el flujo normal con el VM.
- `ble/RemoteApiServer.kt` — servidor HTTP (ServerSocket, tope de concurrencia
  4) que traduce endpoints a llamadas de `RemoteController`; nunca habla con el
  VM (que vive y muere con la activity).
- `ble/RemoteService.kt` — foreground service (type `connectedDevice`) con
  notificación persistente + wakelock; levanta RemoteController + RemoteApiServer.
- `RobotViewModel`: `startRemoteMode()/stopRemoteMode()`, `UiState.remoteRunning`
  (+ estado remoto compartido vía prefs/`SharedFlow` estático para que la UI
  refleje lo que hace el servicio). Rutinas: `UiState.routines` + CRUD en prefs.
- `RoutineWorker` (WorkManager): si el modo remoto está activo, ejecuta la
  rutina vía el servicio/RemoteController; si no, publica una notificación
  "Rutina pendiente — abrí la app" (full-screen intent) que al abrir la app
  dispara la rutina por el camino normal del VM.
- UI: toggle en Herramientas, sección Rutinas, pestaña Laboratorio, campo de
  plantilla en la config del chat.

## Definición de "done"

1. C1: con el robot conectado y el modo remoto activo, `curl` desde el PC de
   Hermes (misma LAN) ejecuta speak/play/light y devuelve estado correcto;
   token obligatorio verificado (401 sin token).
2. C2: el prompt enviado incluye el bloque de contexto (verificable en logcat
   del VM o en el reporte del request).
3. C3: una rutina programada a +2 min se ejecuta sola con la app en background
   (o notifica fallo claro); notificación de batería baja verificable.
4. C4: comando raw de plantilla → respuesta del robot en la consola.
5. Sin regresiones en A/B (máquina de reconexión, modos, UI); build gate por
   tarea + QA por tarea + QA senior final + verificación en vivo con el robot.

## Gate de calidad por tarea

```bash
cd ~/aibi/AibiPilot
JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
```

## Riesgos conocidos

- MIUI puede matar el foreground service igual (agresividad de HyperOS):
  mitigar con notificación persistente + wakelock + reintentos; documentar la
  limitación en la guía. WorkManager en MIUI requiere autostart habilitado:
  documentar.
- El robot acepta UNA conexión BLE: con modo remoto activo la app no puede
  usar su conexión normal (diseño de "una conexión a la vez" de la sección
  Arquitectura).
- El modo remoto mantiene BLE activo → consume batería del robot: es opt-in
  y se documenta.
