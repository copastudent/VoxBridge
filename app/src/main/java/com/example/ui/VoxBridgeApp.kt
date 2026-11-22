package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.ai.VoiceAiService
import com.example.audio.AudioRecorderEngine
import com.example.audio.AudioStorageManager
import com.example.auth.signOutUser
import com.example.data.PhraseAssetRepository
import com.example.data.VoxBridgeRepository
import com.example.local.LocalSpeechModelManager
import com.example.model.UiState
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DictateScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RecordPhrasesScreen
import com.example.ui.screens.VoiceModelsScreen
import com.example.viewmodel.VoxBridgeViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private enum class VoxTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    RECORD("Record", Icons.Filled.Mic, Icons.Outlined.Mic, "nav_tab_record"),
    DICTATE("Dictate", Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq, "nav_tab_dictate"),
    MODELS("Models", Icons.Filled.Psychology, Icons.Outlined.Psychology, "nav_tab_models"),
    PROFILE("Profile", Icons.Filled.Person, Icons.Outlined.Person, "nav_tab_profile")
}

@Composable
fun VoxBridgeApp() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            }
        )
    } else {
        val uid = user.uid
        key(uid) {
            AuthenticatedStudioShell(
                currentUserId = uid,
                displayName = user.displayName ?: "VoxBridge Speaker",
                email = user.email.orEmpty()
            )
        }
    }
}

@Composable
private fun AuthenticatedStudioShell(
    currentUserId: String,
    displayName: String,
    email: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val viewModel: VoxBridgeViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                VoxBridgeViewModel(
                    repository = VoxBridgeRepository(db),
                    phraseRepository = PhraseAssetRepository(app),
                    storageManager = AudioStorageManager(app),
                    audioEngine = AudioRecorderEngine(),
                    aiService = VoiceAiService(app),
                    localSpeechManager = LocalSpeechModelManager(app),
                    currentUserId = currentUserId,
                    initialDisplayName = displayName
                )
            }
        }
    )

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    var activeTab by remember { mutableStateOf(VoxTab.RECORD) }
    BackHandler(enabled = activeTab != VoxTab.RECORD) {
        activeTab = VoxTab.RECORD
    }

    val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val selectedVoiceGender by viewModel.selectedVoiceGender.collectAsStateWithLifecycle()
    val currentPhraseIndex by viewModel.currentPhraseIndex.collectAsStateWithLifecycle()
    val phrases by viewModel.mergedPhrases.collectAsStateWithLifecycle()
    val recordingsState by viewModel.recordingsState.collectAsStateWithLifecycle()
    val voiceModelsState by viewModel.voiceModelsState.collectAsStateWithLifecycle()
    val dictationsState by viewModel.dictationsState.collectAsStateWithLifecycle()
    val userProfileState by viewModel.userProfileState.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val recordFeedback by viewModel.recordFeedback.collectAsStateWithLifecycle()
    val dictationState by viewModel.dictationState.collectAsStateWithLifecycle()
    val trainingState by viewModel.trainingState.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val localPackStatuses by viewModel.localPackStatuses.collectAsStateWithLifecycle()

    val isRecording by viewModel.audioEngine.isRecording.collectAsStateWithLifecycle()
    val engineAmplitude by viewModel.audioEngine.amplitudeLevel.collectAsStateWithLifecycle()
    val localAmplitude by viewModel.localAmplitude.collectAsStateWithLifecycle()
    val amplitudeLevel = maxOf(engineAmplitude, localAmplitude)
    val elapsedMs by viewModel.audioEngine.elapsedMs.collectAsStateWithLifecycle()

    val recordings = (recordingsState as? UiState.Success)?.data.orEmpty()
    val models = (voiceModelsState as? UiState.Success)?.data.orEmpty()
    val dictations = (dictationsState as? UiState.Success)?.data.orEmpty()
    val activeModelId = (userProfileState as? UiState.Success)?.data?.activeModelId.orEmpty()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        VoxTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        VoxTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        VoxTab.RECORD -> RecordPhrasesScreen(
                            phrases = phrases,
                            recordings = recordings,
                            selectedLanguage = selectedLanguage,
                            currentPhraseIndex = currentPhraseIndex,
                            isRecording = isRecording && !dictationState.isLiveDictating,
                            amplitudeLevel = amplitudeLevel,
                            elapsedMs = elapsedMs,
                            feedbackMessage = recordFeedback,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onLanguageSelected = viewModel::setLanguage,
                            onSelectPhraseIndex = viewModel::selectPhraseIndex,
                            onNextPhrase = viewModel::nextPhrase,
                            onPreviousPhrase = viewModel::previousPhrase,
                            onStartRecording = viewModel::startPhraseRecording,
                            onStopRecording = viewModel::stopAndSavePhraseRecording,
                            onPlayRecording = viewModel::playRecording,
                            onDeleteRecording = viewModel::deleteRecording,
                            onHearPhraseGuide = { text -> viewModel.speakTextAloud(text) },
                            onAddCustomPhrase = viewModel::addCustomPhrase,
                            onDeleteCustomPhrase = viewModel::deleteCustomPhrase
                        )

                        VoxTab.DICTATE -> DictateScreen(
                            dictationState = dictationState,
                            liveState = liveState,
                            trainedModels = models,
                            activeModelId = activeModelId,
                            selectedLanguage = selectedLanguage,
                            selectedVoiceGender = selectedVoiceGender,
                            amplitudeLevel = amplitudeLevel,
                            history = dictations,
                            hasMicPermission = hasMicPermission,
                            onRequestMicPermission = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onSelectTrainedModel = viewModel::selectActiveVoiceModel,
                            onSelectArchitecturePreset = viewModel::selectArchitecturePreset,
                            onSelectVoiceGender = viewModel::setVoiceGender,
                            onStartDictation = viewModel::startRealtimeDictation,
                            onStopDictation = viewModel::stopRealtimeDictation,
                            onTextChanged = viewModel::updateDictationText,
                            onClearText = viewModel::clearDictationText,
                            onSaveDictation = { viewModel.saveCurrentDictation(spokenAloud = false) },
                            onSpeakAloud = viewModel::speakTextAloud,
                            onDeleteDictation = viewModel::deleteDictation,
                            onStartLiveConversation = viewModel::startLiveConversation,
                            onStopLiveConversation = viewModel::stopLiveConversation
                        )

                        VoxTab.MODELS -> VoiceModelsScreen(
                            models = models,
                            recordings = recordings,
                            activeModelId = activeModelId,
                            selectedLanguage = selectedLanguage,
                            trainingState = trainingState,
                            localPackStatuses = localPackStatuses,
                            onDownloadLocalPack = viewModel::downloadLocalModelPack,
                            onDeleteLocalPack = viewModel::deleteLocalModelPack,
                            onTrainModel = viewModel::trainNewVoiceModel,
                            onSelectActiveModel = viewModel::selectActiveVoiceModel,
                            onRenameModel = viewModel::renameVoiceModel,
                            onDeleteModel = viewModel::deleteVoiceModel,
                            onDismissTrainingBanner = viewModel::clearTrainingStatus
                        )

                        VoxTab.PROFILE -> ProfileScreen(
                            displayName = displayName,
                            email = email,
                            storageStats = storageStats,
                            recordings = recordings,
                            modelCount = models.size,
                            onPlayRecording = viewModel::playRecording,
                            onDeleteRecording = viewModel::deleteRecording,
                            onSignOut = {
                                signOutUser(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
