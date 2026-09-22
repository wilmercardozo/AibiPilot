package com.wil.aibipilot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wil.aibipilot.ble.BleClient
import com.wil.aibipilot.ble.BleEvent
import com.wil.aibipilot.ble.PhotoTcpServer
import com.wil.aibipilot.ble.ScanDevice
import com.wil.aibipilot.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

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

data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val devices: List<ScanDevice> = emptyList(),
    val info: RobotInfo = RobotInfo(),
    val log: List<LogLine> = emptyList(),
    val volume: String = "high",
    val alarms: List<AlarmItem> = emptyList(),
    val lights: List<LightItem> = emptyList(),
    val photoServerRunning: Boolean = false,
    val photos: List<String> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatThinking: Boolean = false
)

class RobotViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PREFS = "aibi_pilot_prefs"
        private const val KEY_MAC = "last_device_mac"
        private const val KEY_NAME = "last_device_name"
    }

    val ble = BleClient(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentDevice: android.bluetooth.BluetoothDevice? = null
    private var scanJob: kotlinx.coroutines.Job? = null

    private val modeAckFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 16
    )

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun savedDeviceName(): String? = prefs().getString(KEY_NAME, null)

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
        }
    }

    fun connect(device: ScanDevice) = connectDevice(device.device, device.device.name)

    /**
     * Reconexión automática al último robot guardado (sin escanear).
     * Devuelve false si no hay robot guardado.
     */
    fun tryAutoReconnect(): Boolean {
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

    private fun connectDevice(device: android.bluetooth.BluetoothDevice, deviceName: String?) {
        android.util.Log.d("AibiBle", "VM.connect() called for ${device.address}")
        scanJob?.cancel()
        ble.stopScan()
        _ui.update { it.copy(conn = ConnState.CONNECTING, devices = emptyList()) }
        currentDevice = device
        log("Conectando a ${deviceName ?: "(sin nombre)"} (${device.address})")
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
                    _ui.update { it.copy(conn = ConnState.CONNECTED) }
                    log("Conectado. MTU: ${ble.currentMtu()}")
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        handshake()
                    }
                } else {
                    // fallo de conexión o desconexión del robot
                    _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
                    log("Se perdió la conexión BLE")
                }
            }
        )
        // Timeout de conexión: si en 12s no conecta, volver al escaneo
        viewModelScope.launch {
            kotlinx.coroutines.delay(12000)
            if (_ui.value.conn == ConnState.CONNECTING) {
                log("Timeout de conexión. ¿El robot está despierto? ¿Otra app lo tiene conectado?")
                ble.disconnect()
                _ui.update { it.copy(conn = ConnState.DISCONNECTED) }
            }
        }
    }

    private fun handshake() {
        val payload = Protocol.staQuery(1, 8, 11, 12)
        send(payload, "handshake sta_req query[1,8,11,12]")
    }

    private fun onBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.JsonMessage -> {
                log(LogCat.RX, "RX ${event.json}")
                parseResponse(event.json)
            }
            is BleEvent.RawMessage -> {
                log(LogCat.RX, "RX binario: ${event.bytes.toHex().take(64)}")
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
                    currentMode = null
                    log("El robot salió del modo de función")
                }
            }
            "alarm_rsp" -> parseAlarmRsp(root)
            "light_rsp" -> parseLightRsp(root)
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
            _ui.update { s ->
                s.copy(
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
        } else if (result != null) {
            log("Respuesta: $result")
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
        ensureMode("setting") { send(Protocol.settingVolume(level), "Volumen: $level") }
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
        ensureMode("alarm") { send(Protocol.alarmAdd(index, time), "alarm add #$index $time") }
    fun alarmDel(index: Int) =
        ensureMode("alarm") { send(Protocol.alarmDel(index), "alarm del #$index") }

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
        send(Protocol.photoSync(ip, PhotoTcpServer.PORT), "photo sync $ip:9090")
    }

    fun stopPhotoSync() {
        photoServer?.stop()
        photoServer = null
        _ui.update { it.copy(photoServerRunning = false) }
    }

    private fun getWifiIp(): String? {
        return try {
            val app = getApplication<Application>()
            val wifiManager = app.getSystemService(android.content.Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) null
            else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        currentMode = null
        stopPhotoSync()
        ble.disconnect()
        currentDevice = null
        _ui.update { it.copy(conn = ConnState.DISCONNECTED, info = RobotInfo()) }
        log("Desconectado")
    }

    private fun send(bytes: ByteArray, label: String) {
        android.util.Log.d("AibiBle", "TX $label [${bytes.size} bytes] ${bytes.toHex()}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ble.write(bytes)
                log(LogCat.TX, "TX $label [${bytes.size} bytes]")
            } catch (e: Exception) {
                log(LogCat.ERR, "Error enviando $label: ${e.message}")
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
    // Chat con IA (LLM -> el robot habla la respuesta)
    // ------------------------------------------------------------------
    data class LlmConfig(
        val baseUrl: String = "https://api.openai.com/v1/chat/completions",
        val apiKey: String = "",
        val model: String = "gpt-4o-mini"
    )

    private val llmClient = okhttp3.OkHttpClient.Builder()
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun loadLlmConfig(): LlmConfig = LlmConfig(
        baseUrl = prefs().getString("llm_url", LlmConfig().baseUrl) ?: LlmConfig().baseUrl,
        apiKey = prefs().getString("llm_key", "") ?: "",
        model = prefs().getString("llm_model", LlmConfig().model) ?: LlmConfig().model
    )

    fun saveLlmConfig(cfg: LlmConfig) {
        prefs().edit()
            .putString("llm_url", cfg.baseUrl)
            .putString("llm_key", cfg.apiKey)
            .putString("llm_model", cfg.model)
            .apply()
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
        val sysPrompt = "Eres AIBI, una mascota robot adorable y pequeña con gran personalidad. " +
            "Respondes en español, de forma breve (máximo 2 frases), cálida y con humor. " +
            "Te gusta jugar, animar a tu dueño y hacer bromas tiernas."
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

    override fun onCleared() {
        stopPhotoSync()
        ble.disconnect()
        super.onCleared()
    }
}

fun ByteArray.toHex(): String =
    joinToString(" ") { "%02x".format(it) }
