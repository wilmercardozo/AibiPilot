package com.wil.aibipilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.LogCat
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.StatusPill
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary

enum class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    INICIO("Inicio", Icons.Default.Home),
    CHAT("Chat IA", Icons.Default.AutoAwesome),
    HABLAR("Hablar", Icons.Default.RecordVoiceOver),
    JUGAR("Jugar", Icons.Default.SportsEsports),
    HERRAMIENTAS("Herramientas", Icons.Default.Build),
}

@Composable
fun AibiPilotApp(vm: RobotViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        vm.tryAutoReconnect()
    }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840
    var dest by rememberSaveable { mutableStateOf(Destination.INICIO) }
    when {
        ui.conn == ConnState.CONNECTED ||
            ui.conn == ConnState.CONNECTING ||
            ui.conn == ConnState.RECONNECTING -> {
            if (isTablet) {
                Row(Modifier.fillMaxSize().padding(16.dp)) {
                    NavigationRail {
                        Spacer(Modifier.height(8.dp))
                        Text("AIBI\nPilot", style = MaterialTheme.typography.titleSmall)
                        Destination.entries.forEach { d ->
                            NavigationRailItem(
                                selected = dest == d,
                                onClick = { dest = d },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) }
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.fillMaxSize()) {
                        ConnectedHeader(vm, ui)
                        Spacer(Modifier.height(12.dp))
                        DestinationContent(dest, vm, ui)
                    }
                }
            } else {
                Scaffold(bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { d ->
                            NavigationBarItem(
                                selected = dest == d,
                                onClick = { dest = d },
                                icon = { Icon(d.icon, contentDescription = d.label) },
                                label = { Text(d.label) }
                            )
                        }
                    }
                }) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                        ConnectedHeader(vm, ui)
                        Spacer(Modifier.height(8.dp))
                        DestinationContent(dest, vm, ui)
                    }
                }
            }
        }
        else -> ConnectScreen(vm, ui, Modifier)
    }
}

@Composable
private fun DestinationContent(dest: Destination, vm: RobotViewModel, ui: UiState) {
    when (dest) {
        Destination.INICIO -> HomeScreen(vm, ui)
        Destination.CHAT -> ChatScreen(vm, ui)
        Destination.HABLAR -> TalkScreen(vm, ui)
        Destination.JUGAR -> GamesScreen(vm, ui)
        Destination.HERRAMIENTAS -> ToolsScreen(vm, ui)
    }
}

@Composable
private fun ConnectedHeader(vm: RobotViewModel, ui: UiState) {
    var showTheme by remember { mutableStateOf(false) }
    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Apariencia", style = MaterialTheme.typography.titleMedium) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "system" to "Sistema",
                        "light" to "Claro",
                        "dark" to "Oscuro"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = ui.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTheme = false }) { Text("Cerrar") }
            }
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "AIBI Pilot",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        StatusPill(ui.conn, ui.reconnectAttempt, ui.connHint)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { showTheme = true }) {
            Icon(Icons.Default.Settings, contentDescription = "Apariencia")
        }
        TextButton(onClick = { vm.disconnect() }) {
            Text("Desconectar", color = TextSecondary)
        }
    }
}

@Composable
private fun LogTab(vm: RobotViewModel, ui: UiState) {
    var filter by remember { mutableStateOf<LogCat?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tráfico BLE", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.clearLog() }) { Text("Limpiar") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("Todos") },
                modifier = Modifier.padding(end = 6.dp)
            )
            LogCat.entries.forEach { cat ->
                FilterChip(
                    selected = filter == cat,
                    onClick = { filter = cat },
                    label = { Text(cat.name) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        val visible = remember(ui.log, filter) {
            ui.log.filter { filter == null || it.cat == filter }
        }
        LaunchedEffect(visible.size) {
            if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(visible) { line ->
                Text(
                    "${line.cat.name} ${line.text}",
                    color = when (line.cat) {
                        LogCat.ERR -> Color(0xFFD32F2F)
                        LogCat.TX -> Color(0xFF1976D2)
                        LogCat.RX -> Color(0xFF2E7D32)
                        LogCat.EVT -> Color(0xFFF57C00)
                        LogCat.SYS -> Color(0xFF757575)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LightsTab(vm: RobotViewModel, ui: UiState) {
    var mode by remember { mutableStateOf("default") }
    var brightness by remember { mutableStateOf(50f) }
    var red by remember { mutableStateOf(255f) }
    var green by remember { mutableStateOf(0f) }
    var blue by remember { mutableStateOf(128f) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Modo luz de estrellas", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "default" to "Fijo",
                            "breath" to "Respirar",
                            "color" to "Color",
                            "flow" to "Flujo"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = mode == value,
                                onClick = { mode = value },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Color RGB", style = MaterialTheme.typography.titleSmall)
                    ColorSlider("Rojo", red) { red = it }
                    ColorSlider("Verde", green) { green = it }
                    ColorSlider("Azul", blue) { blue = it }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(
                                Color(
                                    0xFF000000.toInt() or
                                        (red.toInt() shl 16) or
                                        (green.toInt() shl 8) or
                                        blue.toInt()
                                )
                            )
                    )
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Brillo: ${brightness.toInt()}%", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = 0f..100f
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            vm.lightSet(
                                mode,
                                listOf(red.toInt(), green.toInt(), blue.toInt()),
                                brightness.toInt()
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Aplicar luz")
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Control", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.lightEnter() }) { Text("Entrar") }
                        Button(onClick = { vm.lightOn(0) }) { Text("Encender") }
                        Button(onClick = { vm.lightOff(0) }) { Text("Apagar") }
                        Button(onClick = { vm.lightExit() }) { Text("Salir") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f
        )
    }
}

@Composable
private fun AlarmsTab(vm: RobotViewModel, ui: UiState) {
    var time by remember { mutableStateOf("08:00") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nueva alarma", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Hora (HH:mm, 24h)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val idx = ui.alarms.size
                            vm.alarmAdd(idx, time)
                        },
                        enabled = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(time),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Agregar alarma")
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { vm.alarmRefresh() }, modifier = Modifier.weight(1f)) {
                    Text("Listar")
                }
                Button(onClick = { vm.alarmEnter() }, modifier = Modifier.weight(1f)) {
                    Text("Entrar al modo")
                }
                Button(onClick = { vm.alarmExit() }, modifier = Modifier.weight(1f)) {
                    Text("Salir")
                }
            }
        }
        if (ui.alarms.isEmpty()) {
            item {
                Text(
                    "Sin alarmas todavía. Tocá \"Listar\" para cargarlas del robot.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(ui.alarms) { alarm ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        alarm.time,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.alarmDel(alarm.index) }) {
                        Text("Eliminar")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotosTab(vm: RobotViewModel, ui: UiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sincronizar fotos", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "La app levanta un servidor TCP en el puerto 9090 y le pide al robot " +
                            "que envíe sus fotos por WiFi. Ambas deben estar en la misma red.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (ui.photoServerRunning) {
                        Button(
                            onClick = { vm.stopPhotoSync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Detener servidor")
                        }
                    } else {
                        Button(
                            onClick = { vm.startPhotoSync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Iniciar sincronización")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.photoEnter() }, modifier = Modifier.weight(1f)) {
                            Text("Modo foto")
                        }
                        Button(onClick = { vm.photoShow() }, modifier = Modifier.weight(1f)) {
                            Text("Mostrar")
                        }
                        Button(onClick = { vm.photoExit() }, modifier = Modifier.weight(1f)) {
                            Text("Salir")
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Fotos recibidas: ${ui.photos.size}",
                style = MaterialTheme.typography.titleSmall
            )
        }
        items(ui.photos) { path ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
