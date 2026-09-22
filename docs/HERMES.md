# Integración con Hermes Agent — control del robot AIBI desde Telegram/HA/cron

El modo remoto de AibiPilot expone una API HTTP local (LAN) que traduce requests
JSON a comandos BLE del robot. Con un **tool HTTP** Hermes Agent puede controlar
el robot desde Telegram, Home Assistant o un cron.

## 1. Activar el modo remoto

1. Conectá el robot desde la app (flujo normal).
2. Herramientas → pestaña **Remoto** → **Configurar**:
   - **Token de acceso**: un secreto cualquiera (ej. `TU_TOKEN`). Obligatorio.
   - **Puerto**: default `8080`.
3. Activá el toggle **Modo remoto**. La app pasa la conexión BLE a un servicio
   de fondo (notificación persistente "Modo remoto activo") y levanta el
   servidor HTTP. La app en foreground deja de manejar la conexión.

> Notas: sin token configurado el servidor no arranca y la app te avisa por
> notificación. El robot acepta UNA sola conexión BLE: con el modo remoto activo
> la conexión es del servicio. En MIUI/HyperOS conviene habilitar autostart de
> la app para que el servicio sobreviva en background (limitación documentada).

## 2. Obtener la IP de la tablet

La API escucha en `0.0.0.0:<puerto>`, así que sirve cualquier IP de la tablet en
tu WiFi local. Para verla:

- En la tablet: Ajustes → Wi-Fi → tu red → detalles (o `adb shell ip addr show wlan0`).
- El robot y la tablet deben estar en la misma red que la máquina donde corre Hermes.

Prueba rápida desde la máquina de Hermes (o desde la tablet vía
`adb forward tcp:8080 tcp:8080` y `localhost`):

```bash
curl -s -H "Authorization: Bearer TU_TOKEN" http://TABLET_IP:8080/status
# {"ok":true,"conn":"connected","battery":4,"steps":1234,"coins":56,"food":2,"version":"...","mode":null,"name":"AIBI-CF6A"}
```

Sin token (o token mal) la API responde `401 {"ok":false,"error":"token inválido o faltante"}`.
Con el robot desconectado responde `409 {"ok":false,"error":"robot no conectado"}`.

## 3. Endpoints

Todas las respuestas son `{"ok":true,...}` o `{"ok":false,"error":"..."}`.
Header obligatorio: `Authorization: Bearer <token>`.

| Endpoint          | Método | Body JSON                                      | Efecto |
|-------------------|--------|------------------------------------------------|--------|
| `/status`         | GET    | —                                              | estado del robot (conn, batería, pasos, monedas, comida, versión, modo) |
| `/speak`          | POST   | `{"text":"hola"}`                              | TTS del robot |
| `/play`           | POST   | `{"animation":"dance_ai1"}`                    | animación (catálogo en `Animations`) |
| `/light`          | POST   | `{"mode":"color|breath|flow|default","color":[r,g,b],"brightness":0-100}` | configurar luz |
| `/light/on`       | POST   | —                                              | prender luz |
| `/light/off`      | POST   | —                                              | apagar luz |
| `/volume`         | POST   | `{"level":"mute|low|high"}`                    | volumen |
| `/alarms`         | GET    | —                                              | listar alarmas |
| `/alarms`         | POST   | `{"time":"HH:mm","tag":0-6}`                   | agregar alarma (0 Alarma, 1 Medicamento, 2 Agua, 3 Deporte, 4 Levantarse, 5 Comida, 6 Reunión) |
| `/alarms`         | DELETE | `{"index":N}`                                  | borrar alarma |
| `/game`           | POST   | `{"type":"chess|snake|pirate|zero","op":"in|start|play|out"}` | juego |
| `/scene`          | POST   | `{"id":"fiesta|despertar|relax|noche"}`        | escena |

## 4. Tool para Hermes Agent

Guion de ejemplo que Hermes puede invocar como tool de shell. Guardalo como
`aibi.sh`, dale `chmod +x`, y registralo en Hermes como herramienta con
parámetros (endpoint + JSON):

```bash
#!/usr/bin/env bash
# aibi.sh <token> <host:port> <endpoint> [json_body]
# Ejemplos:
#   aibi.sh TU_TOKEN TABLET_IP:8080 status
#   aibi.sh TU_TOKEN TABLET_IP:8080 speak '{"text":"Hola desde Hermes"}'
#   aibi.sh TU_TOKEN TABLET_IP:8080 play  '{"animation":"dance_ai1"}'
set -euo pipefail

TOKEN="${1:?token}"
BASE="${2:?host:puerto}"
ENDPOINT="${3:?endpoint}"
BODY="${4:-}"

case "$ENDPOINT" in
  status)
    METHOD="GET";;
  alarms)
    if [ -n "$BODY" ]; then
      METHOD="POST"
      [ "${BODY#*index}" != "$BODY" ] && METHOD="DELETE"
    else
      METHOD="GET"
    fi;;
  speak|play|light|light/on|light/off|volume|game|scene)
    METHOD="POST";;
  *)
    echo "Endpoint desconocido: $ENDPOINT" >&2
    exit 2;;
esac

ARGS=(-s -X "$METHOD" -H "Authorization: Bearer $TOKEN")
if [ -n "$BODY" ]; then
  ARGS+=(-H "Content-Type: application/json" -d "$BODY")
fi

curl "${ARGS[@]}" "http://${BASE}/${ENDPOINT}" -w '\nHTTP %{http_code}\n'
```

En Hermes, describí la tool más o menos así:

> `aibi.sh <token> <host:puerto> <endpoint> [json_body]` — controla el robot AIBI
> por su API local. Endpoints: status (GET), speak {"text"}, play {"animation"},
> light {"mode","color":[r,g,b],"brightness"}, light/on, light/off,
> volume {"level":"mute|low|high"}, alarms (GET lista, POST add
> {"time":"HH:mm","tag":0-6}, DELETE {"index":N}), game {"type","op"},
> scene {"id":"fiesta|despertar|relax|noche"}. Devuelve JSON + código HTTP.

Equivalente en Python (para Hermes con runtime de Python):

```python
#!/usr/bin/env python3
import sys, json, urllib.request

token, base, endpoint = sys.argv[1], sys.argv[2], sys.argv[3]
body = sys.argv[4] if len(sys.argv) > 4 else None
method = "GET" if endpoint in ("status",) else ("POST" if not body else "POST")
if endpoint == "alarms" and body and '"index"' in body:
    method = "DELETE"

req = urllib.request.Request(
    f"http://{base}/{endpoint}",
    data=body.encode() if body else None,
    headers={"Authorization": f"Bearer {token}",
             "Content-Type": "application/json"},
    method=method,
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
```

## 5. Limitaciones conocidas

- El robot mantiene BLE activo todo el tiempo en modo remoto → más consumo de
  batería del robot. Es opt-in.
- HyperOS/MIUI puede matar el servicio igual; mitigación: notificación
  persistente + wakelock + reconexión automática del `RemoteController`
  (backoff 1-30s, 10 intentos). Si se cae, revisá que la app tenga autostart.
- Seguridad: el único factor de autenticación es el token. Usá un token largo
  y red WiFi de confianza; la API escribe en todas las interfaces (0.0.0.0).
- Solo una conexión BLE a la vez: con remoto activo la app no conecta por su
  cuenta; al apagarlo la app retoma la conexión normal.
