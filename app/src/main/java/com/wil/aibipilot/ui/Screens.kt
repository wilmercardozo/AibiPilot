package com.wil.aibipilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wil.aibipilot.ConnState
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
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.dismissSnackbar()
        }
    }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840
    var dest by rememberSaveable { mutableStateOf(Destination.INICIO) }
    when {
        ui.conn == ConnState.CONNECTED ||
            ui.conn == ConnState.CONNECTING ||
            ui.conn == ConnState.RECONNECTING -> {
            if (isTablet) {
                Box(Modifier.fillMaxSize()) {
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
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
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
                    }
                ) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                        ConnectedHeader(vm, ui)
                        Spacer(Modifier.height(8.dp))
                        DestinationContent(dest, vm, ui)
                    }
                }
            }
        }
        else -> Box(Modifier.fillMaxSize()) {
            ConnectScreen(vm, ui, Modifier)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
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
