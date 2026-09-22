package com.wil.aibipilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.protocol.Animations
import kotlin.math.max

@Composable
fun AibiPilotApp(vm: RobotViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        vm.tryAutoReconnect()
    }
    Scaffold { padding ->
        when (ui.conn) {
            ConnState.DISCONNECTED,
            ConnState.SCANNING -> ScanScreen(vm, ui, Modifier.padding(padding))
            else -> ConnectedScreen(vm, ui, Modifier.padding(padding))
        }
    }
}

private fun batteryLabel(level: Int?): String = when (level) {
    1 -> "Baja"
    2 -> "Media"
    3 -> "Alta"
    4 -> "Llena"
    else -> "—"
}

@Composable
private fun ScanScreen(vm: RobotViewModel, ui: UiState, modifier: Modifier) {
    val context = LocalContext.current
    var hasPerms by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPerms = result.values.all { it }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("AIBI Pilot", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Control alternativo para el robot mascota AIBI Pocket.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        if (!hasPerms) {
            ElevatedCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Permisos necesarios", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Para escanear y conectarse por Bluetooth es necesario otorgar permisos.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(vm.ble.requiredPermissions()) }) {
                        Text("Otorgar permisos")
                    }
                }
            }
            return@Column
        }

        if (!vm.ble.isBluetoothEnabled()) {
            ElevatedCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Bluetooth apagado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Encendé el Bluetooth del teléfono y volvé a intentar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            return@Column
        }

        Button(
            onClick = { vm.startScan() },
            enabled = ui.conn != ConnState.SCANNING
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (ui.conn == ConnState.SCANNING) "Escaneando..." else "Buscar robots")
        }
        vm.savedDeviceName()?.let { savedName ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.tryAutoReconnect() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reconectar a $savedName")
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            ui.conn == ConnState.SCANNING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buscando dispositivos \"AIBI\"...")
                }
            }
            ui.devices.isEmpty() -> Text(
                "No se ven dispositivos todavía. ¿El robot está encendido y cerca?",
                style = MaterialTheme.typography.bodySmall
            )
            else -> Text(
                "Dispositivos (los \"AIBI\" primero):",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.devices.distinctBy { it.device.address }) { dev ->
                val isAibi = dev.device.name?.contains("AIBI", ignoreCase = true) == true
                ElevatedCard(
                    Modifier.fillMaxWidth().clickable { vm.connect(dev) }
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                dev.device.name ?: "(sin nombre)",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isAibi) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                            Text(
                                "${dev.device.address}  •  ${dev.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            if (isAibi) "Robot" else "Conectar",
                            color = if (isAibi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class TabDef(
    val label: String,
    val icon: ImageVector,
    val content: @Composable (RobotViewModel, UiState) -> Unit
)

private val tabs = listOf(
    TabDef("Estado", Icons.Default.MonitorHeart) { vm, ui -> StatusTab(vm, ui) },
    TabDef("Chat IA", Icons.Default.AutoAwesome) { vm, ui -> ChatTab(vm, ui) },
    TabDef("Hablar", Icons.Default.RecordVoiceOver) { vm, ui -> TalkTab(vm, ui) },
    TabDef("Animaciones", Icons.Default.TheaterComedy) { vm, _ -> AnimationsTab(vm) },
    TabDef("Luces", Icons.Default.LightMode) { vm, ui -> LightsTab(vm, ui) },
    TabDef("Alarmas", Icons.Default.AccessAlarm) { vm, ui -> AlarmsTab(vm, ui) },
    TabDef("Juegos", Icons.Default.SportsEsports) { vm, _ -> GamesTab(vm) },
    TabDef("Fotos", Icons.Default.PhotoCamera) { vm, ui -> PhotosTab(vm, ui) },
    TabDef("Log BLE", Icons.Default.Terminal) { vm, ui -> LogTab(vm, ui) },
)

@Composable
private fun ConnectedScreen(vm: RobotViewModel, ui: UiState, modifier: Modifier) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840

    if (isTablet) {
        Row(modifier = modifier.fillMaxSize().padding(16.dp)) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("AIBI\nPilot", style = MaterialTheme.typography.titleSmall)
                    }
                }
                tabs.forEachIndexed { index, tabDef ->
                    NavigationRailItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(tabDef.icon, contentDescription = tabDef.label) },
                        label = { Text(tabDef.label) }
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxSize()) {
                ConnectedHeader(vm, ui)
                Spacer(Modifier.height(12.dp))
                tabs[tab].content(vm, ui)
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
            ConnectedHeader(vm, ui)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { index, tabDef ->
                    TabChip(tabDef.label, tab == index) { tab = index }
                }
            }
            Spacer(Modifier.height(12.dp))
            tabs[tab].content(vm, ui)
        }
    }
}

@Composable
private fun ConnectedHeader(vm: RobotViewModel, ui: UiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Text("AIBI Pilot", style = MaterialTheme.typography.titleLarge)
            Text(
                if (ui.conn == ConnState.CONNECTED) "Conectado ✓" else "Conectando...",
                color = if (ui.conn == ConnState.CONNECTED) Color(0xFF2E7D32) else Color(0xFFF9A825),
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(onClick = { vm.disconnect() }) {
            Text("Desconectar")
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 8.dp)
    )
}

@Composable
private fun StatusTab(vm: RobotViewModel, ui: UiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    InfoCell("Batería", batteryLabel(ui.info.battery), Modifier.weight(1f))
                    InfoCell("Pasos", ui.info.steps?.toString() ?: "—", Modifier.weight(1f))
                    InfoCell("Monedas", ui.info.gold?.toString() ?: "—", Modifier.weight(1f))
                    InfoCell("Comida", ui.info.food?.toString() ?: "—", Modifier.weight(1f))
                }
            }
        }
        item {
            ui.info.battery?.let { level ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Nivel de batería", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { level / 4f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            batteryLabel(level),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (level <= 2) Color(0xFFC62828) else Color(0xFF2E7D32)
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        InfoCell("Versión", ui.info.version.ifEmpty { "—" }, Modifier.weight(1f))
                        InfoCell("Build", ui.info.versionNumber.ifEmpty { "—" }, Modifier.weight(1f))
                        InfoCell("MTU", ui.info.mtu.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Volumen", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mute" to "Silencio", "low" to "Bajo", "high" to "Alto").forEach { (value, label) ->
                            FilterChip(
                                selected = ui.volume == value,
                                onClick = { vm.setVolume(value) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (value != "mute") {
                                            Icon(
                                                Icons.Default.VolumeUp,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(label)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { vm.refreshStatus() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Actualizar estado")
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TalkTab(vm: RobotViewModel, ui: UiState) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            vm.speak(spoken)
        }
    }
    Column {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Texto a voz (TTS)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "El robot dirá el texto en voz alta. Respuesta esperada: show_speak_ok",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ej: ¡Hola! ¿Cómo estás?") },
                    maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.speak(text)
                            text = ""
                        },
                        enabled = text.isNotBlank() && ui.conn == ConnState.CONNECTED,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Hablar")
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                                ).apply {
                                    putExtra(
                                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Habla ahora")
                                }
                                voiceLauncher.launch(intent)
                            } catch (e: Exception) {
                                // sin reconocimiento de voz instalado
                            }
                        }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Voz")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Escenas", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Macros: combinan luces, animación y voz.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneButton("🎉 Fiesta", Modifier.weight(1f)) { vm.playScene("fiesta") }
                    SceneButton("🌅 Despertar", Modifier.weight(1f)) { vm.playScene("despertar") }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneButton("🧘 Relax", Modifier.weight(1f)) { vm.playScene("relax") }
                    SceneButton("🌙 Buenas noches", Modifier.weight(1f)) { vm.playScene("noche") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Frases rápidas", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val quick = listOf(
                    "¡Hola!",
                    "Buenos días",
                    "Te quiero mucho",
                    "Baila para mí",
                    "Buenas noches"
                )
                quick.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { phrase ->
                            TextButton(
                                onClick = { vm.speak(phrase) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(phrase)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier) {
        Text(label)
    }
}

@Composable
private fun ChatTab(vm: RobotViewModel, ui: UiState) {
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var cfg = remember { vm.loadLlmConfig() }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Configuración de la IA") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cfg.apiKey,
                        onValueChange = { cfg = cfg.copy(apiKey = it) },
                        label = { Text("API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cfg.baseUrl,
                        onValueChange = { cfg = cfg.copy(baseUrl = it) },
                        label = { Text("URL de la API (OpenAI-compatible)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cfg.model,
                        onValueChange = { cfg = cfg.copy(model = it) },
                        label = { Text("Modelo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveLlmConfig(cfg)
                    showSettings = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Cancelar") }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Chat con el robot", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Configurar IA")
            }
        }
        Text(
            "Tu mensaje se envía a la IA y el robot lo dice en voz alta. " +
                "Configurá tu API key con el engranaje.",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        val listState = androidx.compose.foundation.lazy.LazyListState()
        LaunchedEffect(ui.chat.size) {
            if (ui.chat.isNotEmpty()) listState.animateScrollToItem(ui.chat.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ui.chat.isEmpty()) {
                item {
                    Text(
                        "Hablá con tu AIBI: preguntale cosas, contale tu día, o pedile que te anime. " +
                            "Ej: \"Contame un chiste\"",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(ui.chat) { msg ->
                val isUser = msg.role == "user"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                ) {
                    ElevatedCard(
                        Modifier.widthIn(max = 420.dp),
                        colors = if (isUser) {
                            CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else CardDefaults.elevatedCardColors()
                    ) {
                        Text(
                            msg.content,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (ui.chatThinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("La IA está pensando...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribí tu mensaje...") },
                maxLines = 3
            )
            IconButton(
                onClick = {
                    vm.sendChatMessage(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !ui.chatThinking
            ) {
                Icon(Icons.Default.Send, contentDescription = "Enviar")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimationsTab(vm: RobotViewModel) {
    val groups = Animations.all.groupBy { it.group }
    LazyColumn {
        groups.forEach { (group, entries) ->
            item {
                Text(
                    group,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    entries.forEach { entry ->
                        ElevatedCard(
                            onClick = { vm.playAnimation(entry.id) },
                            modifier = Modifier.width(170.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTab(vm: RobotViewModel, ui: UiState) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tráfico BLE", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.clearLog() }) { Text("Limpiar") }
        }
        HorizontalDivider()
        val listState = androidx.compose.foundation.lazy.LazyListState()
        LaunchedEffect(ui.log.size) {
            if (ui.log.isNotEmpty()) listState.animateScrollToItem(ui.log.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(ui.log) { line ->
                Text(
                    line,
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
private fun GamesTab(vm: RobotViewModel) {
    val games = listOf(
        "chess" to "Ajedrez",
        "snake" to "Serpientes y escaleras",
        "pirate" to "Pirate Wars",
        "zero" to "Zero"
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        games.forEach { (id, label) ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.gameEnter(id) }, modifier = Modifier.weight(1f)) {
                                Text("Entrar")
                            }
                            Button(onClick = { vm.gameStart(id) }, modifier = Modifier.weight(1f)) {
                                Text("Empezar")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.gamePlay(id) }, modifier = Modifier.weight(1f)) {
                                Text("Jugar")
                            }
                            OutlinedButton(
                                onClick = { vm.gameExit(id) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Salir")
                            }
                        }
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
