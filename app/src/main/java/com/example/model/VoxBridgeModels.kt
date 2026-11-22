package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class SpeechLanguage(val code: String, val label: String, val nativeLabel: String, val assetPath: String) {
    ENGLISH("en", "English", "English", "phrases.txt"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", "phrases_gu.txt");

    companion object {
        fun fromCode(code: String?): SpeechLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

enum class VoiceArchitecture(
    val displayName: String,
    val geminiModelId: String,
    val latencyProfile: String,
    val description: String
) {
    DEEP_PHONETIC(
        displayName = "Deep Phonetic Resonance",
        geminiModelId = "gemini-4-carbon",
        latencyProfile = "High Thinking",
        description = "Uses deep reasoning to decode complex dysarthric or non-standard articulation patterns."
    ),
    ADAPTIVE_GENERAL(
        displayName = "Balanced Adaptive Core",
        geminiModelId = "gemini-4-hydrogen",
        latencyProfile = "Balanced",
        description = "Real-time streaming recognition tuned to your personalized phoneme map."
    ),
    ULTRA_FAST(
        displayName = "Rapid Live Dictation",
        geminiModelId = "gemini-3.1-flash-lite",
        latencyProfile = "Ultra-Low Latency",
        description = "Fastest turnaround for continuous real-time conversational typing."
    ),
    PRECISION_TRANSCRIBE(
        displayName = "Acoustic Speech Transcriber",
        geminiModelId = "gemini-3.5-transcribe",
        latencyProfile = "Speech-Focused",
        description = "Dedicated speech-to-text acoustic model conditioned on your speaker profile."
    );

    companion object {
        fun fromName(raw: String?): VoiceArchitecture =
            entries.firstOrNull { it.name == raw } ?: ADAPTIVE_GENERAL
    }
}

enum class ModelStatus {
    TRAINING,
    READY,
    ARCHIVED;

    companion object {
        fun fromName(raw: String?): ModelStatus =
            entries.firstOrNull { it.name == raw } ?: READY
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class PhraseItem(
    val key: String,
    val text: String,
    val language: SpeechLanguage,
    val category: String,
    val customDocId: String? = null,
    val recordedCount: Int = 0
)

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val preferredLanguage: String = SpeechLanguage.ENGLISH.code,
    val activeModelId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CustomPhrase(
    @DocumentId val id: String = "",
    val userId: String = "",
    val text: String = "",
    val language: String = SpeechLanguage.ENGLISH.code,
    val category: String = "Custom",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class SpeechRecording(
    @DocumentId val id: String = "",
    val userId: String = "",
    val phraseKey: String = "",
    val phraseText: String = "",
    val language: String = SpeechLanguage.ENGLISH.code,
    val localFilePath: String = "",
    val durationMs: Long = 0L,
    val sampleRateHz: Long = 16000L,
    val peakAmplitude: Long = 0L,
    val phoneticNotes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class VoiceModelProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val architecture: String = VoiceArchitecture.ADAPTIVE_GENERAL.name,
    val geminiModelId: String = VoiceArchitecture.ADAPTIVE_GENERAL.geminiModelId,
    val language: String = SpeechLanguage.ENGLISH.code,
    val trainingSampleCount: Long = 0L,
    val phoneticProfileSummary: String = "",
    val adaptationPrompt: String = "",
    val articulationPatterns: List<String> = emptyList(),
    val confidenceScore: Long = 0L,
    val status: String = ModelStatus.READY.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DictationEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val modelId: String = "",
    val modelName: String = "",
    val language: String = SpeechLanguage.ENGLISH.code,
    val rawTranscript: String = "",
    val adaptedText: String = "",
    val spokenAloud: Boolean = false,
    val latencyMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
