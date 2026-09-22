# Subproyecto D — Investigación de firmware: Implementation Plan

> **For agentic workers:** La ejecución sigue el protocolo de la metodología Tita Media
> (briefs por tarea, reportes por tarea, revisión del orquestador, síntesis final). Los
> checkboxes marcan pasos de cada tarea. El orquestador despacha un agente por tarea con el
> brief en `.metodologia/firmware-d/briefs/task-N.md`.

**Goal:** Determinar la viabilidad de construir/flashear firmware propio para el AIBI Pocket,
con evidencia documentada y un veredicto accionable en `docs/FIRMWARE-RESEARCH.md`.

**Architecture:** Investigación secuencial por capas (OTA → binario → hardware → debug →
síntesis). Cada capa entrega un reporte con evidencia en `.metodologia/firmware-d/evidence/`;
la síntesis integra todo en el documento final del repo.

**Tech Stack:** curl, hexdump/file/strings, jadx/apktool (si siguen en /tmp), webfetch
para fuentes públicas. Sin código de app.

## Global Constraints

- Fuente de verdad: spec `docs/superpowers/specs/2026-09-22-firmware-investigacion-design.md`.
- **Solo investigación**: sin abrir/flashear/tocar el robot, sin código de app.
- Toda afirmación con evidencia: URL, hash, o respuesta cruda guardada en
  `.metodologia/firmware-d/evidence/` (workspace; lo que debe sobrevivir va al doc final).
- No promover elusión de DRM de terceros; nota legal breve en el documento final.
- El decompilado vive en `/tmp/aibi/jadx_out` (volátil). Si no existe: re-extraer del
  XAPK `~/aibi/AIBI+Pocket_1.7.0_APKPure.xapk` (apktool + jadx; si las herramientas
  tampoco existen → NEEDS_CONTEXT).
- Base del plan: commit `2c1b5b2`. Repo: `~/aibi/AibiPilot`, branch `main`.

---

### Task 1: Capa 1 — API de OTA (research, rápido)

**Files:**
- Reporte: `.metodologia/firmware-d/reports/task-1-research.md`
- Evidencia: `.metodologia/firmware-d/evidence/api/` (respuestas crudas)

- [ ] **Step 1: Verificar fuentes locales** — `ls /tmp/aibi/jadx_out/sources/ai/living/aibi/network/`
  y las herramientas (`/tmp/jadx/bin/jadx`, `/tmp/bin/apktool`). Si falta el
  decompilado o las herramientas → NEEDS_CONTEXT con qué falta.

- [ ] **Step 2: Leer el código de red de la app oficial** — `ApiService.java`, `RetrofitClient.java`
  y `grep -rn "aibipocket" /tmp/aibi/jadx_out/sources/` para encontrar la base URL,
  endpoints (versión, update, firmware), headers y parámetros del flujo OTA
  (`setting_req op:"update"` en el lado BLE ya conocido).

- [ ] **Step 3: Consultar la API real** — con curl desde la workstation, documentar respuesta
  (status, body) de cada endpoint mapeado (ej. versión actual del firmware, endpoint de
  descarga). Guardar bodies crudos en `evidence/api/`.

- [ ] **Step 4: Escribir el reporte** — endpoints documentados (método, params, headers, ejemplo
  de respuesta), URL(s) de descarga del firmware encontradas, y cualquier requisito de auth
  (firma de requests, tokens fijos).

- [ ] **Step 5: Respondé solo con la línea de Estado en el chat.** (Sin commits: el workspace no
  se commitea; el documento final se commitea en Task 5.)

### Task 2: Capa 2 — Binario del firmware (research, fuerte)

**Files:**
- Reporte: `.metodologia/firmware-d/reports/task-2-research.md`
- Evidencia: `.metodologia/firmware-d/evidence/firmware/` (binario + hashes + strings)

- [ ] **Step 1: Descargar el firmware** — usar la(s) URL(s) de Task 1 (curl, con los headers que
  Task 1 haya mapeado). Guardar el binario en `evidence/firmware/` y calcular
  `sha256sum` + `file` + `ls -l`.

- [ ] **Step 2: Formato** — `xxd -l 256` del encabezado: magic bytes, tamaño declarado,
  checksums; `binwalk` si está disponible (si no, `strings` + análisis manual de offsets).
  Identificar: ¿imagen completa?, ¿particiones?, ¿compresión (gzip/lzma/zstd) o plano?

- [ ] **Step 3: Strings y criptografía** — `strings -n 6` y buscar: `-----BEGIN CERTIFICATE`,
  `rsa|ecdsa|sha256|signature|verify`, rutas (`/dev/`, `mtd`, `uboot`, `bootloader`), comandos
  AT, URLs, nombres de partición. Extraer el certificado si aparece (openssl x509 -text para
  issuer/subject).

- [ ] **Step 4: La pregunta crítica — ¿firmado/encriptado?** — evidencia directa (cabecera de
  firma, cert embebido, verificador en la app) e indirecta (¿la app valida hash/firma antes de
  mandar la OTA? ¿hay partición de keys?). Veredicto preliminar: firma verificada por
  bootloader sin vía de desbloqueo = no-go; firma ausente o en app = viable (con riesgos).

- [ ] **Step 5: Escribir el reporte** con: hash, formato, hallazgos de strings, y el veredicto
  de firma con la evidencia exacta. Respondé solo con la línea de Estado.

### Task 3: Capa 3 — Hardware público (research, rápido)

**Files:**
- Reporte: `.metodologia/firmware-d/reports/task-3-research.md`
- Evidencia: `.metodologia/firmware-d/evidence/hardware/` (URLs y notas)

- [ ] **Step 1: Identificar fabricante/modelo/FCC** — Living.AI, "AIBI Pocket", buscar FCC ID
  (fccid.io / apps.fcc.gov) y teardowns (búsquedas web: "AIBI Pocket teardown", "AIBI robot
  disassembly", foros). Anotar cada fuente con URL.

- [ ] **Step 2: SoC/MCU y memoria** — qué chip corre el robot (ESP32 / BLE SoC / MCU ARM),
  según teardown, FCC photos internos (la FCC publica fotos internas para algunos dispositivos),
  datasheets del chip si se identifica.

- [ ] **Step 3: Pines/puertos** — UART/SWD/JTAG probables (pads de prueba en fotos de teardown),
  botón de modo/boot, tipo de flash (interna SPI vs externa).

- [ ] **Step 4: Escribir el reporte** — modelo de chip (o "no identificado" con lo más cercano),
  implicaciones de flasheo (¿bootloader con recovery? ¿UART expuesto?), gaps de información.
  Respondé solo con la línea de Estado.

### Task 4: Capa 4 — Canales de debug (research, fuerte)

**Files:**
- Reporte: `.metodologia/firmware-d/reports/task-4-research.md`

- [ ] **Step 1: El canal `DD CC`** — en el decompilado oficial: buscar dónde se parsea/emite
  `DD CC` (`BleKt`, `BleUtils`, clases de mensajes binarios). Determinar qué es (telemetría,
  audio, estado) y si la app lo usa o lo descarta. Inferir cómo sondearlo sin riesgo (enviar
  comandos conocidos por BLE y registrar frames `DD CC` — propuesta de método, sin ejecutarlo).

- [ ] **Step 2: UART/bootloader** — con los hallazgos de Task 3: qué daría acceso al UART
  (consola de bootloader? shell?), si el chip tiene modo recovery documentado en el datasheet.

- [ ] **Step 3: Escribir el reporte** — por cada canal: qué es, qué acceso da, qué se
  necesitaría para sondearlo (hardware/permisos), y cuál es el siguiente paso real más barato.
  Respondé solo con la línea de Estado.

### Task 5: Síntesis — docs/FIRMWARE-RESEARCH.md (research, fuerte)

**Files:**
- Create: `docs/FIRMWARE-RESEARCH.md` (commit en el repo)
- Reporte: `.metodologia/firmware-d/reports/task-5-research.md`

- [ ] **Step 1: Integrar** — leer los 4 reportes + evidencia y redactar `docs/FIRMWARE-RESEARCH.md`
  con: hallazgos por capa (con URLs/hashes), **veredicto explícito de viabilidad** (sí / no /
  con qué limitaciones), lo que se necesitaría para el siguiente paso real, riesgo de brick y
  mitigaciones, y nota legal breve de interoperabilidad.

- [ ] **Step 2: Commit** —

```bash
git add docs/FIRMWARE-RESEARCH.md
git commit -m "docs(firmware): investigación de viabilidad de firmware propio (D)"
```

- [ ] **Step 3: Respondé solo con la línea de Estado.**

**Checkpoint del orquestador:** resumir el documento al usuario y esperar su OK antes de cerrar
el plan. Después: docs de cierre (AGENTS/PROGRESO/MEMORIA) + limpieza del workspace.
