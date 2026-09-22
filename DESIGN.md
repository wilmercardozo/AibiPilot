# DESIGN.md — AibiPilot: diseño del rediseño UX ("Tech limpio")

> Fuente de verdad visual para las Tasks 2-8 del plan `docs/superpowers/plans/2026-09-22-rediseno-ux.md`.
> Documento para implementadores: valores exactos, sin "TBD". Copy en español, coloquial y breve.
> Contrato: `RobotViewModel` / `UiState` / capa BLE quedan intactos salvo las adiciones mínimas que cada
> tarea declara (`themeMode`, `snackbar`, `testLlmConnection`).

Índice: [1. Sistema visual](#1-sistema-visual) · [2. Navegación](#2-navegación) ·
[3. Pantalla por pantalla](#3-pantalla-por-pantalla) · [4. Estados y feedback](#4-estados-y-feedback)

---

## 1. Sistema visual

### 1.1 Tokens de color

Los implementadores crean dos `ColorScheme` + constantes extra. Roles M3 + extensiones de la app.

| Token | Rol M3 | Oscuro | Claro |
|---|---|---|---|
| `BackgroundDark` / `BackgroundLight` | `background` | `#0E1621` | `#F4F7FB` |
| `SurfaceDark` / `SurfaceLight` | `surface` | `#152435` | `#FFFFFF` |
| `SurfaceVariantDark` / `SurfaceVariantLight` | `surfaceVariant` | `#1B2E42` | `#E8EEF5` |
| `BorderDark` / `BorderLight` | `outline` | `#2A3D55` | `#D3DEE9` |
| `AccentBlue` / `AccentBlueLight` | `primary` | `#3AA0FF` | `#1B6FC2` |
| `OnPrimaryDark` / `OnPrimaryLight` | `onPrimary` | `#08111C` | `#FFFFFF` |
| `StatusGreen` / `StatusGreenLight` | `secondary` | `#19E3B1` | `#0E8F6B` |
| `OnSecondaryDark` / `OnSecondaryLight` | `onSecondary` | `#06281E` | `#FFFFFF` |
| `ErrorRed` (igual en ambos) | `error` | `#E34B3A` | `#E34B3A` |
| `OnError` (igual en ambos) | `onError` | `#FFFFFF` | `#FFFFFF` |
| `WarningYellow` / `WarningDark` | — (val `Warning`) | `#E3C419` | `#8F6D00` |
| `TextPrimaryDark` / `TextPrimaryLight` | — (val `TextPrimary`) | `#F2F6FA` | `#16202C` |
| `TextSecondaryDark` / `TextSecondaryLight` | — (val `TextSecondary`) | `#8B9BB4` | `#5A6B82` |

Reglas:
- `Warning`, `TextPrimary`, `TextSecondary` son `Color` públicos en `ui/theme/Theme.kt` (como los
  `AccentBlue` etc. del snippet del plan), usados puntualmente con `MaterialTheme` no los expone.
- En tema claro, el texto primario dentro de la app NO se toma de `onSurface` del scheme sino de
  `TextPrimary` (el scheme claro usa `onBackground/onSurface` = `#16202C` para mantenerlo simple).
- Tema por defecto: `themeMode = "system"` (sigue el sistema); opciones "system" | "light" | "dark".
  El oscuro es el look diseñado y el que valida el QA visual.
- Los tintes de estado (pill, chips, badges) se calculan SIEMPRE con alpha: color base + `0.20f` alpha
  para el fondo del contenedor y color pleno para texto/icono. Ej.: fondo pill conectado =
  `Color(0x3319E3B1)` (20% de `#19E3B1`).

### 1.2 Tipografía

Sans por defecto (Roboto). Uso semántico sobre `MaterialTheme.typography`:

| Uso | Style |
|---|---|
| Título del wizard ("AIBI Pilot") | `headlineLarge` (32sp) |
| Header global y títulos de pantalla/destino | `titleLarge` (22sp) |
| Títulos de card | `titleMedium` (16sp) |
| Cuerpo | `bodyMedium` (14sp) |
| Secundario / captions / hint | `bodySmall` (12sp) |
| Botones, chips, pill | `labelMedium` (12sp, medium weight) |

**Mono** (`FontFamily.Monospace`) SOLO en datos técnicos: MAC, RSSI, MTU, versión, valores numéricos
de stats (pasos, monedas, comida), líneas del Log BLE, hex, y URL base en el diálogo del chat.
Se aplica puntual con `fontFamily = FontFamily.Monospace` (estilo `bodySmall`), no global.

### 1.3 Componentes

Todos viven en `ui/components/Components.kt` (Task 2) salvo que se indique lo contrario.

**`AppCard(modifier, content)`** — Card elevada:
- container `surface`, radio **12dp**, padding interno **16dp**, borde 1dp `outline`.
- Elevación 0 (el borde define la superficie; no sombras).

**`PrimaryButton(text, onClick, modifier, enabled)`** — botón primario:
- container `primary`, radio **8dp**, alto 44dp, padding horizontal 16dp, texto `labelMedium` `onPrimary`.
- Disabled: container `surfaceVariant`, contenido `TextSecondary`.
- Solo UN primary button por pantalla visible a la vez.

**Botón secundario** (OutlinedButton directo, no componente propio):
- borde 1dp `outline`, radio 8dp, alto 40dp, contenido `primary`.

**`StatusPill(conn, reconnectAttempt, connHint)`** — pill de estado (spec exacto en §4.1):
- Radio 999 (pill), alto 28dp, padding horizontal 12dp, texto `labelMedium`.
- Fondo = color de la variante a 20% alpha; glyph + texto en color pleno, sin espacio extra entre
  glyph y texto (se separan con un espacio normal).

**`SectionTitle(text)`** — `titleMedium` `TextPrimary`, padding vertical 8dp.

**`EmptyState(icon, title, subtitle)`** — Column centrada, padding vertical 48dp, ancho máx 320dp:
- Icono 48dp `TextSecondary`, título `titleMedium` `TextPrimary`, subtítulo `bodyMedium`
  `TextSecondary` centrado.

**Snackbar** (host en Task 8): container `surface`, borde 1dp `outline`, radio 8dp, texto `bodyMedium`
`TextPrimary`, margen 16dp del borde, duración = la del VM (3s, auto-limpia).

**`AlertDialog` (M3)**: container `surface`, radio 16dp; título `titleMedium`; cuerpo `bodyMedium`
`TextSecondary`; botón confirmar = `PrimaryButton` (texto `labelMedium`); botón secundario =
`TextButton` con contenido `TextSecondary` (o `error` si es destructivo).

**`FilterChip`** (chips de filtro/sub-pestaña): radio 8dp.
- Seleccionado: container `primary` 20% alpha, label + icono `primary`.
- No seleccionado: container `surfaceVariant`, label + icono `TextSecondary`.

---

## 2. Navegación

### 2.1 Destinos (orden fijo)

```kotlin
enum class Destination(val label: String, val icon: ImageVector) {
    INICIO("Inicio", Icons.Default.Home),
    CHAT("Chat IA", Icons.Default.AutoAwesome),
    HABLAR("Hablar", Icons.Default.RecordVoiceOver),
    JUGAR("Jugar", Icons.Default.SportsEsports),
    HERRAMIENTAS("Herramientas", Icons.Default.Build),
}
```

Nota de implementación: `Icons.Default` es alias de `Icons.Filled`; los imports existentes usan
`androidx.compose.material.icons.filled.*`. Los 5 iconos verificados contra `material-icons-core` y
`material-icons-extended` 1.7.8 (ambos ya dependencia del proyecto). Import exacto por icono:
`androidx.compose.material.icons.filled.{Home, AutoAwesome, RecordVoiceOver, SportsEsports, Build}`.

Destino inicial: `INICIO`, guardado con `rememberSaveable`. Los 5 destinos SOLO son visibles con
`ui.conn` en `CONNECTED | CONNECTING | RECONNECTING`; con `DISCONNECTED | SCANNING` se muestra
`ConnectScreen` (wizard).

### 2.2 Contenedores por form factor

**Tablet (`screenWidthDp >= 840`) — NavigationRail:**
- Rail de 5 items + logo arriba: `Text("AIBI\nPilot", style = titleSmall)` con padding vertical 8dp.
- Cada `NavigationRailItem`: icono 24dp + label `labelMedium`.
- Seleccionado: indicator pill `primary` 20%, icono y label `primary`.
- No seleccionado: icono y label `TextSecondary`.
- Layout: `Row` con padding 16dp, `Spacer` 12dp entre rail y contenido; contenido =
  `Column { ConnectedHeader; Spacer 12dp; screen }`.

**Teléfono (`< 840dp`) — NavigationBar (bottom):**
- 5 `NavigationBarItem` (icono 24dp + label `labelMedium`), alto default 80dp.
- Seleccionado: icono/label `primary`; no seleccionado: `TextSecondary`.
- Layout: `Scaffold(bottomBar = ...)` con padding del scaffold + 16dp alrededor del contenido;
  `Column { ConnectedHeader; Spacer 8dp; screen }`.

### 2.3 Header global (`ConnectedHeader`)

`Row` de alto implícito:
1. `Text("AIBI Pilot", titleLarge)` (weight 1f).
2. `StatusPill(ui.conn, ui.reconnectAttempt, ui.connHint)` (§4.1).
3. `TextButton("Desconectar")` contenido `TextSecondary` → `vm.disconnect()`.

### 2.4 Apariencia (toggle de tema)

`ConnectedHeader` agrega un `IconButton` con `Icons.Default.Settings`
(`androidx.compose.material.icons.filled.Settings`), contentDescription "Apariencia". Abre un
`AlertDialog`:
- Título: **"Apariencia"**
- Tres opciones como `FilterChip` (selección única, la activa = `ui.themeMode`):
  **"Sistema"** → `vm.setThemeMode("system")` · **"Claro"** → `"light"` · **"Oscuro"** → `"dark"`.
- Botón `TextButton` **"Cerrar"**.

### 2.5 Wizard de conexión (`ConnectScreen`, con `conn` en DISCONNECTED/SCANNING)

Column con padding 24dp, en este orden (cada paso es un `AppCard`; los pasos 1 y 2 solo aparecen
cuando corresponde):

1. **Encabezado**: `Text("AIBI Pilot", headlineLarge)` + `Text("Control alternativo para el robot mascota AIBI Pocket.", bodyMedium)`.
2. **Paso 1 — Permisos** (solo si faltan permisos; los mismos que hoy: `BLUETOOTH_SCAN`,
   `BLUETOOTH_CONNECT` en S+, `ACCESS_FINE_LOCATION` siempre):
   - Título: **"Permisos necesarios"**
   - Cuerpo: **"Para escanear y conectarse por Bluetooth es necesario otorgar permisos."**
   - `PrimaryButton` **"Otorgar permisos"** → launcher `RequestMultiplePermissions` de
     `vm.ble.requiredPermissions()` (migrar lógica actual de `ScanScreen`).
   - Si faltan permisos NO se muestra el resto del wizard.
3. **Paso 2 — Despertá tu robot** (si los permisos están OK):
   - Título: **"Despertá tu robot"**
   - Cuerpo: **"Tocá la cabeza del robot para despertarlo y dejalo cerca del dispositivo."**
   - Icono `SmartToy` (`androidx.compose.material.icons.filled.SmartToy`).
4. **Bluetooth apagado** (si `!vm.ble.isBluetoothEnabled()`):
   - Título: **"Bluetooth apagado"**
   - Cuerpo: **"Encendé el Bluetooth del teléfono y volvé a intentar."**
   - No se muestra el resto del wizard.
5. **Aviso de diagnóstico** (si `ui.connHint != null`): card con `StatusPill` en su variante roja
   (§4.1) + el texto de `connHint` en `bodySmall`. La card es tocable → abre el diálogo grave (§4.3).
6. **Reconexión rápida** (si `vm.savedDeviceName() != null`): card con `OutlinedButton`
   **"Reconectar a <nombre guardado>"** (icono `Bluetooth` — ya en uso en el proyecto) →
   `vm.tryAutoReconnect()`.
7. **Escanear**: `PrimaryButton` **"Buscar robots"** (icono `Search`; deshabilitado y con texto
   **"Escaneando…"** mientras `conn == SCANNING`) → `vm.startScan()`.
8. **Lista de resultados** (`ui.devices`): cada fila = `AppCard` tocable → `vm.connect(dev)`:
   - Icono `Bluetooth` + nombre (`titleMedium`; en `primary` si el nombre contiene "AIBI",
     ignoreCase) + línea mono `bodySmall`: `"<MAC>  •  <RSSI> dBm"`.
   - Badge pill a la derecha: **"Robot"** (fondo `primary` 20%, texto `primary`) si el nombre
     contiene "AIBI", si no `TextButton` **"Conectar"**.
   - Orden ya lo da el VM (robots AIBI primero, luego RSSI descendente).
9. **Estados de la lista**:
   - Escaneando: `Row` spinner 20dp + `Text("Buscando dispositivos «AIBI»…", bodyMedium)`.
   - Sin resultados (no escaneando): `EmptyState(Icons.Default.Bluetooth, "Sin dispositivos",
     "No se ven dispositivos todavía. ¿El robot está encendido y cerca?")`.

---

## 3. Pantalla por pantalla

Convención: cada pantalla es un `@Composable` público `XScreen(vm: RobotViewModel, ui: UiState)` en
su archivo bajo `ui/`. Todo el contenido en `LazyColumn`/`Column` scrollable con padding 16dp.
`UiState` se recibe como parámetro (ya colectado en `AibiPilotApp`).

### 3.1 Inicio (`HomeScreen`)

**Layout (orden):**
1. **Card robot** (`AppCard`): header `Row` = título (`vm.savedDeviceName() ?: ui.info.deviceName`
   si no vacío, si no **"AIBI"**, `titleMedium`) + `StatusPill` + `IconButton` `Refresh`
   (contentDescription "Actualizar") → `vm.refreshStatus()`.
   Grid de stats de 2 columnas, cada item = icono 18dp `TextSecondary` + label `bodySmall`
   `TextSecondary` + valor `bodyMedium` `TextPrimary` (valores numéricos y técnicos en mono):

   | Stat | Icono (filled) | Valor |
   |---|---|---|
   | Batería | `BatteryFull` | label del nivel: 1 "Baja" · 2 "Media" · 3 "Alta" · 4 "Llena" · null "—" |
   | Pasos | `DirectionsWalk` | `ui.info.steps` (mono) |
   | Monedas | `MonetizationOn` | `ui.info.gold` (mono) |
   | Comida | `Restaurant` | `ui.info.food` (mono) |
   | Versión | `Memory` | `ui.info.versionNumber` (mono) |
   | MTU | `QueryStats` | `ui.info.mtu` (mono) |

   (imports: `androidx.compose.material.icons.filled.{BatteryFull, DirectionsWalk, MonetizationOn,
   Restaurant, Memory, QueryStats}` — todos verificados en material-icons-extended 1.7.8.)
2. **Accesos rápidos** — `SectionTitle("Accesos rápidos")` + grid 2 columnas. Son `AppCard`s que
   EJECUTAN acciones directas del VM (no navegan):

   | Card | Icono (filled) | Acción directa |
   |---|---|---|
   | **"Volumen"** (subtítulo = nivel actual: "Silencio" / "Bajo" / "Alto") | `VolumeUp` | ciclo `mute → low → high → mute` según `ui.volume` → `vm.setVolume(siguiente)` |
   | **"Luz"** (subtítulo "Prender" / "Apagar", estado local `rememberSaveable`) | `LightMode` | alterna: `vm.lightOn(0)` / `vm.lightOff(0)` |
   | **"TTS"** (subtítulo "Frase corta") | `RecordVoiceOver` | `vm.speak("¡Hola! Soy AIBI")` |
   | **"Baile"** (subtítulo "Neon Pulse") | `TheaterComedy` | `vm.playAnimation("dance_ai1")` |

3. **Escenas** — `SectionTitle("Escenas")` + grid 2 columnas (teléfono) / 4 columnas (tablet) de
   `AppCard`s (icono + título + subtítulo) → `vm.playScene(<id>)`:

   | Card | Icono (filled) | Subtítulo | `playScene` |
   |---|---|---|---|
   | **"Fiesta"** | `Celebration` | "Luces de colores y baile" | `"fiesta"` |
   | **"Despertar"** | `WbTwilight` | "Luz y buenos días" | `"despertar"` |
   | **"Relax"** | `SelfImprovement` | "Respiración guiada" | `"relax"` |
   | **"Noche"** | `Bedtime` | "A dormir, con la luz apagada" | `"noche"` |

4. **Banner no conectado** (solo si `ui.conn != CONNECTED`): card con fondo `Warning` 20%, icono
   `Warning` (filled), texto **"El robot no está conectado."** + si `RECONNECTING`
   **"Reintentando… (N/10)"** con `ui.reconnectAttempt`. Botones: `OutlinedButton` **"Reintentar"** →
   `vm.tryAutoReconnect()` y `TextButton` **"Desconectar"** → `vm.disconnect()` (vuelve al wizard).

**Empty states:** no aplica (los stats muestran "—" cuando no hay datos).

### 3.2 Chat IA (`ChatScreen`)

**Layout (orden):**
1. Header de pantalla: `Row` = `Text("Chat IA", titleLarge)` (weight 1f) + `IconButton` `Settings`
   (contentDescription "Ajustes") → abre el diálogo de configuración.
2. `LazyColumn` (weight 1f) de burbujas (`ui.chat`):
   - `role == "user"`: alineada a la derecha, container `primary`, texto `onPrimary`.
   - `role == "assistant"`: alineada a la izquierda, container `surfaceVariant`, texto `TextPrimary`.
   - Radio 12dp (esquina inferior del lado emisor 4dp), padding 12dp, ancho máx 85%.
   - Auto-scroll al último mensaje cuando crece `ui.chat`.
3. Indicador **"pensando…"** si `ui.chatThinking`: burbuja assistant con texto `TextSecondary`
   (bodyMedium, cursiva no — texto plano).
4. Fila de input (fija abajo): `OutlinedTextField` multilínea (maxLines 4), placeholder
   **"Escribí un mensaje…"**, trailing `IconButton` `Send` (deshabilitado si el texto está en blanco
   o `chatThinking`) → `vm.sendChatMessage(texto)`. El envío limpia el campo.

**Diálogo "Configuración del chat"** (al abrir → `vm.loadLlmConfig()` precarga los campos):
- Título: **"Configuración del chat"**
- Campo **"URL base"** (mono, `bodySmall`), campo **"API key"** (visual transformation de password,
  icono `Key` — filled), campo **"Modelo"**.
- Hint `bodySmall` `TextSecondary`: **"Ollama local: http://<IP>:11434/v1/chat/completions"**
- `OutlinedButton` **"Probar"** → `vm.testLlmConnection(cfg) { ok, msg -> … }`; resultado inline en
  el diálogo: **"Conexión OK"** en `StatusGreen` (verde) o el error en `ErrorRed`, `bodySmall`.
- Botones: `TextButton` **"Cancelar"** · `PrimaryButton` **"Guardar"** → `vm.saveLlmConfig(cfg)`.

**Empty state** (si `ui.chat` vacío y no pensando):
`EmptyState(Icons.Default.AutoAwesome, "Hablá con AIBI", "Escribile un mensaje y el robot lo dice en voz alta.")`

**Datos de `UiState`:** `chat`, `chatThinking`. **Acciones:** `sendChatMessage`, `loadLlmConfig`,
`saveLlmConfig`, `testLlmConnection` (Task 5).

### 3.3 Hablar (`TalkScreen`)

**Layout (orden):**
1. `Text("Hablar", titleLarge)`.
2. Card TTS (`AppCard`): `OutlinedTextField` (placeholder **"Escribí algo para que AIBI lo diga…"**)
   con trailing `IconButton` `Mic` (contentDescription "Hablar por voz") — migrar la lógica de
   `SpeechRecognizer` que ya existe en `TalkTab`: el resultado reemplaza el texto del campo — y
   `PrimaryButton` **"Decir"** (icono `RecordVoiceOver`) → `vm.speak(texto)`. Deshabilitado si el
   texto está en blanco.
3. `SectionTitle("Animaciones")` + `LazyColumn` agrupada:
   `Animations.all.groupBy { it.group }` → por grupo: `SectionTitle(<grupo>)` + grid 2 columnas de
   `AppCard`s (label del `Entry` + `IconButton` `PlayArrow`, contentDescription "Reproducir") →
   `vm.playAnimation(entry.id)`.

**Datos:** `Animations.all` (catálogo, no `UiState`). **Acciones:** `speak`, `playAnimation`.

### 3.4 Jugar (`GamesScreen`)

**Vista lista (default):** `Text("Jugar", titleLarge)` + grid 1 columna (teléfono) / 2 (tablet) de
4 cards grandes (icono 32dp + título `titleMedium` + subtítulo `bodySmall` `TextSecondary`); tap →
vista juego (estado local `rememberSaveable` con el juego seleccionado):

| Card | Icono (filled) | Subtítulo | type (VM) |
|---|---|---|---|
| **"Ajedrez"** | `SportsEsports` | "Partida contra AIBI." | `"chess"` |
| **"Serpientes"** | `Pets` | "El clásico de la serpiente." | `"snake"` |
| **"Pirate Wars"** | `Flag` | "Batalla pirata." | `"pirate"` |
| **"Zero"** | `Casino` | "Un juego de azar… descubrilo." | `"zero"` |

**Vista juego (dentro de la card seleccionada):**
- `Row`: `IconButton` `ArrowBack` (contentDescription "Volver") + `Text(<nombre del juego>, titleLarge)`.
- Botones apilados a ancho completo: `PrimaryButton` **"Entrar"** → `vm.gameEnter(type)` ·
  `OutlinedButton` **"Empezar"** → `vm.gameStart(type)` · `OutlinedButton` **"Jugar"** →
  `vm.gamePlay(type)` · `TextButton` (contenido `error`) **"Salir"** → `vm.gameExit(type)`.
- Sin tableros ni lógica de juego (backlog C).

**Empty states:** no aplica. **Acciones:** `gameEnter`, `gameStart`, `gamePlay`, `gameExit`.

### 3.5 Herramientas (`ToolsScreen`)

`Text("Herramientas", titleLarge)` + fila de 4 `FilterChip`s (selección única, `rememberSaveable`):

| Chip | Icono (filled) |
|---|---|
| **"Luces"** | `LightMode` |
| **"Alarmas"** | `AccessAlarm` |
| **"Fotos"** | `PhotoCamera` |
| **"Log BLE"** | `Terminal` |

#### 3.5.1 Luces

1. Card on/off: `Row` icono `Lightbulb` + `Text("Luz")` + `PrimaryButton` **"Prender"** →
   `vm.lightOn(0)` y `OutlinedButton` **"Apagar"** → `vm.lightOff(0)`.
2. Card color: 3 `Slider`s "Rojo" / "Verde" / "Azul" (0-255, color del track activo = rojo/verde/azul
   plenos) + `Slider` **"Brillo"** (0-100).
3. Card modo: 4 `FilterChip`s (selección única, default "default"):

   | Chip | `mode` del VM |
   |---|---|
   | **"Fijo"** | `"default"` |
   | **"Respiración"** | `"breath"` |
   | **"Color"** | `"color"` |
   | **"Flujo"** | `"flow"` |

4. `PrimaryButton` **"Aplicar"** → `vm.lightSet(mode, listOf(r, g, b), brillo)`.
5. `OutlinedButton` **"Listar luces"** → `vm.lightRefresh()`.
6. Si `ui.lights` no vacío: lista de filas `name ?: "Luz #<id>"` con `TextButton` **"On"** →
   `vm.lightOn(id)` / **"Off"** → `vm.lightOff(id)`.
7. Empty: `EmptyState(Icons.Default.LightMode, "Sin luces listadas",
   "Tocá «Listar luces» para ver las luces del robot.")`.

#### 3.5.2 Alarmas

1. Card nueva alarma:
   - `OutlinedTextField` **"Hora"** (placeholder **"HH:mm"**, ej. "08:30").
   - `SectionTitle("Tipo")` + `FlowRow` de 7 `FilterChip`s (selección única, default tag 0). Valor →
     `vm.alarmAdd(tag, time)` (el PRIMER parámetro del VM es el tag; ver nota de implementación).

   **Etiquetas de tag — evidencia real del oficial** (`AlarmListAdapter.alarmIconList`,
   decompilado `ai.living.aibi.ui.view.tools.alarm.AlarmListAdapter`: lista ordenada 0..6 de
   drawables `alarm_icon_*`):

   | tag | Drawable oficial | Etiqueta | Icono Compose (filled, verificado) |
   |---|---|---|---|
   | 0 | `alarm_icon_alarm` | **"Alarma"** | `AccessAlarm` |
   | 1 | `alarm_icon_pill` | **"Medicamento"** | `Medication` |
   | 2 | `alarm_icon_water` | **"Agua"** | `WaterDrop` |
   | 3 | `alarm_icon_sport` | **"Deporte"** | `DirectionsRun` |
   | 4 | `alarm_icon_get_up` | **"Levantarse"** | `WbTwilight` |
   | 5 | `alarm_icon_feed` | **"Comida"** | `Restaurant` |
   | 6 | `alarm_icon_meeting` | **"Reunión"** | `Groups` |

   (imports: `androidx.compose.material.icons.filled.{AccessAlarm, Medication, WaterDrop,
   DirectionsRun, WbTwilight, Restaurant, Groups}` — verificados en material-icons-extended 1.7.8.)
   - `PrimaryButton` **"Agregar"** → valida formato HH:mm (si no, muestra el campo en `error` con
     `supportingText` **"Formato HH:mm"** y no envía) → `vm.alarmAdd(tag, time)`.
2. `OutlinedButton` **"Listar alarmas"** → `vm.alarmRefresh()`.
3. Lista `ui.alarms`: fila = icono del tag (tabla de arriba) + hora `"HH : MM"` (`bodyMedium`) +
   `IconButton` `Delete` (contentDescription "Eliminar") → `vm.alarmDel(index)`.
4. Empty: `EmptyState(Icons.Default.AccessAlarm, "No hay alarmas",
   "Agregá una alarma con hora y tipo.")`.

Nota de implementación: `Protocol.alarmAdd(tag, time)` ya envía `tag` (no `index`); el VM expone
`alarmAdd(index, time)` cuyo primer parámetro ES el tag. El selector pasa el tag elegido directo.

#### 3.5.3 Fotos

1. Card sync: si `!ui.photoServerRunning` → `PrimaryButton` **"Iniciar sync"** →
   `vm.startPhotoSync()`; si corriendo → `OutlinedButton` **"Detener sync"** → `vm.stopPhotoSync()`.
   Texto de estado `bodySmall`: si corriendo, **"Recibiendo fotos por WiFi…"**; hint permanente
   `bodySmall` `TextSecondary`: **"El robot y el dispositivo deben estar en la misma WiFi."**
2. Card modo foto: `OutlinedButton` **"Modo foto"** → `vm.photoEnter()` · **"Mostrar"** →
   `vm.photoShow()` · **"Salir"** → `vm.photoExit()`.
3. Galería `ui.photos` (paths): grid 3 columnas de cards con el nombre de archivo (mono `bodySmall`,
   ellipsis) + tap **"Abrir"** (intent `ACTION_VIEW` — SOLO si hay `FileProvider` configurado en el
   manifest; si no, omitir el botón y reportar NEEDS_CONTEXT). `IconButton` `Delete`
   (contentDescription "Borrar fotos") → `vm.photoClear()`.
4. Empty: `EmptyState(Icons.Default.PhotoCamera, "Todavía no hay fotos",
   "Iniciá el sync con el robot en la misma WiFi.")`.

#### 3.5.4 Log BLE

1. Fila de `FilterChip`s TX / RX / EVT / ERR / SYS (selección múltiple; sin selección = mostrar
   todas) + `IconButton` `Delete` (contentDescription "Limpiar") → `vm.clearLog()`.
2. `LazyColumn` (auto-scroll al final) de `ui.log`: cada línea mono `bodySmall`, color por
   `LogCat` (filtrado por los chips):

   | LogCat | Color (oscuro) | Color (claro) |
   |---|---|---|
   | TX | `#3AA0FF` | `#1B6FC2` |
   | RX | `#19E3B1` | `#0E8F6B` |
   | EVT | `#E3C419` | `#8F6D00` |
   | ERR | `#E34B3A` | `#E34B3A` |
   | SYS | `#8B9BB4` | `#5A6B82` |

3. Empty: `EmptyState(Icons.Default.Terminal, "Log vacío",
   "El tráfico BLE aparece acá cuando hay actividad.")`.

---

## 4. Estados y feedback

### 4.1 Pill del header — 5 variantes

`StatusPill(conn, reconnectAttempt, connHint)`:

| # | Condición | Glyph | Texto | Color (texto/glyph) | Fondo |
|---|---|---|---|---|---|
| 1 | `conn == CONNECTED` | `●` | **CONECTADO** | `StatusGreen` | `StatusGreen` 20% |
| 2 | `conn == CONNECTING` | `◌` | **CONECTANDO…** | `Warning` | `Warning` 20% |
| 3 | `conn == RECONNECTING` | `↻` | **RECONECTANDO (N/10)** (N = `reconnectAttempt`) | `primary` | `primary` 20% |
| 4 | `DISCONNECTED`/`SCANNING` y `connHint` contiene `"dormido"` (ignoreCase) | `⚠` | **ROBOT DORMIDO** | `ErrorRed` | `ErrorRed` 20% |
| 5 | `DISCONNECTED`/`SCANNING` y `connHint` contiene `"otra app"` (ignoreCase) | `⚠` | **OTRA APP CONECTADA** | `ErrorRed` | `ErrorRed` 20% |

Fallback: `DISCONNECTED`/`SCANNING` sin hint → glyph `○`, texto **DESCONECTADO**, color
`TextSecondary` (no es una de las 5 variantes del spec; solo default).

Nota: en el scaffold conectado solo aparecen las variantes 1-3; las 4-5 viven en el wizard
(§2.5, item 5) donde la pill es además el disparador del diálogo grave.

### 4.2 Snackbars transitorios (Task 8; el VM los dispara con `showSnackbar`, auto-limpia a los 3s)

| Evento | Copy exacto |
|---|---|
| `setVolume` tras enviar | **"Volumen ok"** |
| `alarmAdd` tras enviar | **"Alarma agregada"** |
| `alarmDel` tras enviar | **"Alarma eliminada"** |
| `onPhoto` del `PhotoTcpServer` | **"Foto guardada: <nombre>"** |
| `send()` en catch | **"Error enviando…"** |

### 4.3 Diálogos graves (accionables)

Se disparan al tocar la pill roja / card de aviso en el wizard (o al llegar `connHint` con la app
desconectada). Mapping: `connHint` contiene `"otra app"` → diálogo A; si no → diálogo B.

**A — Otra app conectada** (`connHint` con "otra app"):
- Título: **"Otra app está usando el robot"**
- Cuerpo: **"El robot está al alcance pero rechazó la conexión. Si la app oficial (Living.AI) lo tiene conectado, cerrá esa app y reintentá."**
- Botones: `TextButton` **"Entendido"** (cierra) · `PrimaryButton` **"Reintentar"** → `vm.tryAutoReconnect()`.

**B — Robot dormido** (resto de `connHint`s: "dormido", "No se pudo reconectar…"):
- Título: **"El robot está dormido"**
- Cuerpo: **"El robot no responde. Despertalo (tocale la cabeza) y volvé a intentar."**
- Botones: `TextButton` **"Entendido"** (cierra) · `PrimaryButton` **"Reintentar"** → `vm.tryAutoReconnect()`.

### 4.4 Estados vacíos (resumen)

| Lugar | Icono | Título | Subtítulo |
|---|---|---|---|
| Wizard — sin dispositivos | `Bluetooth` | Sin dispositivos | No se ven dispositivos todavía. ¿El robot está encendido y cerca? |
| Chat IA | `AutoAwesome` | Hablá con AIBI | Escribile un mensaje y el robot lo dice en voz alta. |
| Luces | `LightMode` | Sin luces listadas | Tocá «Listar luces» para ver las luces del robot. |
| Alarmas | `AccessAlarm` | No hay alarmas | Agregá una alarma con hora y tipo. |
| Fotos | `PhotoCamera` | Todavía no hay fotos | Iniciá el sync con el robot en la misma WiFi. |
| Log BLE | `Terminal` | Log vacío | El tráfico BLE aparece acá cuando hay actividad. |

---

## Notas de implementación (cross-cutting)

- `Icons.Default` = `Icons.Filled`; todos los iconos citados están verificados contra
  material-icons-core/extended 1.7.8 (dependencia ya presente).
- Los iconos nuevos que NO están importados hoy y deben importarse como
  `androidx.compose.material.icons.filled.*`: `BatteryFull, DirectionsWalk, MonetizationOn,
  Restaurant, Memory, QueryStats, Celebration, WbTwilight, SelfImprovement, Bedtime, SmartToy,
  Medication, WaterDrop, Groups, Lightbulb, Pets, Flag, Casino, Key, Warning, Delete, ArrowBack,
  PlayArrow, Home, Build`.
- Tema: `themeMode` en `UiState` (default `"system"`), `setThemeMode` persiste en `prefs()` con key
  `"theme_mode"` (Task 2).
- El primer parámetro de `vm.alarmAdd(índice, hora)` ES el tag (0..6): el selector lo pasa directo.
- Los accesos rápidos de Inicio ejecutan acciones del VM y NO navegan entre destinos.
