package com.wil.aibipilot.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.wil.aibipilot.AlarmItem
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.RobotInfo
import com.wil.aibipilot.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.cancellation.CancellationException

data class RemoteState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val info: RobotInfo = RobotInfo(),
    val currentMode: String? = null
)

class RemoteController(private val context: Context) {

    companion object {
        private const val TAG = "AibiRemote"
        private const val REQUEST_TIMEOUT_MS = 8000L
        private const val RAW_TIMEOUT_MS = 6000L
        private const val MODE_ACK_TIMEOUT_MS = 4000L
        private const val MODE_FALLBACK_MS = 700L
        private const val CONNECT_TIMEOUT_MS = 12000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private val RECONNECT_BACKOFF = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)
        private val GAMES = setOf("chess", "snake", "pirate", "zero")
    }

    private val ble = BleClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val requestMutex = Mutex()
    private var pending: CompletableDeferred<String>? = null
    private var alarmsPending: CompletableDeferred<List<AlarmItem>>? = null
    private var alarmsCache: List<AlarmItem> = emptyList()

    private val modeAckFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val rxEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentDevice: BluetoothDevice? = null

    @Volatile private var currentMode: String? = null
    @Volatile private var lastRxAt = 0L
    @Volatile private var lastTxAt = 0L
    @Volatile private var reconnectInFlight = false
    @Volatile private var reconnectCancelled = false
    private var reconnectAttempt = 0
    private var savedMac: String? = null
    private var savedName: String? = null

    suspend fun connect(mac: String, name: String?) {
        val device = try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            Log.e(TAG, "connect: ${e.message}")
            _state.update { it.copy(conn = ConnState.DISCONNECTED) }
            return
        }
        startConnection(device, mac, name, isReconnect = false)
    }

    fun disconnect() {
        reconnectCancelled = true
        reconnectInFlight = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        currentMode = null
        scope.launch {
            requestMutex.withLock {
                pending?.completeExceptionally(IllegalStateException("desconectado"))
                pending = null
                alarmsPending?.completeExceptionally(IllegalStateException("desconectado"))
                alarmsPending = null
            }
        }
        _state.update { it.copy(conn = ConnState.DISCONNECTED, currentMode = null, info = RobotInfo()) }
        ble.disconnect()
        currentDevice = null
        savedMac = null
        savedName = null
    }

    suspend fun speak(text: String): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("texto vacío"))
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("show") { sendExpectOk(Protocol.showSpeak(text.trim())) }
    }

    suspend fun play(animation: String): Result<Unit> {
        if (animation.isBlank()) return Result.failure(IllegalArgumentException("animación vacía"))
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("show") { sendExpectOk(Protocol.showPlay(animation)) }
    }

    suspend fun setLight(mode: String, color: List<Int>, brightness: Int): Result<Unit> {
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("light") { sendExpectOk(Protocol.lightSet(0, mode, color, brightness)) }
    }

    suspend fun lightOn(id: Int = 0): Result<Unit> {
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("light") { sendExpectOk(Protocol.lightOn(id)) }
    }

    suspend fun lightOff(id: Int = 0): Result<Unit> {
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("light") { sendExpectOk(Protocol.lightOff(id)) }
    }

    suspend fun setVolume(level: String): Result<Unit> {
        if (level !in setOf("mute", "low", "high")) {
            return Result.failure(IllegalArgumentException("volumen inválido: $level"))
        }
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("setting") { sendExpectOk(Protocol.settingVolume(level)) }
    }

    suspend fun alarmsList(): List<AlarmItem> {
        if (_state.value.conn != ConnState.CONNECTED) return alarmsCache
        return requestMutex.withLock {
            val deferred = CompletableDeferred<List<AlarmItem>>()
            alarmsPending = deferred
            lastTxAt = SystemClock.elapsedRealtime()
            val payload = Protocol.alarmList()
            Log.d(TAG, "TX ${payload.toHex()}")
            try {
                ble.write(payload)
            } catch (e: Exception) {
                if (alarmsPending === deferred) alarmsPending = null
                Log.e(TAG, "alarm list write error: ${e.message}")
                return@withLock alarmsCache
            }
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    alarmsCache
                }
            } ?: alarmsCache
            if (alarmsPending === deferred) alarmsPending = null
            result
        }
    }

    suspend fun alarmAdd(tag: Int, time: String): Result<Unit> {
        if (tag !in 0..6) return Result.failure(IllegalArgumentException("tag inválido: $tag"))
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("alarm") { sendExpectOk(Protocol.alarmAdd(tag, time)) }
    }

    suspend fun alarmDel(index: Int): Result<Unit> {
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode("alarm") { sendExpectOk(Protocol.alarmDel(index)) }
    }

    suspend fun game(type: String, op: String): Result<Unit> {
        if (type !in GAMES) return Result.failure(IllegalArgumentException("juego inválido: $type"))
        if (op == "out") {
            val guard = guardConnected()
            if (guard.isFailure) return guard
            return requestMutex.withLock {
                currentMode = null
                _state.update { it.copy(currentMode = null) }
                sendExpectOk(Protocol.gameOut(type))
            }
        }
        if (op == "in") {
            val guard = guardConnected()
            if (guard.isFailure) return guard
            return requestMutex.withLock {
                if (currentMode != type) {
                    currentMode = type
                    _state.update { it.copy(currentMode = type) }
                    writeNoLock(Protocol.gameIn(type))
                    waitModeAck(type)
                }
                Result.success(Unit)
            }
        }
        val bytes = when (op) {
            "start" -> Protocol.gameStart(type)
            "play" -> Protocol.gamePlay(type)
            else -> return Result.failure(IllegalArgumentException("op inválida: $op"))
        }
        val guard = guardConnected()
        if (guard.isFailure) return guard
        return withMode(type) { sendExpectOk(bytes) }
    }

    suspend fun scene(id: String): Result<Unit> {
        if (_state.value.conn != ConnState.CONNECTED) {
            return Result.failure(IllegalStateException("robot no conectado"))
        }
        var firstFailure: Result<Unit>? = null
        suspend fun step(r: Result<Unit>): Result<Unit> {
            if (r.isFailure && firstFailure == null) firstFailure = r
            return r
        }
        when (id) {
            "fiesta" -> {
                step(setLight("flow", listOf(255, 80, 180), 90))
                delay(1000)
                step(play("dance_ai1"))
                delay(1500)
                step(speak("¡A bailar!"))
            }
            "despertar" -> {
                step(lightOn(0))
                delay(800)
                step(speak("¡Buenos días! Que tengas un gran día."))
            }
            "relax" -> {
                step(setLight("breath", listOf(80, 140, 255), 40))
                delay(1000)
                step(speak("Respira profundo... adentro... y afuera."))
            }
            "noche" -> {
                step(speak("Buenas noches, dulces sueños."))
                delay(1500)
                step(lightOff(0))
            }
            else -> return Result.failure(IllegalArgumentException("escena desconocida: $id"))
        }
        return firstFailure ?: Result.success(Unit)
    }

    suspend fun sendRaw(json: String): String? = requestMutex.withLock {
        requestNoLock(Protocol.frame(json), RAW_TIMEOUT_MS)
    }

    private fun startConnection(
        device: BluetoothDevice,
        mac: String,
        name: String?,
        isReconnect: Boolean
    ) {
        currentDevice = device
        savedMac = mac
        savedName = name ?: device.name
        if (!isReconnect) {
            reconnectCancelled = false
            reconnectInFlight = false
            reconnectAttempt = 0
            reconnectJob?.cancel()
        }
        _state.update {
            it.copy(
                conn = if (isReconnect) ConnState.RECONNECTING else ConnState.CONNECTING,
                info = it.info.copy(deviceName = name ?: device.name ?: "")
            )
        }
        Log.d(TAG, "connectGatt -> ${device.name} ($mac)")
        ble.connect(device, onEvent = ::onBleEvent, onState = ::onConnectionState)
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val c = _state.value.conn
            if (c == ConnState.CONNECTING || c == ConnState.RECONNECTING) {
                Log.e(TAG, "timeout de conexión")
                ble.disconnect()
                if (reconnectInFlight) scheduleReconnect()
                else _state.update { it.copy(conn = ConnState.DISCONNECTED) }
            }
        }
    }

    private fun onConnectionState(connected: Boolean) {
        if (connected) {
            reconnectInFlight = false
            reconnectAttempt = 0
            currentMode = null
            _state.update { it.copy(conn = ConnState.CONNECTED, currentMode = null) }
            Log.d(TAG, "conectado, MTU=${ble.currentMtu()}")
            startKeepAlive()
            scope.launch {
                delay(1000)
                handshake()
            }
        } else {
            currentMode = null
            _state.update { it.copy(currentMode = null) }
            if (reconnectCancelled) {
                _state.update { it.copy(conn = ConnState.DISCONNECTED) }
            } else if (reconnectInFlight || _state.value.conn == ConnState.CONNECTED) {
                scheduleReconnect()
            } else {
                _state.update { it.copy(conn = ConnState.DISCONNECTED) }
                Log.e(TAG, "no se pudo conectar")
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob?.cancel()
        reconnectAttempt++
        if (reconnectCancelled || reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectInFlight = false
            _state.update { it.copy(conn = ConnState.DISCONNECTED) }
            Log.e(TAG, "reconexión agotada (${reconnectAttempt - 1} intentos)")
            return
        }
        reconnectInFlight = true
        val delayMs = RECONNECT_BACKOFF[minOf(reconnectAttempt - 1, RECONNECT_BACKOFF.size - 1)]
        _state.update { it.copy(conn = ConnState.RECONNECTING) }
        Log.d(TAG, "reconexión $reconnectAttempt en ${delayMs / 1000}s")
        reconnectJob = scope.launch {
            delay(delayMs)
            val mac = savedMac
            if (mac == null) {
                reconnectInFlight = false
                _state.update { it.copy(conn = ConnState.DISCONNECTED) }
                return@launch
            }
            val device = try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bm.adapter.getRemoteDevice(mac)
            } catch (e: Exception) {
                null
            }
            if (device == null) {
                reconnectInFlight = false
                _state.update { it.copy(conn = ConnState.DISCONNECTED) }
                return@launch
            }
            startConnection(device, mac, savedName, isReconnect = true)
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(5000)
                val now = SystemClock.elapsedRealtime()
                val idle = now - lastRxAt > 20000 && now - lastTxAt > 20000
                if (_state.value.conn == ConnState.CONNECTED && currentMode == null && idle) {
                    val pingAt = lastRxAt
                    Log.d(TAG, "sin tráfico del robot en 20s: ping de verificación")
                    val responded = withTimeoutOrNull(10000) {
                        requestMutex.withLock { writeNoLock(Protocol.staQuery(12)) }
                        rxEvents.filter { lastRxAt > pingAt }.first()
                        true
                    } ?: false
                    if (!responded) {
                        Log.e(TAG, "el robot no responde al ping: forzando reconexión")
                        reconnectInFlight = true
                        ble.disconnect()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private suspend fun handshake() {
        requestMutex.withLock {
            requestNoLock(Protocol.staQuery(1, 8, 11, 12), REQUEST_TIMEOUT_MS)
        }
    }

    private fun onBleEvent(event: BleEvent) {
        lastRxAt = SystemClock.elapsedRealtime()
        rxEvents.tryEmit(Unit)
        when (event) {
            is BleEvent.JsonMessage -> parseResponse(event.json)
            is BleEvent.RawMessage -> Unit
        }
    }

    private fun parseResponse(jsonStr: String) {
        Log.d(TAG, "RX $jsonStr")
        try {
            val root = Protocol.json.parseToJsonElement(jsonStr).jsonObject
            when (root["type"]?.jsonPrimitive?.contentOrNull) {
                "sta_rsp" -> parseStaRsp(root)
                "aibi_event" -> {
                    val eventName = root["data"]?.jsonObject?.get("event")
                        ?.jsonPrimitive?.contentOrNull
                    if (eventName == "modeout") {
                        val evCurrent = root["data"]?.jsonObject?.get("current")
                            ?.jsonPrimitive?.contentOrNull
                        if (evCurrent == null || evCurrent == currentMode) {
                            currentMode = null
                            _state.update { it.copy(currentMode = null) }
                            Log.d(TAG, "el robot salió del modo de función")
                        }
                    }
                }
                "alarm_rsp" -> parseAlarmRsp(root)
                "light_rsp" -> parseLightRsp(root)
                else -> {
                    root["data"]?.jsonObject?.get("result")?.jsonPrimitive
                        ?.contentOrNull?.let { result ->
                            val p = pending
                            if (p != null) {
                                pending = null
                                p.complete(result)
                            } else {
                                modeAckFlow.tryEmit(result)
                            }
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseResponse error: ${e.message}", e)
        }
    }

    private fun parseStaRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull
        if (result == "sta_query_ok") {
            _state.update { s ->
                s.copy(
                    info = s.info.copy(
                        version = data["version"]?.jsonObject?.get("name")?.jsonPrimitive
                            ?.contentOrNull ?: s.info.version,
                        versionNumber = data["version"]?.jsonObject?.get("number")?.jsonPrimitive
                            ?.contentOrNull ?: s.info.versionNumber,
                        battery = data["battery"]?.jsonObject?.get("level")?.jsonPrimitive
                            ?.intOrNull,
                        steps = data["property"]?.jsonObject?.get("steps")?.jsonPrimitive
                            ?.longOrNull,
                        gold = data["property"]?.jsonObject?.get("gold")?.jsonPrimitive
                            ?.longOrNull?.let { it.toInt() },
                        food = data["property"]?.jsonObject?.get("food")?.jsonArray?.size,
                        glasses = data["property"]?.jsonObject?.get("glasses")?.jsonArray?.size,
                        timerDura = data["features"]?.jsonObject?.get("timerdura")?.jsonPrimitive
                            ?.intOrNull,
                        breathTimes = data["features"]?.jsonObject?.get("breathtimes")
                            ?.jsonPrimitive?.intOrNull,
                        mtu = ble.currentMtu()
                    )
                )
            }
            val p = pending
            if (p != null) {
                pending = null
                p.complete(result)
            }
        }
    }

    private fun parseAlarmRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull ?: return
        if (result == "alarm_list_ok") {
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
            alarmsCache = alarms
            val d = alarmsPending
            alarmsPending = null
            if (d != null) d.complete(alarms)
        } else {
            val p = pending
            if (p != null) {
                pending = null
                p.complete(result)
            } else {
                modeAckFlow.tryEmit(result)
            }
        }
    }

    private fun parseLightRsp(root: JsonObject) {
        val data = root["data"]?.jsonObject ?: return
        val result = data["result"]?.jsonPrimitive?.contentOrNull ?: return
        val p = pending
        if (p != null) {
            pending = null
            p.complete(result)
        } else {
            modeAckFlow.tryEmit(result)
        }
    }

    private suspend fun withMode(
        mode: String,
        action: suspend () -> Result<Unit>
    ): Result<Unit> = requestMutex.withLock {
        if (currentMode != mode) {
            currentMode = mode
            _state.update { it.copy(currentMode = mode) }
            writeNoLock(modeIn(mode))
            waitModeAck(mode)
        }
        action()
    }

    private suspend fun waitModeAck(mode: String) {
        val ok = withTimeoutOrNull(MODE_ACK_TIMEOUT_MS) {
            modeAckFlow.filter { it == "${mode}_in_ok" }.first()
        } != null
        if (!ok) {
            delay(MODE_FALLBACK_MS)
            Log.d(TAG, "sin ACK de modo $mode, continúo igual")
        }
    }

    private suspend fun sendExpectOk(bytes: ByteArray): Result<Unit> {
        val result = requestNoLock(bytes, REQUEST_TIMEOUT_MS)
        return if (result == null) {
            Result.failure(IllegalStateException("sin respuesta del robot"))
        } else {
            Result.success(Unit)
        }
    }

    private suspend fun requestNoLock(bytes: ByteArray, timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String>()
        pending = deferred
        lastTxAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "TX ${bytes.toHex()}")
        try {
            ble.write(bytes)
        } catch (e: Exception) {
            if (pending === deferred) pending = null
            Log.e(TAG, "write error: ${e.message}")
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (pending === deferred) pending = null
        return result
    }

    private suspend fun writeNoLock(bytes: ByteArray) {
        lastTxAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "TX ${bytes.toHex()}")
        try {
            ble.write(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "write error: ${e.message}")
        }
    }

    private fun guardConnected(): Result<Unit> =
        if (_state.value.conn == ConnState.CONNECTED) Result.success(Unit)
        else Result.failure(IllegalStateException("robot no conectado"))

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

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it) }
}
