# AibiPilot

App Android alternativa (Kotlin + Jetpack Compose) para controlar el robot **AIBI Pocket** por BLE, con protocolo recuperado por ingeniería inversa.

## Descargo de responsabilidad

- **Proyecto independiente y no oficial. No está afiliado, respaldado ni relacionado con Living.AI / LIVING TECHNOLOGY CO., LTD.**
- "AIBI" y "AIBI Pocket" son marcas de sus respectivos dueños; este proyecto las menciona solo con fines de identificación e interoperabilidad.
- Desarrollado con **fines educativos y de interoperabilidad con hardware propio**. No se distribuye firmware ni contenido propietario de Living.AI.
- Usá esta app **solo con robots y redes de tu propiedad**.

## Funcionalidades

- Conexión BLE y protocolo `BB AA + len + JSON` (servicio `0000ffe0`, característica `0000ffe1`)
- Luces y animaciones, alarmas (tags 0–6), juegos (chess/snake/pirate/zero), fotos (servidor TCP 9090), TTS
- Ajustes del robot (idioma, volumen, wake model, quiet list, horarios…), configuración WiFi
- Chat con LLM (endpoint OpenAI-compatible, configurable), escenas
- Rutinas programadas (WorkManager) y modo remoto con API HTTP local (token Bearer)
- Dashboard, herramientas, laboratorio y log BLE con filtros

## Estado

Todo lo implementado fue **verificado en vivo** contra el robot real (septiembre–octubre 2026). El proyecto está en desarrollo activo; hay bugs conocidos y roadmap documentados en `docs/` (ver `docs/HANDOFF-OPENCODE.md`).

## Documentación

- `AGENTS.md` — datos técnicos clave del protocolo y estado del proyecto
- `docs/` — investigación de firmware, banco de captura esp32-spy, integración Hermes, diseño UX

## Firmware e investigación

El firmware de contenido del robot (bundle SD) se descarga de CDNs públicos de Living.AI. Cómo se descubrió y capturó (banco esp32-spy, DNS/relay, análisis de los binarios ESP32-S3 y STM32) está documentado en `docs/FIRMWARE-RESEARCH.md`. Este repositorio **documenta el método de obtención pero no redistribuye binarios ni contenido propietario**.

## Compilar

Requisitos: JDK 17, Android SDK (platform-35, build-tools 35.0.0).

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Contribuir

Este proyecto fue desarrollado **con ayuda de IA**. Toda persona que quiera mejorarlo es bienvenida: abrí issues, proponé mejoras o mandá pull requests.

## Licencia

[MIT](LICENSE)
