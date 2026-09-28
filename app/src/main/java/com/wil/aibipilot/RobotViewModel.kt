package com.wil.aibipilot

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wil.aibipilot.ble.BleClient
import com.wil.aibipilot.ble.BleEvent
import com.wil.aibipilot.ble.PhotoTcpServer
import com.wil.aibipilot.ble.RemoteService
import com.wil.aibipilot.ble.RemoteStateBus
import com.wil.aibipilot.ble.ScanDevice
import com.wil.aibipilot.protocol.Protocol
import com.wil.aibipilot.routines.Routine
import com.wil.aibipilot.routines.RoutineScheduler
import com.wil.aibipilot.routines.RoutinesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

enum class WifiConnState { IDLE, CONNECTING, CONNECTED, FAILED }

data class WifiNet(val ssid: String, val rssi: Int? = null)

data class RobotInfo(
    val deviceName: String = "",
    val version: String = "",
    val versionNumber: String = "",
    val battery: Int? = null,
    val steps: Long? = null,
    val gold: Int? = null,
    val food: Int? = null,
    val glasses: Int? = null,
    val timerDura: Int? = null,
    val breathTimes: Int? = null,
    val mtu: Int = 23
)

data class AlarmItem(val index: Int, val time: String, val tag: Int?)

data class LightItem(val id: Int, val name: String?)

data class QuietItem(val index: Int, val from: String, val to: String)

data class ScheduleItem(val index: Int, val tag: Int?, val time: Int?)

data class ChatMsg(val role: String, val content: String)

enum class LogCat { TX, RX, EVT, ERR, SYS }

data class LogLine(val cat: LogCat, val text: String)

fun batteryLabel(level: Int?): String = when (level) {
    1 -> "Baja"
    2 -> "Media"
    3 -> "Alta"
    4 -> "Llena"
    else -> "—"
}

/**
 * Notificaciones de la app (spec C3): canal único "aibi" para rutinas
 * pendientes, batería baja y desconexión prolongada. Requiere el permiso
 * POST_NOTIFICATIONS en Android 13+; sin él, notify() no hace nada.
 */
object AibiNotifier {

    const val CHANNEL_ID = "aibi"

    fun hasPermission(context: android.content.Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: android.content.Context) {
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                "Avisos",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Rutinas pendientes, batería baja y desconexión del robot"
            }
        )
    }

    fun mainIntent(context: android.content.Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            context,
            0,
            android.content.Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun notify(
        context: android.content.Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: android.app.PendingIntent? = null
    ) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.wil.aibipilot.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: mainIntent(context))
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("AibiNotifier", "error notificando: ${e.message}")
        }
    }
}

const val DEFAULT_LLM_PROMPT = "Eres AIBI, una mascota robot adorable y pequeña con gran personalidad. " +
    "Respondes en español, de forma breve (máximo 2 frases), cálida y con humor. " +
    "Te gusta jugar, animar a tu dueño y hacer bromas tiernas."

data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val reconnectAttempt: Int = 0,
    val connHint: String? = null,
    val devices: List<ScanDevice> = emptyList(),
    val info: RobotInfo = RobotInfo(),
    val log: List<LogLine> = emptyList(),
    val volume: String = "high",
    val alarms: List<AlarmItem> = emptyList(),
    val lights: List<LightItem> = emptyList(),
    val quiets: List<QuietItem> = emptyList(),
    val schedules: List<ScheduleItem> = emptyList(),
    val scheduleSwitch: Boolean? = null,
    val photoServerRunning: Boolean = false,
    val photos: List<String> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatThinking: Boolean = false,
    val themeMode: String = "system",
    val remoteRunning: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val rawHistory: List<String> = emptyList(),
    val lastRawResponse: String? = null,
    val motionSweeping: Boolean = false,
    val motionSweepCmd: Int? = null,
    val wifiNetworks: List<WifiNet> = emptyList(),
    val wifiScanning: Boolean = false,
    val robotWifi: String? = null,
    val wifiConnState: WifiConnState = WifiConnState.IDLE,
    val robotWifiTarget: String? = null,
    val robotWifiConnected: Boolean? = null,
    val bleRssi: Int? = null,
    val currentMode: String? = null,
    val lastRxSeconds: Long = -1L,
    val snackbar: String? = null
)

class RobotViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PREFS = "aibi_pilot_prefs"
        private const val KEY_MAC = "last_device_mac"
        private const val KEY_NAME = "last_device_name"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_REMOTE_TOKEN = "remote_token"
        private const val KEY_REMOTE_PORT = "remote_port"
        private const val KEY_RAW_HISTORY = "raw_history"
        private const val MAX_RAW_HISTORY = 50
    }

    val ble = BleClient(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        loadThemeMode()
        _ui.update {
            it.copy(
                routines = RoutinesStore.load(getApplication()),
                rawHistory = loadRawHistory()
            )
        }
        RoutineScheduler.schedule(getApplication())
        viewModelScope.launch {
            RemoteStateBus.running.collect { running ->
                _ui.update { it.copy(remoteRunning = running) }
            }
        }
    }

    private var currentDevice: android.bluetooth.BluetoothDevice? = null
    private var scanJob: kotlinx.coroutines.Job? = null

    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var keepAliveJob: kotlinx.coroutines.Job? = null
    private var rssiJob: kotlinx.coroutines.Job? = null
    private var rxTickerJob: kotlinx.coroutines.Job? = null
    private var snackbarNonce = 0
    private var reconnectAttempt = 0
    private var reconnectCancelled = false
    private var reconnectInFlight = false
    private var diagnosePending = false
    private var connectTimeoutJob: kotlinx.coroutines.Job? = null
    private var lastRxAt = 0L
    private var lastTxAt = 0L
    private var rawPending = false
    private var lowBatteryNotified = false

    // Apagado robusto (BUG-1): mientras poweringOff, una desconexión BLE es éxito
    private var poweringOff = false
    private var powerOffRequestedAt = 0L
    private var powerOffJob: kotlinx.coroutines.Job? = null

    private val rxEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 64
    )

    private val reconnectBackoff = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)
    private val MAX_RECONNECT_ATTEMPTS = 10

    private val modeAckFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 16
    )

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun savedDeviceName(): String? = prefs().getString(KEY_NAME, null)

    fun setThemeMode(mode: String) {
        _ui.update { it.copy(themeMode = mode) }
        prefs().edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun loadThemeMode() {
        val mode = prefs().getString(KEY_THEME_MODE, "system") ?: "system"
        _ui.update { it.copy(themeMode = mode) }
    }

    // ------------------------------------------------------------------
    // Rutinas (spec C3): CRUD local con persistencia en RoutinesStore
    // ------------------------------------------------------------------
    fun routinesUpsert(routine: Routine) {
        val updated = (_ui.value.routines.filter { it.id != routine.id } + routine)
            .sortedBy { it.time }
        RoutinesStore.save(getApplication(), updated)
        _ui.update { it.copy(routines = updated) }
        RoutineScheduler.schedule(getApplication())
    }

    fun routinesDelete(id: String) {
        val updated = _ui.value.routines.filter { it.id != id }
        RoutinesStore.save(getApplication(), updated)
        _ui.update { it.copy(routines = updated) }
        RoutineScheduler.schedule(getApplication())
    }

    fun routinesToggle(id: String, enabled: Boolean) {
        val updated = _ui.value.routines.map { if (it.id == id) it.copy(enabled = enabled) else it }
        RoutinesStore.save(getApplication(), updated)
        _ui.update { it.copy(routines = updated) }
        RoutineScheduler.schedule(getApplication())
    }

    // ------------------------------------------------------------------
    // Modo remoto (API HTTP en foreground service — spec C1)
    // Una sola conexión BLE a la vez: con remoto activo la conexión la
    // maneja RemoteController dentro del servicio y el VM no conecta.
    // ------------------------------------------------------------------
    private fun remoteActive(): Boolean = RemoteStateBus.running.value

    fun remoteToken(): String = prefs().getString(KEY_REMOTE_TOKEN, "") ?: ""

    fun saveRemoteToken(token: String) {
        prefs().edit().putString(KEY_REMOTE_TOKEN, token.trim()).apply()
    }

    fun remotePort(): Int = prefs().getString(KEY_REMOTE_PORT, "8080")?.toIntOrNull() ?: 8080

    fun saveRemotePort(port: Int) {
        prefs().edit().putString(KEY_REMOTE_PORT, port.toString()).apply()
    }

    fun startRemoteMode() {
        disconnect()
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteService::class.java))
        log("Modo remoto iniciado: la conexión pasa al servicio")
        showSnackbar("Modo remoto activo: la conexión la maneja el servicio")
    }

    fun stopRemoteMode() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, RemoteService::class.java))
        log("Modo remoto detenido")
        _ui.update { it.copy(connHint = null) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            tryAutoReconnect()
        }
    }

    /**
     * Muestra un snackbar transitorio (DESIGN §4.2). Se auto-limpia a los 3s solo si
     * sigue siendo el mismo mensaje (nonce para no pisar mensajes nuevos).
     */
    fun showSnackbar(msg: String) {
        val nonce = ++snackbarNonce
        _ui.update { it.copy(snackbar = msg) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (snackbarNonce == nonce) {
                _ui.update { it.copy(snackbar = null) }
            }
        }
    }

    fun dismissSnackbar() {
        snackbarNonce++
        _ui.update { it.copy(snackbar = null) }
    }

    private val photoDir: java.io.File
        get() = java.io.File(getApplication<Application>().getExternalFilesDir(null), "photos")

    private var photoServer: PhotoTcpServer? = null

    fun startScan() {
        scanJob?.cancel()
        ble.stopScan()
        _ui.update { it.copy(conn = ConnState.SCANNING, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            ble.scan().collect { dev ->
                _ui.update { s ->
                    if (s.conn != ConnState.SCANNING) return@update s
                    val updated = s.devices.filter { it.device.address != dev.device.address } + dev
                    // robots AIBI primero, luego por señal
                    val sorted = updated.sortedWith(
                        compareByDescending<ScanDevice> {
                            it.device.name?.contains("AIBI", ignoreCase = true) == true
                        }.thenByDescending { it.rssi }
                    )
                    s.copy(devices = sorted)
                }
            }
        }
    }

    /**
     * HyperOS/MIUI suspende los scans BLE cuando la app pasa a segundo plano
     * y a veces no los reactiva al volver. Se reinicia el scan al retomar.
     */
    fun onAppForeground() {
        if (_ui.value.conn == ConnState.SCANNING) {
            startScan()
        } else if (_ui.value.conn == ConnState.DISCONNECTED && !reconnectCancelled) {
            tryAutoReconnect()
        }
    }

    fun connect(device: ScanDevice) = connectDevice(device.device, device.device.name)

    /**
     * Reconexión automática al último robot guardado (sin escanear).
     * Devuelve false si no hay robot guardado.
     */
    fun tryAutoReconnect(): Boolean {
        if (remoteActive()) {
            showSnackbar("Modo remoto activo: la conexión la maneja el servicio")
            return false
        }
        val mac = prefs().getString(KEY_MAC, null) ?: return false
        if (_ui.value.conn != ConnState.DISCONNECTED) return true
        val name = prefs().getString(KEY_NAME, null)
        return try {
            val btManager = getApplication<Application>()
                .getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            val device = btManager.adapter.getRemoteDevice(mac)
            log("Reconectando al robot guardado: ${name ?: mac}")
            connectDevice(device, name)
            true
        } catch (e: Exception) {
            log("No se pudo reconectar: ${e.message}")
            false
        }
    }

    private fun connectDevice(device: android.bluetooth.BluetoothDevice, deviceName: String?, isReconnect: Boolean = false) {
        if (remoteActive()) {
            log(LogCat.SYS, "Modo remoto activo: no se inicia conexión BLE propia")
            showSnackbar("Modo remoto activo: la conexión la maneja el servicio")
            return
        }
        android.util.Log.d("AibiBle", "VM.connect() called for ${device.address}")
        scanJob?.cancel()
        ble.stopScan()
        _ui.update { it.copy(conn = ConnState.CONNECTING, devices = emptyList()) }
        currentDevice = device
        log("Conectando a ${deviceName ?: "(sin nombre)"} (${device.address})")
        if (!isReconnect) {
            reconnectCancelled = false
            reconnectInFlight = false
            diagnosePending = true
            reconnectAttempt = 0
            reconnectJob?.cancel()
            _ui.update { it.copy(reconnectAttempt = 0, connHint = null) }
        }
        ble.connect(
            device,
            onEvent = ::onBleEvent,
            onState = { connected ->
                if (connected) {
                    // guardar para reconexión automática
                    prefs().edit()
                        .putString(KEY_MAC, device.address)
                        .putString(KEY_NAME, device.name ?: deviceName)
                        .apply()
                    reconnectInFlight = false
                    connectTimeoutJob?.cancel()
                    reconnectAttempt = 0
                    _ui.update { it.copy(conn = ConnState.CONNECTED) }
                    log("Conectado. MTU: ${ble.currentMtu()}")
                    startKeepAlive()
                    startRssiLoop()
                    startRxTicker()
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        handshake()
                    }
                    firePendingMotion()
                } else {
                    if (poweringOff) {
                        handlePowerOffDisconnect()
                    } else if (reconnectCancelled) {
                        _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
                        log(LogCat.SYS, "Se perdió la conexión BLE")
                    } else if (reconnectInFlight || _ui.value.conn == ConnState.CONNECTED) {
                        scheduleReconnect()
                    } else {
                        // fallo de un connect de usuario (conn == CONNECTING)
                        connectTimeoutJob?.cancel()
                        _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
                        log(LogCat.ERR, "No se pudo conectar")
                        diagnoseConnectFailure(device.address)
                    }
                }
            }
        )
        // Timeout de conexión: si en 12s no conecta, volver al escaneo
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(12000)
            if (_ui.value.conn == ConnState.CONNECTING) {
                ble.disconnect()
                if (reconnectInFlight) {
                    scheduleReconnect()
                } else {
                    _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
                    log(LogCat.ERR, "Timeout de conexión")
                    diagnoseConnectFailure(device.address)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return // idempotente: ya hay un intento en curso
        reconnectJob?.cancel()
        // sin BLE no se puede seguir el estado del WiFi del robot
        _ui.update {
            it.copy(
                wifiConnState = WifiConnState.IDLE,
                robotWifiTarget = null,
                robotWifiConnected = null,
                bleRssi = null
            )
        }
        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectInFlight = false
            _ui.update {
                it.copy(
                    conn = ConnState.DISCONNECTED,
                    reconnectAttempt = 0,
                    connHint = "No se pudo reconectar. ¿El robot está dormido o apagado?"
                )
            }
            log(LogCat.ERR, "Reconexión agotada tras $MAX_RECONNECT_ATTEMPTS intentos")
            AibiNotifier.notify(
                getApplication(),
                3002,
                "No se pudo reconectar",
                "El robot no responde. ¿Está dormido o apagado?",
                AibiNotifier.mainIntent(getApplication())
            )
            diagnoseConnectFailure(prefs().getString(KEY_MAC, null) ?: return)
            return
        }
        reconnectInFlight = true
        val delayMs = reconnectBackoff[minOf(reconnectAttempt - 1, reconnectBackoff.size - 1)]
        _ui.update { it.copy(conn = ConnState.RECONNECTING, reconnectAttempt = reconnectAttempt) }
        log(LogCat.SYS, "Reconexión automática: intento $reconnectAttempt en ${delayMs / 1000}s")
        reconnectJob = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            val mac = prefs().getString(KEY_MAC, null)
            if (mac == null) {
                _ui.update { it.copy(conn = ConnState.DISCONNECTED, reconnectAttempt = 0) }
                return@launch
            }
            try {
                val btManager = getApplication<Application>()
                    .getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                    as android.bluetooth.BluetoothManager
                val device = btManager.adapter.getRemoteDevice(mac)
                connectDevice(device, prefs().getString(KEY_NAME, null), isReconnect = true)
            } catch (e: Exception) {
                reconnectInFlight = false
                _ui.update { it.copy(conn = ConnState.DISCONNECTED, reconnectAttempt = 0) }
                log(LogCat.ERR, "Reconexión fallida: ${e.message}")
            }
        }
    }

    /** Diagnóstico tras timeout de conexión: re-scan de 5s del MAC guardado. */
    private fun diagnoseConnectFailure(mac: String) {
        if (!diagnosePending) return
        diagnosePending = false
        viewModelScope.launch {
            var seen = false
            try {
                kotlinx.coroutines.withTimeoutOrNull(5000) {
                    ble.scan().collect { dev ->
                        if (dev.device.address.equals(mac, ignoreCase = true)) seen = true
                    }
                }
            } finally {
                ble.stopScan()
            }
            _ui.update {
                it.copy(
                    connHint = if (seen) {
                        "El robot está al alcance pero rechazó la conexión. ¿Otra app (la oficial) lo tiene conectado? Cerrala y reintentá."
                    } else {
                        "El robot está dormido o fuera de alcance. Despertalo y volvé a intentar."
                    }
                )
            }
            log(LogCat.SYS, "Diagnóstico: ${if (seen) "al alcance, conexión rechazada" else "no visible (dormido/fuera de alcance)"}")
        }
    }

    /**
     * Keep-alive: si no hubo tráfico RX/TX en 20s y no estamos dentro de un modo
     * de función (para no molestar juegos/fotos), hace un ping sta query[12] y, si
     * no responde en 10s, fuerza la reconexión.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5000)
                val now = android.os.SystemClock.elapsedRealtime()
                val idle = now - lastRxAt > 20000 && now - lastTxAt > 20000
                if (_ui.value.conn == ConnState.CONNECTED && currentMode == null && idle) {
                    val pingAt = lastRxAt
                    log(LogCat.SYS, "Sin tráfico del robot en 20s: ping de verificación")
                    send(Protocol.staQuery(12), "ping sta battery")
                    val responded = kotlinx.coroutines.withTimeoutOrNull(10000) {
                        rxEvents.filter { lastRxAt > pingAt }.first()
                        true
                    } ?: false
                    if (!responded) {
                        log(LogCat.ERR, "El robot no responde al ping: forzando reconexión")
                        // MIUI puede no entregar el callback de disconnect: arrancar el
                        // ciclo directamente; un onState(false) tardío se descarta por la
                        // guarda de idempotencia de scheduleReconnect
                        reconnectInFlight = true
                        ble.disconnect()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun handshake() {
        val payload = Protocol.staQuery(1, 8, 11, 12)
        send(payload, "handshake sta_req query[1,8,11,12]")
    }

    /**
     * RSSI en vivo para Diagnóstico: pide el RSSI cada ~5s mientras esté
     * conectado. Es una lectura local de la radio; no cuenta como tráfico
     * RX/TX, así que no interfiere con el keep-alive.
     */
    private fun startRssiLoop() {
        rssiJob?.cancel()
        rssiJob = viewModelScope.launch {
            while (isActive) {
                if (_ui.value.conn == ConnState.CONNECTED) {
                    ble.readRemoteRssi { rssi ->
                        _ui.update { it.copy(bleRssi = rssi) }
                    }
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    /**
     * Ticker liviano (~1s): actualiza lastRxSeconds para Diagnóstico solo
     * mientras la conexión está activa. No toca lastRxAt (lo usa keep-alive).
     */
    private fun startRxTicker() {
        rxTickerJob?.cancel()
        rxTickerJob = viewModelScope.launch {
            while (isActive) {
                if (_ui.value.conn == ConnState.CONNECTED) {
                    val seconds = if (lastRxAt == 0L) -1L
                    else (android.os.SystemClock.elapsedRealtime() - lastRxAt) / 1000
                    _ui.update { it.copy(lastRxSeconds = seconds) }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun onBleEvent(event: BleEvent) {
        lastRxAt = android.os.SystemClock.elapsedRealtime()
        rxEvents.tryEmit(Unit)
        when (event) {
            is BleEvent.JsonMessage -> {
                log(LogCat.RX, "RX ${event.json}")
                parseResponse(event.json)
            }
            is BleEvent.RawMessage -> {
                log(LogCat.RX, "RX binario: ${event.bytes.toHex().take(64)}")
                android.util.Log.d("AibiBle", "RX binario ${event.bytes.toHex()}")
            }
        }
    }

    private fun parseResponse(jsonStr: String) {
        android.util.Log.d("AibiBle", "RX $jsonStr")
        if (rawPending) {
            rawPending = false
            _ui.update { it.copy(lastRawResponse = jsonStr) }
        }
        try {
            val root = Protocol.json.parseToJsonElement(jsonStr).jsonObject
            when (val type = root["type"]?.jsonPrimitive?.contentOrNull) {
                "sta_rsp" -> parseStaRsp(root)
            "aibi_event" -> {
                val eventName = root["data"]?.jsonObject?.get("event")?.jsonPrimitive?.contentOrNull
                log(LogCat.EVT, "Evento robot: $eventName")
                if (eventName == "modeout") {
                    val evCurrent = root["data"]?.jsonObject?.get("current")?.jsonPrimitive?.contentOrNull
                    if (evCurrent == null || evCurrent == currentMode) {
                        setCurrentMode(null)
                        log(LogCat.EVT, "El robot salió del modo de función")
                    } else {
                        log(LogCat.EVT, "modeout de un modo anterior ($evCurrent), ignorado")
                    }
                }
            }
            "alarm_rsp" -> parseAlarmRsp(root)
            "light_rsp" -> parseLightRsp(root)
            "setting_rsp" -> parseSettingRsp(root)
            else -> {
                // ACKs de modo y resultados de features: avisar a ensureMode
                root["data"]?.jsonObject?.get("result")?.jsonPrimitive?.contentOrNull?.let {
                    modeAckFlow.tryEmit(it)
                }
            }
            }
        } catch (e: Exception) {
            android.util.Log.e("AibiBle", "parseResponse error: ${e.message}", e)
        }
    }

    private fun parseSettingRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull ?: return
        // loguear el resultado al modo ack (setting_in_ok, setting_volume_ok, etc.)
        modeAckFlow.tryEmit(result)
        if (result == "setting_quiet_list_ok") {
            val list = data["list"]?.jsonArray
            val quiets = mutableListOf<QuietItem>()
            if (list != null) {
                for (el in list) {
                    val obj = el as? JsonObject ?: continue
                    quiets.add(
                        QuietItem(
                            index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                            from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                            to = obj["to"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    )
                }
            }
            _ui.update { it.copy(quiets = quiets) }
            log("Horas silenciosas: ${quiets.joinToString { q -> "${q.from}-${q.to}" }}")
        }
        if (result == "setting_schedule_list_ok") {
            val list = data["list"]?.jsonArray
            val schedules = mutableListOf<ScheduleItem>()
            if (list != null) {
                for (el in list) {
                    val obj = el as? JsonObject ?: continue
                    schedules.add(
                        ScheduleItem(
                            index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                            tag = obj["tag"]?.jsonPrimitive?.intOrNull,
                            time = obj["time"]?.jsonPrimitive?.intOrNull
                        )
                    )
                }
            }
            val sw = data["switch"]?.jsonPrimitive?.let { p ->
                p.contentOrNull?.let { it == "on" } ?: p.intOrNull?.let { it != 0 }
            }
            _ui.update { it.copy(schedules = schedules, scheduleSwitch = sw) }
            log("Horario: ${schedules.joinToString { s -> "${s.time}#${s.tag}" }} switch=$sw")
        }
        // respuesta de wifilist: el formato real se aprende en vivo (lista en "list" o similar)
        if (result.contains("wifilist", ignoreCase = true)) {
            val list = data["list"]?.jsonArray
            val networks = mutableListOf<WifiNet>()
            if (list != null) {
                for (el in list) {
                    val obj = el as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["ssid"]?.jsonPrimitive?.contentOrNull
                    if (name != null) {
                        networks.add(WifiNet(name, obj["rssi"]?.jsonPrimitive?.intOrNull))
                    }
                }
            }
            _ui.update { it.copy(wifiNetworks = networks, wifiScanning = false) }
            log(LogCat.RX, "WiFi list: ${networks.joinToString { n -> "${n.ssid}(${n.rssi} dBm)" }}")
        }
    }

    private fun parseAlarmRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull ?: return
        // feedback con ACK: alarm_add_ok/no, alarm_del_ok/no, alarm_list_ok…
        modeAckFlow.tryEmit(result)
        when (result) {
            "alarm_list_ok" -> {
                val list = data["list"]?.jsonArray ?: return
                val alarms = list.mapNotNull { el ->
                    val obj = el.jsonObject
                    val time = obj["time"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    AlarmItem(
                        index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                        time = time,
                        tag = obj["tag"]?.jsonPrimitive?.intOrNull
                    )
                }
                _ui.update { it.copy(alarms = alarms) }
                log("Alarmas: ${alarms.joinToString { a -> "${a.time}#${a.index}" }}")
            }
            else -> log("Respuesta alarma: $result")
        }
    }

    private fun parseLightRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull ?: return
        // feedback con ACK: light_on_ok/no, light_off_ok/no, light_list_ok…
        modeAckFlow.tryEmit(result)
        when (result) {
            "light_list_ok" -> {
                val list = data["list"]?.jsonArray ?: return
                val lights = list.mapNotNull { el ->
                    val obj = el.jsonObject
                    LightItem(
                        id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                        name = obj["name"]?.jsonPrimitive?.contentOrNull
                    )
                }
                _ui.update { it.copy(lights = lights) }
                log("Luces: ${lights.joinToString { l -> "${l.name ?: l.id}" }}")
            }
            else -> log("Respuesta luz: $result")
        }
    }

    private fun parseStaRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull
        if (result == "sta_query_ok") {
            val wifi = data["wifi"]?.jsonObject
            val wifiSsid = wifi?.get("ssid")?.jsonPrimitive?.contentOrNull
                ?: wifi?.get("name")?.jsonPrimitive?.contentOrNull
            // formato real observado en vivo: "wifi":{"connected":1,"name":"<ssid>"}
            val wifiConnected: Boolean? = wifi?.get("connected")?.jsonPrimitive?.let { p ->
                p.intOrNull?.let { it != 0 } ?: p.booleanOrNull
            }
            _ui.update { s ->
                val target = s.robotWifiTarget
                val targetConnected = target != null && wifiSsid == target &&
                    (wifiConnected == null || wifiConnected)
                val wifiState = when (s.wifiConnState) {
                    WifiConnState.CONNECTING ->
                        if (targetConnected) WifiConnState.CONNECTED else WifiConnState.CONNECTING
                    WifiConnState.CONNECTED ->
                        if (targetConnected) WifiConnState.CONNECTED else WifiConnState.IDLE
                    else -> s.wifiConnState
                }
                s.copy(
                    robotWifi = wifiSsid ?: s.robotWifi,
                    robotWifiConnected = wifiConnected ?: s.robotWifiConnected,
                    wifiConnState = wifiState,
                    info = s.info.copy(
                        version = data["version"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: s.info.version,
                        versionNumber = data["version"]?.jsonObject?.get("number")?.jsonPrimitive?.contentOrNull ?: s.info.versionNumber,
                        battery = data["battery"]?.jsonObject?.get("level")?.jsonPrimitive?.intOrNull,
                        steps = data["property"]?.jsonObject?.get("steps")?.jsonPrimitive?.longOrNull,
                        gold = data["property"]?.jsonObject?.get("gold")?.jsonPrimitive?.longOrNull?.let { it.toInt() },
                        // food y glasses son List<Integer> en el protocolo oficial
                        food = data["property"]?.jsonObject?.get("food")?.jsonArray?.size,
                        glasses = data["property"]?.jsonObject?.get("glasses")?.jsonArray?.size,
                        timerDura = data["features"]?.jsonObject?.get("timerdura")?.jsonPrimitive?.intOrNull,
                        breathTimes = data["features"]?.jsonObject?.get("breathtimes")?.jsonPrimitive?.intOrNull,
                        mtu = ble.currentMtu()
                    )
                )
            }
            // cubre también el sta query[12] del keep-alive (pasa por acá)
            notifyLowBatteryIfNeeded()
        } else if (result != null) {
            log("Respuesta: $result")
        }
    }

    /**
     * Batería baja (spec C3): notifica una sola vez cuando el nivel es 1 y
     * re-arma el flag cuando vuelve a subir de nivel.
     */
    private fun notifyLowBatteryIfNeeded() {
        val level = _ui.value.info.battery ?: return
        if (level == 1) {
            if (!lowBatteryNotified) {
                lowBatteryNotified = true
                AibiNotifier.notify(
                    getApplication(),
                    3001,
                    "Batería baja",
                    "Cargá al robot",
                    AibiNotifier.mainIntent(getApplication())
                )
                log(LogCat.EVT, "Notificación: batería baja")
            }
        } else if (level > 1) {
            lowBatteryNotified = false
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        ensureMode("show") { send(Protocol.showSpeak(text.trim()), "TTS: ${text.trim()}") }
    }

    fun playAnimation(animation: String) {
        ensureMode("show") { send(Protocol.showPlay(animation), "Animación: $animation") }
    }

    // ------------------------------------------------------------------
    // Modo de función: el robot exige "<feature>_in" antes de aceptar
    // comandos de cada feature (patrón de la app oficial).
    // ------------------------------------------------------------------
    private var currentMode: String? = null

    /** Actualiza el modo privado y lo expone en UiState (Diagnóstico). */
    private fun setCurrentMode(mode: String?) {
        currentMode = mode
        _ui.update { it.copy(currentMode = mode) }
    }

    private fun modeIn(mode: String): ByteArray = when (mode) {
        "show" -> Protocol.showIn()
        "light" -> Protocol.lightIn()
        "alarm" -> Protocol.alarmIn()
        "setting" -> Protocol.settingIn()
        "chess", "snake", "pirate", "zero" -> Protocol.gameIn(mode)
        "photo" -> Protocol.photoIn()
        else -> Protocol.showIn()
    }

    private fun modeOut(mode: String): ByteArray = when (mode) {
        "show" -> Protocol.showOut()
        "light" -> Protocol.lightOut()
        "alarm" -> Protocol.alarmOut()
        "setting" -> Protocol.settingOut()
        "chess", "snake", "pirate", "zero" -> Protocol.gameOut(mode)
        "photo" -> Protocol.photoOut()
        else -> Protocol.showOut()
    }

    private fun ensureMode(mode: String, action: () -> Unit) {
        if (currentMode == mode) {
            action()
            return
        }
        setCurrentMode(mode)
        send(modeIn(mode), "$mode in")
        viewModelScope.launch {
            // esperar el ACK real del robot ("<feature>_in_ok") con timeout
            val ok = kotlinx.coroutines.withTimeoutOrNull(4000) {
                modeAckFlow.filter { it == "${mode}_in_ok" }.first()
            } != null
            if (!ok) {
                kotlinx.coroutines.delay(700)
                log("Sin ACK de modo $mode, intentando igual")
            }
            action()
        }
    }

    fun exitCurrentMode() {
        val mode = currentMode ?: return
        setCurrentMode(null)
        send(modeOut(mode), "$mode out")
    }

    /**
     * Patrón de feedback con ACK (tarea 4): ejecuta [block] (envío del
     * comando) y espera en modeAckFlow el result "<expect>_ok" o
     * "<expect>_no" con timeout. Devuelve true solo si llegó el ACK
     * positivo; si llega "<expect>_no" devuelve false sin esperar el
     * timeout, igual que si no llega nada.
     * Ej.: withAck("setting_volume") { send(Protocol.settingVolume(..)) }
     */
    suspend fun withAck(expect: String, timeoutMs: Long = 8000, block: suspend () -> Unit): Boolean {
        block()
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            modeAckFlow.filter { it == "${expect}_ok" || it == "${expect}_no" }.first()
        }
        return result == "${expect}_ok"
    }

    /**
     * Variante de [withAck] que acepta varios result posibles. Sirve para
     * ops cuyo ACK real no se pudo confirmar: la app oficial compara los
     * result de tapani/doubletap contra "setting_selfani_ok" (constante
     * duplicada en BleSettingsResponse.kt), así que se acepta el result
     * canónico `<op>_ok` o el alternativo "setting_selfani_ok".
     */
    suspend fun withAckAny(expects: List<String>, timeoutMs: Long = 8000, block: suspend () -> Unit): Boolean {
        block()
        val candidates = expects.flatMap { e -> listOf("${e}_ok", "${e}_no") }.toSet()
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            modeAckFlow.filter { it in candidates }.first()
        }
        return result?.endsWith("_ok") == true
    }

    /**
     * Acción con feedback pendiente→ok→error vía withAck: muestra el
     * snackbar de éxito si el robot confirmó, o "No respondió…" ante
     * un "_no" o timeout.
     */
    private fun runAcked(expect: String, okMsg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val ok = withAck(expect) { block() }
            if (ok) {
                showSnackbar(okMsg)
            } else {
                log(LogCat.ERR, "Sin ACK de $expect (o respuesta negativa)")
                showSnackbar("No respondió…")
            }
        }
    }

    private fun runAckedAny(expects: List<String>, okMsg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val ok = withAckAny(expects) { block() }
            if (ok) {
                showSnackbar(okMsg)
            } else {
                log(LogCat.ERR, "Sin ACK de ${expects.joinToString("/")} (o respuesta negativa)")
                showSnackbar("No respondió…")
            }
        }
    }

    fun setVolume(level: String) {
        _ui.update { it.copy(volume = level) }
        showSnackbar("Enviando volumen…")
        ensureMode("setting") {
            runAcked("setting_volume", "Volumen ok") {
                send(Protocol.settingVolume(level), "Volumen: $level")
            }
        }
    }

    // ------------------------------------------------------------------
    // Configuración del robot (settings oficiales, con withAck)
    // ------------------------------------------------------------------
    fun setLang(langcode: String) {
        showSnackbar("Cambiando idioma…")
        ensureMode("setting") {
            runAcked("setting_lang", "Idioma actualizado") {
                send(Protocol.settingLang(langcode), "lang $langcode")
            }
        }
    }

    fun setTempUnit(celsius: Boolean) {
        val label = if (celsius) "Celsius" else "Fahrenheit"
        showSnackbar("Cambiando unidad…")
        ensureMode("setting") {
            runAcked("setting_temp", "Unidad de temperatura: $label") {
                send(Protocol.settingTemp(if (celsius) 0 else 1), "temp ${if (celsius) 0 else 1}")
            }
        }
    }

    fun setLengthUnit(metric: Boolean) {
        val label = if (metric) "Métrico" else "Imperial"
        showSnackbar("Cambiando unidad…")
        ensureMode("setting") {
            runAcked("setting_length", "Unidad de longitud: $label") {
                send(Protocol.settingLength(if (metric) 0 else 1), "length ${if (metric) 0 else 1}")
            }
        }
    }

    fun setHour24(on: Boolean) {
        showSnackbar("Cambiando formato de hora…")
        ensureMode("setting") {
            runAcked("setting_24hour", if (on) "Formato 24 h activado" else "Formato 12 h activado") {
                send(Protocol.settingHour24(if (on) 1 else 0), "24hour ${if (on) 1 else 0}")
            }
        }
    }

    fun setWakeModel(model: Int) {
        showSnackbar("Cambiando modelo de despertar…")
        ensureMode("setting") {
            runAcked("setting_wakemodel", "Modelo de despertar: V${model + 1}") {
                send(Protocol.settingWakeModel(model), "wakemodel $model")
            }
        }
    }

    fun setChatty(on: Boolean) {
        showSnackbar(if (on) "Activando modo charlatán…" else "Desactivando modo charlatán…")
        ensureMode("setting") {
            runAcked("setting_chatty", "Modo charlatán: ${if (on) "ON" else "OFF"}") {
                send(Protocol.settingChatty(if (on) 1 else 0), "chatty ${if (on) 1 else 0}")
            }
        }
    }

    fun setSelfani(on: Boolean) {
        showSnackbar(if (on) "Activando animaciones propias…" else "Desactivando animaciones propias…")
        ensureMode("setting") {
            runAcked("setting_selfani", "Animaciones propias: ${if (on) "ON" else "OFF"}") {
                send(Protocol.settingSelfani(if (on) 1 else 0), "selfani ${if (on) 1 else 0}")
            }
        }
    }

    fun setTapani(on: Boolean) {
        showSnackbar(if (on) "Activando animaciones al tocar…" else "Desactivando animaciones al tocar…")
        ensureMode("setting") {
            // el oficial compara tapani contra "setting_selfani_ok" (constante duplicada);
            // se aceptan ambos result
            runAckedAny(listOf("setting_tapani", "setting_selfani"), "Animaciones al tocar: ${if (on) "ON" else "OFF"}") {
                send(Protocol.settingTapani(if (on) 1 else 0), "tapani ${if (on) 1 else 0}")
            }
        }
    }

    fun setDoubletap(on: Boolean) {
        showSnackbar(if (on) "Activando reacción al doble toque…" else "Desactivando reacción al doble toque…")
        ensureMode("setting") {
            // mismo caso que tapani: la UI oficial espera "setting_selfani_ok"
            runAckedAny(listOf("setting_doubletap", "setting_selfani"), "Doble toque: ${if (on) "ON" else "OFF"}") {
                send(Protocol.settingDoubletap(if (on) 1 else 0), "doubletap ${if (on) 1 else 0}")
            }
        }
    }

    fun setLastName(name: String) {
        showSnackbar("Guardando nombre…")
        ensureMode("setting") {
            runAcked("setting_lastname", "Nombre guardado") {
                send(Protocol.settingLastName(name), "lastname $name")
            }
        }
    }

    fun setBirthday(birthday: String) {
        showSnackbar("Guardando cumpleaños…")
        ensureMode("setting") {
            runAcked("setting_birthday", "Cumpleaños guardado") {
                send(Protocol.settingBirthday(birthday), "birthday $birthday")
            }
        }
    }

    fun quietList() = ensureMode("setting") { send(Protocol.settingQuietList(), "quiet list") }

    fun quietAdd(from: String, to: String) {
        showSnackbar("Agregando horas silenciosas…")
        ensureMode("setting") {
            runAcked("setting_quiet_add", "Horas silenciosas agregadas") {
                send(Protocol.settingQuietAdd(from, to), "quiet add $from-$to")
            }
        }
    }

    fun quietDel(index: Int) {
        showSnackbar("Eliminando período…")
        ensureMode("setting") {
            runAcked("setting_quiet_del", "Período eliminado") {
                send(Protocol.settingQuietDel(index), "quiet del #$index")
            }
        }
    }

    fun scheduleList() = ensureMode("setting") { send(Protocol.settingScheduleList(), "schedule list") }

    fun scheduleAdd(time: String, tag: Int) {
        val t = time.replace(":", "").toIntOrNull() ?: return
        showSnackbar("Agregando horario…")
        ensureMode("setting") {
            runAcked("setting_schedule_add", "Horario agregado") {
                send(Protocol.settingScheduleAdd(t, tag), "schedule add $t#$tag")
            }
        }
    }

    fun scheduleDel(index: Int) {
        showSnackbar("Eliminando horario…")
        ensureMode("setting") {
            runAcked("setting_schedule_del", "Horario eliminado") {
                send(Protocol.settingScheduleDel(index), "schedule del #$index")
            }
        }
    }

    fun scheduleSwitch(on: Boolean) {
        showSnackbar(if (on) "Activando horario…" else "Desactivando horario…")
        ensureMode("setting") {
            runAcked("setting_schedule_switch", if (on) "Horario activado" else "Horario desactivado") {
                send(Protocol.settingScheduleSwitch(on), "schedule switch ${if (on) "on" else "off"}")
            }
        }
    }

    fun wifiScan() {
        _ui.update { it.copy(wifiScanning = true) }
        ensureMode("setting") { send(Protocol.settingWifiList(), "wifi list") }
    }

    fun wifiStatus() {
        send(Protocol.staQuery(4), "wifi status")
    }

    fun setRobotWifi(ssid: String, password: String) {
        if (_ui.value.wifiConnState == WifiConnState.CONNECTING) return
        _ui.update {
            it.copy(
                wifiConnState = WifiConnState.CONNECTING,
                robotWifiTarget = ssid
            )
        }
        ensureMode("setting") {
            send(Protocol.settingWifiSet(ssid, password), "wifi set $ssid")
        }
        viewModelScope.launch {
            // 1) esperar el ACK del wifiset (los ACK del robot tardan varios segundos)
            val ack = kotlinx.coroutines.withTimeoutOrNull(15000) {
                modeAckFlow.filter { it == "setting_wifiset_ok" }.first()
            }
            if (ack == null) {
                log(LogCat.ERR, "Sin ACK de wifiset para $ssid, se sigue con el poll")
            } else {
                log(LogCat.RX, "wifiset aceptado, polleando estado…")
            }
            // 2) poll cada 2s hasta 20s hasta que wifi.ssid == target (o flag connected)
            repeat(10) {
                if (_ui.value.wifiConnState != WifiConnState.CONNECTING) return@launch
                wifiStatus()
                kotlinx.coroutines.delay(2000)
                val s = _ui.value
                if (s.robotWifi == ssid && s.robotWifiConnected != false) {
                    _ui.update { it.copy(wifiConnState = WifiConnState.CONNECTED) }
                    log(LogCat.SYS, "Robot conectado a $ssid")
                    showSnackbar("Robot conectado a $ssid ✓")
                    return@launch
                }
            }
            if (_ui.value.wifiConnState == WifiConnState.CONNECTING) {
                _ui.update { it.copy(wifiConnState = WifiConnState.FAILED) }
                log(LogCat.ERR, "Timeout: el robot no conectó a $ssid")
                showSnackbar("No se pudo conectar a $ssid")
            }
        }
    }

    fun powerOff() {
        if (_ui.value.conn != ConnState.CONNECTED || poweringOff) return
        poweringOff = true
        powerOffRequestedAt = android.os.SystemClock.elapsedRealtime()
        // 1) off directo, sin exigir modo (BUG-1: ensureMode podía trabarse
        //    y el off se perdía antes de enviarse)
        send(Protocol.settingOff(), "Apagar robot")
        showSnackbar("Apagando robot…")
        powerOffJob?.cancel()
        powerOffJob = viewModelScope.launch {
            val deadline = powerOffRequestedAt + 5000
            // 2) si el robot confirma con "setting_off_ok", el comando fue
            //    aceptado: no reintenta, solo espera la desconexión de éxito.
            //    Sin ACK ni desconexión en ~1.5s → un único reintento tras settingIn()
            val offOk = withAck("setting_off", 1500) { /* ya enviado arriba */ }
            if (offOk) log(LogCat.RX, "Apagado aceptado (setting_off_ok)")
            if (!poweringOff || _ui.value.conn != ConnState.CONNECTED) return@launch
            if (!offOk) {
                log(LogCat.SYS, "Sin desconexión tras 1.5s: reintentando apagado (setting in)")
                send(Protocol.settingIn(), "setting in (reintento apagado)")
                val acked = kotlinx.coroutines.withTimeoutOrNull(1500) {
                    modeAckFlow.filter { it == "setting_in_ok" }.first()
                } != null
                if (!acked) {
                    kotlinx.coroutines.delay(400)
                    log("Sin ACK de setting_in en el reintento, enviando off igual")
                }
                if (!poweringOff || _ui.value.conn != ConnState.CONNECTED) return@launch
                send(Protocol.settingOff(), "Apagar robot (reintento)")
            }
            // 3) esperar hasta el timeout total (~5s) por la desconexión de éxito
            kotlinx.coroutines.delay(
                (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(100)
            )
            if (!poweringOff || _ui.value.conn != ConnState.CONNECTED) return@launch
            poweringOff = false
            log(LogCat.ERR, "El robot no respondió al apagado (sin desconexión BLE)")
            showSnackbar("No respondió — reintentá o tocá al robot")
        }
    }

    /**
     * BUG-1: desconexión BLE con poweringOff activo = el robot se apagó.
     * No se programa reconexión (robot apagado) y la UI pasa a la pantalla
     * de conexión con el snackbar "Robot apagado ✓".
     */
    private fun handlePowerOffDisconnect() {
        poweringOff = false
        powerOffJob?.cancel()
        powerOffJob = null
        reconnectCancelled = true
        reconnectInFlight = false
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        connectTimeoutJob?.cancel()
        rssiJob?.cancel()
        rxTickerJob?.cancel()
        setCurrentMode(null)
        currentDevice = null
        _ui.update {
            it.copy(
                conn = ConnState.DISCONNECTED,
                reconnectAttempt = 0,
                connHint = "Robot apagado ✓",
                info = RobotInfo(),
                wifiConnState = WifiConnState.IDLE,
                robotWifiTarget = null,
                robotWifiConnected = null,
                bleRssi = null,
                lastRxSeconds = -1L
            )
        }
        log(LogCat.SYS, "Robot apagado (desconexión BLE tras el comando off)")
        showSnackbar("Robot apagado ✓")
    }

    fun refreshStatus() {
        handshake()
    }

    // ------------------------------------------------------------------
    // Alarmas
    // ------------------------------------------------------------------
    fun alarmEnter() {
        setCurrentMode("alarm")
        send(Protocol.alarmIn(), "alarm in")
    }
    fun alarmExit() {
        setCurrentMode(null)
        send(Protocol.alarmOut(), "alarm out")
    }
    fun alarmRefresh() = ensureMode("alarm") { send(Protocol.alarmList(), "alarm list") }
    fun alarmAdd(index: Int, time: String) {
        showSnackbar("Enviando alarma…")
        ensureMode("alarm") {
            runAcked("alarm_add", "Alarma agregada") {
                send(Protocol.alarmAdd(index, time), "alarm add #$index $time")
            }
        }
    }
    fun alarmDel(index: Int) {
        showSnackbar("Enviando eliminación…")
        ensureMode("alarm") {
            runAcked("alarm_del", "Alarma eliminada") {
                send(Protocol.alarmDel(index), "alarm del #$index")
            }
        }
    }

    // ------------------------------------------------------------------
    // Luces
    // ------------------------------------------------------------------
    fun lightEnter() {
        setCurrentMode("light")
        send(Protocol.lightIn(), "light in")
    }
    fun lightExit() {
        setCurrentMode(null)
        send(Protocol.lightOut(), "light out")
    }
    fun lightRefresh() = ensureMode("light") { send(Protocol.lightList(), "light list") }
    fun lightOn(id: Int = 0) = ensureMode("light") {
        runAcked("light_on", "Luz prendida") {
            send(Protocol.lightOn(id), "light on #$id")
        }
    }
    fun lightOff(id: Int = 0) = ensureMode("light") {
        runAcked("light_off", "Luz apagada") {
            send(Protocol.lightOff(id), "light off #$id")
        }
    }
    fun lightSet(mode: String, color: List<Int>, brightness: Int) =
        ensureMode("light") {
            send(Protocol.lightSet(0, mode, color, brightness), "light set $mode $color @$brightness")
        }

    // ------------------------------------------------------------------
    // Juegos (chess | snake | pirate | zero)
    // ------------------------------------------------------------------
    fun gameEnter(game: String) {
        setCurrentMode(game)
        send(Protocol.gameIn(game), "$game in")
    }
    fun gameExit(game: String) {
        setCurrentMode(null)
        send(Protocol.gameOut(game), "$game out")
    }
    fun gameStart(game: String) =
        ensureMode(game) { send(Protocol.gameStart(game), "$game start") }
    fun gamePlay(game: String) =
        ensureMode(game) { send(Protocol.gamePlay(game), "$game play") }

    // ------------------------------------------------------------------
    // Fotos (TCP)
    // ------------------------------------------------------------------
    fun photoEnter() {
        setCurrentMode("photo")
        send(Protocol.photoIn(), "photo in")
    }
    fun photoExit() {
        setCurrentMode(null)
        send(Protocol.photoOut(), "photo out")
    }
    fun photoShow() = ensureMode("photo") { send(Protocol.photoShow(), "photo show") }
    fun photoClear() = ensureMode("photo") { send(Protocol.photoClear(), "photo clear") }

    fun startPhotoSync() {
        if (photoServer?.isRunning == true) {
            log("El servidor de fotos ya está corriendo")
            return
        }
        val server = PhotoTcpServer(
            outputDir = photoDir,
            onPhoto = { file ->
                _ui.update { it.copy(photos = it.photos + file.absolutePath) }
                showSnackbar("Foto guardada: ${file.name}")
            },
            onLog = ::log
        )
        photoServer = server
        server.start()
        _ui.update { it.copy(photoServerRunning = true) }
        // Obtener IP wifi local y pedir al robot que envíe
        val ip = getWifiIp()
        if (ip == null) {
            log("No se pudo obtener la IP local. ¿Estás en WiFi?")
            return
        }
        log("Pidiendo al robot que envíe fotos a $ip:${PhotoTcpServer.PORT}")
        ensureMode("photo") {
            send(Protocol.photoSync(ip, PhotoTcpServer.PORT), "photo sync $ip:9090")
        }
    }

    fun stopPhotoSync() {
        photoServer?.stop()
        photoServer = null
        _ui.update { it.copy(photoServerRunning = false) }
    }

    private fun getWifiIp(): String? {
        return try {
            val app = getApplication<Application>()
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return null
            val props = cm.getLinkProperties(network) ?: return null
            props.linkAddresses
                .firstOrNull { it.address is java.net.Inet4Address }
                ?.address
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        poweringOff = false
        powerOffJob?.cancel()
        powerOffJob = null
        reconnectCancelled = true
        reconnectInFlight = false
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        rssiJob?.cancel()
        rxTickerJob?.cancel()
        setCurrentMode(null)
        stopPhotoSync()
        ble.disconnect()
        currentDevice = null
        _ui.update {
            it.copy(
                conn = ConnState.DISCONNECTED,
                reconnectAttempt = 0,
                connHint = null,
                info = RobotInfo(),
                wifiConnState = WifiConnState.IDLE,
                robotWifiTarget = null,
                robotWifiConnected = null,
                bleRssi = null,
                lastRxSeconds = -1L
            )
        }
        log(LogCat.SYS, "Desconectado")
    }

    private fun send(bytes: ByteArray, label: String) {
        lastTxAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("AibiBle", "TX $label [${bytes.size} bytes] ${bytes.toHex()}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ble.write(bytes)
                log(LogCat.TX, "TX $label [${bytes.size} bytes]")
            } catch (e: Exception) {
                log(LogCat.ERR, "Error enviando $label: ${e.message}")
                showSnackbar("Error enviando…")
            }
        }
    }

    private fun log(line: String) = log(LogCat.SYS, line)

    private fun log(cat: LogCat, line: String) {
        _ui.update { s ->
            val maxLines = 200
            s.copy(log = (s.log + LogLine(cat, line)).takeLast(maxLines))
        }
    }

    fun clearLog() {
        _ui.update { it.copy(log = emptyList()) }
    }

    // ------------------------------------------------------------------
    // Laboratorio (spec C4): consola JSON raw
    // ------------------------------------------------------------------
    private val rawHistorySerializer = ListSerializer(String.serializer())

    private fun loadRawHistory(): List<String> {
        val raw = prefs().getString(KEY_RAW_HISTORY, null) ?: return emptyList()
        return try {
            Protocol.json.decodeFromString(rawHistorySerializer, raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Envía un comando JSON crudo al robot (consola Laboratorio). Valida el
     * JSON con Protocol.json, lo envuelve con Protocol.frame y lo envía por
     * el camino send() normal (TX/RX visibles en el Log BLE). Devuelve false
     * si el JSON es inválido (y muestra el error).
     */
    fun sendRaw(json: String): Boolean {
        val text = json.trim()
        if (text.isEmpty()) return false
        return try {
            Protocol.json.parseToJsonElement(text)
            val updated = (_ui.value.rawHistory + text).takeLast(MAX_RAW_HISTORY)
            _ui.update { it.copy(rawHistory = updated, lastRawResponse = null) }
            prefs().edit()
                .putString(KEY_RAW_HISTORY, Protocol.json.encodeToString(rawHistorySerializer, updated))
                .apply()
            rawPending = true
            send(Protocol.frame(text), "raw ${text.take(40)}")
            true
        } catch (e: Exception) {
            log(LogCat.ERR, "JSON inválido: ${e.message}")
            showSnackbar("JSON inválido: ${e.message}")
            false
        }
    }

    fun clearRawHistory() {
        _ui.update { it.copy(rawHistory = emptyList()) }
        prefs().edit().remove(KEY_RAW_HISTORY).apply()
    }

    // ------------------------------------------------------------------
    // Barrido motion (sondeo DD CC): barre cmd 0..255 del frame binario
    // de movimiento con 1.5s entre envíos. Las respuestas binarias del
    // robot se loguean completas en logcat (tag AibiBle).
    // ------------------------------------------------------------------
    private var motionSweepJob: kotlinx.coroutines.Job? = null

    private val dangerousMotionCmds = setOf(24, 25, 26, 97, 98, 99, 100)

    fun startMotionSweep(from: Int, to: Int, confirmed: Boolean = false) {
        if (from !in 0..255 || to !in 0..255 || from > to) return
        if (!confirmed && (from..to).any { it in dangerousMotionCmds }) {
            showSnackbar("El rango incluye comandos de fábrica peligrosos")
            return
        }
        if (_ui.value.motionSweeping) return
        motionSweepJob?.cancel()
        _ui.update { it.copy(motionSweeping = true, motionSweepCmd = from) }
        motionSweepJob = viewModelScope.launch {
            for (cmd in from..to) {
                _ui.update { it.copy(motionSweepCmd = cmd) }
                send(Protocol.motion(cmd), "motion cmd $cmd")
                kotlinx.coroutines.delay(1500)
            }
            _ui.update { it.copy(motionSweeping = false, motionSweepCmd = null) }
            showSnackbar("Barrido completo")
        }
    }

    fun stopMotionSweep() {
        if (!_ui.value.motionSweeping) return
        motionSweepJob?.cancel()
        _ui.update { it.copy(motionSweeping = false, motionSweepCmd = null) }
        showSnackbar("Barrido detenido")
    }

    private var pendingMotionCmd: Int? = null

    fun scheduleMotion(cmd: Int) {
        pendingMotionCmd = cmd
        if (_ui.value.conn == ConnState.CONNECTED) firePendingMotion()
    }

    private fun firePendingMotion() {
        val cmd = pendingMotionCmd ?: return
        pendingMotionCmd = null
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            send(Protocol.motion(cmd), "motion cmd $cmd")
        }
    }

    // ------------------------------------------------------------------
    // Chat con IA (LLM -> el robot habla la respuesta)
    // ------------------------------------------------------------------
    data class LlmConfig(
        val baseUrl: String = "https://api.openai.com/v1/chat/completions",
        val apiKey: String = "",
        val model: String = "gpt-4o-mini",
        val systemPrompt: String? = null
    )

    private val llmClient = okhttp3.OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun loadLlmConfig(): LlmConfig = LlmConfig(
        baseUrl = prefs().getString("llm_url", LlmConfig().baseUrl) ?: LlmConfig().baseUrl,
        apiKey = prefs().getString("llm_key", "") ?: "",
        model = prefs().getString("llm_model", LlmConfig().model) ?: LlmConfig().model,
        systemPrompt = prefs().getString("llm_prompt", null)
    )

    fun saveLlmConfig(cfg: LlmConfig) {
        prefs().edit()
            .putString("llm_url", cfg.baseUrl)
            .putString("llm_key", cfg.apiKey)
            .putString("llm_model", cfg.model)
            .putString("llm_prompt", cfg.systemPrompt)
            .apply()
    }

    /**
     * Prueba la conexión con el endpoint LLM: POST mínimo OpenAI-compatible
     * con un único mensaje "ping" y timeout de 15s. El callback se invoca en
     * el hilo principal con (ok, mensaje legible).
     */
    fun testLlmConnection(cfg: LlmConfig, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val result: Pair<Boolean, String> = try {
                val body = buildJsonObject {
                    put("model", cfg.model)
                    put("messages", JsonArray(listOf(buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    })))
                }.toString()
                val request = okhttp3.Request.Builder()
                    .url(cfg.baseUrl)
                    .header("Authorization", "Bearer ${cfg.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        true to "Conexión OK"
                    } else {
                        false to "HTTP ${resp.code}: ${resp.message}"
                    }
                }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(result.first, result.second)
            }
        }
    }

    fun sendChatMessage(text: String) {
        if (text.isBlank() || _ui.value.chatThinking) return
        val userMsg = ChatMsg("user", text.trim())
        _ui.update { it.copy(chat = it.chat + userMsg, chatThinking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val reply = try {
                askLlm()
            } catch (e: Exception) {
                android.util.Log.e("AibiBle", "LLM error: ${e.message}", e)
                null
            }
            _ui.update { it.copy(chatThinking = false) }
            if (reply.isNullOrBlank()) {
                log("La IA no respondió. ¿Configuraste la API key en el chat?")
            } else {
                val aiMsg = ChatMsg("assistant", reply)
                _ui.update { it.copy(chat = it.chat + aiMsg) }
                // El robot dice la respuesta en voz alta
                ensureMode("show") { send(Protocol.showSpeak(reply.take(400)), "IA: ${reply.take(40)}…") }
            }
        }
    }

    private fun askLlm(): String? {
        val cfg = loadLlmConfig()
        if (cfg.apiKey.isBlank()) return null
        val history = _ui.value.chat.takeLast(8)
        val info = _ui.value.info
        val ctx = buildString {
            append("Estado actual del robot: ")
            append("batería=${batteryLabel(info.battery)}, pasos=${info.steps}, monedas=${info.gold}, ")
            append("comida=${info.food}, hora=${java.time.LocalTime.now()}")
        }
        val sysPrompt = (cfg.systemPrompt ?: DEFAULT_LLM_PROMPT) + "\n\n" + ctx
        val body = buildJsonObject {
            put("model", cfg.model)
            put("messages", JsonArray(
                listOf(buildJsonObject {
                    put("role", "system")
                    put("content", sysPrompt)
                }) + history.map { m ->
                    buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
            ))
        }.toString()
        val request = okhttp3.Request.Builder()
            .url(cfg.baseUrl)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        llmClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.e("AibiBle", "LLM HTTP ${resp.code}: ${resp.message}")
                return null
            }
            val text = resp.body?.string() ?: return null
            return Protocol.json.parseToJsonElement(text).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
        }
    }

    // ------------------------------------------------------------------
    // Escenas (macros: luz + animación + TTS)
    // ------------------------------------------------------------------
    fun playScene(scene: String) {
        viewModelScope.launch {
            when (scene) {
                "fiesta" -> {
                    ensureMode("light") { send(Protocol.lightSet(0, "flow", listOf(255, 80, 180), 90), "Escena: luz fiesta") }
                    kotlinx.coroutines.delay(1000)
                    ensureMode("show") { send(Protocol.showPlay("dance_ai1"), "Escena: baile") }
                    kotlinx.coroutines.delay(1500)
                    ensureMode("show") { send(Protocol.showSpeak("¡A bailar!"), "Escena: voz") }
                }
                "despertar" -> {
                    ensureMode("light") { send(Protocol.lightOn(0), "Escena: luz") }
                    kotlinx.coroutines.delay(800)
                    ensureMode("show") { send(Protocol.showSpeak("¡Buenos días! Que tengas un gran día."), "Escena: voz") }
                }
                "relax" -> {
                    ensureMode("light") { send(Protocol.lightSet(0, "breath", listOf(80, 140, 255), 40), "Escena: luz relax") }
                    kotlinx.coroutines.delay(1000)
                    ensureMode("show") { send(Protocol.showSpeak("Respira profundo... adentro... y afuera."), "Escena: voz") }
                }
                "noche" -> {
                    ensureMode("show") { send(Protocol.showSpeak("Buenas noches, dulces sueños."), "Escena: voz") }
                    kotlinx.coroutines.delay(1500)
                    ensureMode("light") { send(Protocol.lightOff(0), "Escena: luz off") }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Rutina pendiente (spec C3): la notificación del worker abre la app
    // con run_routine_id; se conecta con la máquina normal y ejecuta la
    // acción por el mismo camino que la UI (ensureMode + send / playScene).
    // ------------------------------------------------------------------
    fun runPendingRoutine(id: String) {
        val routine = RoutinesStore.load(getApplication()).firstOrNull { it.id == id }
        if (routine == null) {
            showSnackbar("Rutina no encontrada")
            return
        }
        log("Rutina pendiente: ${routine.time} (${routine.action.type})")
        viewModelScope.launch {
            if (_ui.value.conn != ConnState.CONNECTED) {
                if (!tryAutoReconnect()) {
                    showSnackbar("No se pudo ejecutar la rutina: robot no disponible")
                    return@launch
                }
                val connected = kotlinx.coroutines.withTimeoutOrNull(20000) {
                    ui.filter { it.conn == ConnState.CONNECTED }.first()
                } != null
                if (!connected) {
                    showSnackbar("No se pudo ejecutar la rutina: robot no conectado")
                    return@launch
                }
            }
            executeRoutine(routine)
            showSnackbar("Rutina ejecutada")
        }
    }

    private suspend fun executeRoutine(routine: Routine) {
        when (routine.action.type) {
            "speak" -> ensureMode("show") {
                send(Protocol.showSpeak(routine.action.payload.take(400)), "Rutina: hablar")
            }
            "animation" -> ensureMode("show") {
                send(Protocol.showPlay(routine.action.payload), "Rutina: animación")
            }
            "scene" -> playScene(routine.action.payload)
            "light" -> ensureMode("light") {
                if (routine.action.payload == "on") {
                    send(Protocol.lightOn(0), "Rutina: luz on")
                } else {
                    send(Protocol.lightOff(0), "Rutina: luz off")
                }
            }
            else -> log(LogCat.ERR, "Acción de rutina desconocida: ${routine.action.type}")
        }
    }

    override fun onCleared() {
        stopPhotoSync()
        ble.disconnect()
        super.onCleared()
    }
}

fun ByteArray.toHex(): String =
    joinToString(" ") { "%02x".format(it) }
