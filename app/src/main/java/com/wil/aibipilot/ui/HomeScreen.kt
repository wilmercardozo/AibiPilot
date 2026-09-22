package com.wil.aibipilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
fun HomeScreen(vm: RobotViewModel, ui: UiState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Inicio", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        EmptyState(
            icon = Icons.Default.Home,
            title = "En construcción",
            subtitle = "El dashboard de inicio se arma en la próxima tarea."
        )
    }
}
