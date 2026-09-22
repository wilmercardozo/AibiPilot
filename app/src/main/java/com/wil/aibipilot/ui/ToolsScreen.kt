package com.wil.aibipilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.EmptyState
import com.wil.aibipilot.ui.theme.TextPrimary

@Composable
fun ToolsScreen(vm: RobotViewModel, ui: UiState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Herramientas", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        EmptyState(
            icon = Icons.Default.Build,
            title = "En construcción",
            subtitle = "Luces, alarmas, fotos y log BLE se arman en una próxima tarea."
        )
    }
}
