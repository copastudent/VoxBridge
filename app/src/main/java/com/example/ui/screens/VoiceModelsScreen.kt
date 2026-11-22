package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.local.LocalPackStatus
import com.example.local.OfflineAsrPackState
import com.example.model.ModelTier
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording
import com.example.model.VoiceArchitecture
import com.example.model.VoiceModelProfile
import com.example.viewmodel.TrainingUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceModelsScreen(
    models: List<VoiceModelProfile>,
    recordings: List<SpeechRecording>,
    activeModelId: String,
    selectedLanguage: SpeechLanguage,
    trainingState: TrainingUiState,
    localPackStatuses: Map<SpeechLanguage, LocalPackStatus>,
    onDownloadLocalPack: (SpeechLanguage) -> Unit,
    onDeleteLocalPack: (SpeechLanguage) -> Unit,
    onTrainModel: (name: String, arch: VoiceArchitecture, lang: SpeechLanguage) -> Unit,
    onSelectActiveModel: (VoiceModelProfile) -> Unit,
    onRenameModel: (modelId: String, newName: String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDismissTrainingBanner: () -> Unit
) {
    var showTrainDialog by remember { mutableStateOf(false) }
    var modelToRename by remember { mutableStateOf<VoiceModelProfile?>(null) }

    val currentPackStatus = localPackStatuses[selectedLanguage] ?: LocalPackStatus(selectedLanguage)

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
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Image(
                            painter = painterResource(id = R.drawable.img_voice_model_card),
                            contentDescription = "Neural voice model calibration artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Personalized Voice Models",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Train acoustic-phonetic models from your ${recordings.size} recorded speech samples using Free Tier Gemini cloud models, Paid Blaze models, or an Offline Local Acoustic Model.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showTrainDialog = true },
                                enabled = !trainingState.isTraining,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("open_train_model_button")
                            ) {
                                if (trainingState.isTraining) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Training Voice Model...")
                                } else {
                                    Icon(Icons.Filled.Psychology, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Start New Training Session")
                                }
                            }
                        }
                    }
                }
            }

            // Downloadable Local On-Device Model Pack Card
            item {
                val isDownloading = currentPackStatus.offlineAsrState == OfflineAsrPackState.DOWNLOADING
                val packReady = currentPackStatus.localLexiconReady ||
                    currentPackStatus.offlineAsrState == OfflineAsrPackState.INSTALLED

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local_model_pack_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (packReady) Icons.Filled.OfflinePin else Icons.Filled.DownloadForOffline,
                                    contentDescription = "Local offline speech model status",
                                    tint = if (packReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column {
                                    Text(
                                        text = "Offline Local Model Pack (${selectedLanguage.label})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = currentPackStatus.statusDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (currentPackStatus.localLexiconReady) {
                                IconButton(
                                    onClick = { onDeleteLocalPack(selectedLanguage) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("delete_local_pack_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete downloaded local voice pack",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (currentPackStatus.localLexiconReady || currentPackStatus.offlineAsrState == OfflineAsrPackState.INSTALLED) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (currentPackStatus.localLexiconReady) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "Lexicon: ${currentPackStatus.localLexiconSizeKb} KB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = when (currentPackStatus.offlineAsrState) {
                                            OfflineAsrPackState.INSTALLED -> "System Offline ASR: Installed"
                                            OfflineAsrPackState.DOWNLOADABLE -> "System Offline ASR: Downloadable"
                                            OfflineAsrPackState.DOWNLOADING -> "Downloading ASR Pack..."
                                            OfflineAsrPackState.CHECKING -> "Checking Offline Support..."
                                            OfflineAsrPackState.UNAVAILABLE -> "Local DTW Engine Ready"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = { (currentPackStatus.downloadProgressPct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }

                        OutlinedButton(
                            onClick = { onDownloadLocalPack(selectedLanguage) },
                            enabled = !isDownloading,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("download_local_pack_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DownloadForOffline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isDownloading -> "Downloading (${currentPackStatus.downloadProgressPct}%)..."
                                    packReady -> "Update / Verify Local ${selectedLanguage.label} Pack"
                                    else -> "Download Local ${selectedLanguage.label} Model Pack"
                                }
                            )
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(visible = trainingState.trainingMessage != null || trainingState.errorMessage != null) {
                    val isError = trainingState.errorMessage != null
                    Surface(
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = trainingState.errorMessage ?: trainingState.trainingMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onDismissTrainingBanner,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Your Trained Models (${models.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (models.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "No trained voice models yet",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Record a few phrases in the Record tab, then start a Free, Paid, or Local offline training session above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(models, key = { it.id }) { profile ->
                    val isActive = profile.id == activeModelId || (activeModelId.isBlank() && models.firstOrNull()?.id == profile.id)
                    val arch = VoiceArchitecture.fromName(profile.architecture)
                    val lang = SpeechLanguage.fromCode(profile.language)

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 3.dp else 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_model_card_${profile.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isActive) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Active voice model",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = when (arch.tier) {
                                                ModelTier.FREE -> MaterialTheme.colorScheme.primaryContainer
                                                ModelTier.PAID -> MaterialTheme.colorScheme.tertiaryContainer
                                                ModelTier.LOCAL -> MaterialTheme.colorScheme.secondaryContainer
                                            }
                                        ) {
                                            Text(
                                                text = arch.tier.shortLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = when (arch.tier) {
                                                    ModelTier.FREE -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    ModelTier.PAID -> MaterialTheme.colorScheme.onTertiaryContainer
                                                    ModelTier.LOCAL -> MaterialTheme.colorScheme.onSecondaryContainer
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = "${arch.displayName} (${profile.geminiModelId}) • ${lang.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row {
                                    IconButton(
                                        onClick = { modelToRename = profile },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Rename voice model"
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteModel(profile.id) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("delete_model_${profile.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Delete voice model",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Trained on ${profile.trainingSampleCount} samples",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${profile.confidenceScore}% fit",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (profile.confidenceScore.toFloat() / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }

                            Text(
                                text = profile.phoneticProfileSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (profile.articulationPatterns.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    profile.articulationPatterns.take(5).forEach { pattern ->
                                        AssistChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    text = pattern,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            if (!isActive) {
                                OutlinedButton(
                                    onClick = { onSelectActiveModel(profile) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .testTag("activate_model_${profile.id}")
                                ) {
                                    Text("Set as Active Dictation Model")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTrainDialog) {
        var nameInput by remember { mutableStateOf("My ${selectedLanguage.label} Voice") }
        var trainLang by remember { mutableStateOf(selectedLanguage) }
        var selectedTier by remember { mutableStateOf(ModelTier.FREE) }
        var selectedArch by remember { mutableStateOf(VoiceArchitecture.ADAPTIVE_FLASH) }
        val tierArchitectures = VoiceArchitecture.byTier(selectedTier)
        val langPack = localPackStatuses[trainLang]

        AlertDialog(
            onDismissRequest = { showTrainDialog = false },
            title = { Text("New Voice Training Session") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { if (it.length <= 60) nameInput = it },
                        label = { Text("Model Profile Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("train_model_name_input")
                    )

                    Text("Target Language", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeechLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = trainLang == lang,
                                onClick = { trainLang = lang },
                                label = { Text(lang.nativeLabel) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("train_lang_${lang.code}")
                            )
                        }
                    }

                    Text("Model Tier", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModelTier.entries.forEach { tier ->
                            FilterChip(
                                selected = selectedTier == tier,
                                onClick = {
                                    selectedTier = tier
                                    selectedArch = VoiceArchitecture.byTier(tier).first()
                                },
                                label = { Text(tier.shortLabel) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("train_tier_${tier.name}")
                            )
                        }
                    }

                    if (selectedTier == ModelTier.LOCAL && langPack?.localLexiconReady != true) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Tip: Download the Local ${trainLang.label} Model Pack on the Models screen for hybrid on-device ASR + personalized DTW templates.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Text("Select Model Architecture", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tierArchitectures.forEach { arch ->
                            FilterChip(
                                selected = selectedArch == arch,
                                onClick = { selectedArch = arch },
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text("${arch.displayName} (${arch.latencyProfile})", fontWeight = FontWeight.SemiBold)
                                        Text(arch.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("train_arch_${arch.name}")
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTrainModel(nameInput, selectedArch, trainLang)
                        showTrainDialog = false
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_train_model_button")
                ) {
                    Text("Train Now")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTrainDialog = false },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val renaming = modelToRename
    if (renaming != null) {
        var newName by remember(renaming.id) { mutableStateOf(renaming.name) }
        AlertDialog(
            onDismissRequest = { modelToRename = null },
            title = { Text("Rename Voice Model") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 60) newName = it },
                    label = { Text("Model Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRenameModel(renaming.id, newName)
                            modelToRename = null
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { modelToRename = null },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
