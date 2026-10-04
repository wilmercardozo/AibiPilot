# Handoff — AIBI Pocket: estado, hallazgos y qué desarrollar

Documento de traspaso para un agente de desarrollo (opencode). Consolida el análisis de
firmware + app y lista **qué se puede construir**, priorizado y accionable. Contexto profundo:
`docs/FIRMWARE-RESEARCH.md`, `docs/CONTEXTO-ESP32-ANDROID.md`, `AGENTS.md`.

Fecha: 28 sep 2026.

---

## 1. Qué tenemos hoy (estado real)

- **App Android `AibiPilot`** (Kotlin+Compose) que controla el robot por BLE. Protocolo recuperado
  por RE de la app oficial. Funciona: luces, alarmas, juegos, fotos, TTS, rutinas, modo remoto, WiFi.
- **Banco de captura** (ESP32 puente + mitmproxy) que interceptó el tráfico del robot **sin abrir
  el robot**. Hallazgo habilitante: **el robot NO valida el certificado TLS** → se puede MITM.
- **Firmware de contenido descargado y analizado**: `aibi-sd1.7.0.zip` (492 MB, íntegro).

### Arquitectura del robot (confirmada por los binarios)
| Procesador | Firmware | Rol |
|---|---|---|
| **ESP32-S3 #1 "cerebro"** (ausente del zip) | OTA `op:update` | **BLE + protocolo JSON + dispatch de `motion` + WiFi/OTA**. Es lo que la app toca. SPI master. |
| **ESP32-S3 #2 "pcam"** | `bin/pcam.bin` | Cámara + reconocimiento facial (MobileFaceNet) + voz ESP-SR. SPI slave. |
| **ARM Cortex-M "body"** | `bin/body.bin` (32K) | Servos HEAD y NECK |
| **ARM Cortex-M "base"** | `bin/base.bin` (35K) | Servo base + sensor de ángulo + LED |

**Clave:** el `op:update` (firmware del cerebro, con el protocolo BLE) **NO viene en este zip** —
es un binario aparte que hoy no baja (robot en la última versión). Se obtiene forzando el
`ota/checkupdate` por MITM (el robot no valida cert). Ver `FIRMWARE-RESEARCH.md`.

### Seguridad del firmware: ABIERTO
Sin secure-boot, sin flash-encryption, sin firma. Solo SHA256 (ESP) / CRC (ARM) de integridad,
**recalculables** → firmware propio inyectable por el mismo OTA. Igual que el EMO (mismo fabricante).

---

## 2. Bugs conocidos del app (con causa raíz y fix)

### BUG-1 · Apagar el robot no funciona bien
`RobotViewModel.powerOff()` (línea ~836):
```kotlin
fun powerOff() = ensureMode("setting") { send(Protocol.settingOff(), "Apagar robot") }
// settingOff() = setting_req {"op":"off"}
```
**Causa raíz probable:**
- Depende de `ensureMode("setting")` (espera `setting_in_ok`, timeout 4s + fallback 700ms). Si el
  robot está en otro modo/ocupado, el `in` puede no llegar y el `off` se pierde o se manda antes de
  tiempo.
- No hay confirmación: tras `off` el robot corta BLE; la app no distingue "apagó" de "se desconectó".
- El comando exacto de apagado **no está verificado contra el firmware del cerebro** (ausente).

**Fix propuesto:**
1. No exigir modo: probar `settingOff()` directo; si falla, entonces `ensureMode`. O reintentar.
2. Tratar la **desconexión BLE dentro de ~3s post-off como éxito** ("Robot apagado ✓").
3. Timeout con feedback claro: si no desconecta, "No respondió — reintentá / tocá al robot".
4. Confirmar el comando cuando tengamos el firmware del cerebro (fase 4).

### BUG-2 · WiFi es complejo de conectar y no dice cuándo conectó
`setRobotWifi()` (línea ~829) y `parseStaRsp()` (línea ~696):
```kotlin
fun setRobotWifi(ssid, password) = ensureMode("setting") {
    send(Protocol.settingWifiSet(ssid, password))   // wifiset
    showSnackbar("Conectando…")                      // <-- fire-and-forget, NO confirma
}
// parseStaRsp solo lee wifi.ssid; NO hay campo 'connected'; solo actualiza en refresh manual
```
**Causa raíz:**
- **No hay loop de confirmación** tras `wifiset`: se manda y se muestra un snackbar; el usuario nunca
  ve "conectado". La conexión en el robot tarda segundos (asíncrona) y la app no re-consulta.
- El estado solo se actualiza con `sta_req query[4]` **tocando manualmente** el texto ("tocá para
  refrescar"). UX pobre.
- `parseStaRsp` no interpreta un flag `connected` — solo el SSID → no distingue "configurado" de
  "conectado de verdad".

**Fix propuesto (máquina de estados de conexión):**
1. Tras `wifiset` → esperar `setting_wifiset_ok`, luego **poll automático** `wifiStatus()` cada ~2s
   por ~20s hasta que `wifi.ssid == ssid` (o flag `connected`).
2. UI con estados claros: **Conectando… → Conectado a X ✓** / **No se pudo conectar ✗** (con reintento).
3. Parsear `wifi.connected`/`wifi.state` si el robot lo envía (verificar en vivo el formato real de
   `sta query[4]`).
4. Mostrar RSSI de la red elegida (ya viene en `wifilist`) y un indicador de señal.

---

## 3. Mejoras del app (diseño, funcionalidad, nuevas features)

### Diseño / UX
- **Estado de conexión global** siempre visible (header): Desconectado / Conectando / Conectado +
  batería + WiFi del robot, con color e ícono. Hoy el estado está disperso.
- **Feedback consistente**: toda acción que va por BLE debería mostrar pendiente→ok→error (hoy varias
  son fire-and-forget con snackbar optimista). Un patrón `withAck { }` reusable.
- **Batería**: indicador persistente con nivel (1–4) e ícono; ya se parsea, falta mostrarlo bien.
- Revisar `DESIGN.md`: unificar el sistema visual (tema "Tech limpio") y accesibilidad (tamaños de
  toque, contraste, labels).

### Funcionalidad (robustez de lo existente)
- **ensureMode** más robusto: reintento, y distinguir "modo no confirmado" de "comando fallido".
- **Reconexión BLE**: ya hay `RECONNECTING`; exponerla en UI y hacerla más agresiva/clara.
- **Log BLE**: filtros por categoría y export (útil para seguir reverseando).

### Nuevas funcionalidades (habilitadas por lo que sabemos)
- **Control fino de servos** (cuando mapeemos el cerebro): mover head/neck/base por ángulo, con la
  calibración/offset que vive en `body.bin`/`base.bin` (`hoffset`/`noffset`/`boffset`).
- **Reconocimiento facial**: `pcam.bin` tiene MobileFaceNet + `get_face_info` → exponer enrolar/borrar
  caras desde la app (si el cerebro lo rutea por BLE).
- **Modo offline / voz local**: el robot trae modelos ESP-SR (wake-word "Hi Lexin", comandos EN en
  `mn7_en`). Documentar y, si se puede, togglear features offline.
- **Gestión de assets/versiones**: la app podría mostrar/forzar la descarga del contenido SD
  (`aibi-sd<ver>.zip`) — útil para la recuperación (ver §5).

---

## 4. Cruce firmware ↔ app (qué toca qué)

```
App (BLE JSON)  ──►  Cerebro ESP32-S3 (op:update, protocolo)  ──SPI──►  pcam (cámara/voz)
                                                              ──SPI──►  body/base ARM (servos)
```
- Los `motion <n>` (frame `55AA55AA21<n>`) que manda la app → los interpreta el **cerebro** →
  algunos disparan acciones del cerebro (update, diskmode, factory), otros se traducen a comandos SPI
  de servo (`hoffset`/`noffset`/`angle`/… en los ARM).
- **La tabla exacta motion-ID→acción está en el cerebro (ausente).** Para el barrido 27-100: la
  mecánica de servos se lee de `body.bin`/`base.bin` (Ghidra ARM, `Firmware/body_decomp.c`); los IDs
  especiales (update/diskmode/factory) requieren el firmware del cerebro.
- Endpoints del robot (de captura de red, no de los binarios): `api.aibipocket.com` (control/OTA,
  HTTPS), `res-us-east-1.living.ai` (contenido, HTTPS, bucket Aliyun público), `api-guigu.aibipocket.com`
  (CDN HTTP), `api.livingai.cn` (solo pcam). Los binarios **no** traen templates de URL (viven en el cerebro).

---

## 5. Recuperación del robot (si queda "sin firmware"/sin contenido SD)
El robot NO se brickea con esto: el cerebro (flash) queda; falta el **contenido SD**. Recuperar:
- **Confiable (recomendado):** apuntar el WiFi del robot a una red normal (WPA2) con AibiPilot y
  disparar update/factory → baja `aibi-sd1.7.0.zip` de Aliyun y se restaura.
- **Local (rápido, para la fase de modding):** servir nuestra copia verbatim del zip por MITM (el
  robot no valida cert). Copia local verificada: `Firmware/aibi-sd1.7.0.zip` (516430631 bytes, unzip OK).
  Cuello de botella: el relay del ESP para 492MB es lento/inestable.

---

## 6. Roadmap a firmware propio (el objetivo final)
1. **Obtener el firmware del cerebro** (donde vive el protocolo/motion): forzar `op:update` por MITM
   del `ota/checkupdate` (robot no valida cert) → capturar su URL/binario. Banco ya montado.
2. **Reversear el cerebro** (ESP32-S3/Xtensa): objdump Espressif (`xtensa-esp32s3-elf-objdump`) o IDA;
   Ghidra necesita módulo Xtensa LX7. Mapear el protocolo BLE completo y el dispatch de `motion`.
3. **Modificar + reinyectar**: cambiar el binario, recalcular SHA256 con esptool, servir por MITM.
   Empezar por cambios chicos y reversibles. Sin firma → factible.
4. **Nuevas funcionalidades en firmware**: comandos BLE nuevos, features offline, control de servos
   extendido, etc.

### Herramientas ya instaladas (workstation)
- Ghidra 11.2.1 `/tmp/ghidra_11.2.1_PUBLIC` + **JDK 21** `/tmp/jdk21` (Ghidra 11.2 exige 21, no 17).
  ARM Cortex-M nativo. Decompile de `body.bin`: `Firmware/body_decomp.c` (199 funcs).
- `xtensa-esp32s3-elf-objdump` (core arduino-esp32) para Xtensa.
- `/tmp/esp2elf.py`: imagen ESP32 → ELF Xtensa. mitmproxy 12, esptool 5.

---

## 7. Prioridades sugeridas para opencode
1. **BUG-2 (WiFi)**: máquina de estados de conexión con confirmación automática. Alto impacto UX.
2. **BUG-1 (apagar)**: fix de ensureMode + detección de éxito por desconexión.
3. **Estado global de conexión + batería** en el header (diseño).
4. Patrón `withAck { }` reusable para feedback consistente en acciones BLE.
5. (Investigación) Forzar el OTA del cerebro para conseguir el binario del protocolo → habilita todo §6.

---

## 8. INSTRUCCIONES PARA OPENCODE (ejecutar acá)

Este es tu trabajo. Implementá en este orden. No rompas el protocolo BLE ni los "datos técnicos
clave" de `AGENTS.md`. Build: `JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug`; probar en el
Redmi Pad SE (`adb`, robot AIBI-CF6A). Un commit por tarea, en branch (no `main`).

### Tarea 1 — Arreglar conexión WiFi con confirmación (BUG-2) · PRIORIDAD MÁXIMA
Archivos: `RobotViewModel.kt` (`setRobotWifi`, `parseStaRsp`, `UiState`), `ui/ToolsScreen.kt` (`WifiPane`).
- Agregá un estado de conexión WiFi al `UiState`: `enum WifiConnState { IDLE, CONNECTING, CONNECTED, FAILED }` + `robotWifiTarget: String?`.
- En `setRobotWifi(ssid, pass)`: tras enviar `wifiset`, esperá el ACK `setting_wifiset_ok` (via `modeAckFlow`), y luego **pollá `wifiStatus()` cada 2s hasta 20s** hasta que `sta query[4]` devuelva `wifi.ssid == ssid` (o un flag `connected`). Actualizá el estado: CONNECTING → CONNECTED / FAILED.
- En `parseStaRsp`: parseá también un posible flag `wifi.connected`/`wifi.state` (verificá el formato real en vivo con el Log BLE). Si no existe, usá "ssid == target" como señal de conexión.
- En `WifiPane`: reemplazá el texto "Sin red (tocá para refrescar)" por un indicador claro:
  **Conectando a X…** (spinner) → **Conectado a X ✓** (verde) / **No se pudo conectar ✗** (con botón Reintentar). Mostrá el RSSI de cada red (ya viene en `wifilist`).
- Criterio de aceptación: al conectar a una red real, la UI pasa sola a "Conectado ✓" sin tocar nada.

### Tarea 2 — Arreglar apagar el robot (BUG-1)
Archivos: `RobotViewModel.kt` (`powerOff`), `ui/Screens.kt` (diálogo power off).
- No dependas de `ensureMode("setting")` como bloqueo duro: enviá `settingOff()` y, si no hay efecto en ~1.5s, reintentá una vez tras `settingIn()`.
- Tratá la **desconexión BLE dentro de ~3s posteriores al `off`** como éxito → mostrá "Robot apagado ✓" y andá a la pantalla de conexión.
- Si no desconecta en el timeout, mostrá "No respondió — reintentá o tocá al robot".
- Criterio de aceptación: apagar desde la app apaga el robot y la UI lo refleja de forma consistente.

### Tarea 3 — Estado global de conexión + batería (diseño)
- Header persistente en `MainActivity`/scaffold: chip con Desconectado/Conectando/Conectado, nivel de batería (1–4, ya se parsea en `parseStaRsp`), y WiFi del robot. Color + ícono. Accesible (contraste, labels).

### Tarea 4 — Patrón de feedback `withAck { }`
- Helper reusable en `RobotViewModel` que envía un comando, espera su `*_ok`/`*_no` (o timeout) y expone pendiente→ok→error. Migrá las acciones fire-and-forget (volumen, luces, alarmas, wifi, off) a este patrón para feedback consistente.

### Reglas
- Cada tarea: código + una verificación mínima (probar en el robot real y anotar el resultado).
- No toques el firmware ni el banco ESP en estas tareas (son solo app Android).
- Las mejoras "nuevas features" de §3 y el firmware propio de §6 son fase posterior — no las encares
  hasta cerrar Tareas 1–4.
