package com.wil.aibipilot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Protocolo BLE del robot AIBI Pocket (Living.AI), recuperado por
 * ingeniería inversa de la app oficial (ai.living.aibi v1.7.0).
 *
 * Transporte: BLE GATT, servicio 0000ffe0-..., característica 0000ffe1-...
 * (puerto serie virtual).
 *
 * Frame saliente (app -> robot): [0xBB, 0xAA, len%256, len/256] + JSON UTF-8
 * Frame entrante (robot -> app):  [0xBB, 0xAA, len%256, len/256] + JSON UTF-8
 * Mensaje binario entrante:       [0xDD, 0xCC] + raw bytes
 */
object Protocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val DEVICE_NAME = "AIBI"

    val JSON_HEADER = byteArrayOf(0xBB.toByte(), 0xAA.toByte())
    val MSG_HEADER = byteArrayOf(0xDD.toByte(), 0xCC.toByte())

    val json = Json { ignoreUnknownKeys = true }

    /** Envuelve un JSON en el frame BB AA + longitud little-endian. */
    fun frame(jsonPayload: String): ByteArray {
        val body = jsonPayload.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + body.size)
        out[0] = JSON_HEADER[0]
        out[1] = JSON_HEADER[1]
        out[2] = (body.size % 256).toByte()
        out[3] = ((body.size / 256) % 256).toByte()
        body.copyInto(out, destinationOffset = 4)
        return out
    }

    private fun request(type: String, data: String): ByteArray = frame(
        """{"type":"$type","data":$data}"""
    )

    // ------------------------------------------------------------------
    // sta (estado global)
    // IDs: 0=deviceid 1=version 2=info 3=pref 4=wifi 5=city 6=timezone
    //      7=lang 8=property 9=message 10=look 11=features 12=battery
    // ------------------------------------------------------------------
    fun staQuery(vararg ids: Int): ByteArray = request(
        "sta_req",
        """{"op":"query","list":[${ids.joinToString(",")}]}"""
    )

    fun staExchange(steps: Long): ByteArray = request(
        "sta_req",
        """{"op":"exchange","steps":$steps}"""
    )

    // ------------------------------------------------------------------
    // show (TTS y animaciones)
    // ------------------------------------------------------------------
    fun showIn(): ByteArray = request("show_req", """{"op":"in"}""")
    fun showOut(): ByteArray = request("show_req", """{"op":"out"}""")

    fun showSpeak(text: String): ByteArray = request(
        "show_req",
        buildJsonObject {
            put("op", "speak")
            put("txt", text)
        }.toString()
    )

    fun showPlay(animation: String): ByteArray = request(
        "show_req",
        buildJsonObject {
            put("op", "play")
            put("animation", animation)
        }.toString()
    )

    // ------------------------------------------------------------------
    // settings
    // Volumen: "mute" | "low" | "high"
    // ------------------------------------------------------------------
    fun settingIn(): ByteArray = request("setting_req", """{"op":"in"}""")
    fun settingOut(): ByteArray = request("setting_req", """{"op":"out"}""")

    fun settingVolume(level: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "volume")
            put("volume", level)
        }.toString()
    )

    fun settingOff(): ByteArray = request("setting_req", """{"op":"off"}""")

    fun settingWifiList(): ByteArray = request("setting_req", """{"op":"wifilist"}""")

    fun settingWifiSet(ssid: String, password: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "wifiset")
            put("ssid", ssid)
            put("password", password)
        }.toString()
    )

    // ------------------------------------------------------------------
    // configuración del robot (op + campos exactos del oficial
    // BleSettingsRequest.kt: lang/langcode, temp/length/24hour/chatty/
    // selfani/tapani/doubletap/otanotify -> option(int), wakemodel ->
    // model(int), lastname -> name, birthday -> birthday, quiet_add ->
    // from/to (String "HH:MM"), schedule_add -> time(int HHMM)+tag(int),
    // schedule_switch -> switch("on"|"off")
    // ------------------------------------------------------------------
    fun settingLang(langcode: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "lang")
            put("langcode", langcode)
        }.toString()
    )

    fun settingTemp(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "temp")
            put("option", option)
        }.toString()
    )

    fun settingLength(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "length")
            put("option", option)
        }.toString()
    )

    fun settingOtaNotify(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "otanotify")
            put("option", option)
        }.toString()
    )

    fun settingHour24(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "24hour")
            put("option", option)
        }.toString()
    )

    fun settingChatty(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "chatty")
            put("option", option)
        }.toString()
    )

    fun settingSelfani(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "selfani")
            put("option", option)
        }.toString()
    )

    fun settingTapani(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "tapani")
            put("option", option)
        }.toString()
    )

    fun settingDoubletap(option: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "doubletap")
            put("option", option)
        }.toString()
    )

    fun settingWakeModel(model: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "wakemodel")
            put("model", model)
        }.toString()
    )

    fun settingLastName(name: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "lastname")
            put("name", name)
        }.toString()
    )

    fun settingBirthday(birthday: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "birthday")
            put("birthday", birthday)
        }.toString()
    )

    fun settingQuietList(): ByteArray = request("setting_req", """{"op":"quiet_list"}""")

    fun settingQuietAdd(from: String, to: String): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "quiet_add")
            put("from", from)
            put("to", to)
        }.toString()
    )

    fun settingQuietDel(index: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "quiet_del")
            put("index", index)
        }.toString()
    )

    fun settingScheduleList(): ByteArray = request("setting_req", """{"op":"schedule_list"}""")

    fun settingScheduleAdd(time: Int, tag: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "schedule_add")
            put("time", time)
            put("tag", tag)
        }.toString()
    )

    fun settingScheduleDel(index: Int): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "schedule_del")
            put("index", index)
        }.toString()
    )

    fun settingScheduleSwitch(on: Boolean): ByteArray = request(
        "setting_req",
        buildJsonObject {
            put("op", "schedule_switch")
            put("switch", if (on) "on" else "off")
        }.toString()
    )

    /** Comando binario de movimiento (formato del modo debug oficial):
     *  55 AA 55 AA 21 <cmd> 00...00 ED (20 bytes) */
    fun motion(cmd: Int): ByteArray {
        val out = ByteArray(20)
        out[0] = 0x55.toByte()
        out[1] = 0xAA.toByte()
        out[2] = 0x55.toByte()
        out[3] = 0xAA.toByte()
        out[4] = 0x21
        out[5] = (cmd and 0xFF).toByte()
        out[19] = 0xED.toByte()
        return out
    }

    // ------------------------------------------------------------------
    // alarm (alarm_req / alarm_rsp): in, out, list, add, del
    // add: tag (tipo/recordatorio 0..6) + time "HH:mm" (24h).
    // del: index (posición en la lista).
    // ------------------------------------------------------------------
    fun alarmIn(): ByteArray = request("alarm_req", """{"op":"in"}""")
    fun alarmOut(): ByteArray = request("alarm_req", """{"op":"out"}""")
    fun alarmList(): ByteArray = request("alarm_req", """{"op":"list"}""")
    fun alarmAdd(tag: Int, time: String): ByteArray = request(
        "alarm_req",
        buildJsonObject {
            put("op", "add")
            put("tag", tag)
            put("time", time)
        }.toString()
    )
    fun alarmDel(index: Int): ByteArray = request(
        "alarm_req",
        buildJsonObject {
            put("op", "del")
            put("index", index)
        }.toString()
    )

    // ------------------------------------------------------------------
    // starLight (light_req / light_rsp): in, out, list, on, off, set, refresh
    // mode: "default" | "breath" | "color" | "flow"
    // color: [r, g, b]
    // ------------------------------------------------------------------
    fun lightIn(): ByteArray = request("light_req", """{"op":"in"}""")
    fun lightOut(): ByteArray = request("light_req", """{"op":"out"}""")
    fun lightList(): ByteArray = request("light_req", """{"op":"list"}""")
    fun lightOn(id: Int = 0): ByteArray = request(
        "light_req",
        buildJsonObject { put("op", "on"); put("id", id) }.toString()
    )
    fun lightOff(id: Int = 0): ByteArray = request(
        "light_req",
        buildJsonObject { put("op", "off"); put("id", id) }.toString()
    )
    fun lightSet(id: Int, mode: String, color: List<Int>, brightness: Int): ByteArray =
        request(
            "light_req",
            buildJsonObject {
                put("op", "set")
                put("id", id)
                put("mode", mode)
                put("color", JsonArray(color.map { JsonPrimitive(it) }))
                put("brightness", brightness)
            }.toString()
        )

    // ------------------------------------------------------------------
    // juegos: chess / snake (serpientes y escaleras) / pirate / zero
    // ops comunes: in (entrar al modo), out, start, play
    // ------------------------------------------------------------------
    fun gameIn(type: String): ByteArray = request("${type}_req", """{"op":"in"}""")
    fun gameOut(type: String): ByteArray = request("${type}_req", """{"op":"out"}""")
    fun gameStart(type: String): ByteArray = request("${type}_req", """{"op":"start"}""")
    fun gamePlay(type: String): ByteArray = request("${type}_req", """{"op":"play"}""")

    // ------------------------------------------------------------------
    // photo (photo_req / photo_rsp): in, out, sync, clear, show, del, single
    // sync: el robot conecta por TCP al teléfono (server.ip / server.port)
    // ------------------------------------------------------------------
    fun photoIn(): ByteArray = request("photo_req", """{"op":"in"}""")
    fun photoOut(): ByteArray = request("photo_req", """{"op":"out"}""")
    fun photoSync(ip: String, port: Int): ByteArray = request(
        "photo_req",
        buildJsonObject {
            put("op", "sync")
            put("server", buildJsonObject {
                put("ip", ip)
                put("port", port)
            })
        }.toString()
    )
    fun photoShow(): ByteArray = request("photo_req", """{"op":"show"}""")
    fun photoClear(): ByteArray = request("photo_req", """{"op":"clear"}""")
    fun photoDel(name: String): ByteArray = request(
        "photo_req",
        buildJsonObject { put("op", "del"); put("name", name) }.toString()
    )
    fun photoSingle(): ByteArray = request("photo_req", """{"op":"single"}""")
}

/** Catálogo de animaciones confirmadas en la app oficial (ShowDataKt). */
object Animations {
    data class Entry(val id: String, val label: String, val group: String)

    val all: List<Entry> = listOf(
        // bailes
        Entry("dance_ai1", "Neon Pulse", "Bailes"),
        Entry("dance_ai2", "Beat Mosaic", "Bailes"),
        Entry("dance_ai3", "Sunset Lagoon", "Bailes"),
        Entry("dance_ai4", "Snack Attack Groove", "Bailes"),
        Entry("dance_ai5", "Tap Dance", "Bailes"),
        Entry("dance_ai6", "Fonkey Youngblood", "Bailes"),
        Entry("dance_ai7", "Sing and dance", "Bailes"),
        // canciones
        Entry("sing_menuet_g_major", "Minuet in G Major", "Canciones"),
        Entry("sing_mozart_40_1st", "Sinfonía 40 (Mozart)", "Canciones"),
        Entry("sing_cancan", "Cancan", "Canciones"),
        Entry("sing_colonel_bogey", "Colonel Bogey", "Canciones"),
        Entry("sing_carnival_animals", "Carnaval de los Animales", "Canciones"),
        Entry("sing_fur_elise", "Für Elise", "Canciones"),
        Entry("sing_ode_to_joy", "Oda a la Alegría", "Canciones"),
        Entry("sing_te_deum", "Te Deum", "Canciones"),
        // animales y shows
        Entry("animal_dog", "Perro", "Animales"),
        Entry("animal_fox", "Zorro", "Animales"),
        Entry("animal_wolf", "Lobo", "Animales"),
        Entry("animal_cat3", "Gato", "Animales"),
        Entry("animal_owl", "Búho", "Animales"),
        Entry("animal_bear", "Oso", "Animales"),
        Entry("animal_ghost", "Fantasma", "Animales"),
        Entry("show_monster", "Monstruo", "Animales"),
        Entry("show_cyclopia", "Cíclope", "Animales"),
        Entry("show_demon", "Demonio", "Animales"),
        Entry("show_blow_bubbles", "Burbujas", "Shows"),
        Entry("show_spitfire", "Escupefuego", "Shows"),
        Entry("show_interview", "Entrevista", "Shows"),
        Entry("show_meteor_shower", "Lluvia de meteoros", "Shows"),
        Entry("creative_world_cup1", "Fútbol 1", "Creativos"),
        Entry("creative_world_cup2", "Fútbol 2", "Creativos"),
        Entry("creative_world_cup3", "Hinchada", "Creativos"),
        Entry("creative_cough", "Tos", "Creativos"),
        // emociones (emoji12..23)
        Entry("emoji12", "Furioso", "Emociones"),
        Entry("emoji13", "Truco", "Emociones"),
        Entry("emoji14", "Enojado", "Emociones"),
        Entry("emoji15", "Orgulloso", "Emociones"),
        Entry("emoji16", "Loco", "Emociones"),
        Entry("emoji17", "Guiño", "Emociones"),
        Entry("emoji18", "Sonrisa", "Emociones"),
        Entry("emoji19", "Atónito", "Emociones"),
        Entry("emoji20", "Tímido", "Emociones"),
        Entry("emoji21", "Rabia", "Emociones"),
        Entry("emoji22", "Sollozo", "Emociones"),
        Entry("emoji23", "Llanto", "Emociones"),
    )
}
