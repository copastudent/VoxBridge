package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.PhraseItem
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording
import com.example.ui.components.LiveAudioWaveform

private enum class PhraseFilter(val label: String) {
    ALL("All"),
    UNRECORDED("Unrecorded"),
    RECORDED("Recorded"),
    CUSTOM("Custom")
}

@Composable
fun RecordPhrasesScreen(
    phrases: List<PhraseItem>,
    recordings: List<SpeechRecording>,
    selectedLanguage: SpeechLanguage,
    currentPhraseIndex: Int,
    isRecording: Boolean,
    amplitudeLevel: Float,
    elapsedMs: Long,
    feedbackMessage: String?,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onLanguageSelected: (SpeechLanguage) -> Unit,
    onSelectPhraseIndex: (Int) -> Unit,
    onNextPhrase: () -> Unit,
    onPreviousPhrase: () -> Unit,
    onStartRecording: (PhraseItem) -> Unit,
    onStopRecording: (PhraseItem) -> Unit,
    onPlayRecording: (SpeechRecording) -> Unit,
    onDeleteRecording: (SpeechRecording) -> Unit,
    onHearPhraseGuide: (String) -> Unit,
    onAddCustomPhrase: (text: String, category: String) -> Unit,
    onDeleteCustomPhrase: (String) -> Unit
) {
    var activeFilter by remember { mutableStateOf(PhraseFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddPhraseDialog by remember { mutableStateOf(false) }

    val safeIndex = currentPhraseIndex.coerceIn(0, (phrases.size - 1).coerceAtLeast(0))
    val activePhrase = phrases.getOrNull(safeIndex)
    val recordingsForActive = remember(recordings, activePhrase?.key) {
        if (activePhrase == null) emptyList()
        else recordings.filter { it.phraseKey == activePhrase.key }
    }
    val completedCount = remember(phrases) { phrases.count { it.recordedCount > 0 } }
    val progress = if (phrases.isNotEmpty()) completedCount.toFloat() / phrases.size.toFloat() else 0f

    val filteredPhrases = remember(phrases, activeFilter, searchQuery) {
        phrases.mapIndexed { idx, item -> idx to item }.filter { (_, item) ->
            val matchesFilter = when (activeFilter) {
                PhraseFilter.ALL -> true
                PhraseFilter.UNRECORDED -> item.recordedCount == 0
                PhraseFilter.RECORDED -> item.recordedCount > 0
                PhraseFilter.CUSTOM -> item.customDocId != null
            }
            val matchesQuery = searchQuery.isBlank() ||
                item.text.contains(searchQuery.trim(), ignoreCase = true) ||
                item.category.contains(searchQuery.trim(), ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header & Language Switcher
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Voice Recording Studio",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "$completedCount of ${phrases.size} phrases recorded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeechLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = selectedLanguage == lang,
                                onClick = { onLanguageSelected(lang) },
                                label = { Text(lang.nativeLabel) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("lang_chip_${lang.code}")
                            )
                        }
                    }
                }
            }

            // Progress bar + Add Custom Phrase
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showAddPhraseDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_custom_phrase_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add custom phrase",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Custom")
                    }
                }
            }

            // Active Phrase Studio Recorder Card
            if (activePhrase != null) {
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
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = "${activePhrase.category} • #${safeIndex + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onHearPhraseGuide(activePhrase.text) },
                                        modifier = Modifier.testTag("hear_phrase_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Hear phrase pronunciation guide",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (activePhrase.customDocId != null) {
                                        IconButton(
                                            onClick = { onDeleteCustomPhrase(activePhrase.customDocId) },
                                            modifier = Modifier.testTag("delete_custom_phrase_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete custom phrase",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = activePhrase.text,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            LiveAudioWaveform(
                                amplitude = amplitudeLevel,
                                isActive = isRecording,
                                height = 54.dp
                            )

                            if (!hasMicPermission) {
                                Button(
                                    onClick = onRequestMicPermission,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("grant_mic_permission_button")
                                ) {
                                    Text("Allow Microphone Access to Record")
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = onPreviousPhrase,
                                        enabled = !isRecording,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("prev_phrase_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Previous phrase"
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            if (isRecording) {
                                                onStopRecording(activePhrase)
                                            } else {
                                                onStartRecording(activePhrase)
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRecording) {
                                                MaterialTheme.colorScheme.secondary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        ),
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                        modifier = Modifier
                                            .height(58.dp)
                                            .testTag("record_phrase_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                            contentDescription = if (isRecording) "Stop recording" else "Start recording",
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isRecording) {
                                                "Stop (${elapsedMs / 1000}.${(elapsedMs % 1000) / 100}s)"
                                            } else {
                                                "Record Voice"
                                            },
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onNextPhrase,
                                        enabled = !isRecording,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("next_phrase_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Next phrase"
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = feedbackMessage != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = feedbackMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            if (recordingsForActive.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Recorded Takes (${recordingsForActive.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    recordingsForActive.forEach { rec ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(onClick = { onPlayRecording(rec) }) {
                                                        Icon(
                                                            imageVector = Icons.Filled.PlayArrow,
                                                            contentDescription = "Play recorded audio",
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    Column {
                                                        Text(
                                                            text = "${rec.durationMs} ms • 16kHz WAV",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "Peak amp: ${rec.peakAmplitude}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = { onDeleteRecording(rec) }) {
                                                    Icon(
                                                        imageVector = Icons.Filled.DeleteOutline,
                                                        contentDescription = "Delete recording",
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
            }

            // Phrase Search & Filter Bar
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search 100+ training phrases...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phrase_search_input")
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PhraseFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = { Text(filter.label) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            )
                        }
                    }
                }
            }

            // Phrase List
            itemsIndexed(
                items = filteredPhrases,
                key = { _, pair -> pair.second.key }
            ) { _, (originalIndex, item) ->
                val isSelected = originalIndex == safeIndex
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    tonalElevation = if (isSelected) 3.dp else 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectPhraseIndex(originalIndex) }
                        .testTag("phrase_row_${originalIndex}")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (item.recordedCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Recorded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${item.recordedCount}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${originalIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddPhraseDialog) {
        var customText by remember { mutableStateOf("") }
        var customCategory by remember { mutableStateOf("Daily Speech") }
        AlertDialog(
            onDismissRequest = { showAddPhraseDialog = false },
            title = { Text("Add Custom Training Phrase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { if (it.length <= 140) customText = it },
                        label = { Text("Phrase (${selectedLanguage.label})") },
                        supportingText = { Text("${customText.length}/140 characters") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_phrase_text_input")
                    )
                    OutlinedTextField(
                        value = customCategory,
                        onValueChange = { if (it.length <= 40) customCategory = it },
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customText.isNotBlank()) {
                            onAddCustomPhrase(customText, customCategory)
                            showAddPhraseDialog = false
                        }
                    },
                    enabled = customText.isNotBlank(),
                    modifier = Modifier.testTag("save_custom_phrase_button")
                ) {
                    Text("Save Phrase")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPhraseDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
