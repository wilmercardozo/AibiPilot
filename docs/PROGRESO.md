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
