package com.wil.aibipilot.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wil.aibipilot.MainActivity
import com.wil.aibipilot.routines.Routine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Estado compartido del modo remoto (proceso): el servicio lo enciende/apaga
 * y la UI del ViewModel lo refleja aunque el servicio lo haya arrancado otro.
 */
object RemoteStateBus {
    val running = MutableStateFlow(false)
}

/**
 * Foreground service del modo remoto (spec C1): notificación persistente
 * "Modo remoto activo" con acción Parar, partial wakelock (MIUI/HyperOS no
 * lo mata en background) y dueño de la ÚNICA conexión BLE (RemoteController)
 * + servidor HTTP (RemoteApiServer). Lee MAC/token/puerto de las prefs del VM.
 */
class RemoteService : Service() {

    companion object {
        private const val TAG = "AibiRemoteService"
        const val CHANNEL_ID = "remote_mode"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.wil.aibipilot.action.STOP_REMOTE"
        const val ACTION_RUN_ROUTINES = "com.wil.aibipilot.action.RUN_ROUTINES"
        const val EXTRA_ROUTINES_JSON = "routines_json"
        private const val PREFS = "aibi_pilot_prefs"
        private const val KEY_MAC = "last_device_mac"
        private const val KEY_NAME = "last_device_name"
        private const val KEY_TOKEN = "remote_token"
        private const val KEY_PORT = "remote_port"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controller: RemoteController? = null
    private var server: RemoteApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        RemoteStateBus.running.value = true
        Log.d(TAG, "servicio creado, modo remoto activo")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "acción Parar recibida")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_ROUTINES -> {
                startAsForeground()
                if (!started) {
                    started = true
                    startEngine()
                }
                runRoutines(intent.getStringExtra(EXTRA_ROUTINES_JSON))
                return START_NOT_STICKY
            }
        }
        startAsForeground()
        if (!started) {
            started = true
            startEngine()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        controller?.disconnect()
        controller = null
        releaseWakeLock()
        scope.cancel()
        started = false
        RemoteStateBus.running.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        Log.d(TAG, "servicio destruido, modo remoto inactivo")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Modo remoto",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Servicio en segundo plano del modo remoto (API HTTP)"
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "aibipilot:remote"
        ).apply { acquire() }
    }

    @Suppress("DEPRECATION")
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun startAsForeground() {
        val stopIntent = Intent(this, RemoteService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val token = prefs().getString(KEY_TOKEN, "") ?: ""
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Modo remoto activo")
            .setContentText(
                if (token.isEmpty()) "Configurá el token del modo remoto"
                else "API HTTP sirviendo comandos del robot en la red local"
            )
            .setSmallIcon(com.wil.aibipilot.R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Parar", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startEngine() {
        val p = prefs()
        val token = p.getString(KEY_TOKEN, "") ?: ""
        val port = p.getString(KEY_PORT, "8080")?.toIntOrNull() ?: 8080

        val controller = RemoteController(this)
        this.controller = controller

        val mac = p.getString(KEY_MAC, null)
        val name = p.getString(KEY_NAME, null)
        if (mac != null) {
            scope.launch {
                controller.connect(mac, name)
            }
        } else {
            Log.w(TAG, "sin robot guardado: la API responderá 409 hasta conectar")
        }

        if (token.isEmpty()) {
            Log.w(TAG, "token vacío: no levanto el servidor")
            updateNotification("Configurá el token del modo remoto")
        } else {
            val server = RemoteApiServer(
                controller = controller,
                token = token,
                onLog = { msg -> Log.d(TAG, msg) }
            )
            this.server = server
            server.start(port)
        }
    }

    /**
     * Ejecuta rutinas debidas (spec C3) con el RemoteController del servicio,
     * mapeando RoutineAction → speak/play/scene/lightOn/lightOff. Las rutinas
     * llegan serializadas en el extra JSON de ACTION_RUN_ROUTINES (RoutineWorker).
     */
    private fun runRoutines(json: String?) {
        if (json.isNullOrBlank()) return
        val routines = try {
            Json.decodeFromString<List<Routine>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "runRoutines: JSON inválido: ${e.message}")
            return
        }
        if (routines.isEmpty()) return
        Log.d(TAG, "ejecutando ${routines.size} rutina(s) debida(s)")
        scope.launch {
            val c = controller
            if (c == null) {
                Log.w(TAG, "sin RemoteController: no se pueden ejecutar rutinas")
                return@launch
            }
            routines.forEach { r ->
                Log.d(TAG, "rutina: ${r.time} ${r.action.type}")
                when (r.action.type) {
                    "speak" -> c.speak(r.action.payload)
                    "animation" -> c.play(r.action.payload)
                    "scene" -> c.scene(r.action.payload)
                    "light" -> if (r.action.payload == "on") c.lightOn(0) else c.lightOff(0)
                    else -> Log.w(TAG, "acción de rutina desconocida: ${r.action.type}")
                }
                delay(1500)
            }
        }
    }

    private fun updateNotification(text: String) {
        val stopIntent = Intent(this, RemoteService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Modo remoto activo")
            .setContentText(text)
            .setSmallIcon(com.wil.aibipilot.R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(0, "Parar", stopPending)
            .build()
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "error actualizando notificación: ${e.message}")
        }
    }
}
