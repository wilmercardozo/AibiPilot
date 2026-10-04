// Plantilla de credenciales locales para docs/esp32-spy/esp-spy.ino
//
// Uso:
//   1. Copiá este archivo a secrets.h (en este mismo directorio).
//   2. Completá con tus datos.
//   3. secrets.h está en .gitignore: nunca se commitea ni se publica.
//
// Ejemplo:
//   const char* STA_SSID = "MiRedWiFi";
//   const char* STA_PASS = "MiClaveSecreta";

const char* AP_SSID = "AIBI-CAP";     // SSID del AP que levanta el ESP (se levanta abierto)
const char* AP_PASS = "";             // sin uso actual (WiFi.softAP se llama con NULL)
const char* STA_SSID = "TU_WIFI";     // tu red WiFi a la que se conecta el ESP
const char* STA_PASS = "TU_PASSWORD"; // contraseña de tu red WiFi
