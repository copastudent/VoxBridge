package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DictationEntry
import com.example.model.SpeechLanguage
import com.example.model.VoiceArchitecture
import com.example.model.VoiceModelProfile
import com.example.ui.components.LiveAudioWaveform
import com.example.viewmodel.DictationUiState
import com.example.viewmodel.LiveConversationState

@Composable
fun DictateScreen(
    dictationState: DictationUiState,
    liveState: LiveConversationState,
    trainedModels: List<VoiceModelProfile>,
    activeModelId: String,
    selectedLanguage: SpeechLanguage,
    amplitudeLevel: Float,
    history: List<DictationEntry>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onSelectTrainedModel: (VoiceModelProfile) -> Unit,
    onSelectArchitecturePreset: (VoiceArchitecture) -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onTextChanged: (String) -> Unit,
    onClearText: () -> Unit,
    onSaveDictation: () -> Unit,
    onSpeakAloud: (text: String, dictationId: String?) -> Unit,
    onDeleteDictation: (String) -> Unit,
    onStartLiveConversation: () -> Unit,
    onStopLiveConversation: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showLiveDialog by remember { mutableStateOf(false) }

    val activeProfile = trainedModels.firstOrNull { it.id == activeModelId } ?: trainedModels.firstOrNull()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Real-Time Dictation",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${selectedLanguage.label} • Adaptive speech-to-text & speech synthesis",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (!hasMicPermission) {
                                onRequestMicPermission()
                            } else {
                                showLiveDialog = true
                                onStartLiveConversation()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("live_conversation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhoneInTalk,
                            contentDescription = "Open live voice conversation",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Live Chat")
                    }
                }
            }

            // Voice Model Selector (Multiple Models for Best Fit)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (trainedModels.isNotEmpty()) {
                            "Select Trained Voice Model (${trainedModels.size} available)"
                        } else {
                            "Choose Voice Recognition Engine"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    if (trainedModels.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(trainedModels, key = { it.id }) { profile ->
                                val isSelected = activeProfile?.id == profile.id
                                val arch = VoiceArchitecture.fromName(profile.architecture)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectTrainedModel(profile) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = {
                                        Text("${profile.name} (${arch.latencyProfile})")
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("select_model_${profile.id}")
                                )
                            }
                        }
                    }

                    // Engine Presets row so users can compare architectures on the fly
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(VoiceArchitecture.entries) { arch ->
                            FilterChip(
                                selected = dictationState.selectedArchitecture == arch,
                                onClick = { onSelectArchitecturePreset(arch) },
                                label = {
                                    Text("${arch.displayName} • ${arch.latencyProfile}")
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("arch_preset_${arch.name}")
                            )
                        }
                    }
                }
            }

            // Live Dictation Canvas Card
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = if (dictationState.isLiveDictating) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (dictationState.isLiveDictating) {
                                            "LIVE STREAMING"
                                        } else {
                                            dictationState.selectedArchitecture.geminiModelId
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            if (dictationState.lastLatencyMs > 0L) {
                                Text(
                                    text = "${dictationState.lastLatencyMs} ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        LiveAudioWaveform(
                            amplitude = amplitudeLevel,
                            isActive = dictationState.isLiveDictating,
                            height = 48.dp
                        )

                        OutlinedTextField(
                            value = dictationState.liveText,
                            onValueChange = onTextChanged,
                            placeholder = {
                                Text("Tap Start Live Dictation and speak naturally. Decoded words appear here in real time...")
                            },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dictation_text_field")
                        )

                        AnimatedVisibility(visible = dictationState.partialChunkText.isNotBlank() || dictationState.isProcessingChunk) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = dictationState.partialChunkText.ifBlank { "Decoding speech chunk..." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        AnimatedVisibility(visible = dictationState.errorMessage != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = dictationState.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        if (!hasMicPermission) {
                            Button(
                                onClick = onRequestMicPermission,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("Enable Microphone for Live Dictation")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (dictationState.isLiveDictating) {
                                            onStopDictation()
                                        } else {
                                            onStartDictation()
                                        }
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (dictationState.isLiveDictating) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp)
                                        .testTag("toggle_dictation_button")
                                ) {
                                    Icon(
                                        imageVector = if (dictationState.isLiveDictating) Icons.Filled.Stop else Icons.Filled.Mic,
                                        contentDescription = if (dictationState.isLiveDictating) "Stop dictation" else "Start live dictation"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (dictationState.isLiveDictating) "Stop Dictation" else "Start Live Dictation",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }

                                Button(
                                    onClick = { onSpeakAloud(dictationState.liveText, null) },
                                    enabled = dictationState.liveText.isNotBlank() && !dictationState.isSpeakingTts,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    modifier = Modifier
                                        .height(54.dp)
                                        .testTag("speak_aloud_button")
                                ) {
                                    if (dictationState.isSpeakingTts) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.onTertiary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Speak dictated text aloud"
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Speak")
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(dictationState.liveText))
                                },
                                enabled = dictationState.liveText.isNotBlank()
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy")
                            }
                            TextButton(
                                onClick = onSaveDictation,
                                enabled = dictationState.liveText.isNotBlank(),
                                modifier = Modifier.testTag("save_dictation_button")
                            ) {
                                Icon(Icons.Filled.BookmarkAdd, contentDescription = "Save transcript", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save")
                            }
                            TextButton(
                                onClick = onClearText,
                                enabled = dictationState.liveText.isNotBlank()
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear text", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Saved Dictation History
            item {
                Text(
                    text = "Saved Dictations (${history.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (history.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No saved dictations yet. Speak a sentence above and tap Save or Speak to build your communication log.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = entry.adaptedText,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${entry.modelName} • ${entry.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row {
                                    IconButton(onClick = { onSpeakAloud(entry.adaptedText, entry.id) }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Speak entry aloud",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(entry.adaptedText)) }) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "Copy entry",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onDeleteDictation(entry.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Delete entry",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLiveDialog) {
        AlertDialog(
            onDismissRequest = {
                onStopLiveConversation()
                showLiveDialog = false
            },
            title = { Text("Live Voice Bridge (Gemini Live)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when {
                            liveState.isConnecting -> "Connecting to Gemini 3.8 Live audio stream..."
                            liveState.isConnected -> "Connected. Speak naturally — VoxBridge listens and replies in real time."
                            liveState.errorMessage != null -> liveState.errorMessage
                            else -> "Session ready."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (liveState.inputTranscript.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "You: ${liveState.inputTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    if (liveState.outputTranscript.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Bridge: ${liveState.outputTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onStopLiveConversation()
                        showLiveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("End Live Session")
                }
            }
        )
    }
}
