package com.wil.aibipilot.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SportsEsports
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
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary

private data class Game(
    val type: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val games = listOf(
    Game("chess", "Ajedrez", "Partida contra AIBI.", Icons.Default.SportsEsports),
    Game("snake", "Serpientes", "El clásico de la serpiente.", Icons.Default.Pets),
    Game("pirate", "Pirate Wars", "Batalla pirata.", Icons.Default.Flag),
    Game("zero", "Zero", "Un juego de azar… descubrilo.", Icons.Default.Casino),
)

@Composable
fun GamesScreen(vm: RobotViewModel, ui: UiState) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val game = games.firstOrNull { it.type == selected }
    if (game == null) {
        GameList(onSelect = { selected = it })
    } else {
        GameView(vm, game, onBack = { selected = null })
    }
}

@Composable
private fun GameList(onSelect: (String) -> Unit) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 840) 2 else 1
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Jugar", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        games.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { game ->
                    GameCard(game, Modifier.weight(1f)) { onSelect(game.type) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GameCard(game: Game, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier.clickable { onClick() }) {
        Icon(
            imageVector = game.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(game.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(game.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun GameView(vm: RobotViewModel, game: Game, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text(game.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }
        AppCard(Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = "Entrar",
                onClick = { vm.gameEnter(game.type) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.gameStart(game.type) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Empezar")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.gamePlay(game.type) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Jugar")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { vm.gameExit(game.type) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salir", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
