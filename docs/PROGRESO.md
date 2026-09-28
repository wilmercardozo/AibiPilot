# Progreso del proyecto — AibiPilot

## 2026-09-22 — Plan "estabilizar-aibipilot" (subproyecto A)

Estado: **COMPLETO**. Todo verificado en vivo con el robot AIBI-CF6A salvo lo aparcado.

### Resultado
- **Volumen** mute/low/high → `setting_volume_ok` (sin cambios de código; builder ya era correcto).
- **Alarmas** list/add/del → ACKs. Fix: `add` usa `tag` (0..6), no `index` (`e63b109`).
- **Juegos** chess/snake/pirate/zero in/start/play → ACKs (sin cambios; `play` va sin parámetros).
- **Fotos TCP**: parser corregido a la app oficial (cabecera `name=...;filesize=N;delimited=X#`,
  `finish` = centinela de fin; `24b478e`), IP WiFi vía ConnectivityManager (`76546c8`), entrar a
  modo photo antes del sync (`326465b`). En vivo: `photo_in_ok` + `photo_sync_no` "Failed to
  connect to App through Wi-Fi" → **transferencia real pendiente de configurar WiFi del robot**.
- **Log BLE** categorizado TX/RX/EVT/ERR/SYS con filtros + hex TX en logcat (`6b4ebfb`, `9c50bfe`).
- **Robustez BLE**: reconexión automática (backoff 1-30s, 10 intentos, idempotente), keep-alive
  (ping a los 20s idle sin modo activo; fuerza reconexión sin depender del callback GATT de MIUI),
  diagnóstico de fallo (re-scan del MAC → hint "otra app conectada" vs "dormido").
  Probado en vivo: BT off → ping fallido → ciclo → reconectado con handshake.

### Aparcado para el subproyecto B (rediseño UX)
- Selector de tipo (tag 0..6) en la UI de alarmas (hoy pasa `ui.alarms.size`).
- Pulido de la máquina de modos (modeout del modo anterior, "in" redundante, ACK 3-5s).
- 3 observaciones Menores del QA senior (recuperación lenta ante fallo síncrono de connectGatt;
  doble diagnóstico en timeout; `reconnectInFlight` sin limpiar en un catch) — ver
  `.metodologia/estabilizar-aibipilot/reports/final-qa-senior.md` (workspace borrado: ver git).
- Ajuste de soTimeout TCP (10s) si fotos grandes lo requieren.
- Transferencia real de fotos (pendiente de WiFi del robot).

### Costo del plan
- Modelos: deepseek-v4-flash (dev/QA por tarea) y deepseek-v4-pro (QA senior + orquestador).
- ~19 despachos flash + 2 pro (smoke + QA senior) en pestañas Herdr. Sin costos de Claude.
- Aprendizaje de proceso: los fix loops con QA flash funcionaron bien (2 bugs Mayores reales
  detectados: hex TX perdido y reconexión que moría en el primer intento).

### Aprendizajes técnicos (ver AGENTS.md para el detalle)
- El header TCP real de fotos NO lleva prefijo `finish;` (documentación previa corregida).
- WifiManager.connectionInfo.ipAddress devuelve 0 en MIUI → ConnectivityManager.
- El robot responde `photo_sync_no` explícito si no tiene WiFi.
- MIUI no entrega callback GATT al apagar el adaptador BT.

## 2026-09-22 — Plan "rediseño UX" (subproyecto B)

Estado: **COMPLETO**. Verificado en vivo con el robot salvo lo ambiental.

### Resultado
- Tema "Tech limpio" (oscuro por defecto + toggle Sistema/Claro/Oscuro, pixel-check en vivo).
- Navegación: 5 destinos (Inicio, Chat IA, Hablar, Jugar, Herramientas) con rail en tablet y
  bottom bar en teléfono; wizard de conexión (permisos → despertar → escanear + reconexión rápida).
- Pantallas: dashboard con accesos rápidos y escenas, chat IA reestilizado con diálogo de
  configuración (botón Probar + hint Ollama), TTS + animaciones agrupadas, juegos con controles,
  Herramientas con sub-pestañas (Luces, Alarmas con selector de tag, Fotos, Log BLE).
- Feedback: pill de estado en header, snackbars transitorios, diálogos graves, estados vacíos.
- Pulido aparcado de A resuelto: selector de tag real (etiquetas del oficial), modeout filtrado
  por modo actual, fallo síncrono de connectGatt fuera del job, diagnóstico único, soTimeout 30s.
- `DESIGN.md` como fuente de diseño (entregado por rol ux con checkpoint del usuario).

### En vivo con el robot
- Baile `show_play_ok` · volumen ciclo · luz on · chess in/start · alarm add tag:2 + del con
  ACKs · keep-alive vivo · tema claro/oscuro verificado con PIL. TTS dio `show_speak_no`
  "Server disconnected" (servidor del robot, no de la app).

### Aparcado (backlog C)
- Transferencia real de fotos (pendiente WiFi del robot) · botón "Abrir" en galería (FileProvider).
- Integración Hermes Agent (HTTP bridge + MCP) · rutinas, notificaciones, widget, consola raw,
  sniffer, gamificación, editor de escenas, UI completa de juegos.

### Costo del plan
- ~10 despachos flash (dev/QA) + 2 pro (ux DESIGN.md + QA senior) en pestañas Herdr.
- El checkpoint de DESIGN.md con el usuario evitó retrabajo; los QA flash siguieron rindiendo
  (2 desvíos menores capturados y resueltos en T8).

## 2026-09-22 — Plan "features nuevas" (subproyecto C)

Estado: **COMPLETO**. C1 verificado de punta a punta en vivo; C2-C4 verificados por QA +
pruebas en vivo parciales (robot se durmió en tramos de sesiones largas).

### Resultado
- **C1 Modo remoto (Hermes)**: API HTTP local (puerto 8080, token Bearer) + foreground service
  que toma LA conexión BLE + toggle/config en Herramientas + `docs/HERMES.md`. En vivo con
  curl: 401 sin token, /status, /speak (el robot habló), /play, /volume, /light/on.
  Fix de evidencia en vivo: timeout de comandos 8s→15s (TTS del robot tarda ~11s).
- **C2 Chat IA con contexto**: bloque de estado del robot (batería/pasos/monedas/comida/hora)
  en el system prompt + plantilla de sistema editable.
- **C3 Rutinas + notificaciones**: WorkManager 15 min, CRUD con UI (hora, días L-D, acción),
  ventana [ahora-14min, ahora] con wrap de medianoche, ejecución vía servicio (remoto ON) o
  notificación + `run_routine_id` (singleTop/onNewIntent), notificaciones de batería baja y
  desconexión. En vivo: rutina 15:34 ejecutada → `show_speak_ok`.
- **C4 Laboratorio**: consola JSON raw con 12 plantillas e historial. En vivo: sta query →
  `sta_query_ok`.

### Aparcado
- Ejecución del worker con remoto ON (ventana de 15 min) y notificación "Rutina pendiente" —
  no observadas en vivo; cubiertas por QA de código.
- Token de prueba en la tablet: cambiarlo antes de usar Hermes en serio.
- Backlog C5: gamificación, editor de escenas, widget, UI de juegos, botón "Abrir" galería.
- Transferencia real de fotos (WiFi del robot, heredado de A/B).

### Costo del plan
- ~9 despachos flash + 2 pro (QA senior + re-verificación) en Herdr; 3 fix loops con hallazgos
  reales (modo alarm, wrap de medianoche, doble instancia MainActivity).

## 2026-09-22 — Plan "investigación de firmware" (subproyecto D)

Estado: **COMPLETO**. Veredicto: firmware propio hoy NO viable (sin binario accesible ni estado
de eFuses), con camino concreto de 2-3 pasos. Documento: `docs/FIRMWARE-RESEARCH.md`.

### Resultado (4 capas de investigación)
- **OTA**: API sin auth, escalera de versiones completa (1.0.1→1.7.0), sin URL de firmware; el
  robot descarga por su propio WiFi.
- **Binario**: no accesible públicamente (117 paths CDN probados, sin listado de bucket); la
  app no verifica nada → la firma, si existe, está en el bootloader del robot.
- **Hardware**: ESP32 confirmado (OUI MAC + herramienta RF en FCC); eFuses = incógnita crítica;
  modelo exacto pendiente de análisis visual de fotos FCC.
- **Debug**: canal `DD CC` sin consumidor en la app oficial, sondeable por BLE sin abrir el
  robot; UART (abriendo) resolvería binario + eFuses + particiones de un golpe.

### Próximos pasos priorizados (del documento)
1. Sondear `DD CC` por BLE con la app actual (gratis).
2. Capturar WiFi del robot durante una OTA (requiere robot con WiFi + AP/PCAP).
3. Abrir el robot → UART → espefuse.py summary + dump (fuera del alcance actual).

### Costo del plan
- 5 despachos de research en Herdr (2 flash + 3 pro); 2 excedieron el tope de tiempo del run y
  se recuperaron con wait. Evidencia completa guardada en el workspace (borrado al cerrar; lo
  esencial está en el documento).

## 2026-09-28 — Handoff de Claude + robustez de herramientas

### Resultado (todo mergeado a main, build OK)
- **Handoff Claude ejecutado** (branch dev/handoff-28sep): WiFi con confirmación automática
  (WifiConnState + poll query[4]), apagado robusto (reintento + éxito por desconexión ≤3s),
  header global (conexión+batería+WiFi), patrón `withAck {}` con migración de acciones.
- **Modo seguro en Laboratorio**: el barrido con comandos peligrosos (24-26, 97-100) pide
  confirmación; guard en el VM (`confirmed` flag).
- **Pestaña Diagnóstico**: estado, firmware, batería, pasos/monedas/MTU, arquitectura del robot,
  contenido SD; luego reforzada con RSSI BLE en vivo, modo actual, último RX, reconexión.
- **Acciones de fábrica** con botones directos (forzar update 97-100, disk mode 24-26, tests
  52-53 y 85) — marcadas "observadas en vivo, sin confirmar en fuentes" (la tabla real vive en
  el firmware del cerebro).
- **Pestaña Configuración del robot** (builders exactos del decompilado regenerado): idioma 12,
  24h, unidades, OTA notify, chatty/selfani/tapani/doubletap, wakemodel, quiet, schedule (con
  fix del campo `switch` — bug ALTA cazado por QA contra el oficial), nombre, cumpleaños.
- **Laboratorio**: plantillas de todos los settings + resultado del último comando inline.
- **Logs**: exportar (FileProvider), contador de líneas, toggle auto-scroll.

### Verificado en vivo (robot post-update de firmware)
- `lang es` → `setting_lang_ok` · `wakemodel 1` → `setting_wakemodel_ok` ✓
- Fotos: `photo_in_ok` + sync → `photo_sync_no "No photos in AIBI"` (el rebuild vació la SD;
  flujo OK, falta botón "Tomar foto" con `photo_single` para probar la transferencia real).

### Fuentes extraídas (correcciones al doc de Claude)
- Comandos SPI reales de los ARM: body = `hoffset/hreoffset/noffset/nreoffset/irda/plow/prps/
  pota/start/close`; base = `bled/blow/boffset/breoffset/bota/start/close` (Claude puso
  `led`/`angle` — incorrecto).
- Módulos del oficial NO mapeados aún (ops reales): buyFood (buyfood/feed), changeLook
  (buyglass/wearglass), meet (list/add/del/rescan), tarot (shuffle/choose/read), coaster
  (start/over), friends (messages/list/send…). Candidatos a implementar.
- Los IDs del motion BLE NO están en ninguna fuente disponible (los sirve el servidor;
  viven en el cerebro) — los rangos observados son empíricos.

### Pendiente
- Verificación en vivo completa (config/header/withAck/apagado/export) en la próxima sesión
  con robot despierto.
- Botón "Tomar foto" en Fotos (photo_single) para cerrar la transferencia real.
- Watcher OTA (aviso cuando salga nueva versión) · Hermes real · C5 (gamificación/widget/…).
- Firmware del cerebro: proceso definido (descargar por MITM → parchear → rehashear con esptool
  → reinyectar por OTA o UART; sin firma → factible).
