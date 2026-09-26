package com.wil.aibipilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.LogCat
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.protocol.Animations
import com.wil.aibipilot.routines.Routine
import com.wil.aibipilot.routines.RoutineAction
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.EmptyState
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.components.SectionTitle
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary
import com.wil.aibipilot.ui.theme.Warning
import com.wil.aibipilot.ui.theme.StatusGreen

private enum class ToolTab(val label: String, val icon: ImageVector) {
    LUCES("Luces", Icons.Default.LightMode),
    ALARMAS("Alarmas", Icons.Default.AccessAlarm),
    RUTINAS("Rutinas", Icons.Default.Schedule),
    FOTOS("Fotos", Icons.Default.PhotoCamera),
    WIFI("WiFi", Icons.Default.Wifi),
    REMOTO("Remoto", Icons.Default.Cloud),
    LABORATORIO("Laboratorio", Icons.Default.Science),
    LOG("Log BLE", Icons.Default.Terminal),
}

@Composable
fun ToolsScreen(vm: RobotViewModel, ui: UiState) {
    var tab by rememberSaveable { mutableStateOf(ToolTab.LUCES) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Herramientas", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(t.label) },
                    leadingIcon = {
                        Icon(t.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            ToolTab.LUCES -> LightsPane(vm, ui)
            ToolTab.ALARMAS -> AlarmsPane(vm, ui)
            ToolTab.RUTINAS -> RoutinesPane(vm, ui)
            ToolTab.FOTOS -> PhotosPane(vm, ui)
            ToolTab.WIFI -> WifiPane(vm, ui)
            ToolTab.REMOTO -> RemotePane(vm, ui)
            ToolTab.LABORATORIO -> LabPane(vm, ui)
            ToolTab.LOG -> LogPane(vm, ui)
        }
    }
}

// ---------------------------------------------------------------------------
// Luces
// ---------------------------------------------------------------------------

@Composable
private fun LightsPane(vm: RobotViewModel, ui: UiState) {
    var mode by rememberSaveable { mutableStateOf("default") }
    var brightness by rememberSaveable { mutableStateOf(50f) }
    var red by rememberSaveable { mutableStateOf(255f) }
    var green by rememberSaveable { mutableStateOf(0f) }
    var blue by rememberSaveable { mutableStateOf(128f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Luz",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(text = "Prender", onClick = { vm.lightOn(0) })
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.lightOff(0) }) { Text("Apagar") }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Color", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                ColorSlider("Rojo", red, Color.Red) { red = it }
                ColorSlider("Verde", green, Color.Green) { green = it }
                ColorSlider("Azul", blue, Color.Blue) { blue = it }
                Spacer(Modifier.height(8.dp))
                Text("Brillo: ${brightness.toInt()}%", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0f..100f,
                )
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Modo", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "default" to "Fijo",
                        "breath" to "Respiración",
                        "color" to "Color",
                        "flow" to "Flujo",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            PrimaryButton(
                text = "Aplicar",
                onClick = {
                    vm.lightSet(mode, listOf(red.toInt(), green.toInt(), blue.toInt()), brightness.toInt())
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedButton(
                onClick = { vm.lightRefresh() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Listar luces")
            }
        }
        if (ui.lights.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.LightMode,
                    title = "Sin luces listadas",
                    subtitle = "Tocá «Listar luces» para ver las luces del robot.",
                )
            }
        } else {
            items(ui.lights) { light ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = light.name ?: "Luz #${light.id}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.lightOn(light.id) }) { Text("On") }
                        TextButton(onClick = { vm.lightOff(light.id) }) { Text("Off") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    color: Color,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
        )
    }
}

// ---------------------------------------------------------------------------
// Alarmas
// ---------------------------------------------------------------------------

private data class AlarmTag(val tag: Int, val label: String, val icon: ImageVector)

private val alarmTags = listOf(
    AlarmTag(0, "Alarma", Icons.Default.AccessAlarm),
    AlarmTag(1, "Medicamento", Icons.Default.Medication),
    AlarmTag(2, "Agua", Icons.Default.WaterDrop),
    AlarmTag(3, "Deporte", Icons.Default.DirectionsRun),
    AlarmTag(4, "Levantarse", Icons.Default.WbTwilight),
    AlarmTag(5, "Comida", Icons.Default.Restaurant),
    AlarmTag(6, "Reunión", Icons.Default.Groups),
)

private fun alarmIcon(tag: Int?): ImageVector =
    alarmTags.firstOrNull { it.tag == tag }?.icon ?: Icons.Default.AccessAlarm

@Composable
private fun AlarmsPane(vm: RobotViewModel, ui: UiState) {
    var time by rememberSaveable { mutableStateOf("08:00") }
    var tag by rememberSaveable { mutableStateOf(0) }
    var error by rememberSaveable { mutableStateOf(false) }
    val timeValid = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(time)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hora") },
                    placeholder = { Text("HH:mm") },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text("Formato HH:mm") }
                    } else {
                        null
                    },
                )
                SectionTitle("Tipo")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    alarmTags.forEach { t ->
                        FilterChip(
                            selected = tag == t.tag,
                            onClick = { tag = t.tag },
                            label = { Text(t.label) },
                            leadingIcon = {
                                Icon(t.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "Agregar",
                    onClick = {
                        if (timeValid) {
                            error = false
                            vm.alarmAdd(tag, time)
                        } else {
                            error = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { vm.alarmRefresh() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Listar alarmas")
            }
        }
        if (ui.alarms.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.AccessAlarm,
                    title = "No hay alarmas",
                    subtitle = "Agregá una alarma con hora y tipo.",
                )
            }
        } else {
            items(ui.alarms) { alarm ->
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(alarmIcon(alarm.tag), contentDescription = null, tint = TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = alarm.time.replace(":", " : "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.alarmDel(alarm.index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rutinas (spec C3: CRUD local + UI)
// ---------------------------------------------------------------------------

private val dayLabels = listOf("L", "M", "X", "J", "V", "S", "D")

private val sceneOptions = listOf(
    "fiesta" to "Fiesta",
    "despertar" to "Despertar",
    "relax" to "Relax",
    "noche" to "Noche",
)

private val actionOptions = listOf(
    Triple("speak", "Hablar", Icons.Default.RecordVoiceOver),
    Triple("animation", "Animación", Icons.Default.TheaterComedy),
    Triple("scene", "Escena", Icons.Default.Celebration),
    Triple("light", "Luz", Icons.Default.Lightbulb),
)

private fun routineActionSummary(action: RoutineAction): String = when (action.type) {
    "speak" -> "Decir: ${action.payload}"
    "animation" -> "Animación: " + (Animations.all.firstOrNull { it.id == action.payload }?.label ?: action.payload)
    "scene" -> "Escena: " + (sceneOptions.firstOrNull { it.first == action.payload }?.second ?: action.payload)
    "light" -> if (action.payload == "on") "Encender luz" else "Apagar luz"
    else -> action.type
}

@Composable
private fun RoutinesPane(vm: RobotViewModel, ui: UiState) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = ui.routines.firstOrNull { it.id == editingId }

    if (showDialog) {
        RoutineDialog(
            initial = editing,
            onDismiss = { showDialog = false; editingId = null },
            onSave = { routine ->
                vm.routinesUpsert(routine)
                showDialog = false
                editingId = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PrimaryButton(
                text = "Nueva rutina",
                onClick = { editingId = null; showDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.routines.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Schedule,
                    title = "No hay rutinas",
                    subtitle = "Programá una acción para que el robot la haga a una hora y días fijos.",
                )
            }
        } else {
            items(ui.routines) { routine ->
                AppCard(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editingId = routine.id; showDialog = true },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = routine.time,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    dayLabels.forEachIndexed { i, label ->
                                        val active = (i + 1) in routine.days
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) MaterialTheme.colorScheme.primary else TextSecondary,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = routineActionSummary(routine.action),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = routine.enabled,
                            onCheckedChange = { vm.routinesToggle(routine.id, it) },
                        )
                        IconButton(onClick = { vm.routinesDelete(routine.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar rutina", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineDialog(
    initial: Routine?,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
) {
    var time by remember(initial?.id) { mutableStateOf(initial?.time ?: "08:00") }
    var days by remember(initial?.id) { mutableStateOf(initial?.days ?: emptySet()) }
    var actionType by remember(initial?.id) { mutableStateOf(initial?.action?.type ?: "speak") }
    var speakText by remember(initial?.id) {
        mutableStateOf(initial?.action?.takeIf { it.type == "speak" }?.payload ?: "")
    }
    var animation by remember(initial?.id) {
        mutableStateOf(initial?.action?.takeIf { it.type == "animation" }?.payload ?: "dance_ai1")
    }
    var scene by remember(initial?.id) {
        mutableStateOf(initial?.action?.takeIf { it.type == "scene" }?.payload ?: "fiesta")
    }
    var lightOn by remember(initial?.id) {
        mutableStateOf(initial?.action?.takeIf { it.type == "light" }?.payload ?: "on")
    }
    var error by remember(initial?.id) { mutableStateOf(false) }
    val timeValid = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(time)
    val canSave = timeValid && days.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                if (initial == null) "Nueva rutina" else "Editar rutina",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hora") },
                    placeholder = { Text("HH:mm") },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text("Formato HH:mm") }
                    } else {
                        null
                    },
                )
                SectionTitle("Días")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dayLabels.forEachIndexed { i, label ->
                        val day = i + 1
                        FilterChip(
                            selected = day in days,
                            onClick = {
                                days = if (day in days) days - day else days + day
                            },
                            label = { Text(label) },
                        )
                    }
                }
                SectionTitle("Acción")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actionOptions.forEach { (type, label, icon) ->
                        FilterChip(
                            selected = actionType == type,
                            onClick = { actionType = type },
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            },
                        )
                    }
                }
                when (actionType) {
                    "speak" -> OutlinedTextField(
                        value = speakText,
                        onValueChange = { speakText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Texto para hablar") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    "animation" -> AnimationSelector(animation) { animation = it }
                    "scene" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sceneOptions.forEach { (id, label) ->
                            FilterChip(
                                selected = scene == id,
                                onClick = { scene = id },
                                label = { Text(label) },
                            )
                        }
                    }
                    "light" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = lightOn == "on",
                            onClick = { lightOn = "on" },
                            label = { Text("Encender") },
                        )
                        FilterChip(
                            selected = lightOn == "off",
                            onClick = { lightOn = "off" },
                            label = { Text("Apagar") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (timeValid && days.isNotEmpty()) {
                        error = false
                        val payload = when (actionType) {
                            "speak" -> speakText
                            "animation" -> animation
                            "scene" -> scene
                            "light" -> lightOn
                            else -> ""
                        }
                        onSave(
                            Routine(
                                id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                time = time,
                                days = days,
                                enabled = initial?.enabled ?: true,
                                action = RoutineAction(type = actionType, payload = payload),
                            )
                        )
                    } else {
                        error = true
                    }
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimationSelector(selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = Animations.all.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.label ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Animación") },
            placeholder = { Text("Elegir animación") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Animations.all.forEach { entry ->
                DropdownMenuItem(
                    text = { Text("${entry.label} (${entry.group})") },
                    onClick = { onSelect(entry.id); expanded = false },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fotos
// ---------------------------------------------------------------------------

@Composable
private fun PhotosPane(vm: RobotViewModel, ui: UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Sincronizar fotos", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (ui.photoServerRunning) {
                    OutlinedButton(
                        onClick = { vm.stopPhotoSync() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Detener sync")
                    }
                } else {
                    PrimaryButton(
                        text = "Iniciar sync",
                        onClick = { vm.startPhotoSync() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.photoServerRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Recibiendo fotos por WiFi…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "El robot y el dispositivo deben estar en la misma WiFi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Modo foto", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.photoEnter() }, modifier = Modifier.weight(1f)) {
                        Text("Modo foto")
                    }
                    OutlinedButton(onClick = { vm.photoShow() }, modifier = Modifier.weight(1f)) {
                        Text("Mostrar")
                    }
                    OutlinedButton(onClick = { vm.photoExit() }, modifier = Modifier.weight(1f)) {
                        Text("Salir")
                    }
                }
            }
        }
        if (ui.photos.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.PhotoCamera,
                    title = "Todavía no hay fotos",
                    subtitle = "Iniciá el sync con el robot en la misma WiFi.",
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Fotos recibidas (${ui.photos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.photoClear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar fotos", tint = TextSecondary)
                    }
                }
            }
            items(ui.photos.chunked(3)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { path ->
                        AppCard(Modifier.weight(1f)) {
                            Text(
                                text = path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Modo remoto (spec C1: API HTTP en foreground service)
// ---------------------------------------------------------------------------

@Composable
private fun WifiPane(vm: RobotViewModel, ui: UiState) {
    var selected by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Red del robot")
                LaunchedEffect(Unit) { vm.wifiStatus() }
                Text(
                    if (ui.robotWifi != null) "Conectado a: ${ui.robotWifi}"
                    else "Sin red (o sin datos todavía — tocá el estado para refrescar)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.robotWifi != null) StatusGreen else TextSecondary,
                    modifier = Modifier.clickable { vm.wifiStatus() },
                )
                Text(
                    "El robot escanea las redes cercanas por BLE. Elegí una y pasale la contraseña.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                PrimaryButton(
                    text = if (ui.wifiScanning) "Escaneando…" else "Buscar redes",
                    onClick = { vm.wifiScan() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !ui.wifiScanning,
                )
            }
        }
        if (ui.wifiNetworks.isEmpty() && !ui.wifiScanning) {
            EmptyState(
                icon = Icons.Default.Wifi,
                title = "Sin redes",
                subtitle = "Tocá \"Buscar redes\" para que el robot escanee.",
            )
        } else {
            ui.wifiNetworks.forEach { net ->
                AppCard(Modifier.fillMaxWidth().clickable {
                    selected = net
                    password = ""
                    showDialog = true
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = TextPrimary)
                        Spacer(Modifier.width(12.dp))
                        Text(net, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                }
            }
        }
    }

    if (showDialog && selected != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Conectar a ${selected}", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Contraseña") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setRobotWifi(selected!!, password)
                    showDialog = false
                }) { Text("Conectar", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancelar", color = TextSecondary) }
            },
        )
    }
}

@Composable
private fun RemotePane(vm: RobotViewModel, ui: UiState) {
    var showConfig by remember { mutableStateOf(false) }

    if (showConfig) {
        var token by rememberSaveable { mutableStateOf(vm.remoteToken()) }
        var port by rememberSaveable { mutableStateOf(vm.remotePort().toString()) }
        AlertDialog(
            onDismissRequest = { showConfig = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Configurar modo remoto", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Token de acceso") },
                        placeholder = { Text("secreto") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v ->
                            if (v.length <= 5 && v.all { it.isDigit() }) port = v
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Puerto") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Text(
                        "La API escucha en tu red local (LAN) con token obligatorio. " +
                            "Guía de integración con Hermes Agent: docs/HERMES.md",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveRemoteToken(token)
                    vm.saveRemotePort(port.toIntOrNull() ?: 8080)
                    showConfig = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showConfig = false }) { Text("Cancelar") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (ui.remoteRunning) MaterialTheme.colorScheme.primary else TextSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ui.remoteRunning) "Modo remoto activo" else "Modo remoto",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        Text(
                            if (ui.remoteRunning) {
                                "La conexión la maneja el servicio"
                            } else {
                                "API HTTP local para Hermes, Telegram o cron"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Switch(
                        checked = ui.remoteRunning,
                        onCheckedChange = { on ->
                            if (on) vm.startRemoteMode() else vm.stopRemoteMode()
                        },
                    )
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Configuración", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (vm.remoteToken().isEmpty()) {
                                "Token: sin configurar"
                            } else {
                                "Token: configurado (${vm.remoteToken().length} caracteres)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vm.remoteToken().isEmpty()) Warning else TextSecondary,
                        )
                        Text(
                            "Puerto: ${vm.remotePort()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    OutlinedButton(onClick = { showConfig = true }) { Text("Configurar") }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                if (ui.remoteRunning) {
                    Text(
                        "Modo remoto activo: la conexión BLE la maneja el servicio en segundo " +
                            "plano y la app no iniciará su propia conexión. Podés detenerlo con el " +
                            "toggle o desde la notificación («Parar»).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning,
                    )
                } else {
                    Text(
                        "Al activar el modo remoto la app pasa la conexión al servicio de fondo, " +
                            "que levanta la API HTTP en tu red local. El token es obligatorio: sin " +
                            "token configurado el servidor no arranca y se te avisa por notificación.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Laboratorio (spec C4: consola JSON raw)
// ---------------------------------------------------------------------------

private data class LabTemplate(val label: String, val json: String)

private val labTemplates = listOf(
    LabTemplate("sta query", """{"type":"sta_req","data":{"op":"query","list":[1,8,11,12]}}"""),
    LabTemplate("show speak", """{"type":"show_req","data":{"op":"speak","txt":"hola"}}"""),
    LabTemplate("show play", """{"type":"show_req","data":{"op":"play","animation":"dance_ai1"}}"""),
    LabTemplate("light set", """{"type":"light_req","data":{"op":"set","id":0,"mode":"flow","color":[255,80,180],"brightness":90}}"""),
    LabTemplate("alarm list", """{"type":"alarm_req","data":{"op":"list"}}"""),
    LabTemplate("alarm add", """{"type":"alarm_req","data":{"op":"add","tag":0,"time":"08:00"}}"""),
    LabTemplate("alarm del", """{"type":"alarm_req","data":{"op":"del","index":0}}"""),
    LabTemplate("game in", """{"type":"chess_req","data":{"op":"in"}}"""),
    LabTemplate("game start", """{"type":"chess_req","data":{"op":"start"}}"""),
    LabTemplate("game play", """{"type":"chess_req","data":{"op":"play"}}"""),
    LabTemplate("photo in", """{"type":"photo_req","data":{"op":"in"}}"""),
    LabTemplate("photo sync", """{"type":"photo_req","data":{"op":"sync","server":{"ip":"TU_IP","port":9090}}}"""),
)

@Composable
private fun LabPane(vm: RobotViewModel, ui: UiState) {
    var editor by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var sweepFrom by rememberSaveable { mutableStateOf("0") }
    var sweepTo by rememberSaveable { mutableStateOf("15") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Comando JSON", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editor,
                    onValueChange = { editor = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("JSON") },
                    placeholder = { Text("{\"type\":\"sta_req\",\"data\":{\"op\":\"query\",\"list\":[12]}}") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                    maxLines = 8,
                    isError = error,
                    supportingText = if (error) {
                        { Text("JSON inválido") }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "Enviar",
                    onClick = { if (!vm.sendRaw(editor)) error = true },
                    enabled = editor.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Las respuestas del robot se ven en la sub-pestaña Log BLE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Barrido motion (DD CC)", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                val from = sweepFrom.toIntOrNull()
                val to = sweepTo.toIntOrNull()
                val rangeValid = from != null && to != null && from in 0..255 && to in 0..255 && from <= to
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sweepFrom,
                        onValueChange = { v ->
                            if (v.length <= 3 && v.all { it.isDigit() }) sweepFrom = v
                        },
                        modifier = Modifier.width(120.dp),
                        label = { Text("Desde") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sweepTo,
                        onValueChange = { v ->
                            if (v.length <= 3 && v.all { it.isDigit() }) sweepTo = v
                        },
                        modifier = Modifier.width(120.dp),
                        label = { Text("Hasta") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "Iniciar barrido",
                    onClick = {
                        if (from != null && to != null) vm.startMotionSweep(from, to)
                    },
                    enabled = rangeValid && !ui.motionSweeping,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.motionSweeping) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Barriendo cmd ${ui.motionSweepCmd ?: ""}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.stopMotionSweep() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Parar")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Las respuestas binarias (DD CC) se ven completas en logcat y truncadas en Log BLE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Plantillas", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labTemplates.forEach { t ->
                        FilterChip(
                            selected = false,
                            onClick = { editor = t.json; error = false },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Historial (${ui.rawHistory.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.clearRawHistory() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpiar historial", tint = TextSecondary)
                    }
                }
                if (ui.rawHistory.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Los comandos enviados aparecen acá. Tocalos para rellenar el editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                } else {
                    ui.rawHistory.asReversed().forEach { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editor = entry; error = false }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Capturar la app oficial: adb logcat | grep BleUtils",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Log BLE
// ---------------------------------------------------------------------------

@Composable
private fun LogPane(vm: RobotViewModel, ui: UiState) {
    var filters by remember { mutableStateOf(setOf<LogCat>()) }
    val visible = remember(ui.log, filters) {
        ui.log.filter { filters.isEmpty() || it.cat in filters }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogCat.entries.forEach { cat ->
                    FilterChip(
                        selected = cat in filters,
                        onClick = {
                            filters = if (cat in filters) filters - cat else filters + cat
                        },
                        label = { Text(cat.name) },
                    )
                }
            }
            IconButton(onClick = { vm.clearLog() }) {
                Icon(Icons.Default.Delete, contentDescription = "Limpiar", tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (visible.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Terminal,
                title = "Log vacío",
                subtitle = "El tráfico BLE aparece acá cuando hay actividad.",
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(visible) { line ->
                    Text(
                        text = "${line.cat.name} ${line.text}",
                        color = when (line.cat) {
                            LogCat.TX -> MaterialTheme.colorScheme.primary
                            LogCat.RX -> MaterialTheme.colorScheme.secondary
                            LogCat.EVT -> Warning
                            LogCat.ERR -> MaterialTheme.colorScheme.error
                            LogCat.SYS -> TextSecondary
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
