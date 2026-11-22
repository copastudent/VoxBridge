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

enum class ModelTier(val displayName: String, val shortLabel: String) {
    FREE("Free Cloud Models", "Free"),
    PAID("Paid Cloud Models (Blaze)", "Paid"),
    LOCAL("Local On-Device (Offline)", "Local")
}

enum class SpeakingVoiceGender(
    val displayName: String,
    val geminiVoiceName: String,
    val description: String
) {
    FEMALE(
        displayName = "Female",
        geminiVoiceName = "Kore",
        description = "Kore • Clear & warm female voice"
    ),
    MALE(
        displayName = "Male",
        geminiVoiceName = "Orus",
        description = "Orus • Clear & firm male voice"
    );

    companion object {
        fun fromName(raw: String?): SpeakingVoiceGender =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FEMALE
    }
}

enum class VoiceArchitecture(
    val displayName: String,
    val geminiModelId: String,
    val tier: ModelTier,
    val latencyProfile: String,
    val description: String
) {
    ADAPTIVE_FLASH(
        displayName = "Gemini 3.8 Flash",
        geminiModelId = "gemini-3.8-flash",
        tier = ModelTier.FREE,
        latencyProfile = "Free • Balanced",
        description = "Frontier-class speech adaptation and real-time dictation. No billing required."
    ),
    ULTRA_FAST(
        displayName = "Gemini 3.5 Flash-Lite",
        geminiModelId = "gemini-3.5-flash-lite",
        tier = ModelTier.FREE,
        latencyProfile = "Free • Ultra-Fast",
        description = "High-volume, ultra-low latency model for continuous dictation on the Free Tier."
    ),
    FLAGSHIP_ARGON(
        displayName = "Gemini 4 Argon",
        geminiModelId = "gemini-4-argon",
        tier = ModelTier.PAID,
        latencyProfile = "Paid • Flagship",
        description = "Most powerful Gemini 4 reasoning model for complex dysarthric patterns. Requires Blaze billing."
    ),
    DEEP_PHONETIC(
        displayName = "Gemini 4 Carbon",
        geminiModelId = "gemini-4-carbon",
        tier = ModelTier.PAID,
        latencyProfile = "Paid • High Thinking",
        description = "Deep phonetic reasoning with High Thinking calibration. Requires Blaze billing."
    ),
    ADAPTIVE_GENERAL(
        displayName = "Gemini 4 Hydrogen",
        geminiModelId = "gemini-4-hydrogen",
        tier = ModelTier.PAID,
        latencyProfile = "Paid • Streaming Pro",
        description = "Balanced Gemini 4 streaming speech adaptation engine. Requires Blaze billing."
    ),
    PRO_TRANSCRIBE(
        displayName = "Gemini 3.1 Pro",
        geminiModelId = "gemini-3.1-pro-preview",
        tier = ModelTier.PAID,
        latencyProfile = "Paid • Precision Pro",
        description = "Gemini 3.1 Pro precision speech decoding for subtle articulation cues. Requires Blaze billing."
    ),
    LOCAL_ON_DEVICE(
        displayName = "Local Acoustic Model",
        geminiModelId = "local-on-device-v1",
        tier = ModelTier.LOCAL,
        latencyProfile = "Local • Offline",
        description = "100% on-device personalized acoustic DTW trainer + downloadable offline speech pack."
    );

    companion object {
        fun fromName(raw: String?): VoiceArchitecture = when (raw) {
            "PRECISION_TRANSCRIBE" -> PRO_TRANSCRIBE
            else -> entries.firstOrNull { it.name == raw } ?: ADAPTIVE_FLASH
        }

        fun byTier(tier: ModelTier): List<VoiceArchitecture> = entries.filter { it.tier == tier }
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
    val preferredVoiceGender: String = SpeakingVoiceGender.FEMALE.name,
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
