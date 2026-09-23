# Investigación de viabilidad: firmware propio para el AIBI Pocket

Fecha: 2026-09-22 · Subproyecto D · Alcance: solo investigación (sin abrir ni tocar el robot, sin código de app)
Evidencia cruda: `.metodologia/firmware-d/evidence/` (api/, firmware/, hardware/, debug/) y reportes por capa en `.metodologia/firmware-d/reports/`

## Veredicto

**Hoy NO es viable construir firmware propio para el AIBI Pocket con lo que se pudo obtener,
pero el hardware no cierra la puerta y hay un camino concreto de 2 pasos para resolverlo.**

El obstáculo no es el chip (es un Espressif de la familia ESP32, flasheable de forma rutinaria
con esptool si se accede al UART), sino dos incógnitas que hoy no se pueden responder sin el
binario o sin abrir el robot:

1. **El binario del firmware no es accesible por ninguna vía pública** (la API de OTA no
   entrega URL; el CDN no lista y el nombre no es adivinable). Sin binario no se puede saber
   si está firmado/encriptado, ni si el bootloader verifica firma.
2. **El estado de los eFuses del ESP32 es desconocido** (secure boot / flash encryption /
   UART_DOWNLOAD_DIS). Si Living.AI quemó los eFuses de seguridad, un firmware propio no
   arrancaría; si no, el reemplazo es rutinario y el brick siempre recuperable por el modo
   download de la ROM.

Precisión importante: "no demostrable aún" **no** es "imposible". La familia ESP32 es abierta,
con toolchain, esptool y modo download documentados; Living.AI es un fabricante chico de
juguetes y en ese segmento la firma de firmware suele ser ausente o trivial. Falta la
evidencia para afirmarlo, no hay evidencia de lo contrario.

---

## Hallazgos por capa

### Capa 1 — API de OTA (`api.aibipocket.com`): sin auth, escalera de versiones, sin URL de firmware

Todos los endpoints responden **sin autenticación** (curl desde workstation, sin headers ni
tokens → 200). Evidencia: `.metodologia/firmware-d/evidence/api/` (respuestas crudas).

- `GET /aibiapp/app/checkupdate` — actualización de la **app**. Respuesta real:
  ```json
  {"responsetag":"checkupdate","androidNumber":48,"androidVersion":"1.7.0",
   "androidUrl":"https://download.livingai.cn/app/aibi_pocket_1_7_0.apk",
   "server":"us","iosNumber":4,"iosVersion":"1.7.0"}
  ```
- `POST /aibiapp/ota/checkupdate` — firmware del **robot**. Body:
  `{"currentName":"<nombre>","currentVersion":<int>,"deviceID":"<MAC sin ':'>"}`.
  Escalera completa verificada en vivo: `0→1.0.1 (1)`, `1→1.1.0 (2)`, `2→1.2.0 (3)`,
  `3→1.2.1 (4)`, `4→1.3.0 (5)`, `5→1.4.0 (6)`, `6→1.4.1 (7)`, `7→1.5.0 (8)`,
  `8→1.6.0 (9)`, `9→1.7.0 (10)`, `≥10 → isUpdate:0`. **Último firmware: 1.7.0 (versionNum 10).**
  La respuesta es siempre `{isUpdate,versionName,versionNum}` — **sin campo URL**.
- `POST /aibiapp/support/testpage` — página de debug por deviceID (ver Capa 4).
- `GET /` → `{"name":"api.living.ai"}`.

**Flujo OTA real** (decompilado `ai.living.aibi` v1.7.0): la app pide versión por BLE
(`sta_req query[1]`), consulta `ota/checkupdate`, y si hay update solo envía por BLE
`setting_req {"op":"update"}` (`BleSettingsRequest.settingsUpdate()`). **El robot descarga y
aplica el firmware por su propio WiFi. La app jamás ve la URL ni el binario.** La URL vive
dentro del firmware del robot (o de su canal WiFi); capturarla exige un AP/PCAP entre el
robot y el router.

**Conclusión de capa: la API no expone el firmware. Sin URL → sin descarga directa (Capa 2).**

### Capa 2 — Binario del firmware: no accesible públicamente; la app no verifica nada

Evidencia: `.metodologia/firmware-d/evidence/firmware/` (probes.log, respuestas crudas de OTA).

- **URLs de firmware probadas: 117 paths únicos en `download.livingai.cn` → 100 % 404.**
  El dominio es un bucket Aliyun OSS (`ch-living-res.oss-cn-shenzhen.aliyuncs.com`)
  public-read por objeto pero **sin listado** (`?list-type=2` → 403 AccessDenied). Los APK
  sí existen (`/app/aibi_pocket_{1_7_0,1_6_0,1_0_0}.apk` → 200); el firmware, si está, usa un
  nombre no adivinable. Enumeración externa agotada: Wayback Machine solo capturó la raíz
  (403), Common Crawl 0 capturas, Sourcegraph 0 referencias.
- **La app no contiene ninguna URL de firmware** (`strings` de classes.dex/resources.arsc/
  assets del APK 1.7.0 y del APK 1.0.0 → 0 coincidencias). El APK 1.0.0 se descargó del CDN:
  185304946 bytes, sha256 `04e2bd03a281bf92981e5ae7d91be82046057147c45b1b77e56b722fccb2ecee`.
- **La app no descarga ni verifica el binario** (no puede: solo dispara `op:"update"` por BLE).
  No hay código de hash/firma en el decompilado. **Cualquier verificación de integridad vive
  en el robot** (bootloader/agente OTA), inobservable desde la app. El canal BLE no participa
  de la transferencia → no se puede observar ni inyectar el binario por BLE.

**Conclusión de capa (la pregunta crítica): ¿firmado/encriptado? — sin evidencia directa
(no hay binario).** Por el segmento de mercado (juguetería de Shenzhen) es habitual binario
plano por HTTP con firma ausente o trivial, pero no es demostrable sin el binario.

### Capa 3 — Hardware: ESP32 confirmado (OUI de MAC + herramienta RF de la FCC); eFuses = incógnita crítica

Evidencia: `.metodologia/firmware-d/evidence/hardware/sources.md` (URLs por hallazgo).

- **SoC = Espressif (familia ESP32), modelo exacto no identificado.** Confirmado por:
  - MAC BLE del robot `B4:3A:45:AA:BB:CC` → OUI **B4:3A:45 = Espressif Inc.** (MA-L,
    verificado con api.macvendors.com y api.maclookup.app).
  - El test report FCC del AIBI (FCC ID **2AZ6R-AIBI**, project **ZKT-24092512242E-2**,
    Living Technology (Shenzhen) Co., Ltd.) usa **"EspRFTestTool_v2.6"** — la herramienta de
    certificación RF de Espressif → el chip bajo test es Espressif.
  - El EMO (producto hermano del mismo fabricante) usa ESP32 confirmado por la comunidad
    (https://forums.living.ai/t/ive-been-curious-about-emos-cpu-information/952), con números
    de parte ocultos en las fotos FCC — práctica probable también en AIBI.
- Radio: WiFi 2.4GHz-only (802.11b/g/n HT20/HT40) + BLE → candidatos: ESP32 (clásico),
  ESP32-C2/C3, ESP32-C6. Batería LiPo 1S 3.8V 350mAh/1.33Wh, carga USB 5V 1A, antena chip
  CA-C03 4.3dBi.
- **Pines/puertos: desconocidos** (sin evidencia pública de pads UART/SWD/JTAG ni botón de
  boot). Existen 19 fotos internas FCC (exhibit 7761408) y un video de desarme de la comunidad
  (https://www.youtube.com/watch?v=kxP7-glSbnE) pendientes de análisis visual.
- Implicaciones de flasheo: si hay acceso UART0, el reemplazo de firmware es **rutinario con
  esptool.py** (flash SPI del módulo) — el hardware no cierra el camino. **La incógnita
  crítica son los eFuses**: secure boot y flash encryption son opcionales en Espressif y no
  hay evidencia pública de si Living.AI los activó. Si están quemados, un firmware propio no
  arranca; si no, todo el flujo (dump → análisis → reflasheo → recovery) es estándar.

### Capa 4 — Canales de debug: `DD CC` por BLE (sin abrir) y UART/bootloader (abrir)

Evidencia: `.metodologia/firmware-d/evidence/debug/` (testpage probes, rutas del decompilado).

**Canal BLE `DD CC` — existe y nadie lo consume:**
- `BleUtils.java:85-117`: el RX de la app oficial distingue `BB AA` (frame JSON, reensamblado
  por longitud LE) de `DD CC` (frame binario, posteado crudo como `BleMessageEvent`).
- Búsqueda exhaustiva en todo el decompilado: **`BleMessageEvent` tiene 0 observadores**
  (frente a los 11 `observeForever` de `BleJsonEvent`). → La app oficial **recibe y descarta**
  los `DD CC`. Es un canal binario robot→app sin consumidor. No es fotos (TCP 9090), ni TTS
  (lo genera el robot), ni comandos (van por JSON). Hipótesis: telemetría (IMU/motores),
  respuestas de comandos de test o logs del firmware.
- El único frame TX binario de toda la app es `55 AA 55 AA 21 <n> 0x00×13 ED` (`BleUtils.
  motion(int)`), emitido solo por la pantalla de debug oculta (`DeviceDebugFragment.java:119`),
  cuyos botones sirve el servidor vía `POST /aibiapp/support/testpage` si `code==200`. Probe
  real con nuestro MAC (`b43a45a0cf6a`): responde `{"code":0,"errmessage":"DeviceDebug",...}`
  — formato válido pero **no allowlisteado** → no hay cmd oficiales; habría que barrerlos a ciegas.
  `Protocol.kt:98-110` de AibiPilot ya reproduce el frame (`Protocol.motion(cmd)`).
- **Sondeo propuesto** (sin ejecutar): fase pasiva — ejecutar los flujos conocidos (handshake,
  `*_in`, volumen, juegos, reposo) y registrar hexdumps de `DD CC` (AibiPilot ya los loguea en
  Log BLE); fase activa — `Protocol.motion(cmd)` barriendo 0..255 de a uno con pausa 1-2 s,
  empezando por 0..15. Coste: cero hardware.

### Sondeo DD CC EN VIVO (22 sep 2026) — resultado y advertencia

Ejecutado con el robot real (AIBI-CF6A, fw 1.7.0) usando el barrido integrado en AibiPilot:

- **Fase pasiva**: flujos normales (handshake, TTS, luz, volumen, dance, chess in/start) →
  **cero frames `DD CC`**. El robot no emite binario espontáneo en uso normal.
- **Fase activa (motion 0..31)**: cero respuestas `DD CC` y cero respuestas JSON.
  **PERO** al enviar los comandos **24-26 (0x18-0x1A)** el robot **entró en "diskmode"**
  (modo disco USB de fábrica, mostrado en su pantalla) y **cortó el BLE inmediatamente**
  (`conn state: 8/0`). Los barridos posteriores (32-159) cayeron al vacío (robot sin BLE).
- **diskmode**: sin referencia en la app oficial (no lo usa). Al conectarlo por USB a la PC a
  través de la base de carga, **NO aparece ningún dispositivo USB** (la base es solo
  alimentación, sin líneas de datos) y **el robot no tiene puerto USB ni botón físico**.
  Salida del modo: solo agotando la batería (o el posible touch long-press / timeout).
- **⚠ ADVERTENCIA**: NO barrer `motion` a ciegas en este robot: los cmds 0x18-0x1A activan
  diskmode, que mata el BLE y NO tiene salida por software ni botón (recuperación = batería
  agotada). El barrido quedó implementado en AibiPilot (Laboratorio) pero debe usarse con
  rangos informados, no a ciegas.
- Conclusión del sondeo: el canal `DD CC` no respondió en 0..31; el hallazgo real fue el
  comando de diskmode. Quedan sin sondear 32..255 (con las reservas de arriba) y la hipótesis
  de que `DD CC` solo emita ante comandos específicos de fábrica.

**UART/bootloader — resolvería todo, requiere abrir el robot:**
- Todos los ESP32 arrancan por UART0 a 115200; con GPIO0 a GND durante el reset entran a
  **modo download**. Con pads UART0/GPIO0/EN expuestos y un adaptador USB-UART 3.3V:
  - Consola de bootloader → banner de la ROM, modelo exacto de chip, tabla de particiones,
    banner del firmware (versión/commit) — solo lectura, sin riesgo.
  - Modo download + esptool.py → **volcado completo de la flash** (cierra el gap del binario
    de la Capa 2), **`espefuse.py summary`** (resuelve la incógnita crítica de eFuses de la
    Capa 3: secure boot / flash encryption / UART_DOWNLOAD_DIS / JTAG disable) y, si no hay
    eFuses de seguridad, reflasheo de una imagen propia.
- Sin evidencia pública de pads expuestos (gap de Capa 3, requiere análisis visual) y fuera
  del alcance actual ("no tocar el robot").

---

## Qué se necesitaría para el siguiente paso real (priorizado)

1. **Sondear `DD CC` por BLE con AibiPilot (gratis, sin abrir el robot).** Fase pasiva
   primero (log del tráfico durante flujos conocidos), después fase activa barriendo
   `Protocol.motion(cmd)`. Riesgo mínimo; entrega contenido del canal binario, y quizá una
   pista sobre el firmware (versión, comandos de fábrica, log UART espejado).
2. **Capturar el WiFi del robot durante una OTA** (requiere robot con WiFi propio + AP/PCAP:
   hostapd+dnsmasq en la tablet/PC con tcpdump, o mirror/ARP spoof en la LAN del robot).
   Entrega: la URL real del firmware, el binario, y la observación de si hay intercambio de
   firma/checksum en el flujo. Es el paso definitivo y más barato para la pregunta de firma.
3. **Abrir el robot → UART → `espefuse.py summary` + dump** (resuelve todo de un golpe:
   binario, eFuses, particiones). **Fuera del alcance actual** por la regla "no tocar el
   robot"; documentado como vía futura.

---

## Riesgo de brick y mitigaciones

- **Flashing por UART (esptool): riesgo moderado, recuperable** — el ROM bootloader no se
  borra; el **modo download** (GPIO0 bajo + reset) permite reflashear siempre que no esté
  quemado el eFuse **UART_DOWNLOAD_DIS** (desconocido hoy). Con UART_DOWNLOAD_DIS quemado
  quedarían JTAG (solo en variantes con JTAG y pads expuestos) o extracción física de la
  flash SPI externa.
- **Flash encryption activa**: dump ilegible (y con DISABLE_DL_DECRYPT ni siquiera eso);
  firmware propio no arranca en la práctica.
- **Secure boot activo**: el bootloader rechaza imágenes no firmadas con la clave privada de
  Living.AI → firmware propio no arranca; el dump sigue valiendo como artefacto de análisis.
- **Sondeo BLE (`DD CC` / motion cmd): riesgo mínimo.** No introduce primitivas nuevas (es
  el comportamiento del botón de debug oficial); mitigación si algún `cmd` dispara un test
  raro: resetear el robot con el botón de encendido (apagado/encendido recupera siempre,
  verificado en los subproyectos A/B/C).
- El robot se auto-actualiza por WiFi: cualquier experimento de red debe evitar que el robot
  tome una OTA durante la prueba (sin `op:"update"` no la dispara).

---

## Nota legal (breve)

El objetivo es interoperabilidad de software en **hardware propio**: el usuario es dueño del
robot y puede legítimamente analizarlo, volcar su flash y ejecutar software propio en él
(marco de interoperabilidad de software). Este documento **no** promueve eludir DRM de
terceros, redistribuir el firmware de Living.AI ni usar material con copyright de la app
oficial más allá del análisis técnico aquí citado. Si los eFuses de seguridad o un mecanismo
de firma protegido resultaran activos, la vía correcta sería la compatibilidad con el
protocolo existente (lo que ya hace AibiPilot) y no la elusión.

---

## Resumen por capa

| Capa | Hallazgo | Estado |
|------|----------|--------|
| 1. API OTA | Sin auth; escalera 1→10 (=1.7.0); **sin URL de firmware** | Cerrada con evidencia |
| 2. Binario | **No accesible públicamente** (117 paths → 404; bucket sin listado); la app no descarga ni verifica nada | Gap crítico |
| 3. Hardware | **ESP32 confirmado** (OUI MAC + EspRFTestTool FCC); modelo exacto, pads y **eFuses desconocidos** | Incógnita crítica |
| 4. Debug | `DD CC` sin consumidor → sondeable por BLE gratis; UART resolvería todo (requiere abrir) | Camino abierto |

**Veredicto final:** firmware propio no viable hoy por falta de binario y de estado de
eFuses; el hardware (ESP32) no lo impide y existe un camino de 2 pasos (sondear `DD CC` por
BLE, capturar la OTA por WiFi) más uno fuera de alcance (UART) que resuelve todas las
incógnitas. "No demostrable aún" ≠ "imposible".
