package com.wil.aibipilot.ble

import android.util.Log
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.protocol.Protocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * API HTTP local del modo remoto (spec C1).
 *
 * Servidor mínimo sin dependencias: ServerSocket + un hilo por request con
 * concurrencia máxima de 4 (Semaphore). Parsea HTTP 1.1 básico (request line +
 * headers hasta blank line; body con Content-Length, tope 64KB). El header
 * `Authorization: Bearer <token>` es obligatorio: sin token válido todo es 401.
 * Bind en 0.0.0.0 (la seguridad es el token, no el bind; así adb forward sirve
 * para probar desde el PC). Nunca habla con el ViewModel: consume RemoteController.
 * El log lleva solo IP+endpoint, nunca el token.
 */
class RemoteApiServer(
    private val controller: RemoteController,
    private val token: String,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "RemoteApiServer"
        private const val MAX_CONCURRENT = 4
        private const val MAX_BODY = 64 * 1024
        private const val MAX_LINE = 16 * 1024
        private const val READ_TIMEOUT_MS = 30_000
        private const val SEMAPHORE_WAIT_S = 3L
        private val TIME_RE = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
        private val LIGHT_MODES = setOf("default", "breath", "color", "flow")
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val semaphore = Semaphore(MAX_CONCURRENT)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "aibi-http").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun start(port: Int) {
        if (isRunning) return
        executor.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                onLog(
                    "API HTTP escuchando en 0.0.0.0:$port " +
                        "(token ${if (token.isNotEmpty()) "configurado" else "vacío"})"
                )
                while (!ss.isClosed) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    executor.execute { handleConnection(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "error iniciando API HTTP: ${e.message}")
                onLog("Error iniciando API HTTP: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        onLog("API HTTP detenida")
    }

    private fun handleConnection(socket: Socket) {
        val ip = socket.inetAddress?.hostAddress ?: "?"
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            if (!semaphore.tryAcquire(SEMAPHORE_WAIT_S, TimeUnit.SECONDS)) {
                send(socket, 503, errorJson("servidor ocupado, reintentá"))
                return
            }
            try {
                handleRequest(socket, ip)
            } finally {
                semaphore.release()
            }
        } catch (e: Exception) {
            onLog("$ip error de conexión: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleRequest(socket: Socket, ip: String) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        val requestLine = readLine(input) ?: return
        if (requestLine.isBlank()) return
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            send(output, 400, errorJson("request line inválida"))
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        var authorization: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> authorization = value
                }
            }
        }
        if (contentLength > MAX_BODY) {
            onLog("$ip $method $path -> 413 (body ${contentLength}B)")
            send(output, 413, errorJson("body demasiado grande (máx 64KB)"))
            return
        }
        val body = readBody(input, contentLength)

        // Auth: Bearer obligatorio. Prefijo case-insensitive, token case-sensitive.
        // Log con IP+endpoint, nunca el token.
        if (token.isEmpty() || authorization == null ||
            !authorization.startsWith("bearer", ignoreCase = true) ||
            authorization.drop(6).trim() != token
        ) {
            onLog("$ip $method $path -> 401 (token inválido o faltante)")
            send(output, 401, errorJson("token inválido o faltante"))
            return
        }
        onLog("$ip $method $path")

        val response = route(method, path, body)
        send(output, response.code, response.body)
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length == 0) return ""
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val r = input.read(buf, off, length - off)
            if (r < 0) break
            off += r
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) return null
            sb.append(b.toChar())
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun route(method: String, path: String, body: String): HttpResponse {
        val allowed = when (path) {
            "/status" -> "GET"
            "/speak" -> "POST"
            "/play" -> "POST"
            "/light" -> "POST"
            "/light/on" -> "POST"
            "/light/off" -> "POST"
            "/volume" -> "POST"
            "/alarms" -> "GET,POST,DELETE"
            "/game" -> "POST"
            "/scene" -> "POST"
            else -> null
        }
        if (allowed == null) {
            onLog("routing 404: $method $path")
            return HttpResponse(404, errorJson("endpoint desconocido: $path"))
        }
        if (method !in allowed.split(",")) {
            onLog("routing 405: $method $path (esperado $allowed)")
            return HttpResponse(405, errorJson("método $method no permitido en $path (esperado $allowed)"))
        }
        return when (path) {
            "/status" -> status()
            "/speak" -> robotOp(body) { obj ->
                val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (text.isBlank()) {
                    Result.failure(IllegalArgumentException("campo text obligatorio"))
                } else {
                    controller.speak(text.trim())
                }
            }
            "/play" -> robotOp(body) { obj ->
                val animation = obj["animation"]?.jsonPrimitive?.contentOrNull ?: ""
                if (animation.isBlank()) {
                    Result.failure(IllegalArgumentException("campo animation obligatorio"))
                } else {
                    controller.play(animation)
                }
            }
            "/light" -> robotOp(body) { obj ->
                val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "default"
                val color = obj["color"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
                    ?: emptyList()
                val brightness = obj["brightness"]?.jsonPrimitive?.intOrNull ?: 100
                when {
                    mode !in LIGHT_MODES ->
                        Result.failure(IllegalArgumentException("mode inválido: $mode"))
                    color.size != 3 || color.any { it !in 0..255 } ->
                        Result.failure(IllegalArgumentException("color inválido: [r,g,b] 0-255"))
                    brightness !in 0..100 ->
                        Result.failure(IllegalArgumentException("brightness inválido: 0-100"))
                    else -> controller.setLight(mode, color, brightness)
                }
            }
            "/light/on" -> robotOp(body) { controller.lightOn(0) }
            "/light/off" -> robotOp(body) { controller.lightOff(0) }
            "/volume" -> robotOp(body) { obj ->
                val level = obj["level"]?.jsonPrimitive?.contentOrNull ?: ""
                controller.setVolume(level)
            }
            "/alarms" -> when (method) {
                "GET" -> alarmsGet()
                "POST" -> robotOp(body) { obj ->
                    val time = obj["time"]?.jsonPrimitive?.contentOrNull ?: ""
                    val tag = obj["tag"]?.jsonPrimitive?.intOrNull ?: -1
                    when {
                        !TIME_RE.matches(time) ->
                            Result.failure(IllegalArgumentException("time inválido (HH:mm)"))
                        tag !in 0..6 ->
                            Result.failure(IllegalArgumentException("tag inválido (0..6)"))
                        else -> controller.alarmAdd(tag, time)
                    }
                }
                else -> robotOp(body) { obj ->
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
                    if (index < 0) {
                        Result.failure(IllegalArgumentException("campo index obligatorio"))
                    } else {
                        controller.alarmDel(index)
                    }
                }
            }
            "/game" -> robotOp(body) { obj ->
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val op = obj["op"]?.jsonPrimitive?.contentOrNull ?: ""
                controller.game(type, op)
            }
            "/scene" -> robotOp(body) { obj ->
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                controller.scene(id)
            }
            else -> HttpResponse(404, errorJson("endpoint desconocido: $path"))
        }
    }

    private fun status(): HttpResponse {
        if (controller.state.value.conn != ConnState.CONNECTED) {
            return HttpResponse(409, errorJson("robot no conectado"))
        }
        val s = controller.state.value
        val info = s.info
        val body = buildJsonObject {
            put("ok", true)
            put("conn", s.conn.name.lowercase())
            put("battery", info.battery?.let { JsonPrimitive(it) } ?: JsonNull)
            put("steps", info.steps?.let { JsonPrimitive(it) } ?: JsonNull)
            put("coins", info.gold?.let { JsonPrimitive(it) } ?: JsonNull)
            put("food", info.food?.let { JsonPrimitive(it) } ?: JsonNull)
            put("version", JsonPrimitive(info.version))
            put("mode", s.currentMode?.let { JsonPrimitive(it) } ?: JsonNull)
            put("name", JsonPrimitive(info.deviceName))
        }.toString()
        return HttpResponse(200, body)
    }

    private fun alarmsGet(): HttpResponse {
        if (controller.state.value.conn != ConnState.CONNECTED) {
            return HttpResponse(409, errorJson("robot no conectado"))
        }
        val list = runBlocking { controller.alarmsList() }
        val body = buildJsonObject {
            put("ok", true)
            put("alarms", JsonArray(list.map { a ->
                buildJsonObject {
                    put("index", a.index)
                    put("time", a.time)
                    a.tag?.let { put("tag", it) }
                }
            }))
        }.toString()
        return HttpResponse(200, body)
    }

    private fun robotOp(body: String, action: suspend (JsonObject) -> Result<Unit>): HttpResponse {
        if (controller.state.value.conn != ConnState.CONNECTED) {
            return HttpResponse(409, errorJson("robot no conectado"))
        }
        val jsonBody = body.ifBlank { "{}" }
        val obj = try {
            Protocol.json.parseToJsonElement(jsonBody).jsonObject
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("body JSON inválido"))
        }
        val result = try {
            runBlocking { action(obj) }
        } catch (e: Exception) {
            Result.failure(e)
        }
        return if (result.isSuccess) {
            HttpResponse(200, okJson())
        } else {
            val msg = result.exceptionOrNull()?.message ?: "error desconocido"
            onLog("error ejecutando comando: $msg")
            HttpResponse(400, errorJson(msg))
        }
    }

    private fun send(socket: Socket, code: Int, body: String) {
        try {
            val output = BufferedOutputStream(socket.getOutputStream())
            send(output, code, body)
        } catch (_: Exception) {
        }
    }

    private fun send(output: OutputStream, code: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            Log.d(TAG, "error escribiendo respuesta: ${e.message}")
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> ""
    }

    private fun okJson(): String = """{"ok":true}"""

    private fun errorJson(msg: String): String =
        """{"ok":false,"error":${JsonPrimitive(msg)}}"""
}
