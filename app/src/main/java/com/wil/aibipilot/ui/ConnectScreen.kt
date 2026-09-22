package com.wil.aibipilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.EmptyState
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.components.StatusPill
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary

private fun hasBluetoothPermissions(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun ConnectScreen(vm: RobotViewModel, ui: UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasPerms by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    var showHintDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPerms = result.values.all { it }
    }

    val hint = ui.connHint
    LaunchedEffect(hint) {
        if (hint != null) showHintDialog = true
    }

    if (showHintDialog) {
        val otraApp = hint?.contains("otra app", ignoreCase = true) == true
        AlertDialog(
            onDismissRequest = { showHintDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    if (otraApp) "Otra app está usando el robot" else "El robot está dormido",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    if (otraApp) {
                        "El robot está al alcance pero rechazó la conexión. Si la app oficial " +
                            "(Living.AI) lo tiene conectado, cerrá esa app y reintentá."
                    } else {
                        "El robot no responde. Despertalo (tocale la cabeza) y volvé a intentar."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "Reintentar",
                    onClick = {
                        showHintDialog = false
                        vm.tryAutoReconnect()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showHintDialog = false }) {
                    Text("Entendido", color = TextSecondary)
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "AIBI Pilot",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Control alternativo para el robot mascota AIBI Pocket.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(24.dp))

        if (!hasPerms) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(
                    "Permisos necesarios",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Para escanear y conectarse por Bluetooth es necesario otorgar permisos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "Otorgar permisos",
                    onClick = { launcher.launch(vm.ble.requiredPermissions()) }
                )
            }
            return@Column
        }

        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Despertá tu robot",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        "Tocá la cabeza del robot para despertarlo y dejalo cerca del dispositivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!vm.ble.isBluetoothEnabled()) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(
                    "Bluetooth apagado",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Encendé el Bluetooth del teléfono y volvé a intentar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            return@Column
        }

        hint?.let {
            AppCard(
                Modifier
                    .fillMaxWidth()
                    .clickable { showHintDialog = true }
            ) {
                StatusPill(ui.conn, ui.reconnectAttempt, it)
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.height(12.dp))
        }

        vm.savedDeviceName()?.let { savedName ->
            OutlinedButton(
                onClick = { vm.tryAutoReconnect() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reconectar a $savedName")
            }
            Spacer(Modifier.height(12.dp))
        }

        PrimaryButton(
            text = if (ui.conn == ConnState.SCANNING) "Escaneando…" else "Buscar robots",
            onClick = { vm.startScan() },
            enabled = ui.conn != ConnState.SCANNING,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        when {
            ui.conn == ConnState.SCANNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Buscando dispositivos «AIBI»…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }

            ui.devices.isEmpty() -> EmptyState(
                icon = Icons.Default.Bluetooth,
                title = "Sin dispositivos",
                subtitle = "No se ven dispositivos todavía. ¿El robot está encendido y cerca?"
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.devices.distinctBy { it.device.address }) { dev ->
                    val isAibi = dev.device.name?.contains("AIBI", ignoreCase = true) == true
                    AppCard(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.connect(dev) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dev.device.name ?: "(sin nombre)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isAibi) MaterialTheme.colorScheme.primary else TextPrimary
                                )
                                Text(
                                    "${dev.device.address}  •  ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary
                                )
                            }
                            if (isAibi) {
                                Text(
                                    "Robot",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                            shape = RoundedCornerShape(999.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            } else {
                                TextButton(onClick = { vm.connect(dev) }) {
                                    Text("Conectar", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
