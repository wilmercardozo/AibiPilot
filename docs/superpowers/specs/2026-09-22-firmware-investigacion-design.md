# Spec: Subproyecto D — Investigación de firmware del AIBI Pocket

Fecha: 2026-09-22
Estado: aprobado en brainstorming (pendiente revisión final del usuario)
Proyecto: AibiPilot — investigación (sin código de app, sin tocar el robot)

## Objetivo

Determinar la viabilidad de construir y flashear firmware propio para el robot
AIBI Pocket, mediante investigación estructurada por capas, con evidencia
documentada y un veredicto accionable. Sin manipular el hardware.

## Alcance (solo investigación)

- **Sí**: consultar la API pública de OTA (`api.aibipocket.com`), descargar y
  analizar el binario del firmware, analizar la app oficial decompilada
  (`/tmp/aibi/jadx_out` — volátil, re-extraer si se perdió), fuentes
  públicas de hardware (FCC, teardowns, datasheets), evaluación de canales de
  debug (BLE `DD CC`, UART probable, bootloader).
- **No**: abrir/flashear/tocar el robot, escribir código de app, comprar
  hardware, contactar al fabricante.
- Consideraciones legales: interoperabilidad del software en hardware propio;
  el documento debe mencionar brevemente el contexto legal y NO promover
  elusión de DRM de terceros.

## Entregable

`docs/FIRMWARE-RESEARCH.md` commiteado en el repo, con:
1. Hallazgos por capa con evidencia (respuestas de API guardadas, hashes,
   fuentes con URL).
2. Veredicto explícito: ¿se puede construir/flashear firmware propio?
   (sí / no / con qué limitaciones) y qué se necesitaría para dar el paso.
3. Riesgo de brick y mitigaciones.
4. Caminos accionables priorizados.

## Capas (secuenciales, con veredicto temprano)

### Capa 1 — API de OTA
- Mapear `api.aibipocket.com`: endpoints de versión/firmware/update (base:
  `network/ApiService.java` y `RetrofitClient.java` del decompilado, y el flujo
  `setting_req op:"update"` de la app oficial), parámetros, headers, formato de
  respuesta, URLs de descarga del firmware.
- Entregar: lista de endpoints documentados + capturas/respuestas reales de la
  API consultada desde la workstation.

### Capa 2 — Binario del firmware (la pregunta crítica)
- Descargar el firmware (desde la API mapeada en capa 1).
- Identificar: formato (encabezado mágico, tamaño, checksum), estructura
  (particiones, compresión), arquitectura objetivo, strings relevantes
  (comandos, certificados, claves, rutas).
- **Responder: ¿está firmado y/o encriptado?** (certificados, cabeceras de
  firma, verificación de la app al aplicar el OTA). Este es el go/no-go
  principal: si la firma es verificada por el bootloader sin vía de desbloqueo,
  el veredicto se acorta y las capas 3-4 se documentan como contexto.

### Capa 3 — Hardware (solo fuentes públicas)
- SoC/MCU del AIBI Pocket según teardowns, FCC ID y datasheets.
- Pines/puertos probables (UART/JTAG/SWD), botón de modo, cómo llega el
  firmware (NAND/SPI flash interno o externo).
- Implicaciones para flasheo directo (¿se puede flashear por UART o solo por
  el bootloader de fábrica?).

### Capa 4 — Canales de debug
- El mensaje binario `DD CC` (sin consumidor en la app oficial): qué podría ser
  (telemetría, debug, audio) y cómo sondearlo sin riesgo.
- UART/bootloader según capa 3; qué acceso daría cada canal.

## Equipo

- Rol `research` por capa (agentes de investigación, sin código):
  - Capa 1 y 3: tier rápido (flash) — mecánicas.
  - Capa 2: tier fuerte (pro) — análisis de binario, la decisión crítica.
  - Capa 4 y síntesis del documento: tier fuerte (pro) — integrar hallazgos y
    redactar el veredicto.
- Orquestador: revisa evidencia de cada capa y la síntesis final; sin QA de
  código (no hay diffs de app; la "QA" es la verificación de evidencia y
  fuentes, que hace el orquestador con apoyo del reporte de cada capa).

## Definición de "done"

1. `docs/FIRMWARE-RESEARCH.md` commiteado con las 4 capas completas (o
   documentando el corte temprano si la capa 2 da un no-go firme).
2. Veredicto explícito de viabilidad + qué se necesitaría para el siguiente
   paso real (ej. "probar UART con X", "el firmware está firmado: no viable sin
   encontrar vía en el bootloader").
3. Evidencia citada: URLs, hashes, respuestas crudas anexadas al documento.
4. Riesgo de brick + nota legal breve.

## Gate de calidad

- Investigación: cada capa entrega un reporte con evidencia verificable
  (capturas/archivos en `.metodologia/firmware-d/` durante la ejecución; lo que
  debe sobrevivir va al documento final).
- El repo debe seguir compilando (no se toca código; verificación trivial).

## Riesgos conocidos

- El firmware puede estar firmado → el resultado es "no viable" hoy; igual es
  un hallazgo valioso (cierra la puerta con evidencia).
- La API puede requerir headers/firmas de app; si la descarga falla, se
  documenta y se evalúa la captura desde el robot (requiere su WiFi — heredado
  de A/B/C).
- Las fuentes de hardware público pueden ser escasas para este robot
  (fabricante chico); se documenta el gap.
- El decompilado vive en `/tmp` (se pierde al reiniciar): re-extraer del XAPK
  en `~/aibi/AIBI+Pocket_1.7.0_APKPure.xapk` si hace falta.
