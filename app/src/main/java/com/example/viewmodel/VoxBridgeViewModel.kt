package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.VoiceAiService
import com.example.audio.AudioRecorderEngine
import com.example.audio.AudioStorageManager
import com.example.audio.StorageStats
import com.example.data.PhraseAssetRepository
import com.example.data.VoxBridgeRepository
import com.example.local.LocalPackStatus
import com.example.local.LocalSpeechModelManager
import com.example.local.LocalTrainedWeights
import com.example.local.OfflineAsrPackState
import com.example.model.CustomPhrase
import com.example.model.DictationEntry
import com.example.model.ModelTier
import com.example.model.PhraseItem
import com.example.model.SpeakingVoiceGender
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording
import com.example.model.UiState
import com.example.model.UserProfile
import com.example.model.VoiceArchitecture
import com.example.model.VoiceModelProfile
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DictationUiState(
    val isLiveDictating: Boolean = false,
    val isProcessingChunk: Boolean = false,
    val isSpeakingTts: Boolean = false,
    val liveText: String = "",
    val partialChunkText: String = "",
    val lastLatencyMs: Long = 0L,
    val selectedArchitecture: VoiceArchitecture = VoiceArchitecture.ADAPTIVE_FLASH,
    val errorMessage: String? = null,
    val statusNote: String? = null
)

data class LiveConversationState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    val errorMessage: String? = null
)

data class TrainingUiState(
    val isTraining: Boolean = false,
    val trainingMessage: String? = null,
    val errorMessage: String? = null
)

@OptIn(PublicPreviewAPI::class)
class VoxBridgeViewModel(
    private val repository: VoxBridgeRepository,
    private val phraseRepository: PhraseAssetRepository,
    private val storageManager: AudioStorageManager,
    val audioEngine: AudioRecorderEngine,
    private val aiService: VoiceAiService,
    private val localSpeechManager: LocalSpeechModelManager,
    private val currentUserId: String,
    private val initialDisplayName: String
) : ViewModel() {

    private val _selectedLanguage = MutableStateFlow(SpeechLanguage.ENGLISH)
    val selectedLanguage: StateFlow<SpeechLanguage> = _selectedLanguage.asStateFlow()

    private val _selectedVoiceGender = MutableStateFlow(SpeakingVoiceGender.FEMALE)
    val selectedVoiceGender: StateFlow<SpeakingVoiceGender> = _selectedVoiceGender.asStateFlow()

    private val _currentPhraseIndex = MutableStateFlow(0)
    val currentPhraseIndex: StateFlow<Int> = _currentPhraseIndex.asStateFlow()

    private val _recordingPhraseKey = MutableStateFlow<String?>(null)
    val recordingPhraseKey: StateFlow<String?> = _recordingPhraseKey.asStateFlow()

    private val _recordFeedback = MutableStateFlow<String?>(null)
    val recordFeedback: StateFlow<String?> = _recordFeedback.asStateFlow()

    private val _storageStats = MutableStateFlow(storageManager.getStorageStats(currentUserId))
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _dictationState = MutableStateFlow(DictationUiState())
    val dictationState: StateFlow<DictationUiState> = _dictationState.asStateFlow()

    private val _trainingState = MutableStateFlow(TrainingUiState())
    val trainingState: StateFlow<TrainingUiState> = _trainingState.asStateFlow()

    private val _liveState = MutableStateFlow(LiveConversationState())
    val liveState: StateFlow<LiveConversationState> = _liveState.asStateFlow()

    private val _localPackStatuses = MutableStateFlow<Map<SpeechLanguage, LocalPackStatus>>(emptyMap())
    val localPackStatuses: StateFlow<Map<SpeechLanguage, LocalPackStatus>> = _localPackStatuses.asStateFlow()

    private val _localAmplitude = MutableStateFlow(0f)
    val localAmplitude: StateFlow<Float> = _localAmplitude.asStateFlow()

    private var activeLiveSession: LiveSession? = null
    private var liveSessionJob: Job? = null
    private var dictationStreamJob: Job? = null
    private var usingSystemOfflineRecognizer: Boolean = false

    val userProfileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId)
            .map<UserProfile?, UiState<UserProfile?>> { profile ->
                if (profile != null) {
                    val prefLang = SpeechLanguage.fromCode(profile.preferredLanguage)
                    if (_selectedLanguage.value != prefLang) {
                        _selectedLanguage.value = prefLang
                    }
                    val prefGender = SpeakingVoiceGender.fromName(profile.preferredVoiceGender)
                    if (_selectedVoiceGender.value != prefGender) {
                        _selectedVoiceGender.value = prefGender
                    }
                }
                UiState.Success(profile)
            }
            .catch { error ->
                emitFirestoreError(error, "Failed to load profile")
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val customPhrasesState: StateFlow<UiState<List<CustomPhrase>>> =
        repository.observeCustomPhrases(currentUserId)
            .map<List<CustomPhrase>, UiState<List<CustomPhrase>>> { UiState.Success(it) }
            .catch { error ->
                emitFirestoreError(error, "Failed to load custom phrases")
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val recordingsState: StateFlow<UiState<List<SpeechRecording>>> =
        repository.observeRecordings(currentUserId)
            .map<List<SpeechRecording>, UiState<List<SpeechRecording>>> { UiState.Success(it) }
            .catch { error ->
                emitFirestoreError(error, "Failed to load recordings")
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val voiceModelsState: StateFlow<UiState<List<VoiceModelProfile>>> =
        repository.observeVoiceModels(currentUserId)
            .map<List<VoiceModelProfile>, UiState<List<VoiceModelProfile>>> { UiState.Success(it) }
            .catch { error ->
                emitFirestoreError(error, "Failed to load voice models")
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val dictationsState: StateFlow<UiState<List<DictationEntry>>> =
        repository.observeDictations(currentUserId)
            .map<List<DictationEntry>, UiState<List<DictationEntry>>> { UiState.Success(it) }
            .catch { error ->
                emitFirestoreError(error, "Failed to load dictation history")
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val mergedPhrases: StateFlow<List<PhraseItem>> = combine(
        selectedLanguage,
        customPhrasesState,
        recordingsState
    ) { language, customState, recState ->
        val customs = (customState as? UiState.Success)?.data.orEmpty()
        val recs = (recState as? UiState.Success)?.data.orEmpty()
        phraseRepository.buildMergedPhraseList(language, customs, recs)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = phraseRepository.getDefaultPhrases(SpeechLanguage.ENGLISH)
    )

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(initialDisplayName)
            refreshStorageStats()
            refreshLocalPackStatuses()
        }
    }

    fun refreshLocalPackStatuses() {
        viewModelScope.launch {
            val map = SpeechLanguage.entries.associateWith { lang ->
                localSpeechManager.inspectPackStatus(lang)
            }
            _localPackStatuses.value = map
        }
    }

    fun downloadLocalModelPack(language: SpeechLanguage) {
        viewModelScope.launch {
            val existing = _localPackStatuses.value[language] ?: LocalPackStatus(language)
            _localPackStatuses.value = _localPackStatuses.value + (
                language to existing.copy(
                    offlineAsrState = OfflineAsrPackState.DOWNLOADING,
                    downloadProgressPct = 10,
                    statusDetail = "Preparing ${language.label} local voice pack..."
                )
            )
            val result = localSpeechManager.downloadLocalModelPack(language) { pct, msg ->
                val current = _localPackStatuses.value[language] ?: LocalPackStatus(language)
                _localPackStatuses.value = _localPackStatuses.value + (
                    language to current.copy(
                        offlineAsrState = OfflineAsrPackState.DOWNLOADING,
                        downloadProgressPct = pct,
                        statusDetail = msg
                    )
                )
            }
            result.onSuccess { updated ->
                _localPackStatuses.value = _localPackStatuses.value + (language to updated)
                _trainingState.value = TrainingUiState(
                    trainingMessage = "Local ${language.label} speech pack downloaded. You can now train a Local Acoustic Model offline."
                )
            }.onFailure { err ->
                refreshLocalPackStatuses()
                _trainingState.value = TrainingUiState(
                    errorMessage = err.localizedMessage ?: "Failed to download local voice pack."
                )
            }
        }
    }

    fun deleteLocalModelPack(language: SpeechLanguage) {
        viewModelScope.launch(Dispatchers.IO) {
            localSpeechManager.deleteDownloadedPack(language)
            refreshLocalPackStatuses()
        }
    }

    fun setLanguage(language: SpeechLanguage) {
        _selectedLanguage.value = language
        _currentPhraseIndex.value = 0
        viewModelScope.launch {
            repository.updateUserPreferences(preferredLanguage = language)
        }
    }

    fun setVoiceGender(gender: SpeakingVoiceGender) {
        _selectedVoiceGender.value = gender
        _dictationState.value = _dictationState.value.copy(
            statusNote = "Speaking voice set to ${gender.displayName} (${gender.geminiVoiceName})."
        )
        viewModelScope.launch {
            repository.updateUserPreferences(preferredVoiceGender = gender)
        }
    }

    fun selectPhraseIndex(index: Int) {
        val size = mergedPhrases.value.size.coerceAtLeast(1)
        _currentPhraseIndex.value = index.coerceIn(0, size - 1)
    }

    fun nextPhrase() {
        val size = mergedPhrases.value.size.coerceAtLeast(1)
        _currentPhraseIndex.value = (_currentPhraseIndex.value + 1) % size
    }

    fun previousPhrase() {
        val size = mergedPhrases.value.size.coerceAtLeast(1)
        val prev = _currentPhraseIndex.value - 1
        _currentPhraseIndex.value = if (prev < 0) size - 1 else prev
    }

    fun startPhraseRecording(phrase: PhraseItem) {
        if (audioEngine.isRecording.value) return
        _recordFeedback.value = null
        _recordingPhraseKey.value = phrase.key
        audioEngine.startRecording(
            scope = viewModelScope,
            chunkDurationMs = 5000L,
            filterSilence = false,
            onChunkReady = null,
            onError = { msg ->
                _recordingPhraseKey.value = null
                _recordFeedback.value = msg
            }
        )
    }

    fun stopAndSavePhraseRecording(phrase: PhraseItem) {
        if (!audioEngine.isRecording.value) return
        val result = audioEngine.stopRecording()
        _recordingPhraseKey.value = null

        if (result.samples.size < AudioRecorderEngine.SAMPLE_RATE / 4) {
            _recordFeedback.value = "Recording was too short. Hold or speak for at least half a second."
            return
        }

        viewModelScope.launch {
            try {
                val recordingId = repository.newRecordingDocId()
                val wavFile = withContext(Dispatchers.IO) {
                    storageManager.saveWavRecording(
                        userId = currentUserId,
                        recordingId = recordingId,
                        pcmSamples = result.samples,
                        sampleRateHz = result.sampleRateHz
                    )
                }
                val saveRes = repository.saveRecording(
                    recordingId = recordingId,
                    phraseKey = phrase.key,
                    phraseText = phrase.text,
                    language = phrase.language,
                    localFilePath = wavFile.absolutePath,
                    durationMs = result.durationMs,
                    sampleRateHz = result.sampleRateHz.toLong(),
                    peakAmplitude = result.peakAmplitude.toLong()
                )
                refreshStorageStats()
                if (saveRes.isSuccess) {
                    _recordFeedback.value = "Saved ${result.durationMs}ms noise-filtered recording."
                } else {
                    storageManager.deleteRecordingFile(wavFile.absolutePath)
                    _recordFeedback.value = saveRes.exceptionOrNull()?.localizedMessage ?: "Failed to sync recording."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving phrase recording", e)
                _recordFeedback.value = e.localizedMessage ?: "Failed to save recording."
            }
        }
    }

    fun playRecording(recording: SpeechRecording) {
        audioEngine.playWavFile(viewModelScope, recording.localFilePath)
    }

    fun deleteRecording(recording: SpeechRecording) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storageManager.deleteRecordingFile(recording.localFilePath)
            }
            val res = repository.deleteRecording(recording.id)
            refreshStorageStats()
            if (res.isFailure) {
                _recordFeedback.value = res.exceptionOrNull()?.localizedMessage ?: "Delete failed."
            }
        }
    }

    fun addCustomPhrase(text: String, category: String) {
        viewModelScope.launch {
            val res = repository.addCustomPhrase(text, _selectedLanguage.value, category)
            if (res.isFailure) {
                _recordFeedback.value = res.exceptionOrNull()?.localizedMessage ?: "Could not add phrase."
            } else {
                _recordFeedback.value = "Added custom phrase."
                _currentPhraseIndex.value = 0
            }
        }
    }

    fun deleteCustomPhrase(customDocId: String) {
        viewModelScope.launch {
            repository.deleteCustomPhrase(customDocId)
        }
    }

    fun trainNewVoiceModel(
        modelName: String,
        architecture: VoiceArchitecture,
        language: SpeechLanguage
    ) {
        if (_trainingState.value.isTraining) return
        val allRecordings = (recordingsState.value as? UiState.Success)?.data.orEmpty()
        val langRecordings = allRecordings.filter { it.language == language.code }
        val chosenRecordings = if (langRecordings.isNotEmpty()) langRecordings else allRecordings

        if (chosenRecordings.isEmpty()) {
            _trainingState.value = TrainingUiState(
                errorMessage = "Please record at least 1 phrase in the Record tab before training a voice model."
            )
            return
        }

        val finalName = modelName.ifBlank { "${architecture.displayName} (${language.label})" }
        viewModelScope.launch {
            _trainingState.value = TrainingUiState(
                isTraining = true,
                trainingMessage = if (architecture.tier == ModelTier.LOCAL) {
                    "Extracting on-device spectral & DTW templates from ${chosenRecordings.size} samples..."
                } else {
                    "Training with ${architecture.displayName} on your voice samples..."
                }
            )
            try {
                val maxToRead = if (architecture.tier == ModelTier.LOCAL) 60 else 5
                val samplePairs = withContext(Dispatchers.IO) {
                    chosenRecordings.take(maxToRead).mapNotNull { rec ->
                        val bytes = storageManager.readAudioBytes(rec.localFilePath)
                        if (bytes != null) rec to bytes else null
                    }
                }
                if (samplePairs.isEmpty()) {
                    _trainingState.value = TrainingUiState(
                        errorMessage = "Audio files not found in local storage. Record new samples to train."
                    )
                    return@launch
                }

                val trained = if (architecture.tier == ModelTier.LOCAL) {
                    val (result, _) = localSpeechManager.trainLocalPersonalizedModel(
                        userId = currentUserId,
                        modelName = finalName,
                        language = language,
                        samples = samplePairs
                    )
                    result
                } else {
                    aiService.trainVoiceModel(
                        architecture = architecture,
                        language = language,
                        modelName = finalName,
                        samples = samplePairs,
                        totalRecordingCount = chosenRecordings.size
                    )
                }

                val createRes = repository.createVoiceModel(
                    name = finalName,
                    architecture = architecture,
                    language = language,
                    trainingSampleCount = chosenRecordings.size.toLong(),
                    phoneticProfileSummary = trained.summary,
                    adaptationPrompt = trained.adaptationPrompt,
                    articulationPatterns = trained.articulationPatterns,
                    confidenceScore = trained.confidenceScore
                )

                if (createRes.isSuccess) {
                    val newId = createRes.getOrNull().orEmpty()
                    if (newId.isNotBlank()) {
                        repository.updateUserPreferences(activeModelId = newId)
                    }
                    _dictationState.value = _dictationState.value.copy(
                        selectedArchitecture = architecture
                    )
                    _trainingState.value = TrainingUiState(
                        isTraining = false,
                        trainingMessage = "Trained \"$finalName\" with ${architecture.displayName} (${trained.confidenceScore}% fit)."
                    )
                } else {
                    _trainingState.value = TrainingUiState(
                        errorMessage = createRes.exceptionOrNull()?.localizedMessage ?: "Failed to save trained model."
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Model training timed out", e)
                _trainingState.value = TrainingUiState(errorMessage = "Training timed out after 60 seconds. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model training failed", e)
                _trainingState.value = TrainingUiState(
                    errorMessage = formatFriendlyAiError(e, architecture)
                )
            }
        }
    }

    fun clearTrainingStatus() {
        _trainingState.value = TrainingUiState()
    }

    fun selectActiveVoiceModel(profile: VoiceModelProfile) {
        val arch = VoiceArchitecture.fromName(profile.architecture)
        _dictationState.value = _dictationState.value.copy(
            selectedArchitecture = arch,
            errorMessage = null,
            statusNote = "Active model: ${profile.name} (${arch.displayName})"
        )
        viewModelScope.launch {
            repository.updateUserPreferences(activeModelId = profile.id)
        }
    }

    fun selectArchitecturePreset(architecture: VoiceArchitecture) {
        _dictationState.value = _dictationState.value.copy(
            selectedArchitecture = architecture,
            errorMessage = null,
            statusNote = "Switched engine to ${architecture.displayName} (${architecture.tier.shortLabel})"
        )
    }

    fun renameVoiceModel(modelId: String, newName: String) {
        viewModelScope.launch {
            repository.renameVoiceModel(modelId, newName)
        }
    }

    fun deleteVoiceModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteVoiceModel(modelId)
        }
    }

    fun getActiveVoiceProfile(): VoiceModelProfile? {
        val activeId = (userProfileState.value as? UiState.Success)?.data?.activeModelId.orEmpty()
        val models = (voiceModelsState.value as? UiState.Success)?.data.orEmpty()
        return models.firstOrNull { it.id == activeId } ?: models.firstOrNull()
    }

    fun startRealtimeDictation() {
        if (_dictationState.value.isLiveDictating) return
        val activeProfile = getActiveVoiceProfile()
        val selectedArch = _dictationState.value.selectedArchitecture
        val architecture = if (activeProfile != null && activeProfile.architecture == selectedArch.name) {
            VoiceArchitecture.fromName(activeProfile.architecture)
        } else {
            selectedArch
        }
        val language = _selectedLanguage.value

        if (architecture.tier == ModelTier.LOCAL) {
            startLocalOfflineDictation(language, activeProfile)
            return
        }

        usingSystemOfflineRecognizer = false
        _dictationState.value = _dictationState.value.copy(
            isLiveDictating = true,
            errorMessage = null,
            statusNote = "Listening with ${architecture.displayName} • Silence & background noise filtered"
        )

        audioEngine.startRecording(
            scope = viewModelScope,
            chunkDurationMs = 1800L,
            filterSilence = true,
            onChunkReady = { pcmChunk ->
                val wavBytes = AudioStorageManager.encodePcmToWav(pcmChunk, AudioRecorderEngine.SAMPLE_RATE)
                processCloudDictationChunk(architecture, language, activeProfile, wavBytes)
            },
            onSilenceFiltered = {
                _dictationState.value = _dictationState.value.copy(
                    statusNote = "Listening • Background noise / silence gated"
                )
            },
            onError = { msg ->
                _dictationState.value = _dictationState.value.copy(
                    isLiveDictating = false,
                    errorMessage = msg
                )
            }
        )
    }

    private fun startLocalOfflineDictation(
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?
    ) {
        val packStatus = _localPackStatuses.value[language]
        val localWeights = localSpeechManager.loadLatestLocalWeights(currentUserId, language)
        val trainedLexicon = buildList {
            localWeights?.templates?.forEach { add(it.phraseText) }
            mergedPhrases.value.forEach { add(it.text) }
        }.distinct()

        // Prefer Android's downloaded on-device SpeechRecognizer when installed; otherwise use our DTW acoustic engine
        if (packStatus?.offlineAsrState == OfflineAsrPackState.INSTALLED) {
            usingSystemOfflineRecognizer = true
            _dictationState.value = _dictationState.value.copy(
                isLiveDictating = true,
                errorMessage = null,
                statusNote = "Listening offline (On-Device ASR + Local Lexicon)..."
            )
            localSpeechManager.startOfflineSpeechRecognizer(
                language = language,
                trainedLexicon = trainedLexicon,
                onPartial = { partial ->
                    _dictationState.value = _dictationState.value.copy(partialChunkText = partial)
                },
                onFinal = { finalChunk ->
                    val existing = _dictationState.value.liveText.trim()
                    val combined = if (existing.isEmpty()) finalChunk else "$existing $finalChunk"
                    _dictationState.value = _dictationState.value.copy(
                        liveText = combined,
                        partialChunkText = "",
                        lastLatencyMs = 80L
                    )
                },
                onRms = { norm ->
                    _localAmplitude.value = norm
                },
                onError = { err ->
                    usingSystemOfflineRecognizer = false
                    localSpeechManager.stopOfflineSpeechRecognizer()
                    startLocalDtwFallbackCapture(language, activeProfile, localWeights, err)
                }
            )
        } else {
            startLocalDtwFallbackCapture(language, activeProfile, localWeights, null)
        }
    }

    private fun startLocalDtwFallbackCapture(
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?,
        preloadedWeights: LocalTrainedWeights?,
        notePrefix: String?
    ) {
        usingSystemOfflineRecognizer = false
        val weights = preloadedWeights ?: localSpeechManager.loadLatestLocalWeights(currentUserId, language)
        if (weights == null || weights.templates.isEmpty()) {
            _dictationState.value = _dictationState.value.copy(
                isLiveDictating = false,
                errorMessage = "No local model trained for ${language.label} yet. Download the local pack and train a Local Acoustic Model in the Models tab first."
            )
            return
        }

        val banner = if (notePrefix != null) {
            "$notePrefix • Switched to Local DTW (${weights.templates.size} templates)"
        } else {
            "Listening offline via Local Acoustic DTW (${weights.templates.size} templates) • Noise gated"
        }
        _dictationState.value = _dictationState.value.copy(
            isLiveDictating = true,
            errorMessage = null,
            statusNote = banner
        )

        audioEngine.startRecording(
            scope = viewModelScope,
            chunkDurationMs = 2000L,
            filterSilence = true,
            onChunkReady = { pcmChunk ->
                val startMs = System.currentTimeMillis()
                val match = localSpeechManager.matchLocalPcmChunk(pcmChunk, weights)
                val latency = System.currentTimeMillis() - startMs
                if (match != null) {
                    val existing = _dictationState.value.liveText.trim()
                    val combined = if (existing.isEmpty()) match.phraseText else "$existing ${match.phraseText}"
                    _dictationState.value = _dictationState.value.copy(
                        liveText = combined,
                        partialChunkText = "",
                        lastLatencyMs = latency,
                        statusNote = "Matched offline (${(match.confidence * 100).toInt()}% fit • ${activeProfile?.name ?: weights.modelName})"
                    )
                } else {
                    _dictationState.value = _dictationState.value.copy(
                        statusNote = "Listening offline • Speak a trained phrase clearly"
                    )
                }
            },
            onSilenceFiltered = {
                _dictationState.value = _dictationState.value.copy(
                    statusNote = "Listening offline • Silence & background noise gated"
                )
            },
            onError = { msg ->
                _dictationState.value = _dictationState.value.copy(
                    isLiveDictating = false,
                    errorMessage = msg
                )
            }
        )
    }

    private fun processCloudDictationChunk(
        architecture: VoiceArchitecture,
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?,
        wavBytes: ByteArray
    ) {
        dictationStreamJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            _dictationState.value = _dictationState.value.copy(isProcessingChunk = true)
            try {
                var latestChunk = ""
                aiService.dictateAudioStream(
                    architecture = architecture,
                    language = language,
                    activeProfile = activeProfile,
                    wavBytes = wavBytes
                ).collect { partial ->
                    latestChunk = partial
                    _dictationState.value = _dictationState.value.copy(
                        partialChunkText = partial
                    )
                }
                val latency = System.currentTimeMillis() - startMs
                if (latestChunk.isNotBlank()) {
                    val existing = _dictationState.value.liveText.trim()
                    val combined = if (existing.isEmpty()) latestChunk else "$existing $latestChunk"
                    _dictationState.value = _dictationState.value.copy(
                        liveText = combined,
                        partialChunkText = "",
                        isProcessingChunk = false,
                        lastLatencyMs = latency
                    )
                } else {
                    _dictationState.value = _dictationState.value.copy(
                        partialChunkText = "",
                        isProcessingChunk = false,
                        lastLatencyMs = latency
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _dictationState.value = _dictationState.value.copy(
                    isProcessingChunk = false,
                    errorMessage = "Dictation chunk timed out."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Dictation streaming failed", e)
                _dictationState.value = _dictationState.value.copy(
                    isProcessingChunk = false,
                    errorMessage = formatFriendlyAiError(e, architecture)
                )
            }
        }
    }

    fun stopRealtimeDictation() {
        if (!_dictationState.value.isLiveDictating) return
        if (usingSystemOfflineRecognizer) {
            localSpeechManager.stopOfflineSpeechRecognizer()
            usingSystemOfflineRecognizer = false
            _localAmplitude.value = 0f
        } else {
            audioEngine.stopRecording()
        }
        _dictationState.value = _dictationState.value.copy(
            isLiveDictating = false,
            partialChunkText = "",
            isProcessingChunk = false,
            statusNote = "Dictation paused."
        )
    }

    fun updateDictationText(text: String) {
        _dictationState.value = _dictationState.value.copy(liveText = text)
    }

    fun clearDictationText() {
        _dictationState.value = _dictationState.value.copy(
            liveText = "",
            partialChunkText = "",
            errorMessage = null
        )
    }

    fun saveCurrentDictation(spokenAloud: Boolean = false) {
        val text = _dictationState.value.liveText.trim()
        if (text.isEmpty()) return
        val profile = getActiveVoiceProfile()
        val arch = _dictationState.value.selectedArchitecture
        viewModelScope.launch {
            val res = repository.saveDictation(
                modelId = profile?.id ?: arch.name,
                modelName = profile?.name ?: arch.displayName,
                language = _selectedLanguage.value,
                rawTranscript = text,
                adaptedText = text,
                spokenAloud = spokenAloud,
                latencyMs = _dictationState.value.lastLatencyMs
            )
            if (res.isSuccess) {
                _dictationState.value = _dictationState.value.copy(
                    statusNote = "Saved dictation to cloud history."
                )
            } else {
                _dictationState.value = _dictationState.value.copy(
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to save dictation."
                )
            }
        }
    }

    fun speakTextAloud(text: String, dictationId: String? = null) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        val voiceGender = _selectedVoiceGender.value
        viewModelScope.launch {
            _dictationState.value = _dictationState.value.copy(isSpeakingTts = true, errorMessage = null)
            val result = aiService.synthesizeSpeech(cleanText, _selectedLanguage.value, voiceGender)
            result.onSuccess { audio ->
                audioEngine.playPcmBytes(
                    scope = viewModelScope,
                    pcmBytes = audio.audioBytes,
                    sampleRateHz = audio.sampleRateHz,
                    onFinished = {
                        _dictationState.value = _dictationState.value.copy(isSpeakingTts = false)
                    }
                )
            }.onFailure {
                aiService.speakWithSystemTtsFallback(cleanText, _selectedLanguage.value, voiceGender)
                _dictationState.value = _dictationState.value.copy(
                    isSpeakingTts = false,
                    statusNote = "Spoken via ${voiceGender.displayName.lowercase()} on-device voice."
                )
            }
            if (dictationId != null) {
                repository.markDictationSpoken(dictationId)
            }
        }
    }

    fun deleteDictation(dictationId: String) {
        viewModelScope.launch {
            repository.deleteDictation(dictationId)
        }
    }

    fun startLiveConversation() {
        if (_liveState.value.isConnected || _liveState.value.isConnecting) return
        val profile = getActiveVoiceProfile()
        val language = _selectedLanguage.value
        val voiceGender = _selectedVoiceGender.value

        liveSessionJob = viewModelScope.launch {
            _liveState.value = LiveConversationState(isConnecting = true)
            try {
                val session = aiService.connectLiveSession(language, profile, voiceGender)
                activeLiveSession = session
                _liveState.value = LiveConversationState(isConnected = true)
                session.startAudioConversation(
                    transcriptHandler = { inputTx, outputTx ->
                        val inText = inputTx?.text.orEmpty()
                        val outText = outputTx?.text.orEmpty()
                        _liveState.value = _liveState.value.copy(
                            inputTranscript = if (inText.isNotBlank()) inText else _liveState.value.inputTranscript,
                            outputTranscript = if (outText.isNotBlank()) outText else _liveState.value.outputTranscript
                        )
                    },
                    goAwayHandler = {
                        _liveState.value = _liveState.value.copy(
                            isConnected = false,
                            errorMessage = "Live session ended by server."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live API error", e)
                _liveState.value = LiveConversationState(
                    isConnected = false,
                    isConnecting = false,
                    errorMessage = e.localizedMessage ?: "Live conversation unavailable."
                )
            }
        }
    }

    fun stopLiveConversation() {
        val session = activeLiveSession
        activeLiveSession = null
        liveSessionJob?.cancel()
        liveSessionJob = null
        viewModelScope.launch {
            try {
                session?.stopAudioConversation()
                session?.close()
            } catch (_: Exception) {
            }
            _liveState.value = LiveConversationState()
        }
    }

    private fun formatFriendlyAiError(e: Exception, architecture: VoiceArchitecture): String {
        val raw = e.localizedMessage.orEmpty()
        return if (raw.contains("limit: 0", ignoreCase = true) ||
            raw.contains("free_tier", ignoreCase = true) ||
            (architecture.tier == ModelTier.PAID && raw.contains("quota", ignoreCase = true))
        ) {
            "${architecture.displayName} (${architecture.geminiModelId}) requires a paid Blaze billing plan. " +
                "Switch to a Free Tier model (Gemini 3.8 Flash or Gemini 3.5 Flash-Lite) or train a Local Acoustic Model offline."
        } else {
            raw.ifBlank { "AI model request failed." }
        }
    }

    private fun refreshStorageStats() {
        _storageStats.value = storageManager.getStorageStats(currentUserId)
    }

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<UiState<T>>.emitFirestoreError(
        error: Throwable,
        fallback: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, fallback, rootError)
        emit(UiState.Error(rootError.localizedMessage ?: fallback))
    }

    override fun onCleared() {
        super.onCleared()
        localSpeechManager.stopOfflineSpeechRecognizer()
        audioEngine.release()
        aiService.release()
        liveSessionJob?.cancel()
    }

    companion object {
        private const val TAG = "VoxBridgeVM"
        private const val STOP_TIMEOUT_MS = 5000L
    }
}
