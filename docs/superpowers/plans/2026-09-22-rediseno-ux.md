# Subproyecto B — Rediseño UX: Implementation Plan

> **For agentic workers:** La ejecución sigue el protocolo de la metodología Tita Media
> (briefs por tarea, dev → QA, fix loop, revisión final). Los checkboxes marcan pasos de
> cada tarea. El orquestador despacha un agente por tarea con el brief en
> `.metodologia/rediseno-ux/briefs/task-N.md`.

**Goal:** Rediseñar la UI completa (tema "Tech limpio", 5 destinos, wizard de conexión,
estados claros) e incluir el pulido funcional aparcado del subproyecto A, sin agregar features.

**Architecture:** Refactor de UI en archivos por pantalla bajo `ui/` (un archivo por destino),
tema en `ui/theme/`, componentes compartidos en `ui/components/`. El contrato
`RobotViewModel`/`UiState`/BLE queda intacto salvo las adiciones mínimas que cada tarea
declara (theme pref, snackbar, testLlmConnection) y el pulido de T9.

**Tech Stack:** Kotlin 2.1.20, Jetpack Compose, Material 3. Sin dependencias nuevas.
Verificación: adb + uiautomator + logcat (robot real), screencap para ambos form factors.

## Global Constraints

- Fuente de verdad visual: `DESIGN.md` (entregable de Task 1) + spec
  `docs/superpowers/specs/2026-09-22-rediseno-ux-design.md`.
- **Sin features nuevas** (rutinas, notificaciones, widget, consola raw, sniffer, Hermes, UI
  de juegos = backlog C). No tocar la máquina de reconexión más allá del pulido exacto de T9.
- No romper el protocolo BLE ni los builders: las tareas de UI no tocan `Protocol.kt` ni
  `BleClient.kt` (salvo T9 en los puntos exactos indicados).
- Gate de calidad de toda tarea:
  `cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug`
- Sin tests unitarios (decisión del spec). Verificación = build + QA de diff + pruebas en vivo.
- Copy de la UI en español, estilo coloquial breve ("Despertalo y volvé a intentar").
- Base del plan: commit `a05373d` (docs spec). Repo: `~/aibi/AibiPilot`, branch `main`.
- Form factors: tablet >= 840dp → NavigationRail de 5; teléfono → NavigationBar (bottom) de 5.

---

### Task 1: DESIGN.md (UX — tier fuerte)

**Files:**
- Create: `DESIGN.md` (raíz del repo)

**Interfaces:**
- Consumes: spec `docs/superpowers/specs/2026-09-22-rediseno-ux-design.md` (secciones 1-4).
- Produces: `DESIGN.md` con TODO lo que las tareas 2-8 consumen (secciones exactas abajo).

- [ ] **Step 1: Escribir el sistema visual** — sección "Sistema visual": tokens exactos de color
  para tema oscuro y claro (fondo, superficie, borde, acento, verde estado, error, warning,
  texto primario/secundario), escala tipográfica (qué `MaterialTheme.typography` para títulos,
  cuerpo, datos mono), specs de componentes: Card (radio 12dp, padding 16), botón primario
  (acento, radius 8), pill de estado (variantes: conectado/conectando/reconectando/dormido/otra
  app — color, texto, icono), snackbar, diálogo, chip de filtro, empty state.

- [ ] **Step 2: Escribir la navegación** — sección "Navegación": los 5 destinos (Inicio, Chat IA,
  Hablar, Jugar, Herramientas) con icono Material (elegí los `Icons` exactos disponibles en
  material-icons-extended ya usado en el proyecto) y orden; specs del NavigationRail (tablet) y
  NavigationBar (teléfono); comportamiento del wizard de conexión (3 pasos + reconexión rápida +
  estados sin permiso/BT apagado).

- [ ] **Step 3: Escribir pantalla por pantalla** — secciones "Inicio", "Chat IA", "Hablar",
  "Jugar", "Herramientas" (sub-pestañas Luces, Alarmas, Fotos, Log BLE) y "Estados":
  para cada pantalla: layout (qué cards/grillas/botones y en qué orden), qué datos de `UiState`
  muestra, qué acciones del `RobotViewModel` dispara, estados vacíos con su copy, y el copy
  exacto en español de títulos, botones y mensajes. Para Alarmas: el selector de tag 0..6 con
  7 etiquetas (buscá el significado real en `/tmp/aibi/jadx_out/sources/ai/living/aibi/ui/view/tools/alarm/AlarmListAdapter.java` — mapea tag→icono; si no hay evidencia clara, usá
  "Tipo 1".."Tipo 6" y anotalo como abierto).

- [ ] **Step 4: Escribir la sección "Feedback"** — snackbars transitorios (copy exacto: "Volumen
  ok", "Alarma agregada", "Foto guardada", errores de envío), diálogos graves (copy exacto de
  "otra app conectada" y "robot dormido", botones), y la pill del header con sus 5 variantes.

- [ ] **Step 5: Commit**

```bash
git add DESIGN.md
git commit -m "docs(design): DESIGN.md del rediseño UX (sistema visual, 5 destinos, pantallas)"
```

**Checkpoint del orquestador:** resumir DESIGN.md al usuario y esperar su OK antes de despachar
Task 2+. Si el usuario pide cambios, re-despachar esta tarea como fix.

---

### Task 2: Tema y componentes compartidos

**Files:**
- Create: `app/src/main/java/com/wil/aibipilot/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/wil/aibipilot/ui/components/Components.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (UiState + prefs de tema)
- Modify: `app/src/main/java/com/wil/aibipilot/MainActivity.kt`

**Interfaces:**
- Consumes: `DESIGN.md` §Sistema visual (tokens, tipografía, componentes).
- Produces (para tareas siguientes):
  - `AibiPilotTheme(themeMode: String, content: @Composable () -> Unit)` en `ui/theme/Theme.kt` —
    aplica el `MaterialTheme` según `"system" | "light" | "dark"`.
  - `UiState.themeMode: String = "system"` + `RobotViewModel.setThemeMode(mode: String)` +
    `loadThemeMode()` (persistencia en las mismas `prefs()` del VM, key `"theme_mode"`).
  - Componentes en `ui/components/Components.kt`:
    `StatusPill(conn: ConnState, reconnectAttempt: Int, connHint: String?)`,
    `AppCard(modifier, content)`, `SectionTitle(text)`, `EmptyState(icon, title, subtitle)`,
    `PrimaryButton(text, onClick, modifier, enabled)`.

- [ ] **Step 1: Crear `Theme.kt`** — dos `ColorScheme` (dark y light) con los tokens exactos del
  DESIGN.md, tipografía (sans por defecto; `bodySmall` mono NO se toca aquí: el mono se aplica
  puntual con `fontFamily` en cada pantalla), y `AibiPilotTheme`:

```kotlin
package com.wil.aibipilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentBlue = Color(0xFF3AA0FF)
val StatusGreen = Color(0xFF19E3B1)
val ErrorRed = Color(0xFFE34B3A)
val WarningYellow = Color(0xFFE3C419)
val TextSecondary = Color(0xFF8B9BB4)
val BgDark = Color(0xFF0E1621)
val SurfaceDark = Color(0xFF152435)
val BorderDark = Color(0xFF2A3D55)

private val dark = darkColorScheme(
    primary = AccentBlue, secondary = StatusGreen, error = ErrorRed,
    background = BgDark, surface = SurfaceDark, outline = BorderDark
)
private val light = lightColorScheme(
    primary = Color(0xFF1B6FC2), secondary = Color(0xFF0E8F6B), error = ErrorRed,
    background = Color(0xFFF4F7FB), surface = Color(0xFFFFFFFF)
)

@Composable
fun AibiPilotTheme(themeMode: String, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}
```

- [ ] **Step 2: Crear `Components.kt`** con los 5 componentes del bloque "Interfaces", usando los
  tokens del tema vía `MaterialTheme.colorScheme` y los specs del DESIGN.md (StatusPill: 5
  variantes — CONNECTED verde, CONNECTING warning, RECONNECTING azul "↻ RECONECTANDO (N/10)",
  DISCONNECTED+connHint="otra app" rojo, DISCONNECTED+connHint="dormido" rojo).

- [ ] **Step 3: Wiring en `RobotViewModel.kt` y `MainActivity.kt`** — `UiState.themeMode`,
  `setThemeMode` (update + persistir), y en `MainActivity.setContent`:

```kotlin
val ui by vm.ui.collectAsStateWithLifecycle()
AibiPilotTheme(themeMode = ui.themeMode) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AibiPilotApp(vm)
    }
}
```

- [ ] **Step 4: Build** — `cd ~/aibi/AibiPilot && JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/theme app/src/main/java/com/wil/aibipilot/ui/components app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/MainActivity.kt
git commit -m "feat(theme): sistema visual Tech limpio (oscuro/claro/sistema) + componentes base"
```

---

### Task 3: Navegación de 5 destinos + wizard de conexión + header pill

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/Screens.kt` (AibiPilotApp → scaffold nuevo)
- Create: `app/src/main/java/com/wil/aibipilot/ui/ConnectScreen.kt`
- Create: `app/src/main/java/com/wil/aibipilot/ui/HomeScreen.kt` (stub)
- Create: `app/src/main/java/com/wil/aibipilot/ui/ChatScreen.kt` (stub)
- Create: `app/src/main/java/com/wil/aibipilot/ui/TalkScreen.kt` (stub)
- Create: `app/src/main/java/com/wil/aibipilot/ui/GamesScreen.kt` (stub)
- Create: `app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt` (stub)

**Interfaces:**
- Consumes: Task 2 (`AibiPilotTheme` ya aplicado; `StatusPill`, `AppCard`, etc. disponibles);
  `DESIGN.md` §Navegación y §Estados.
- Produces:
  - `enum class Destination(label, icon): INICIO, CHAT, HABLAR, JUGAR, HERRAMIENTAS`
    (con `ImageVector` de material-icons; el icono exacto según DESIGN.md).
  - Composable públicos (los consumen Tasks 4-7):
    `HomeScreen(vm, ui)`, `ChatScreen(vm, ui)`, `TalkScreen(vm, ui)`,
    `GamesScreen(vm, ui)`, `ToolsScreen(vm, ui)` — acá quedan como stubs con
    `EmptyState("En construcción", ...)`.
  - `ConnectScreen(vm, ui, modifier)` — wizard completo.
  - `ConnectedHeader(vm, ui)` — con `StatusPill` en lugar del texto actual.

- [ ] **Step 1: Definir `Destination`** en `Screens.kt` (top-level, público):

```kotlin
enum class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    INICIO("Inicio", androidx.compose.material.icons.Icons.Default.Home),
    CHAT("Chat IA", androidx.compose.material.icons.Icons.Default.AutoAwesome),
    HABLAR("Hablar", androidx.compose.material.icons.Icons.Default.RecordVoiceOver),
    JUGAR("Jugar", androidx.compose.material.icons.Icons.Default.SportsEsports),
    HERRAMIENTAS("Herramientas", androidx.compose.material.icons.Icons.Default.Build),
}
```

- [ ] **Step 2: Reescribir `AibiPilotApp`** — `ui.conn` en DISCONNECTED/SCANNING → `ConnectScreen`;
  si no → scaffold con rail (>= 840dp) o bottom bar (agregá los imports que falten:
  `NavigationBar`, `NavigationBarItem`):

```kotlin
@Composable
fun AibiPilotApp(vm: RobotViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); vm.tryAutoReconnect() }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840
    var dest by rememberSaveable { mutableStateOf(Destination.INICIO) }
    when {
        ui.conn == ConnState.CONNECTED || ui.conn == ConnState.CONNECTING || ui.conn == ConnState.RECONNECTING -> {
            val screen: @Composable (Modifier) -> Unit = { m -> DestinationContent(dest, vm, ui, m) }
            if (isTablet) {
                Row(Modifier.fillMaxSize().padding(16.dp)) {
                    NavigationRail {
                        Spacer(Modifier.height(8.dp))
                        Text("AIBI\nPilot", style = MaterialTheme.typography.titleSmall)
                        Destination.entries.forEach { d ->
                            NavigationRailItem(selected = dest == d, onClick = { dest = d },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) })
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.fillMaxSize()) { ConnectedHeader(vm, ui); Spacer(Modifier.height(12.dp)); screen(Modifier) }
                }
            } else {
                Scaffold(bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { d ->
                            NavigationBarItem(selected = dest == d, onClick = { dest = d },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) })
                        }
                    }
                }) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                        ConnectedHeader(vm, ui); Spacer(Modifier.height(8.dp)); screen(Modifier)
                    }
                }
            }
        }
        else -> ConnectScreen(vm, ui, Modifier)
    }
}

@Composable
private fun DestinationContent(dest: Destination, vm: RobotViewModel, ui: UiState, m: Modifier) {
    when (dest) {
        Destination.INICIO -> HomeScreen(vm, ui)
        Destination.CHAT -> ChatScreen(vm, ui)
        Destination.HABLAR -> TalkScreen(vm, ui)
        Destination.JUGAR -> GamesScreen(vm, ui)
        Destination.HERRAMIENTAS -> ToolsScreen(vm, ui)
    }
}
```

- [ ] **Step 3: `ConnectedHeader` con pill** — reemplazar el texto de estado por
  `StatusPill(ui.conn, ui.reconnectAttempt, ui.connHint)`; botón "Desconectar" igual.

- [ ] **Step 4: Crear `ConnectScreen.kt`** — el wizard según DESIGN.md §Navegación:
  paso 1 permisos (card con botón otorgar, como hoy), paso 2 "despertá tu robot" (si no hay
  permisos pendientes), escanear (botón + lista con badge "Robot" para nombres con "AIBI",
  MAC/RSSI en mono), tarjeta "Reconectar a <nombre>" si `vm.savedDeviceName() != null`, y
  `ui.connHint` como card de aviso. Migrar la lógica de permisos y scan de la `ScanScreen`
  actual (eliminarla después de migrar).

- [ ] **Step 5: Crear los 5 stubs** — un `@Composable` público por archivo nuevo con
  `EmptyState` y título del destino. Eliminar de `Screens.kt` las funciones viejas que ya no
  se referencian (tabs viejos, `TabChip`, `ConnectedScreen`, `ScanScreen`), dejando solo
  `AibiPilotApp`, `DestinationContent`, `ConnectedHeader` y `Destination`.
  (Las pantallas viejas StatusTab/ChatTab/... quedan en el archivo aunque sin uso; cada
  tarea siguiente las elimina al reemplazarlas.)

- [ ] **Step 6: Build** — gate: BUILD SUCCESSFUL. Atención: borrar solo lo no referenciado.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/
git commit -m "feat(nav): 5 destinos (rail/bottom bar) + wizard de conexión + pill de estado"
```

---

### Task 4: Inicio (dashboard)

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/HomeScreen.kt` (reemplazar stub)

**Interfaces:**
- Consumes: `UiState.info` (RobotInfo), `ui.conn`. Los accesos rápidos EJECUTAN acciones
  directas del VM (no navegan entre destinos): `setVolume`, `lightOn/lightOff`, `speak`,
  `playAnimation`, `playScene`. Ver DESIGN.md §Inicio.
- Produces: `HomeScreen(vm: RobotViewModel, ui: UiState)` implementado.

- [ ] **Step 1: Implementar HomeScreen** — según DESIGN.md §Inicio: card robot (batería con
  label, pasos, monedas, comida, versión, MTU — datos en mono), grid de accesos rápidos
  (volumen mute/low/high, luz on/off, TTS corto, animación baile), cards de escenas
  (fiesta/despertar/relax/noche → `vm.playScene`), banner "no conectado" → botón desconectar/
  reconectar si `conn != CONNECTED`.

- [ ] **Step 2: Eliminar `StatusTab` vieja de `Screens.kt`** si ya no se referencia.

- [ ] **Step 3: Build + verificación en vivo** — gate build; después el orquestador verifica
  con adb (ver nota al final del plan).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/HomeScreen.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(home): dashboard de inicio con estado, accesos rápidos y escenas"
```

---

### Task 5: Chat IA

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ChatScreen.kt` (reemplazar stub)
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt` (solo agregar testLlmConnection)

**Interfaces:**
- Consumes: `UiState.chat: List<ChatMsg>`, `chatThinking`, `sendChatMessage`, `loadLlmConfig`,
  `saveLlmConfig` (ya existen, sin cambiar firmas).
- Produces: `RobotViewModel.testLlmConnection(cfg: LlmConfig, onResult: (Boolean, String) -> Unit)`
  — POST mínimo a `cfg.baseUrl` con model `cfg.model` y un mensaje "ping", timeout 15s; reporta
  ok/error legible. Usa `llmClient` existente. `ChatScreen(vm, ui)` implementado.

- [ ] **Step 1: `testLlmConnection` en el VM** — implementación con `viewModelScope.launch(IO)`,
  `OkHttpClient` con timeout 15s, parseo igual que `askLlm` pero sin historia (un solo mensaje
  "ping"); `onResult(true, "Conexión OK")` o `onResult(false, "HTTP <code>: <mensaje>")` /
  excepción.

- [ ] **Step 2: Implementar ChatScreen** — según DESIGN.md §Chat IA: lista de burbujas
  (usuario a la derecha con acento, assistant a la izquierda con superficie), indicador
  "pensando…", input multilínea + enviar, botón de ajustes → diálogo de configuración:
  campos URL base / API key / modelo, botón **Probar** (llama `testLlmConnection` y muestra
  resultado en el diálogo), hint textual de Ollama local (`http://<ip>:11434/v1/chat/completions`).

- [ ] **Step 3: Eliminar `ChatTab` vieja de `Screens.kt`.**

- [ ] **Step 4: Build** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/ChatScreen.kt app/src/main/java/com/wil/aibipilot/RobotViewModel.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(chat): chat IA reestilizado + diálogo de config con botón Probar"
```

---

### Task 6: Hablar + Jugar

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/TalkScreen.kt` (reemplazar stub)
- Modify: `app/src/main/java/com/wil/aibipilot/ui/GamesScreen.kt` (reemplazar stub)

**Interfaces:**
- Consumes: `vm.speak`, `vm.playAnimation`, `Animations.all` (catálogo con `group`), `vm.gameEnter/
  gameExit/gameStart/gamePlay`. Sin cambios de firma.
- Produces: `TalkScreen(vm, ui)`, `GamesScreen(vm, ui)` implementados.

- [ ] **Step 1: TalkScreen** — según DESIGN.md §Hablar: campo TTS + botón voz (SpeechRecognizer,
  el botón 🎤 que ya existe en TalkTab: migrar esa lógica) + enviar; debajo, animaciones en
  grid agrupado por categoría (`Animations.all.groupBy { it.group }` con headers), tarjeta =
  label + botón play.

- [ ] **Step 2: GamesScreen** — según DESIGN.md §Jugar: 4 cards (Ajedrez/Serpientes/Pirate
  Wars/Zero) con descripción corta; al tocar una → vista del juego con Entrar / Empezar /
  Jugar / Salir (`gameEnter`/`gameStart`/`gamePlay`/`gameExit`) y botón volver.

- [ ] **Step 3: Eliminar `TalkTab`, `AnimationsTab` y `GamesTab` viejas de `Screens.kt`.**

- [ ] **Step 4: Build** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/TalkScreen.kt app/src/main/java/com/wil/aibipilot/ui/GamesScreen.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(talk): TTS + animaciones agrupadas · feat(games): cards con controles in/start/play"
```

---

### Task 7: Herramientas (Luces · Alarmas · Fotos · Log BLE)

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt` (reemplazar stub)

**Interfaces:**
- Consumes: `ui.lights`, `lightRefresh/lightOn/lightOff/lightSet`, `ui.alarms`,
  `alarmRefresh/alarmAdd/alarmDel`, `ui.photos`, `ui.photoServerRunning`,
  `startPhotoSync/stopPhotoSync/photoShow/photoClear`, `ui.log`, `clearLog`.
- Produces: `ToolsScreen(vm, ui)` con 4 sub-pestañas internas (chips).

- [ ] **Step 1: Sub-pestaña Luces** — según DESIGN.md §Herramientas/Luces: sliders RGB +
  brillo, selector de modo (default/breath/color/flow), on/off, listado si `ui.lights` no vacío.

- [ ] **Step 2: Sub-pestaña Alarmas** — hora (`OutlinedTextField` "HH:mm"), **selector de tag
  0..6** (7 chips con las etiquetas del DESIGN.md; el valor va a `alarmAdd(tag, time)`),
  botón agregar, lista con eliminar (`alarmDel(index)`), botón listar.

- [ ] **Step 3: Sub-pestaña Fotos** — iniciar/detener sync (estado `photoServerRunning`),
  botones modo foto (in/show/out vía `photoEnter/photoShow/photoExit`), galería de
  `ui.photos` (nombre de archivo; si se puede, botón "Abrir" con intent `ACTION_VIEW` —
  solo si hay `FileProvider` configurado; si no, devolvé NEEDS_CONTEXT y omití el botón).

- [ ] **Step 4: Sub-pestaña Log BLE** — migrar `LogTab` actual (chips de filtro y colores por
  `LogCat`) con el estilo mono del nuevo tema.

- [ ] **Step 5: Eliminar `LightsTab`, `AlarmsTab`, `PhotosTab` y `LogTab` viejas de `Screens.kt`;**
  verificar que `Screens.kt` queda solo con scaffold/nav/header. Mover a `ToolsScreen.kt` todo
  lo reutilizado.

- [ ] **Step 6: Build** — BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ui/ToolsScreen.kt app/src/main/java/com/wil/aibipilot/ui/Screens.kt
git commit -m "feat(tools): luces, alarmas con selector de tag, fotos y log BLE"
```

---

### Task 8: Feedback cross-cutting (snackbar, diálogos graves, estados vacíos)

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/ui/Screens.kt` (host del snackbar en AibiPilotApp)
- Modify: pantallas según corresponda (ConnectScreen, HomeScreen, ToolsScreen…)

**Interfaces:**
- Consumes: Task 2 componentes; `DESIGN.md` §Feedback (copy exacto).
- Produces:
  - `UiState.snackbar: String? = null` + `RobotViewModel.showSnackbar(msg: String)` (se
    auto-limpia a los 3s) + `dismissSnackbar()`.
  - `SnackbarHost` en `AibiPilotApp` (dentro del `Scaffold` del wizard también si lo requiere —
    un solo host por rama de UI).
  - Diálogos graves en `ConnectScreen`/`ConnectedHeader` según DESIGN.md: al tocar la pill roja
    (o al llegar `connHint` con la app desconectada) → `AlertDialog` con copy del DESIGN.md y
    acciones (Reintentar → `tryAutoReconnect`; Descartar).
  - Estados vacíos con `EmptyState` en: lista de dispositivos, alarmas, fotos, chat (mensaje
    inicial "Hablá con AIBI"), log vacío.

- [ ] **Step 1: Snackbar en VM** — `UiState.snackbar`, `showSnackbar` (set + `viewModelScope.launch
  { delay(3000); si sigue siendo el mismo mensaje → null }`), `dismissSnackbar`.

- [ ] **Step 2: Disparar snackbars transitorios** — en el VM donde ya hay confirmaciones/errores:
  `setVolume` (tras enviar), `alarmAdd`/`alarmDel`, `send()` catch (`Error enviando…`), y
  `onPhoto` del PhotoTcpServer ("Foto guardada: <nombre>"). Copy del DESIGN.md.

- [ ] **Step 3: Host del snackbar + diálogos graves** — `SnackbarHost`/`SnackbarHostState` en el
  scaffold de `AibiPilotApp` (y en ConnectScreen); diálogo de error grave en ConnectScreen
  (pill roja tocable o automático con `connHint`); acciones según DESIGN.md.

- [ ] **Step 4: Estados vacíos** — aplicar `EmptyState` en las 5 pantallas donde aplique.

- [ ] **Step 5: Build** — BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/
git commit -m "feat(feedback): snackbars transitorios, diálogos graves y estados vacíos"
```

---

### Task 9: Pulido funcional aparcado de A

**Files:**
- Modify: `app/src/main/java/com/wil/aibipilot/ble/BleClient.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/ble/PhotoTcpServer.kt`
- Modify: `app/src/main/java/com/wil/aibipilot/RobotViewModel.kt`

**Interfaces:**
- Consumes: hallazgos del QA senior (revisión final de A) — correcciones exactas abajo.
- Produces: sin cambios de firma pública.

- [ ] **Step 1: BleClient — fallo síncrono de `connectGatt` fuera del job** — en `connect()`,
  los dos caminos de fallo síncrono (`g == null` tras el try/catch y la excepción dentro del
  try) hoy llaman `onState(false)` en el hilo llamante (dentro del job de reconexión del VM, lo
  que hace que la guarda de idempotencia descarte el `scheduleReconnect`). Cambiar ambos por:

```kotlin
android.os.Handler(android.os.Looper.getMainLooper()).post { onState(false) }
```

  (el `onState(false)` llega después de que el job termine → la guarda no lo bloquea).

- [ ] **Step 2: RobotViewModel — diagnóstico único** — agregar `private var diagnosePending =
  false`; en `connectDevice` (solo rama `!isReconnect`): `diagnosePending = true`; al inicio de
  `diagnoseConnectFailure`: `if (!diagnosePending) return` y `diagnosePending = false` antes del
  scan. Esto elimina el doble scan de 5s cuando `onState(false)` y el timeout compiten.

- [ ] **Step 3: RobotViewModel — `reconnectInFlight` en el catch** — en el `catch` del job de
  `scheduleReconnect` (junto al reset de `conn`/`reconnectAttempt`) agregar:
  `reconnectInFlight = false`.

- [ ] **Step 4: RobotViewModel — modeout filtrado por modo actual** — en `parseResponse`,
  reemplazar el bloque del `aibi_event` `modeout`:

```kotlin
if (eventName == "modeout") {
    val evCurrent = root["data"]?.jsonObject?.get("current")?.jsonPrimitive?.contentOrNull
    if (evCurrent == null || evCurrent == currentMode) {
        currentMode = null
        log(LogCat.EVT, "El robot salió del modo de función")
    } else {
        log(LogCat.EVT, "modeout de un modo anterior ($evCurrent), ignorado")
    }
}
```

  (con `currentMode` actualmente `String?` var privada — si el evento trae `current` distinto
  del nuestro, es el modeout del modo anterior y no se limpia).

- [ ] **Step 5: PhotoTcpServer — soTimeout 30s** — `SOCKET_TIMEOUT_MS = 10_000` → `30_000`.

- [ ] **Step 6: Build** — BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wil/aibipilot/ble/ app/src/main/java/com/wil/aibipilot/RobotViewModel.kt
git commit -m "fix(ble): pulido aparcado de A (fallo síncrono, diagnóstico único, modeout filtrado, soTimeout 30s)"
```

---

## Verificación en vivo final (orquestador, tras Task 9)

1. Build + `adb install -r` + lanzar. Con el robot despierto:
2. Wizard: desconectar → verificar pasos (permisos otorgados, escanear, badge Robot).
3. Los 5 destinos navegables (uiautomator dump en tablet y — con `adb shell wm size`/ventana
   angosta si es posible — teléfono).
4. Una acción por función con ACK en logcat: TTS (speak), luz (set), alarma (add con tag nuevo),
   juego (chess in/start), volumen, chat IA (si hay key configurada; si no, solo UI).
5. Estados: pill verde conectado; desconexión (robot apagado) → pill/diálogo correctos.
6. Tema: toggle oscuro/claro/sistema visible (screencap ×2).
7. Revisión final QA senior del branch completo + una ola de fix + actualización de AGENTS.md,
   docs/PROGRESO.md y MEMORIA-AIBI-PILOT.md, y cierre según elección del usuario.
