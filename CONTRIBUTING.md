# Contribuir

Gracias por querer mejorar AibiPilot. Toda contribución es bienvenida (issues, ideas, pull requests).

## Reglas para pull requests

Para mantener el proyecto seguro y legible, los PRs deben cumplir:

- **Nada malicioso**: sin backdoors, sin recolección de datos, sin llamadas a servidores externos no documentadas, sin código que degrade el robot o el dispositivo del usuario.
- **Sin binarios ni ofuscación**: no incluir APKs, .jar, .so, binarios de firmware ni archivos compilados; solo código fuente legible. Si necesitás un recurso (audio, imagen), explicá por qué y su origen.
- **Cambios explicados**: describí qué hace tu cambio, cómo lo probaste y qué problema resuelve.
- **Un PR = un cambio**: cambios acotados y revisables. Split en varios PRs si es grande.
- **Nada de secretos**: claves, tokens y contraseñas van en variables locales (p. ej. `secrets.h` en esp32-spy), nunca en el código ni en commits.

## Proceso

- Todo PR requiere **revisión y aprobación del mantenedor** (branch protection + CODEOWNERS) antes de mergear.
- El repo usa Secret Scanning con push protection: GitHub rechaza automáticamente commits con tokens o claves.
- Si encontrás un problema de seguridad, seguí `SECURITY.md` en vez de abrir un issue público.

El proyecto fue desarrollado con ayuda de IA; podés usarla también para tus contribuciones, pero revisá siempre el resultado.
