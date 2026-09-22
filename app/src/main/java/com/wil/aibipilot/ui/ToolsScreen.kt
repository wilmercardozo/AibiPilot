package com.wil.aibipilot.ui

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.LogCat
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.EmptyState
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.components.SectionTitle
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary
import com.wil.aibipilot.ui.theme.Warning

private enum class ToolTab(val label: String, val icon: ImageVector) {
    LUCES("Luces", Icons.Default.LightMode),
    ALARMAS("Alarmas", Icons.Default.AccessAlarm),
    FOTOS("Fotos", Icons.Default.PhotoCamera),
    REMOTO("Remoto", Icons.Default.Cloud),
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
            ToolTab.FOTOS -> PhotosPane(vm, ui)
            ToolTab.REMOTO -> RemotePane(vm, ui)
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
