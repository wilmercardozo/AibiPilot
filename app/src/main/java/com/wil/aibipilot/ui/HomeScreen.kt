package com.wil.aibipilot.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.ConnState
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.batteryLabel
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.SectionTitle
import com.wil.aibipilot.ui.components.StatusPill
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary
import com.wil.aibipilot.ui.theme.Warning

private data class Stat(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val mono: Boolean,
)

private data class Scene(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
fun HomeScreen(vm: RobotViewModel, ui: UiState) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 840
    val sceneColumns = if (isTablet) 4 else 2
    var lightOn by rememberSaveable { mutableStateOf(false) }

    val volumeNext = when (ui.volume) {
        "mute" -> "low"
        "low" -> "high"
        else -> "mute"
    }
    val volumeLabel = when (ui.volume) {
        "mute" -> "Silencio"
        "low" -> "Bajo"
        "high" -> "Alto"
        else -> ui.volume
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RobotCard(vm, ui)

        SectionTitle("Accesos rápidos")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickCard(
                icon = Icons.Default.VolumeUp,
                title = "Volumen",
                subtitle = volumeLabel,
                modifier = Modifier.weight(1f),
            ) { vm.setVolume(volumeNext) }
            QuickCard(
                icon = Icons.Default.LightMode,
                title = "Luz",
                subtitle = if (lightOn) "Apagar" else "Prender",
                modifier = Modifier.weight(1f),
            ) {
                lightOn = !lightOn
                if (lightOn) vm.lightOn(0) else vm.lightOff(0)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickCard(
                icon = Icons.Default.RecordVoiceOver,
                title = "TTS",
                subtitle = "Frase corta",
                modifier = Modifier.weight(1f),
            ) { vm.speak("¡Hola! Soy AIBI") }
            QuickCard(
                icon = Icons.Default.TheaterComedy,
                title = "Baile",
                subtitle = "Neon Pulse",
                modifier = Modifier.weight(1f),
            ) { vm.playAnimation("dance_ai1") }
        }

        SectionTitle("Escenas")
        val scenes = listOf(
            Scene("fiesta", "Fiesta", "Luces de colores y baile", Icons.Default.Celebration),
            Scene("despertar", "Despertar", "Luz y buenos días", Icons.Default.WbTwilight),
            Scene("relax", "Relax", "Respiración guiada", Icons.Default.SelfImprovement),
            Scene("noche", "Noche", "A dormir, con la luz apagada", Icons.Default.Bedtime),
        )
        scenes.chunked(sceneColumns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { scene ->
                    SceneCard(scene, Modifier.weight(1f)) { vm.playScene(scene.id) }
                }
                repeat(sceneColumns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (ui.conn != ConnState.CONNECTED) {
            NotConnectedBanner(vm, ui)
        }
    }
}

@Composable
private fun RobotCard(vm: RobotViewModel, ui: UiState) {
    val name = vm.savedDeviceName()?.takeIf { it.isNotBlank() }
        ?: ui.info.deviceName.takeIf { it.isNotBlank() }
        ?: "AIBI"
    val stats = listOf(
        Stat(Icons.Default.BatteryFull, "Batería", batteryLabel(ui.info.battery), mono = false),
        Stat(Icons.Default.DirectionsWalk, "Pasos", ui.info.steps?.toString() ?: "—", mono = true),
        Stat(Icons.Default.MonetizationOn, "Monedas", ui.info.gold?.toString() ?: "—", mono = true),
        Stat(Icons.Default.Restaurant, "Comida", ui.info.food?.toString() ?: "—", mono = true),
        Stat(Icons.Default.Memory, "Versión", ui.info.versionNumber.ifEmpty { "—" }, mono = true),
        Stat(Icons.Default.QueryStats, "MTU", ui.info.mtu.toString(), mono = true),
    )

    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusPill(ui.conn, ui.reconnectAttempt, ui.connHint)
            IconButton(onClick = { vm.refreshStatus() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        stats.chunked(2).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { stat -> StatItem(stat, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatItem(stat: Stat, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = stat.icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stat.label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                text = stat.value,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontFamily = if (stat.mono) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
private fun QuickCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AppCard(modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun SceneCard(scene: Scene, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppCard(modifier.clickable { onClick() }) {
        Icon(
            imageVector = scene.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(scene.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(scene.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun NotConnectedBanner(vm: RobotViewModel, ui: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.20f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Warning)
                Spacer(Modifier.width(8.dp))
                Text(
                    "El robot no está conectado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            }
            if (ui.conn == ConnState.RECONNECTING) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reintentando… (${ui.reconnectAttempt}/10)",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.tryAutoReconnect() }) {
                    Text("Reintentar")
                }
                TextButton(onClick = { vm.disconnect() }) {
                    Text("Desconectar", color = TextSecondary)
                }
            }
        }
    }
}
