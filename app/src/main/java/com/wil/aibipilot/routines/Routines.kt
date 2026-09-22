package com.wil.aibipilot.routines

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rutinas programadas (spec C3): hora "HH:mm", días 1=Lunes..7=Domingo,
 * acción plana (type + payload) y toggle enabled. Persistencia: JSON en
 * las prefs de la app bajo la key "routines".
 */
@Serializable
data class RoutineAction(
    val type: String, // "speak" | "animation" | "scene" | "light"
    val payload: String // texto, id de animación, id de escena, "on"/"off"
)

@Serializable
data class Routine(
    val id: String,
    val time: String, // "HH:mm"
    val days: Set<Int>, // 1=Lunes .. 7=Domingo
    val enabled: Boolean,
    val action: RoutineAction
)

object RoutinesStore {
    private const val PREFS = "aibi_pilot_prefs"
    private const val KEY_ROUTINES = "routines"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(ctx: Context): List<Routine> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROUTINES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Routine>>(raw).sortedBy { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, routines: List<Routine>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROUTINES, json.encodeToString(routines))
            .apply()
    }
}
