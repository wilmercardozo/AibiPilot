# Subproyecto A — Estabilizar AibiPilot: Implementation Plan

> **For agentic workers:** La ejecución sigue el protocolo de la metodología Tita Media
> (briefs por tarea, dev → QA, fix loop, revisión final). Los checkboxes (`- [ ]`) marcan
> los pasos de cada tarea. El orquestador despacha un agente por tarea con el brief en
> `.metodologia/estabilizar-aibipilot/briefs/task-N.md`.

**Goal:** Verificar y arreglar en vivo las features pendientes (volumen, alarmas, juegos,
fotos TCP) y robustecer la capa BLE (reconexión, desconexión silenciosa, "otra app",
log categorizado) — sin rediseñar la UI ni agregar features nuevas.

**Architecture:** Los cambios son incrementales sobre la estructura actual (6 archivos
Kotlin, MVVM con `RobotViewModel` + `StateFlow<UiState>`). Se agregan: log categorizado
(`LogCat`/`LogLine`), estados de reconexión en `ConnState`, keep-alive basado en inactividad
RX, y diagnóstico de fallo de conexión mediante re-scan del MAC guardado.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose, coroutines, okhttp (sin cambios de deps).
Verificación en vivo: adb + screencap + uiautomator dump + logcat (tags `AibiBle`, `BleUtils`).

## Global Constraints

- Frame TX: `BB AA` + len(2B little-endian) + JSON UTF-8. RX igual; `DD CC` = binario. No cambiar `Protocol.frame()`.
- Modos: el robot exige `<feature>_in` antes de comandos. Mantener `ensureMode()` y su patrón de ACK `*_in_ok` (timeout 4s).
- MIUI: ACCESS_FINE_LOCATION obligatorio para scan BLE. No sacar ese permiso.
- **Sin tests unitarios** (decisión del spec): el gate es build + verificación en vivo + revisión de QA sobre el diff.
- No rediseñar UI (es subproyecto B): solo cambios mínimos en `Screens.kt` donde la tarea lo pide.
- Gate de calidad de toda tarea: `cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug`
- Commit style: `fix(<area>): ...`, `feat(<area>): ...`, `test(<area>): ...` (repo nuevo, convención simple).
- Base del plan: commit `98a2f77` (docs spec). Repo ya inicializado (branch `main`).

---

### Task 1: Log BLE categorizado con filtros

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (líneas 55-67 UiState, 508-513 log, y call sites 159-160, 203-207, 221, 501, 559)
- Modify: `app/src/main/java/com/wil/aibipilot/ui/Screens.kt` (líneas 744-774 LogTab, imports)

**Interfaces:**
- Consumes: nada nuevo (base del plan).
- Produces:
  - `enum class LogCat { TX, RX, EVT, ERR, SYS }` y `data class LogLine(val cat: LogCat, val text: String)` en `RobotViewModel.kt` (package `com.wil.aibipilot`).
  - `UiState.log: List<LogLine>` (antes `List<String>`).
  - `RobotViewModel.log(cat: LogCat, line: String)` (privada, con overload `log(line: String)` = SYS).
  - Task 6 consumirá `log(LogCat.SYS/ERR, ...)`.

- [ ] **Step 1: Cambiar el modelo de log en `RobotViewModel.kt`**

Reemplazar el `data class UiState` (líneas 55-67) así (solo cambia el tipo de `log`):

```kotlin
enum class LogCat { TX, RX, EVT, ERR, SYS }

data class LogLine(val cat: LogCat, val text: String)

data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val devices: List<ScanDevice> = emptyList(),
    val info: RobotInfo = RobotInfo(),
    val log: List<LogLine> = emptyList(),
    val volume: String = "high",
    val alarms: List<AlarmItem> = emptyList(),
    val lights: List<LightItem> = emptyList(),
    val photoServerRunning: Boolean = false,
    val photos: List<String> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatThinking: Boolean = false
)
```

- [ ] **Step 2: Categorizar los call sites de `log()` en `RobotViewModel.kt`**

Reemplazar `private fun log(line: String)` (líneas 508-513) por:

```kotlin
private fun log(line: String) = log(LogCat.SYS, line)

private fun log(cat: LogCat, line: String) {
    _ui.update { s ->
        val maxLines = 200
        s.copy(log = (s.log + LogLine(cat, line)).takeLast(maxLines))
    }
}
```

Después, estos cambios puntuales:
- En `send()` (línea 501): `log("TX $label [${bytes.size} bytes] ${bytes.toHex()}")` → `log(LogCat.TX, "TX $label [${bytes.size} bytes]")`; y `android.util.Log.d("AibiBle", "TX $label")` (línea 497) → `android.util.Log.d("AibiBle", "TX $label [${bytes.size} bytes] ${bytes.toHex()}")` (el hex completo debe seguir disponible en logcat).
- En `onBleEvent` JsonMessage (línea 202): `log("RX ${event.json}")` → `log(LogCat.RX, "RX ${event.json}")`.
- En `onBleEvent` RawMessage (línea 206): `log("RX binario: ${event.bytes.toHex()}")` → `log(LogCat.RX, "RX binario: ${event.bytes.toHex().take(64)}")`.
- En `parseResponse` aibi_event (línea 219): `log("Evento robot: $eventName")` → `log(LogCat.EVT, "Evento robot: $eventName")`.
- En `send()` catch (línea 503): `log("Error enviando $label: ${e.message}")` → `log(LogCat.ERR, "Error enviando $label: ${e.message}")`.

- [ ] **Step 3: Reescribir `LogTab` en `Screens.kt` con filtros y colores**

Reemplazar `LogTab` completa (líneas 744-774) por:

```kotlin
@Composable
private fun LogTab(vm: RobotViewModel, ui: UiState) {
    var filter by remember { mutableStateOf<LogCat?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tráfico BLE", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.clearLog() }) { Text("Limpiar") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("Todos") },
                modifier = Modifier.padding(end = 6.dp)
            )
            LogCat.entries.forEach { cat ->
                FilterChip(
                    selected = filter == cat,
                    onClick = { filter = cat },
                    label = { Text(cat.name) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        val visible = remember(ui.log, filter) {
            ui.log.filter { filter == null || it.cat == filter }
        }
        LaunchedEffect(visible.size) {
            if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(visible) { line ->
                Text(
                    "${line.cat.name} ${line.text}",
                    color = when (line.cat) {
                        LogCat.ERR -> Color(0xFFD32F2F)
                        LogCat.TX -> Color(0xFF1976D2)
                        LogCat.RX -> Color(0xFF2E7D32)
                        LogCat.EVT -> Color(0xFFF57C00)
                        LogCat.SYS -> Color(0xFF757575)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                )
            }
        }
    }
}
```

Agregar import en `Screens.kt` junto a los otros imports de `com.wil.aibipilot`:
`import com.wil.aibipilot.LogCat` y
`import androidx.compose.foundation.lazy.rememberLazyListState`.

- [ ] **Step 4: Build**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verificación en vivo del log**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
Conectar al robot desde la UI (uiautomator dump para localizar el botón "Reconectar a …" o "Buscar robots"):
```bash
adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '>\n' | grep -i "reconectar\|buscar"
```
Verificar en logcat que hay TX/RX y que la pestaña Log BLE muestra líneas con prefijo de categoría y colores (screencap):
```bash
adb logcat -d | grep AibiBle | tail -20
adb exec-out screencap -p > /tmp/logtab.png
```
Expected: logcat con `TX ...` y `RX {...}`; pantalla del tab Log BLE con chips `Todos TX RX EVT ERR SYS` funcionales.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(log): log BLE categorizado con filtros y colores"
```

---

### Task 2: Volumen — verificar en vivo y arreglar si falla

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/protocol/Protocol.kt` (solo si el contraste con la app oficial lo exige)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (solo si el fix lo exige)

**Interfaces:**
- Consumes: `Protocol.settingVolume(level)` y `RobotViewModel.setVolume(level)` ya existentes (sin cambio de firma).
- Produces: nada nuevo; commit `test(volume): ...` o `fix(volume): ...`.

- [ ] **Step 1: Contrastar el builder con el decompilado oficial**

```bash
grep -rn "volume" /tmp/aibi/jadx_out/sources/ai/living/aibi/model/bleModel/settings/ | head -20
```
Expected: la app oficial envía `setting_req` con `op:"volume"` y campo `volume` con valores `"mute"/"low"/"high"` (String). Si el nombre de campo u op difiere, corregir `Protocol.settingVolume` con la evidencia y anotar en el reporte.

- [ ] **Step 2: Build, instalar y conectar**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
Conectar al robot (tap en "Reconectar a …").

- [ ] **Step 3: Cambiar volumen desde la UI y verificar ACK en logcat**

Ir al tab Estado, tocar cada opción de volumen (mute/low/high). Verificar:
```bash
adb logcat -d | grep AibiBle | grep -E "Volumen|setting"
```
Expected para cada nivel: `TX Volumen: mute|low|high` y RX `setting_rsp` con `"result":"setting_volume_ok"` (si el robot responde con otro result, registrar el valor real y ajustar la expectativa en el reporte; el ACK del `setting_in_ok` debe aparecer antes).

- [ ] **Step 4: Fix loop si no responde (solo si Step 3 falla)**

Capturar el tráfico real de la app oficial al cambiar volumen (si está instalada) o revisar smali:
```bash
adb logcat -d | grep BleUtils | grep -iE "volume|setting"
```
Aplicar la corrección mínima en `Protocol.kt`/`RobotViewModel.kt`, rebuild, repetir Step 3.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(volume): verificado en vivo con ACK setting_volume_ok"   # o fix(volume): <qué> si hubo fix
```

---

### Task 3: Alarmas — verificar en vivo y arreglar si falla

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/protocol/Protocol.kt` (solo si el contraste lo exige)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (`parseAlarmRsp`, solo si el fix lo exige)

**Interfaces:**
- Consumes: `Protocol.alarmIn/Out/List/Add/Del`, `RobotViewModel.alarmRefresh/alarmAdd/alarmDel`, `UiState.alarms` ya existentes (sin cambio de firma).
- Produces: nada nuevo; commit `test(alarm): ...` o `fix(alarm): ...`.

- [ ] **Step 1: Contrastar builders con el decompilado oficial**

```bash
ls /tmp/aibi/jadx_out/sources/ai/living/aibi/model/bleModel/alarm/
grep -rn "alarm" /tmp/aibi/jadx_out/sources/ai/living/aibi/model/bleModel/alarm/ | grep -E '"op"|"index"|"time"|list' | head -20
```
Expected: ops `in/out/list/add/del`, campos `index` (int) y `time` (String "HH:mm"). Si difiere, corregir `Protocol` con evidencia.

- [ ] **Step 2: Build, instalar, conectar**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
Conectar al robot.

- [ ] **Step 3: Probar list/add/del desde el tab Alarmas**

Tab Alarmas → "Actualizar" (list). Luego agregar una alarma (ej. 07:30, index libre), luego borrarla. Verificar:
```bash
adb logcat -d | grep AibiBle | grep -iE "alarm"
```
Expected: `TX alarm in`, `TX alarm list` y RX `alarm_rsp` con `"result":"alarm_list_ok"` y lista no vacía (o vacía, pero con result ok); add → `alarm_add_ok`; del → `alarm_del_ok`. Si los result reales difieren, registrarlos y ajustar `parseAlarmRsp` solo si rompe el parseo.

- [ ] **Step 4: Fix loop si falla** — igual que Task 2 Step 4, con `grep BleUtils | grep -i alarm`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(alarm): verificado en vivo list/add/del"   # o fix(alarm): <qué>
```

---

### Task 4: Juegos — verificar en vivo y arreglar si falla

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/protocol/Protocol.kt` (solo si el contraste lo exige)

**Interfaces:**
- Consumes: `Protocol.gameIn/gameOut/gameStart/gamePlay`, `RobotViewModel.gameEnter/gameExit/gameStart/gamePlay` ya existentes (sin cambio de firma).
- Produces: nada nuevo; commit `test(games): ...` o `fix(games): ...`.

- [ ] **Step 1: Contrastar ops de juegos con el decompilado oficial**

```bash
for g in chess snake pirate zero; do echo "== $g =="; grep -rnE '"(op|in|out|start|play)"' /tmp/aibi/jadx_out/sources/ai/living/aibi/model/bleModel/$g/ 2>/dev/null | head -8; done
```
Expected: cada juego usa `{game}_req` con ops `in/start/play` (pirate = `pirateWars` en el decompilado: verificar el nombre real del tipo de feature, ej. `pirate_req` vs `pirateWars_req`, y corregir `Protocol.gameIn/gameOut/gameStart/gamePlay` si difiere).

- [ ] **Step 2: Build, instalar, conectar**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
Conectar al robot.

- [ ] **Step 3: Probar los 4 juegos desde el tab Juegos**

Para cada juego (chess, snake, pirate, zero): tocar el juego (entra), "Iniciar" (start), "Jugar" (play). Verificar:
```bash
adb logcat -d | grep AibiBle | grep -iE "chess|snake|pirate|zero"
```
Expected: `TX <game> in` + ACK `<game>_in_ok`; `TX <game> start` + `<game>_start_ok` (u op equivalente); lo mismo para play. Si algún op no responde, registrar el comportamiento real.

- [ ] **Step 4: Fix loop si falla** — igual que Task 2 Step 4, con `grep BleUtils | grep -iE "chess|snake|pirate|zero"`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(games): verificado en vivo in/start/play de los 4 juegos"   # o fix(games): <qué>
```

---

### Task 5: Fotos TCP — verificar en vivo y arreglar si falla

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ble/PhotoTcpServer.kt` (solo si el fix lo exige)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (`startPhotoSync`, solo si el fix lo exige)

**Interfaces:**
- Consumes: `Protocol.photoSync(ip, port)`, `PhotoTcpServer(outputDir, onPhoto, onLog)`, `RobotViewModel.startPhotoSync/stopPhotoSync` ya existentes (sin cambio de firma).
- Produces: nada nuevo; commit `test(photo): ...` o `fix(photo): ...`.

- [ ] **Step 1: Verificar precondiciones de red**

```bash
adb shell ip addr show wlan0 | grep "inet "
```
Expected: la tablet tiene IP en WiFi. El robot debe estar en la misma red. Si no hay IP, la prueba no puede pasar → reportar `BLOCKED` (se aparca con Ruling y se retoma).

- [ ] **Step 2: Contrastar protocolo TCP con el decompilado oficial**

```bash
grep -rn "finish\|filesize\|delimited" /tmp/aibi/jadx_out/sources/ai/living/aibi/ | grep -v AibiPilot | head -10
```
Expected: header `finish;name=...;filesize=N;delimited=------#`, datos binarios, cierre con 18 guiones, ACK `ok` (igual a `PhotoTcpServer.kt` actual).

- [ ] **Step 3: Build, instalar, conectar**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
Conectar al robot.

- [ ] **Step 4: Sincronizar fotos desde el tab Fotos**

Tab Fotos → "Sincronizar fotos" (inicia el servidor TCP 9090 y envía `photo_sync`). Esperar ~30s. Verificar:
```bash
adb logcat -d | grep -iE "PhotoTcpServer|photo"
adb shell run-as com.wil.aibipilot ls -la files/photos
```
Expected: log `Servidor TCP de fotos escuchando en puerto 9090`, `Robot conectado para enviar fotos: ...`, `Foto guardada: ...`; y al menos un archivo en `files/photos`. Si el robot no conecta en 30s, revisar si `photo_req sync` recibió ACK en logcat (`grep AibiBle | grep photo`).

- [ ] **Step 5: Fix loop si falla** — ajustar `PhotoTcpServer` (marcador/header) o el builder `photoSync` según evidencia; rebuild y repetir Step 4.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test(photo): verificado en vivo TCP 9090 con foto recibida"   # o fix(photo): <qué>
```

---

### Task 6: Robustez BLE — reconexión, desconexión silenciosa y "otra app conectada"

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/ui/Screens.kt` (solo header de conexión y ScanScreen: hint + estado RECONNECTING)

**Interfaces:**
- Consumes: `log(LogCat.SYS/ERR, ...)` y `LogLine`/`LogCat` de Task 1; `prefs()` con `KEY_MAC`/`KEY_NAME` ya existentes; `ble.scan()`, `ble.connect(...)`, `ble.disconnect()` existentes.
- Produces (para tareas futuras B/C):
  - `enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }`
  - `UiState.reconnectAttempt: Int` (intento actual de reconexión, 0 si no hay)
  - `UiState.connHint: String?` (mensaje de diagnóstico mostrado en ScanScreen)

- [ ] **Step 1: Agregar `RECONNECTING` a `ConnState` y campos a `UiState` en `RobotViewModel.kt`**

Reemplazar (líneas 33 y 55-67):

```kotlin
enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }
```

```kotlin
data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val reconnectAttempt: Int = 0,
    val connHint: String? = null,
    val devices: List<ScanDevice> = emptyList(),
    val info: RobotInfo = RobotInfo(),
    val log: List<LogLine> = emptyList(),
    val volume: String = "high",
    val alarms: List<AlarmItem> = emptyList(),
    val lights: List<LightItem> = emptyList(),
    val photoServerRunning: Boolean = false,
    val photos: List<String> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatThinking: Boolean = false
)
```

- [ ] **Step 2: Campos y timestamps de reconexión en `RobotViewModel`**

Junto a `scanJob` (línea 82), agregar:

```kotlin
private var reconnectJob: kotlinx.coroutines.Job? = null
private var keepAliveJob: kotlinx.coroutines.Job? = null
private var reconnectAttempt = 0
private var reconnectCancelled = false
private var lastRxAt = 0L
private var lastTxAt = 0L

private val rxEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
    extraBufferCapacity = 64
)

private val reconnectBackoff = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)
private val MAX_RECONNECT_ATTEMPTS = 10
```

- [ ] **Step 3: Timestamps y emisión de eventos RX en `onBleEvent`**

Reemplazar `onBleEvent` (líneas 199-209) por:

```kotlin
private fun onBleEvent(event: BleEvent) {
    lastRxAt = android.os.SystemClock.elapsedRealtime()
    rxEvents.tryEmit(Unit)
    when (event) {
        is BleEvent.JsonMessage -> {
            log(LogCat.RX, "RX ${event.json}")
            parseResponse(event.json)
        }
        is BleEvent.RawMessage -> {
            log(LogCat.RX, "RX binario: ${event.bytes.toHex().take(64)}")
        }
    }
}
```

Y en `send()` (líneas 496-506) agregar `lastTxAt = android.os.SystemClock.elapsedRealtime()` como primera línea del bloque.

- [ ] **Step 4: Lógica de reconexión y diagnóstico en `connectDevice`**

En `connectDevice` (línea 153), al inicio (antes de `ble.connect`), resetear:

```kotlin
reconnectCancelled = false
reconnectAttempt = 0
reconnectJob?.cancel()
_ui.update { it.copy(reconnectAttempt = 0, connHint = null) }
```

En el callback `onState`:
- En la rama `connected`: después de `log("Conectado. MTU: ...")` agregar `startKeepAlive()`.
- Reemplazar la rama `else` (líneas 176-180) por:

```kotlin
} else {
    if (reconnectCancelled || _ui.value.conn == ConnState.CONNECTING) {
        _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
        log(LogCat.SYS, "Se perdió la conexión BLE")
    } else {
        scheduleReconnect()
    }
}
```

Y reemplazar el timeout de 12s (líneas 184-191) por:

```kotlin
viewModelScope.launch {
    kotlinx.coroutines.delay(12000)
    if (_ui.value.conn == ConnState.CONNECTING) {
        ble.disconnect()
        _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
        log(LogCat.ERR, "Timeout de conexión")
        diagnoseConnectFailure(device.address)
    }
}
```

- [ ] **Step 5: Implementar `scheduleReconnect`, `diagnoseConnectFailure` y `startKeepAlive`**

Agregar estos métodos (junto a `connectDevice`):

```kotlin
private fun scheduleReconnect() {
    reconnectJob?.cancel()
    reconnectAttempt++
    if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
        _ui.update {
            it.copy(
                conn = ConnState.DISCONNECTED,
                reconnectAttempt = 0,
                connHint = "No se pudo reconectar. ¿El robot está dormido o apagado?"
            )
        }
        log(LogCat.ERR, "Reconexión agotada tras $MAX_RECONNECT_ATTEMPTS intentos")
        return
    }
    val delayMs = reconnectBackoff[minOf(reconnectAttempt - 1, reconnectBackoff.size - 1)]
    _ui.update { it.copy(conn = ConnState.RECONNECTING, reconnectAttempt = reconnectAttempt) }
    log(LogCat.SYS, "Reconexión automática: intento $reconnectAttempt en ${delayMs / 1000}s")
    reconnectJob = viewModelScope.launch {
        kotlinx.coroutines.delay(delayMs)
        val mac = prefs().getString(KEY_MAC, null)
        if (mac == null) {
            _ui.update { it.copy(conn = ConnState.DISCONNECTED, reconnectAttempt = 0) }
            return@launch
        }
        try {
            val btManager = getApplication<Application>()
                .getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            val device = btManager.adapter.getRemoteDevice(mac)
            connectDevice(device, prefs().getString(KEY_NAME, null))
        } catch (e: Exception) {
            _ui.update { it.copy(conn = ConnState.DISCONNECTED, reconnectAttempt = 0) }
            log(LogCat.ERR, "Reconexión fallida: ${e.message}")
        }
    }
}

/** Diagnóstico tras timeout de conexión: re-scan de 5s del MAC guardado. */
private fun diagnoseConnectFailure(mac: String) {
    viewModelScope.launch {
        var seen = false
        try {
            kotlinx.coroutines.withTimeoutOrNull(5000) {
                ble.scan().collect { dev ->
                    if (dev.device.address.equals(mac, ignoreCase = true)) seen = true
                }
            }
        } finally {
            ble.stopScan()
        }
        _ui.update {
            it.copy(
                connHint = if (seen) {
                    "El robot está al alcance pero rechazó la conexión. ¿Otra app (la oficial) lo tiene conectado? Cerrala y reintentá."
                } else {
                    "El robot está dormido o fuera de alcance. Despertalo y volvé a intentar."
                }
            )
        }
        log(LogCat.SYS, "Diagnóstico: ${if (seen) "al alcance, conexión rechazada" else "no visible (dormido/fuera de alcance)"}")
    }
}

/**
 * Keep-alive: si no hubo tráfico RX/TX en 20s y no estamos dentro de un modo
 * de función (para no molestar juegos/fotos), hace un ping sta query[12] y, si
 * no responde en 10s, fuerza la reconexión.
 */
private fun startKeepAlive() {
    keepAliveJob?.cancel()
    keepAliveJob = viewModelScope.launch {
        while (kotlinx.coroutines.isActive) {
            kotlinx.coroutines.delay(5000)
            val now = android.os.SystemClock.elapsedRealtime()
            val idle = now - lastRxAt > 20000 && now - lastTxAt > 20000
            if (_ui.value.conn == ConnState.CONNECTED && currentMode == null && idle) {
                val pingAt = lastRxAt
                log(LogCat.SYS, "Sin tráfico del robot en 20s: ping de verificación")
                send(Protocol.staQuery(12), "ping sta battery")
                val responded = kotlinx.coroutines.withTimeoutOrNull(10000) {
                    rxEvents.filter { lastRxAt > pingAt }.first()
                    true
                } ?: false
                if (!responded) {
                    log(LogCat.ERR, "El robot no responde al ping: forzando reconexión")
                    ble.disconnect()
                    scheduleReconnect()
                }
            }
        }
    }
}
```

Nota: `rxEvents.filter { lastRxAt > pingAt }.first()` — `lastRxAt` es un `Long` var leído en cada emisión; al haber RX nuevo, `onBleEvent` actualiza `lastRxAt` antes del `tryEmit`, así que el filtro pasa.

- [ ] **Step 6: Cancelar jobs y resetear en `disconnect()`; reanudar al volver a primer plano**

Reemplazar `onAppForeground()` (líneas 123-127) por:

```kotlin
fun onAppForeground() {
    if (_ui.value.conn == ConnState.SCANNING) {
        startScan()
    } else if (_ui.value.conn == ConnState.DISCONNECTED && !reconnectCancelled) {
        tryAutoReconnect()
    }
}
```

Reemplazar `disconnect()` (líneas 487-494) por:

```kotlin
fun disconnect() {
    reconnectCancelled = true
    reconnectJob?.cancel()
    keepAliveJob?.cancel()
    currentMode = null
    stopPhotoSync()
    ble.disconnect()
    currentDevice = null
    _ui.update {
        it.copy(
            conn = ConnState.DISCONNECTED,
            reconnectAttempt = 0,
            connHint = null,
            info = RobotInfo()
        )
    }
    log(LogCat.SYS, "Desconectado")
}
```

- [ ] **Step 7: Mostrar estado de reconexión y hint en `Screens.kt`**

En `ConnectedHeader` (líneas 313-330), reemplazar la línea del estado (320-324) por:

```kotlin
Text(
    when (ui.conn) {
        ConnState.CONNECTED -> "Conectado ✓"
        ConnState.RECONNECTING -> "Reconectando… (intento ${ui.reconnectAttempt})"
        else -> "Conectando..."
    },
    color = when (ui.conn) {
        ConnState.CONNECTED -> Color(0xFF2E7D32)
        else -> Color(0xFFF9A825)
    },
    style = MaterialTheme.typography.bodySmall
)
```

En `ScanScreen`, después del bloque de permisos/Bluetooth y antes del botón "Buscar robots" (línea 172), agregar:

```kotlin
ui.connHint?.let { hint ->
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Aviso", style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(12.dp))
}
```

(Usa los imports de `ElevatedCard` y `Column` ya presentes en `ScanScreen`.)

Verificar que `AibiPilotApp` (línea 97-101) ya cubre `RECONNECTING` con `ConnectedScreen` (el `else`), no requiere cambio.

- [ ] **Step 8: Build**

```bash
cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Verificación en vivo (orquestador)**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.wil.aibipilot/.MainActivity
```
1. Conectar al robot (esperar "Conectado ✓").
2. **Desconexión silenciosa**: apagar el robot (o sacarlo de alcance). Verificar en logcat:
   ```bash
   adb logcat -d | grep AibiBle | grep -iE "reconex|intento"
   ```
   Expected: `Reconexión automática: intento 1 en 1s`, intento 2 en 2s, … y si el robot vuelve a encenderse, reconexión exitosa con "Conectado ✓" sin tocar nada.
3. **Timeout de conexión**: con el robot apagado, desconectar y tocar "Reconectar a …". Esperar el timeout (~12s). Expected: log `Timeout de conexión`, `Diagnóstico: no visible (dormido/fuera de alcance)` y la tarjeta de aviso en ScanScreen con el mensaje de "dormido".
4. **Keep-alive**: con el robot conectado e inactivo 20s+, verificar `ping de verificación` y que NO fuerza reconexión si el robot responde.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(ble): reconexión automática, keep-alive y diagnóstico de conexión"
```

---

## Revisión final (orquestador + QA senior)

- Diff completo `98a2f77..HEAD` → QA senior (tier fuerte): contratos entre tareas
  (LogCat usado por Task 6, `ConnState.RECONNECTING` cubierto en UI), regresiones en
  `ensureMode`/`handshake`, que el keep-alive no pise modos activos, y triage de lo aparcado.
- Una sola ola de fix final.
- Al cerrar: actualizar `AGENTS.md` con lo aprendido (ACKs reales por feature, quirk del
  robot si surge) y crear `docs/PROGRESO.md` con resumen, rulings y costo del plan.
