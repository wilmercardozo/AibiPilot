package com.wil.aibipilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.ChatMsg
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.ui.components.EmptyState
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.theme.ErrorRed
import com.wil.aibipilot.ui.theme.StatusGreen
import com.wil.aibipilot.ui.theme.TextPrimary
import com.wil.aibipilot.ui.theme.TextSecondary

@Composable
fun ChatScreen(vm: RobotViewModel, ui: UiState) {
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        ChatConfigDialog(vm, onDismiss = { showSettings = false })
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chat IA",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = TextSecondary)
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(ui.chat.size, ui.chatThinking) {
            val target = ui.chat.size + if (ui.chatThinking) 1 else 0
            if (target > 0) listState.animateScrollToItem(target - 1)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.chat.isEmpty() && !ui.chatThinking) {
                item {
                    EmptyState(
                        icon = Icons.Default.AutoAwesome,
                        title = "Hablá con AIBI",
                        subtitle = "Escribile un mensaje y el robot lo dice en voz alta.",
                    )
                }
            }
            items(ui.chat) { msg -> ChatBubble(msg) }
            if (ui.chatThinking) {
                item { ThinkingBubble() }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribí un mensaje…") },
                maxLines = 4,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            vm.sendChatMessage(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !ui.chatThinking,
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Enviar")
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    val shape = if (isUser) {
        RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)
    } else {
        RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp)
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = shape,
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = maxWidth * 0.85f),
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else TextPrimary,
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = maxWidth * 0.85f),
        ) {
            Text(
                text = "pensando…",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun ChatConfigDialog(vm: RobotViewModel, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(vm.loadLlmConfig()) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Configuración del chat", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cfg.baseUrl,
                    onValueChange = { cfg = cfg.copy(baseUrl = it) },
                    label = { Text("URL base") },
                    textStyle = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cfg.apiKey,
                    onValueChange = { cfg = cfg.copy(apiKey = it) },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    trailingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cfg.model,
                    onValueChange = { cfg = cfg.copy(model = it) },
                    label = { Text("Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Ollama local: http://<IP>:11434/v1/chat/completions",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        vm.testLlmConnection(cfg) { ok, msg ->
                            testing = false
                            testResult = ok to msg
                        }
                    },
                ) {
                    Text("Probar")
                }
                if (testing) {
                    Text(
                        text = "Probando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                testResult?.let { (ok, msg) ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) StatusGreen else ErrorRed,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Guardar",
                onClick = {
                    vm.saveLlmConfig(cfg)
                    onDismiss()
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
