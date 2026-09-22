# Spec: Subproyecto B — Rediseño UX de AibiPilot

Fecha: 2026-09-22
Estado: aprobado en brainstorming (pendiente revisión final del usuario)
Proyecto: AibiPilot (app alternativa para el robot AIBI Pocket)
Precede a: Subproyecto C — Features nuevas · D — Investigación firmware (paralelo)

## Objetivo

Rediseñar la interfaz completa de AibiPilot (estilo visual "Tech limpio",
navegación reorganizada en 5 destinos, pantalla de conexión tipo wizard,
estados de conexión claros) e incluir el pulido funcional que quedó aparcado
del subproyecto A. Sin features nuevas: esas son del subproyecto C.

## 1. Sistema visual — "Tech limpio"

- **Tema oscuro por defecto** con opción 3-way en ajustes: Sistema / Claro /
  Oscuro (sigue el sistema con toggle). El tema claro es una variante
  equivalente (mismos acentos, fondos claros).
- **Paleta** (del mockup elegido):
  - fondo: `#0E1621` · superficie: `#152435` · borde: `#2A3D55`
  - acento azul eléctrico: `#3AA0FF` · verde estado: `#19E3B1`
  - error: `#E34B3A` · warning: `#E3C419` · texto secundario: `#8B9BB4`
- **Tipografía**: sans para UI, mono (`FontFamily.Monospace`) para datos
  técnicos: MAC, MTU, versión, RSSI, log BLE, hex.
- **Componentes**: cards con bordes suaves (8-12dp), botón primario con acento,
  chips de estado (pill), snackbars y diálogos con los colores de arriba.
- Implementación: `MaterialTheme` con `darkColorScheme`/`lightColorScheme`
  custom en el tema; persistencia de preferencia en SharedPreferences.

## 2. Navegación — 5 destinos (IA 2)

| Destino | Contenido |
|---|---|
| **Inicio** | dashboard: estado del robot + accesos rápidos + escenas |
| **Chat IA** | chat con AIBI (destino propio) |
| **Hablar** | TTS + biblioteca de animaciones |
| **Jugar** | Ajedrez, Serpientes, Pirate Wars, Zero |
| **Herramientas** | sub-pestañas: Luces · Alarmas · Fotos · Log BLE |

- **Tablet** (>= 840dp): NavigationRail con los 5 destinos.
- **Teléfono**: bottom bar con los 5 destinos (NavigationBar).
- La **pantalla de conexión** (desconectado) es un **wizard** (opción C):
  paso 1 permisos → paso 2 "despertá tu robot" → escanear → lista de
  resultados; con tarjeta de **reconexión rápida** si hay robot guardado.
- Sin modo activo de conexión no se ven los 5 destinos (igual que hoy).

## 3. Estados y feedback

- **Header**: pill de estado de conexión persistente — `● CONECTADO` (verde),
  `◌ CONECTANDO…` (warning), `↻ RECONECTANDO (N/10)` (azul), `⚠ ROBOT DORMIDO`
  o `⚠ OTRA APP CONECTADA` (error) según `connHint`/diagnóstico.
- **Snackbar** para eventos transitorios: "Volumen ok", "Alarma agregada",
  "Foto guardada", errores de envío.
- **Diálogo** para errores graves accionables: "otra app lo tiene conectado
  (cerrala y reintentá)" y "robot dormido (despertalo)" al agotar reconexión.
- Estados vacíos: listas sin alarmas/fotos/dispositivos con texto guía.

## 4. Pantallas por destino

### Inicio (dashboard)
- Card robot: batería (label 1-4), pasos, monedas, comida, versión, MTU,
  pill de estado de conexión.
- Grid de accesos rápidos que EJECUTAN directo (no navegan): volumen,
  luces, ajedrez, fotos, alarma, baile, TTS.
- Cards de escenas: fiesta / despertar / relax / noche.
- Si `conn != CONNECTED`: banner "no conectado" → ir al wizard.

### Chat IA
- Reestilo del chat actual: burbujas usuario/assistant, indicador "pensando",
  soporte multilínea.
- Config de API key en diálogo: URL base, key, modelo, botón **Probar**
  (envía un ping a la API y muestra ok/error), hint de Ollama local
  (`http://<ip>:11434/v1/chat/completions`).

### Hablar
- Campo de texto TTS + botón micrófono (voz) + enviar.
- Animaciones en grid agrupado por categoría (Bailes / Canciones / Animales /
  Shows / Emociones) — catálogo ya existente en `Animations`.

### Jugar
- 4 cards grandes (icono + descripción corta). Dentro de cada juego:
  Entrar / Empezar / Jugar / Salir (ops ya verificadas en A).
- Sin tableros ni lógica de juego (backlog C).

### Herramientas (sub-pestañas)
- **Luces**: picker RGB (sliders), modos default/breath/color/flow,
  brillo, on/off, listado de luces si `light_list` trae más.
- **Alarmas**: selector de hora + **selector de tipo/tag 0..6** (7 opciones
  con etiqueta e icono — fix del aparcado de A), lista con eliminar, listar.
- **Fotos**: iniciar/detener sync (TCP 9090), modo foto (in/show/out),
  galería de fotos recibidas (`ui.photos`), botón abrir archivo.
- **Log BLE**: el actual (chips de filtro TX/RX/EVT/ERR/SYS, colores por
  categoría) con estilo mono del nuevo tema.

## 5. Pulido funcional (aparcado de A)

1. **Alarmas**: la UI ahora envía un tag real (selector 0..6) en vez de
   `ui.alarms.size`. Etiquetas del selector: buscar el significado real de
   cada tag en el decompilado oficial (`AlarmListAdapter` usa tag para
   elegir icono); si no hay evidencia clara, usar etiquetas genéricas
   (Tipo 1..6) y dejarlo ajustable.
2. **modeout**: `parseResponse` solo limpia `currentMode` si el evento
   `modeout` tiene `current == currentMode` (hoy el modeout del modo ANTERIOR
   limpia el modo actual y provoca un "in" redundante). El envío "in"
   redundante en el cambio de modo legítimo se mantiene como está.
3. **QA senior Menor 1**: fallo síncrono de `connectGatt` no deja la app
   esperando 12s — mover el `onState(false)` síncrono para que el ciclo
   siga de inmediato.
4. **QA senior Menor 2**: un solo diagnóstico de fallo (evitar doble scan de
   5s en timeout de connect de usuario).
5. **QA senior Menor 3**: `reconnectInFlight = false` también en el catch
   del job de reconexión.
6. **soTimeout** del servidor TCP de fotos: 10s → 30s (margen para gaps en
   transferencias grandes).

## 6. Fuera de alcance (backlog del subproyecto C)

- Rutinas programadas/cron, notificaciones, widget de escritorio.
- Consola JSON raw, sniffer BLE integrado, gamificación.
- Editor de escenas propio.
- UI completa de juegos (tableros, lógica).
- **Integración con Hermes Agent** (PC 24/7 del usuario, Nous Research):
  AibiPilot expone API HTTP local (hablar/animar/luces/alarmas/juegos/estado)
  con token LAN + foreground service para MIUI; Hermes se conecta vía MCP o
  scripts RPC → control del robot desde Telegram/HA/cron. Se diseña en C.

## Definición de "done" del subproyecto

1. Los 5 destinos + wizard de conexión en tablet (rail) y teléfono (bottom bar).
2. Tema oscuro por defecto + toggle Sistema/Claro/Oscuro persistente.
3. Estados: pill en header, snackbar transitorio, diálogo para errores graves.
4. Pulido aparcado de A aplicado (6 items de la sección 5).
5. Sin regresiones: todo lo verificado en A sigue funcionando en vivo.
6. Verificación en vivo con el robot (una acción por función con ACK) +
   revisión visual en ambos form factors.
7. Commits limpios, AGENTS.md y docs/PROGRESO.md actualizados.

## Gate de calidad por tarea

```bash
cd ~/aibi/AibiPilot
JAVA_HOME=~/jdk17 ./gradlew :app:assembleDebug
```

## Riesgos conocidos

- El rediseño toca `Screens.kt` (1106 líneas) en casi toda su extensión:
  partir el archivo por pantalla si crece demasiado (seguir el criterio de
  "archivos enfocados" solo donde no complique los diffs).
- No romper el contrato `UiState`/`RobotViewModel` que usa la capa BLE:
  los cambios de estado visual no deben alterar la máquina de reconexión.
- El tema claro es derivado: prioridad al oscuro (por defecto), el claro se
  valida visualmente pero no define decisiones.
