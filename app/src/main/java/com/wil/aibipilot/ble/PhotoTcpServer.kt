package com.wil.aibipilot.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Servidor TCP para recibir fotos del robot.
 *
 * Protocolo reconstruido de TcpServerUtil (app oficial, TcpServer.kt):
 *  - El robot conecta por TCP como cliente al puerto 9090 del teléfono.
 *  - Por cada archivo envía:  "finish;name=<nombre>;filesize=<N>;delimited=------#"
 *    seguido de N bytes de imagen, y termina con "------------------" (18 guiones).
 *  - La app guarda el archivo y responde "ok".
 *  - La sesión termina cuando el robot cierra la conexión.
 *  - Codificación ISO-8859-1 en todo el intercambio.
 */
class PhotoTcpServer(
    private val outputDir: File,
    private val onPhoto: (File) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val PORT = 9090
        private const val TAG = "PhotoTcpServer"
        private const val END_OF_MESSAGE = '#'
        private const val DELIMITED = "------"
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun start() {
        if (isRunning) return
        outputDir.mkdirs()
        scope.launch {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                onLog("Servidor TCP de fotos escuchando en puerto $PORT")
                while (true) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    onLog("Robot conectado para enviar fotos: ${socket.inetAddress.hostAddress}")
                    scope.launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                onLog("Error iniciando servidor TCP: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        onLog("Servidor TCP detenido")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input: InputStream = BufferedInputStream(socket.getInputStream())
            val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
            while (!socket.isClosed()) {
                // 1) prefijo "finish" (6 bytes)
                val prefix = readExact(input, 6)
                if (prefix == null) break
                val prefixStr = String(prefix, Charsets.ISO_8859_1)
                if (prefixStr != "finish") {
                    onLog("Cabecera TCP inesperada: \"$prefixStr\" — cerrando")
                    break
                }
                // 2) campos hasta '#'
                val header = readUntil(input, END_OF_MESSAGE.code.toByte()) ?: break
                val fields = parseHeader(String(header, Charsets.ISO_8859_1))
                val name = fields["name"]
                val size = fields["filesize"]?.toIntOrNull()
                val delimited = fields["delimited"] ?: DELIMITED
                if (name == null || size == null) {
                    onLog("Cabecera sin name/filesize: $fields — cerrando")
                    break
                }
                onLog("Recibiendo \"$name\" ($size bytes)")
                // 3) N bytes de imagen
                val imageData = readExact(input, size)
                if (imageData == null) break
                // 4) marcador final: ------<delimited>------ 
                val endMarker = DELIMITED + delimited + DELIMITED
                val marker = readExact(input, endMarker.length)
                if (marker == null) break
                if (String(marker, Charsets.ISO_8859_1) != endMarker) {
                    onLog("Marcador final inesperado — cerrando")
                    break
                }
                // guardar y responder
                val file = File(outputDir, name)
                file.outputStream().use { it.write(imageData) }
                onPhoto(file)
                onLog("Foto guardada: ${file.absolutePath}")
                output.write("ok".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            socket.close()
            onLog("Transferencia de fotos finalizada")
        } catch (e: Exception) {
            onLog("Error TCP: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Lee exactamente n bytes; null si el stream se cerró antes. */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read < 0) return null
            off += read
        }
        return buf
    }

    /** Lee bytes hasta encontrar el delimitador (incluido? no: se descarta). */
    private fun readUntil(input: InputStream, delimiter: Byte): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val one = ByteArray(1)
        while (true) {
            val read = input.read(one)
            if (read < 0) return null
            if (one[0] == delimiter) return out.toByteArray()
            out.writeBytes(one)
        }
    }

    /** "finish;name=x.jpg;filesize=123;delimited=------" -> mapa (salta tokens sin '='). */
    private fun parseHeader(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.split(";").forEach { token ->
            val idx = token.indexOf('=')
            if (idx > 0) {
                map[token.substring(0, idx)] = token.substring(idx + 1)
            }
        }
        return map
    }
}
