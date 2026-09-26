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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

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
    val photoServerRunning: Boolean = false,
    val photos: List<String> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatThinking: Boolean = false,
    val themeMode: String = "system",
    val remoteRunning: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val rawHistory: List<String> = emptyList(),
    val motionSweeping: Boolean = false,
    val motionSweepCmd: Int? = null,
    val wifiNetworks: List<String> = emptyList(),
    val wifiScanning: Boolean = false,
    val robotWifi: String? = null,
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
    private var snackbarNonce = 0
    private var reconnectAttempt = 0
    private var reconnectCancelled = false
    private var reconnectInFlight = false
    private var diagnosePending = false
    private var connectTimeoutJob: kotlinx.coroutines.Job? = null
    private var lastRxAt = 0L
    private var lastTxAt = 0L
    private var lowBatteryNotified = false

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
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        handshake()
                    }
                    firePendingMotion()
                } else {
                    if (reconnectCancelled) {
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
                        currentMode = null
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
        // respuesta de wifilist: el formato real se aprende en vivo (lista en "list" o similar)
        if (result.contains("wifilist", ignoreCase = true)) {
            val list = data["list"]?.jsonArray
            val networks = mutableListOf<String>()
            if (list != null) {
                for (el in list) {
                    val obj = el as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["ssid"]?.jsonPrimitive?.contentOrNull
                    if (name != null) networks.add(name)
                }
            }
            _ui.update { it.copy(wifiNetworks = networks, wifiScanning = false) }
            log(LogCat.RX, "WiFi list: ${networks.joinToString()}")
        }
    }

    private fun parseAlarmRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull
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
        val result = data["result"]?.jsonPrimitive?.contentOrNull
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
            _ui.update { s ->
                s.copy(
                    robotWifi = wifiSsid ?: s.robotWifi,
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
        currentMode = mode
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
        currentMode = null
        send(modeOut(mode), "$mode out")
    }

    fun setVolume(level: String) {
        _ui.update { it.copy(volume = level) }
        ensureMode("setting") {
            send(Protocol.settingVolume(level), "Volumen: $level")
            showSnackbar("Volumen ok")
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
        ensureMode("setting") {
            send(Protocol.settingWifiSet(ssid, password), "wifi set $ssid")
            showSnackbar("Conectando al robot a $ssid…")
        }
    }

    fun powerOff() {
        ensureMode("setting") {
            send(Protocol.settingOff(), "Apagar robot")
            showSnackbar("Apagando robot…")
        }
    }

    fun refreshStatus() {
        handshake()
    }

    // ------------------------------------------------------------------
    // Alarmas
    // ------------------------------------------------------------------
    fun alarmEnter() {
        currentMode = "alarm"
        send(Protocol.alarmIn(), "alarm in")
    }
    fun alarmExit() {
        currentMode = null
        send(Protocol.alarmOut(), "alarm out")
    }
    fun alarmRefresh() = ensureMode("alarm") { send(Protocol.alarmList(), "alarm list") }
    fun alarmAdd(index: Int, time: String) =
        ensureMode("alarm") {
            send(Protocol.alarmAdd(index, time), "alarm add #$index $time")
            showSnackbar("Alarma agregada")
        }
    fun alarmDel(index: Int) =
        ensureMode("alarm") {
            send(Protocol.alarmDel(index), "alarm del #$index")
            showSnackbar("Alarma eliminada")
        }

    // ------------------------------------------------------------------
    // Luces
    // ------------------------------------------------------------------
    fun lightEnter() {
        currentMode = "light"
        send(Protocol.lightIn(), "light in")
    }
    fun lightExit() {
        currentMode = null
        send(Protocol.lightOut(), "light out")
    }
    fun lightRefresh() = ensureMode("light") { send(Protocol.lightList(), "light list") }
    fun lightOn(id: Int = 0) = ensureMode("light") { send(Protocol.lightOn(id), "light on #$id") }
    fun lightOff(id: Int = 0) = ensureMode("light") { send(Protocol.lightOff(id), "light off #$id") }
    fun lightSet(mode: String, color: List<Int>, brightness: Int) =
        ensureMode("light") {
            send(Protocol.lightSet(0, mode, color, brightness), "light set $mode $color @$brightness")
        }

    // ------------------------------------------------------------------
    // Juegos (chess | snake | pirate | zero)
    // ------------------------------------------------------------------
    fun gameEnter(game: String) {
        currentMode = game
        send(Protocol.gameIn(game), "$game in")
    }
    fun gameExit(game: String) {
        currentMode = null
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
        currentMode = "photo"
        send(Protocol.photoIn(), "photo in")
    }
    fun photoExit() {
        currentMode = null
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
        reconnectCancelled = true
        reconnectInFlight = false
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        currentMode = null
        stopPhotoSync()
        ble.disconnect()
        currentDevice = null
        _ui.update {
            it.copy(
                conn = ConnState.DISCONNECTED,
                reconnectAttempt = 0,
                connHint = null,
                info = RobotInfo()
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
            _ui.update { it.copy(rawHistory = updated) }
            prefs().edit()
                .putString(KEY_RAW_HISTORY, Protocol.json.encodeToString(rawHistorySerializer, updated))
                .apply()
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

    fun startMotionSweep(from: Int, to: Int) {
        if (from !in 0..255 || to !in 0..255 || from > to) return
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
