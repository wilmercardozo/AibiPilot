# Subproyecto C — Features nuevas: Implementation Plan

> **For agentic workers:** La ejecución sigue el protocolo de la metodología Tita Media
> (briefs por tarea, dev → QA, fix loop, revisión final). Los checkboxes marcan pasos de
> cada tarea. El orquestador despacha un agente por tarea con el brief en
> `.metodologia/features-c/briefs/task-N.md`.

**Goal:** Agregar 4 features que la app oficial no tiene: modo remoto (HTTP API + Hermes),
chat IA con contexto del robot, rutinas programadas + notificaciones (WorkManager) y
laboratorio (consola JSON raw).

**Architecture:** Un `RemoteController` standalone (conexión BLE propia + ensureMode-lite)
dentro de un foreground service que sirve la API HTTP; una sola conexión BLE a la vez
(remoto ON = el servicio manda, la UI solo muestra estado). Rutinas con WorkManager que
usan el servicio si el modo remoto está activo o notifican para abrir la app. Consola raw
reutiliza `Protocol.frame`.

**Tech Stack:** Kotlin 2.1.20, Compose, coroutines, ServerSocket (sin deps HTTP nuevas),
`androidx.work:work-runtime-ktx` (nueva). Verificación: adb + curl vía `adb forward` +
robot real.

## Global Constraints

- Fuente de verdad: spec `docs/superpowers/specs/2026-09-22-features-c-design.md` +
  DESIGN.md (estilo visual vigente de B) + AGENTS.md (protocolo BLE, modos, quirks MIUI).
- **Una conexión BLE a la vez**: con modo remoto activo, la UI no inicia conexión propia.
- No romper la máquina de reconexión/keep-alive del VM (plan A) ni la UI de B.
- Gate de calidad de toda tarea:
  `cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug`
- Sin tests unitarios (decisión de proyecto). Verificación = build + QA de diff + pruebas en vivo.
- Copy en español, estilo de DESIGN.md. Base del plan: commit `b58a32d`.

---

### Task 1: RemoteController (conexión BLE standalone)

**Files:**
- Create: `app/src/main/java/com/wil/aibipilot/ble/RemoteController.kt`

**Interfaces:**
- Consumes: `BleClient` (scan/connect/onEvent/onState/write/currentMtu/disconnect),
  `Protocol.*` (builders y `frame()`), `Animations` (validación de animation id opcional).
- Produces (consumen Tasks 2 y 5):
  - `class RemoteController(context: Context)`
  - `val state: StateFlow<RemoteState>` con `data class RemoteState(conn: ConnState,
    info: RobotInfo, currentMode: String?)`
  - `suspend fun connect(mac: String, name: String?)` · `fun disconnect()`
  - `suspend fun speak(text: String): Result<Unit>` · `suspend fun play(animation: String)`
    · `suspend fun setLight(mode: String, color: List<Int>, brightness: Int)` ·
    `suspend fun lightOn(id: Int = 0)` · `suspend fun lightOff(id: Int = 0)` ·
    `suspend fun setVolume(level: String)` · `suspend fun alarmsList(): List<AlarmItem>` ·
    `suspend fun alarmAdd(tag: Int, time: String)` · `suspend fun alarmDel(index: Int)` ·
    `suspend fun game(type: String, op: String)` · `suspend fun scene(id: String)`
  - `suspend fun sendRaw(json: String): String?` (utilidad interna del controller: envuelve
    con frame, espera respuesta 6s o null)

- [ ] **Step 1: Estructura** — copiar de `RobotViewModel` SOLO lo esencial y sin UI:
  `ensureMode` (mismo patrón: `modeIn`/`modeOut` con `*_in_ok`, timeout 4s + fallback 700ms),
  espera de respuestas con `Mutex` + `CompletableDeferred` por request (solo un request en
  vuelo a la vez: `withLock`), `onBleEvent` → parseo mínimo (result strings + sta_rsp para
  info), handshake `sta_query(1,8,11,12)` al conectar (1s de espera), keep-alive igual que el
  VM (ping sta query[12] a los 20s idle solo sin modo activo; si no responde → reconnect).
  NO copiar: chat LLM, escenas compuestas (scene() mapea a llamadas equivalentes), foto TCP.
  `scene(id)` = las mismas secuencias de `playScene` del VM pero con las primitivas de arriba
  y `delay`.

- [ ] **Step 2: Modos por feature** — `modeIn/modeOut` con los mismos builders del VM
  (`show/light/alarm/setting/chess/snake/pirate/zero`). Reusá `Protocol`.

- [ ] **Step 3: Build** — gate: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ble/RemoteController.kt
git commit -m "feat(remote): RemoteController con conexión BLE standalone y comandos"
```

---

### Task 2: RemoteApiServer + RemoteService + toggle

**Files:**
- Create: `app/src/main/java/com/wil/aibipilot/ble/RemoteApiServer.kt`
- Create: `app/src/main/java/com/wil/aibipilot/ble/RemoteService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (service + permisos foreground)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (startRemoteMode/
  stopRemoteMode, UiState.remoteRunning, prefs token/puerto, SharedFlow estático de estado remoto)
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt` (toggle Modo remoto +
  campo de token/puerto en diálogo)

**Interfaces:**
- Consumes: Task 1 (`RemoteController`).
- Produces:
  - `object RemoteStateBus { val running = MutableStateFlow(false) }` (estático, para que la
    UI refleje el servicio aunque el VM no lo haya arrancado).
  - `RobotViewModel.startRemoteMode()` / `stopRemoteMode()` (arrancan/paran
    `RemoteService` vía `ContextCompat.startForegroundService` / `stopService`).
  - `UiState.remoteRunning: Boolean = false`.
  - `RemoteApiServer(controller: RemoteController, token: String, onLog: (String) -> Unit)`
    con `start(port: Int)` / `stop()`; bind `0.0.0.0` (token obligatorio: la seguridad es el
    token, no el bind; así `adb forward` sirve para probar).
  - `RemoteService` (foreground, `connectedDevice`, notificación "Modo remoto activo" con
    acción Parar; partial wakelock).

- [ ] **Step 1: RemoteApiServer** — ServerSocket + hilos por request (concurrencia máx 4 con
  `Semaphore`); parseo de request HTTP 1.1 mínimo (request line + headers hasta blank line;
  body con Content-Length, tope 64KB); header `Authorization: Bearer <token>` — si falta o no
  matchea → `401 {"ok":false,"error":"token inválido"}`; ruteo de los 11 endpoints del spec
  C1 con respuestas `{"ok":true,...}`; errores: robot no conectado (`controller.state.conn !=
  CONNECTED`) → 409 con error claro; logging solo IP+endpoint (nunca token). Endpoint
  desconocido → 404. Método incorrecto → 405.

- [ ] **Step 2: RemoteService** — `onCreate`: notificación (canal "remote_mode"), wakelock,
  `RemoteStateBus.running.value = true`, lee mac guardado (mismas prefs del VM), crea
  `RemoteController` + conecta; si token vacío → no levanta el server y notifica
  "Configurá el token del modo remoto". `onDestroy`: stop server/controller/wakelock,
  `running=false`. `onStartCommand` idempotente.

- [ ] **Step 3: Manifest** — `<service android:name=".ble.RemoteService"
  android:foregroundServiceType="connectedDevice" android:exported="false"/>` +
  permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`.

- [ ] **Step 4: VM + UI** — `startRemoteMode()/stopRemoteMode()`, `remoteRunning` (del
  `RemoteStateBus`), toggle en ToolsScreen (sección "Modo remoto") + diálogo de configuración
  con token (PasswordVisualTransformation) y puerto (default 8080) en prefs
  (`remote_token`, `remote_port`). Con remoto activo: UI muestra aviso "modo remoto activo —
  la conexión la maneja el servicio" y no inicia conexión BLE propia (guard en
  `tryAutoReconnect`/`connect` si `remoteRunning`).

- [ ] **Step 5: Build** — BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ble/RemoteApiServer.kt app/src/main/java/com/wil/aibipilot/ble/RemoteService.kt app/src/main/AndroidManifest.xml app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt
git commit -m "feat(remote): API HTTP local + foreground service + toggle y token"
```

- [ ] **Step 7 (mismo commit o extra): Guía Hermes** — `docs/HERMES.md`: cómo activar el modo
  remoto, obtener la IP de la tablet, y un ejemplo de tool para Hermes Agent (script bash o
  Python que hace curl a los endpoints con el token) para controlar el robot desde Telegram.

```bash
git add docs/HERMES.md && git commit -m "docs(hermes): guía de integración con Hermes Agent"
```

---

### Task 3: Chat IA con contexto del robot

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (askLlm, LlmConfig)
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ChatScreen.kt` (campo plantilla en diálogo)

**Interfaces:**
- Consumes: `UiState.info`, `loadLlmConfig/saveLlmConfig` existentes.
- Produces: `LlmConfig.systemPrompt: String? = null` (null = default) + bloque de contexto.

- [ ] **Step 1: Bloque de contexto** — en `askLlm()`, antes de armar el body, construir:

```kotlin
val info = _ui.value.info
val ctx = buildString {
    append("Estado actual del robot: ")
    append("batería=${batteryLabel(info.battery)}, pasos=${info.steps}, monedas=${info.gold}, ")
    append("comida=${info.food}, hora=${java.time.LocalTime.now()}")
}
```

  e inyectarlo **como última línea del mensaje `system`** (template + "\n\n" + ctx).
  `batteryLabel` ya existe como helper privado de la UI: movelo a un lugar compartido o
  duplicalo mínimo en el VM (1 Baja / 2 Media / 3 Alta / 4 Llena).

- [ ] **Step 2: Plantilla editable** — `LlmConfig.systemPrompt: String?`; `loadLlmConfig`/
  `saveLlmConfig` la persisten (`llm_prompt`); `askLlm` usa `cfg.systemPrompt ?: <default
  actual>`. Diálogo del chat: campo multilínea "Prompt del sistema" (placeholder = default) +
  botón "Restaurar default" (pone null).

- [ ] **Step 3: Build** — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/ChatScreen.kt
git commit -m "feat(chat): contexto del robot en el prompt + plantilla de sistema editable"
```

---

### Task 4: Rutinas — modelo, CRUD y UI

**Files:**
- Modify: `app/build.gradle.kts` (dependencia work)
- Create: `app/src/main/java/com/wil/aibipilot/routines/Routines.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (UiState.routines + CRUD)
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt` (sección Rutinas)

**Interfaces:**
- Consumes: prefs existentes; componentes UI de B.
- Produces (consume Task 5):
  - `data class Routine(id: String, time: String /*HH:mm*/, days: Set<Int> /*1=L..7=D*/,
    enabled: Boolean, action: RoutineAction)` con
    `sealed interface RoutineAction { data class Speak(val text: String); data class
    Animation(val id: String); data class Scene(val id: String); data class Light(val on: Boolean) }`
  - `object RoutinesStore { fun load(ctx): List<Routine>; fun save(ctx, List<Routine>) }`
    (JSON en prefs `"routines"` con kotlinx.serialization o `Protocol.json`).
  - `UiState.routines: List<Routine>` + `RobotViewModel.routinesUpsert(r)`, `routinesDelete(id)`,
    `routinesToggle(id, enabled)`.

- [ ] **Step 1: Dependencia** — `implementation("androidx.work:work-runtime-ktx:2.9.1")` en
  `app/build.gradle.kts`.

- [ ] **Step 2: Modelo + store** — `Routines.kt` con las data classes y `RoutinesStore`.
  Representación PLANA de la acción en JSON: `type: "speak"|"animation"|"scene"|"light"` +
  `payload: String` (texto, id de animación, id de escena, "on"/"off") — sin polimorfismo
  kotlinx.

- [ ] **Step 3: VM + UI** — CRUD en el VM (persiste con RoutinesStore, update de UiState);
  en ToolsScreen: lista de rutinas (hora, días "L M X J V S D" con activos resaltados, acción
  resumida, switch enabled, borrar) + diálogo de nueva/editar: hora (HH:mm), días (7 chips),
  acción (4 chips: Hablar/Animación/Escena/Luz) + campo de texto (para hablar) o selector
  (animación de `Animations.all`, escena de las 4, luz on/off).

- [ ] **Step 4: Build** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/wil/aibipilot/routines/Routines.kt app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt
git commit -m "feat(routines): modelo, persistencia y CRUD de rutinas con UI"
```

---

### Task 5: RoutineWorker + notificaciones

**Files:**
- Create: `app/src/main/java/com/wil/aibipilot/routines/RoutineWorker.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (notificaciones batería baja
  y desconexión; ejecución de rutina pendiente al abrir la app)
- Modify: `app/src/main/java/com/wil/aibipilot/MainActivity.kt` (si hace falta recibir el
  intent de "rutina pendiente")

**Interfaces:**
- Consumes: Task 4 (`Routine`, `RoutinesStore`, `RemoteStateBus` de Task 2).
- Produces:
  - `object RoutineScheduler { fun schedule(ctx); fun runDueRoutines(ctx): List<Routine> }`
  - `RemoteService` gana un path interno para ejecutar rutinas (o el worker habla con el
    servicio vía un `startService` con action + extra JSON; elegí UNA y mantenela).

- [ ] **Step 1: RoutineWorker** — `CoroutineWorker`: si `RemoteStateBus.running` → envía un
  intent de servicio (`startService` con action `ACTION_RUN_ROUTINES` y extra JSON con las
  rutinas debidas — el servicio las ejecuta con su RemoteController); si no → por cada rutina
  debida publicar notificación "Rutina pendiente: <resumen>" con content intent que abre
  MainActivity con extra `run_routine_id`.
  `RoutineScheduler.schedule()`: `PeriodicWorkRequest` 15 min, constraints ninguna,
  `ExistingPeriodicWorkPolicy.KEEP`.

- [ ] **Step 2: Ejecución al abrir** — MainActivity/VM: si llega con `run_routine_id`,
  conectar (máquina normal) y ejecutar la rutina por el camino del VM (mismo código que
  `playScene` para acciones equivalentes); marcar snackbar "Rutina ejecutada".

- [ ] **Step 3: Notificaciones** — canal `"aibi"`; (a) batería baja: en `parseStaRsp` y en el
  keep-alive del VM, si `battery == 1` y no se notificó desde la última vez que fue > 1 →
  notificar "Batería baja — cargá al robot"; (b) desconexión: en el agotamiento de la
  máquina de reconexión (`scheduleReconnect` rama MAX) → notificar "No se pudo reconectar".
  Permiso `POST_NOTIFICATIONS` (Android 13+) en manifest + runtime si hace falta (la app ya
  corre en 13+: pedirlo en el wizard o al habilitar rutinas).

- [ ] **Step 4: Build** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/routines/RoutineWorker.kt app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(routines): worker con ejecución vía servicio o notificación + notificaciones"
```

---

### Task 6: Laboratorio — consola JSON raw

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt` (sub-pestaña Laboratorio)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (sendRaw + historial)

**Interfaces:**
- Consumes: `Protocol.frame`/`json`, `LogLine`/log, componentes UI.
- Produces:
  - `RobotViewModel.sendRaw(json: String)` — valida JSON, envuelve con `Protocol.frame`, envía
    por el camino `send()` normal, loguea TX/RX con categoría; historial `UiState.rawHistory:
    List<String>` (últimos 50, persistido en prefs `raw_history`).

- [ ] **Step 1: VM** — `sendRaw` + historial + `clearRawHistory()`.

- [ ] **Step 2: UI** — sub-pestaña "Laboratorio" en ToolsScreen: `OutlinedTextField` mono
  multilínea + botón "Enviar"; lista de plantillas (chips: sta query, show speak, show play,
  light set, alarm list/add/del, game in/start/play, photo in/sync) que rellenan el editor con
  el JSON de ejemplo; historial tocable (rellena el editor); respuestas visibles en la
  sub-pestaña Log BLE (o debajo del editor, mostrando las últimas RX — elegí una).
  Nota de guía: texto chico "Capturar la app oficial: adb logcat | grep BleUtils".

- [ ] **Step 3: Build** — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt app/src/main/java/com/wil/aibipilot/RobotViewModel.kt
git commit -m "feat(lab): consola JSON raw con plantillas e historial"
```

---

## Verificación en vivo final (orquestador, tras Task 6)

1. Build + install. Con el robot despierto:
2. **C1**: activar modo remoto con token; desde esta máquina:
   `adb forward tcp:8080 tcp:8080` + `curl -H "Authorization: Bearer <token>" localhost:8080/status`
   → estado del robot; `POST /speak {"text":"hola desde Hermes"}` → robot habla (ACK en
   logcat); curl sin token → 401. Después `adb forward --remove tcp:8080`.
3. **C2**: abrir chat y verificar en el código/log que el prompt incluye el bloque de contexto.
4. **C3**: crear rutina a +2 min con acción "hablar"; esperar; verificar ejecución (logcat) o
   notificación de pendiente; batería baja simulable solo leyendo código (robot cargado).
5. **C4**: plantilla sta query → respuesta sta_query_ok en la consola.
6. Sin regresiones A/B: conectar normal, una acción por pantalla principal, tema, wizard.
7. QA senior final del branch + una ola de fix + docs de cierre (AGENTS/PROGRESO/MEMORIA +
   limpieza de workspace) + cierre según elección del usuario.
