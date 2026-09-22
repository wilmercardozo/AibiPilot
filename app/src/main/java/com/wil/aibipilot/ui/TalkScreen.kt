package com.wil.aibipilot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wil.aibipilot.RobotViewModel
import com.wil.aibipilot.UiState
import com.wil.aibipilot.protocol.Animations
import com.wil.aibipilot.ui.components.AppCard
import com.wil.aibipilot.ui.components.PrimaryButton
import com.wil.aibipilot.ui.components.SectionTitle
import com.wil.aibipilot.ui.theme.TextPrimary

@Composable
fun TalkScreen(vm: RobotViewModel, ui: UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Hablar", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }
        item { TtsCard(vm) }
        item { SectionTitle("Animaciones") }
        Animations.all.groupBy { it.group }.forEach { (group, entries) ->
            item { SectionTitle(group) }
            item { AnimationGrid(vm, entries) }
        }
    }
}

@Composable
private fun TtsCard(vm: RobotViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) text = spoken
    }

    AppCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Escribí algo para que AIBI lo diga…") },
            maxLines = 4,
            trailingIcon = {
                IconButton(
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
                    Icon(Icons.Default.Mic, contentDescription = "Hablar por voz")
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = "Decir",
            onClick = {
                vm.speak(text)
                text = ""
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AnimationGrid(vm: RobotViewModel, entries: List<Animations.Entry>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { entry ->
                    AnimationCard(vm, entry, Modifier.weight(1f))
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AnimationCard(vm: RobotViewModel, entry: Animations.Entry, modifier: Modifier) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { vm.playAnimation(entry.id) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir")
            }
        }
    }
}
