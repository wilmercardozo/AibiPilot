package com.wil.aibipilot.routines

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wil.aibipilot.AibiNotifier
import com.wil.aibipilot.MainActivity
import com.wil.aibipilot.ble.RemoteService
import com.wil.aibipilot.ble.RemoteStateBus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Programación y ejecución de rutinas (spec C3): un único PeriodicWorkRequest de
 * 15 minutos chequea rutinas debidas en la ventana [ahora-14min, ahora].
 * - Modo remoto activo → el servicio (RemoteService) las ejecuta con su
 *   RemoteController (action ACTION_RUN_ROUTINES + extra JSON de rutinas).
 * - Sin modo remoto → por cada rutina debida, notificación "Rutina pendiente"
 *   con content intent que abre MainActivity con extra run_routine_id.
 */
object RoutineScheduler {

    const val UNIQUE_WORK_NAME = "aibi_routines_check"
    private const val WINDOW_MINUTES = 14

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<RoutineWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun runDueRoutines(ctx: Context): List<Routine> {
        val now = LocalDateTime.now()
        val today = now.dayOfWeek.value // 1=Lunes .. 7=Domingo
        val nowMin = now.hour * 60 + now.minute
        return RoutinesStore.load(ctx).filter { r ->
            if (!r.enabled || today !in r.days) return@filter false
            val parts = r.time.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size != 2) return@filter false
            val routineMin = parts[0] * 60 + parts[1]
            val diff = (nowMin - routineMin + 1440) % 1440
            diff in 0..WINDOW_MINUTES
        }
    }
}

class RoutineWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AibiRoutineWorker"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val due = RoutineScheduler.runDueRoutines(ctx)
        if (due.isEmpty()) return Result.success()
        if (RemoteStateBus.running.value) {
            val intent = Intent(ctx, RemoteService::class.java)
                .setAction(RemoteService.ACTION_RUN_ROUTINES)
                .putExtra(RemoteService.EXTRA_ROUTINES_JSON, Json.encodeToString(due))
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo encargar la rutina al servicio: ${e.message}")
            }
        } else {
            due.forEach { r ->
                val contentIntent = PendingIntent.getActivity(
                    ctx,
                    r.id.hashCode(),
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_RUN_ROUTINE_ID, r.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                AibiNotifier.notify(
                    ctx,
                    r.id.hashCode(),
                    "Rutina pendiente",
                    "${r.time} · ${routineSummary(r.action)}",
                    contentIntent
                )
            }
        }
        return Result.success()
    }

    private fun routineSummary(action: RoutineAction): String = when (action.type) {
        "speak" -> "Decir: ${action.payload}"
        "animation" -> "Animación: ${action.payload}"
        "scene" -> "Escena: ${action.payload}"
        "light" -> if (action.payload == "on") "Encender luz" else "Apagar luz"
        else -> action.type
    }
}
