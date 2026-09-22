# Spec: Subproyecto A — Estabilizar AibiPilot

Fecha: 2026-09-22
Estado: aprobado (pendiente revisión final del usuario)
Proyecto: AibiPilot (app alternativa para el robot AIBI Pocket)
Siguiente subproyecto: B — Rediseño UX · C — Features nuevas · D — Investigación firmware (paralelo, sin código)

## Objetivo

Dejar la app estable y con todo lo implementado verificado contra el robot real:
probar y arreglar en vivo las features pendientes (volumen, alarmas, juegos,
fotos TCP) y robustecer la capa BLE. No se rediseña nada (eso es B) ni se agregan
features nuevas (eso es C).

## Alcance

### 1. Git
- Repositorio inicializado en `~/aibi/AibiPilot` (branch `main`),
  commit inicial `5fb7326` con el código actual + `.gitignore` + `AGENTS.md`.
- (Hecho antes de este plan, por necesidad de commitear el spec.)

### 2. Features a verificar/arreglar en vivo (con robot real)
Cada una termina con su ACK correcto confirmado en logcat (`*_ok`) y, donde
aplique, efecto visible en el robot:

| Feature | Comandos | Criterio de éxito |
|---|---|---|
| Volumen | `setting_req volume` mute/low/high (con `setting_in` previo) | `setting_volume_ok` (u op equivalente) en logcat |
| Alarmas | `alarm_req list/add/del` (con `alarm_in`) | `alarm_list_ok` con lista real; add/del responden ok |
| Juegos | `{chess,snake,pirate,zero}_req in/start/play` | ACK de cada op; el robot entra al modo de juego |
| Fotos TCP | `photo_req sync` → robot conecta TCP 9090 → archivos | Al menos una foto aterriza en `files/photos` de la tablet |

Notas:
- Para fotos, tablet y robot deben estar en la misma WiFi.
- Si una feature no responde, el fix loop es sobre código. Si el protocolo
  parece incorrecto, fallback: capturar tráfico de la app oficial
  (logcat tag `BleUtils`) y contrastar builders contra el decompilado en
  `/tmp/aibi/jadx_out` (volátil; el XAPK persiste en `~/aibi/`).

### 3. Robustez BLE
- **Reconexión automática mejorada**: al perder la conexión, reintentos con
  backoff acotado (con tope), cancelables; al volver a primer plano se reanuda.
  Si el robot está dormido, mensajes claros de estado.
- **Detección de "otra app lo tiene conectado"**: distinguir fallo de conexión
  (GATT status 133/8/19 u ocupado) de "robot dormido"; mostrar mensaje
  accionable ("cerrá la app oficial") y re-intentar.
- **Detección de desconexión silenciosa**: keep-alive liviano (p. ej. `sta_req
  query[12]` o similar cada N segundos solo si no hay tráfico) + timeout de
  inactividad RX; ante silencio prolongado, re-conectar.
- **Log BLE mejorado**: categorías TX / RX / evento / error, filtros visibles
  en la UI, resaltado de errores (rojo), tope de líneas ya existente (200).

### 4. Fuera de alcance (no se toca en A)
- Rediseño UI (B). Editor de escenas, rutinas, notificaciones, widget, consola
  JSON raw, sniffer, gamificación, UI completa de juegos (C).
- Tests unitarios (descartados por el usuario en brainstorming).
- Firmware/flasheo (D, solo investigación).

## Arquitectura objetivo (mínima, sin romper lo existente)

- `ble/BleClient.kt`: máquina de estados GATT más fina. Expone:
  - `ConnectionFailure.Rejected/Busy` vs `RobotAsleep` vs `Lost` (según status
    GATT y contexto).
  - Keep-alive: `suspend fun isStale()` o callback de inactividad (sin
    bloquear el writeMutex).
- `RobotViewModel.kt`:
  - Máquina de reconexión (`ConnState` + contador de intentos + backoff
    [1s, 2s, 4s, 8s…] con tope ~30s y máximo N reintentos), integrada con
    `onAppForeground()` ya existente.
  - `log()` con categoría (TX/RX/EVT/ERR) → `UiState.log` pasa a `List<LogLine>`.
  - Mensajes claros: "robot dormido", "otra app conectada", "reintentando N/∞".
- `ui/Screens.kt`: solo cambios mínimos para el nuevo log (filtros, colores) y
  estado de reconexión visible. Sin rediseño.
- `protocol/Protocol.kt`: sin cambios salvo correcciones que surjan del
  contraste en vivo (con evidencia del decompilado oficial).

Invariantes a respetar (de AGENTS.md):
- Frame TX/RX `BB AA` + len(2B LE) + JSON; `DD CC` binario.
- Modos: `<feature>_in` antes de comandos; `ensureMode()` espera `*_in_ok`
  (timeout 4s) — mantener este patrón.
- Permisos MIUI: ACCESS_FINE_LOCATION obligatorio para scan.

## Definición de "done" del subproyecto

1. Todas las features de la tabla verificadas en vivo con ACK en logcat.
2. Fotos: al menos un archivo recibido por TCP 9090 en la tablet.
3. Desconexión forzada → reconexión automática funciona (observable en logcat
   y en la UI).
4. "Otra app conectada" y "robot dormido" producen mensajes claros.
5. Log BLE con filtros y errores resaltados.
6. Todo commiteado en git, historial limpio, gate de calidad verde por tarea.

## Gate de calidad por tarea

```bash
cd ~/aibi/AibiPilot
JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
```

## Verificación en vivo (orquestador, vía adb)

- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- launch → `adb exec-out screencap -p` → taps dirigidos → logcat
  (`adb logcat | grep -E "AibiBle|BleUtils"`) para TX y ACKs.
- Dispositivo: Redmi Pad SE `XXXXXXXX`. Robot: AIBI-CF6A `B4:3A:45:AA:BB:CC`.

## Riesgos conocidos

- Robot dormido o fuera de alcance durante las pruebas → reintentar al
  despertarlo; no es bloqueante del plan (el mensaje de "dormido" es parte del
  scope).
- Fotos requiere misma WiFi robot/tablet; si no se logra en la sesión, se
  aparca con `Ruling:` y se retoma en otra sesión.
- El protocolo de alguna feature puede diferir del decompilado (v1.7.0) si el
  robot tiene otra versión de firmware → fallback Enfoque 3 (captura oficial).
