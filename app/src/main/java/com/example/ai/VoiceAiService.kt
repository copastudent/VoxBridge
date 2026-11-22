package com.example.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording
import com.example.model.VoiceArchitecture
import com.example.model.VoiceModelProfile
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

data class TrainedModelResult(
    val summary: String,
    val adaptationPrompt: String,
    val articulationPatterns: List<String>,
    val confidenceScore: Long
)

data class SynthesizedAudio(
    val audioBytes: ByteArray,
    val isWav: Boolean,
    val sampleRateHz: Int = 24000
)

@OptIn(PublicPreviewAPI::class)
class VoiceAiService(private val context: Context) {

    private var androidTts: TextToSpeech? = null
    private var androidTtsReady: Boolean = false

    private val trainingSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(description = "Concise human-readable summary of the speaker's vocal and phonetic profile"),
            "adaptationPrompt" to Schema.string(description = "Detailed system instruction for speech recognition models to accurately decode this speaker's non-standard speech"),
            "articulationPatterns" to Schema.array(
                items = Schema.string(),
                description = "Up to 8 specific phoneme, cadence, or allophone patterns observed"
            ),
            "confidenceScore" to Schema.integer(description = "Estimated calibration confidence from 50 to 99 based on sample coverage")
        )
    )

    // Deep reasoning model for training voice profiles (ThinkingLevel.HIGH, no maxOutputTokens)
    private val trainingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-carbon",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = trainingSchema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            }
        )
    }

    // Dictation models across the 4 selectable voice architectures
    private val deepPhoneticModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-carbon",
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            }
        )
    }

    private val adaptiveGeneralModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-hydrogen"
        )
    }

    private val ultraFastModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.1-flash-lite"
        )
    }

    private val precisionTranscribeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-transcribe"
        )
    }

    private val ttsModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash-tts",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(voice = Voice("Kore"))
            }
        )
    }

    private fun buildLiveModel(
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?
    ): LiveGenerativeModel {
        val profileContext = if (activeProfile != null) {
            "Active Speaker Adaptation Profile (${activeProfile.name}): ${activeProfile.adaptationPrompt}"
        } else {
            "Listen patiently to non-standard, accented, or dysarthric speech and respond clearly and concisely."
        }
        val sysInstruction = content {
            text(
                "You are VoxBridge Live, an empathetic real-time voice communication partner. " +
                    "Language: ${language.label}. $profileContext " +
                    "Acknowledge what the speaker meant clearly in concise natural sentences."
            )
        }
        return Firebase.ai.liveModel(
            modelName = "gemini-3.8-live",
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice("Kore"))
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            },
            systemInstruction = sysInstruction
        )
    }

    suspend fun trainVoiceModel(
        architecture: VoiceArchitecture,
        language: SpeechLanguage,
        modelName: String,
        samples: List<Pair<SpeechRecording, ByteArray>>,
        totalRecordingCount: Int
    ): TrainedModelResult {
        require(samples.isNotEmpty()) { "Record at least one phrase before training a voice model." }
        return withContext(Dispatchers.IO) {
            withTimeout(60_000L) {
                val selectedSamples = samples.take(5)
                val promptContent = content {
                    text(
                        "You are an expert clinical speech-language pathologist and ASR personalization architect (Project Euphonia / Relate style). " +
                            "Analyze the following ${selectedSamples.size} speech audio recordings (out of $totalRecordingCount total user recordings) in ${language.label}. " +
                            "Each recording is paired with the exact ground-truth phrase the speaker intended to say. " +
                            "Target architecture being calibrated: ${architecture.displayName} (${architecture.geminiModelId}). " +
                            "Model profile name: $modelName.\n\n" +
                            "Identify systematic phonetic variations, consonant cluster simplifications, voicing/devoicing shifts, " +
                            "vowel elongations, breath pauses, or pacing traits. Produce a structured calibration profile JSON."
                    )
                    selectedSamples.forEachIndexed { idx, (recording, wavBytes) ->
                        text(
                            "Sample ${idx + 1}: Target phrase = \"${recording.phraseText}\" " +
                                "(duration=${recording.durationMs}ms, peakAmplitude=${recording.peakAmplitude})"
                        )
                        if (wavBytes.isNotEmpty() && wavBytes.size < 18 * 1024 * 1024) {
                            inlineData(wavBytes, "audio/wav")
                        }
                    }
                }

                val response = trainingModel.generateContent(promptContent)
                val jsonText = response.text ?: throw IllegalStateException("Training model returned empty response.")
                val json = JSONObject(jsonText)
                val summary = json.optString("summary", "Calibrated speaker profile for ${language.label}.")
                val adaptationPrompt = json.optString(
                    "adaptationPrompt",
                    "Adapt transcription for non-standard speech in ${language.label} using recorded phoneme shifts."
                )
                val patternsJson = json.optJSONArray("articulationPatterns")
                val patterns = buildList {
                    if (patternsJson != null) {
                        for (i in 0 until minOf(patternsJson.length(), 10)) {
                            val item = patternsJson.optString(i).trim()
                            if (item.isNotEmpty()) add(item)
                        }
                    }
                }
                val score = json.optLong("confidenceScore", 78L).coerceIn(50L, 99L)
                TrainedModelResult(
                    summary = summary,
                    adaptationPrompt = adaptationPrompt,
                    articulationPatterns = patterns,
                    confidenceScore = score
                )
            }
        }
    }

    fun dictateAudioStream(
        architecture: VoiceArchitecture,
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?,
        wavBytes: ByteArray
    ): Flow<String> = flow {
        val model = when (architecture) {
            VoiceArchitecture.DEEP_PHONETIC -> deepPhoneticModel
            VoiceArchitecture.ADAPTIVE_GENERAL -> adaptiveGeneralModel
            VoiceArchitecture.ULTRA_FAST -> ultraFastModel
            VoiceArchitecture.PRECISION_TRANSCRIBE -> precisionTranscribeModel
        }

        val adaptationContext = if (activeProfile != null) {
            "Trained Voice Model: ${activeProfile.name}\n" +
                "Observed Patterns: ${activeProfile.articulationPatterns.joinToString("; ")}\n" +
                "Adaptation Instructions: ${activeProfile.adaptationPrompt}"
        } else {
            "No custom profile selected yet; apply careful acoustic decoding for non-standard, slurred, or accented speech."
        }

        val prompt = content {
            text(
                "Transcribe the spoken audio accurately into clean ${language.label} text.\n" +
                    "$adaptationContext\n" +
                    "Rules: Output ONLY the decoded words the speaker intended to say, without commentary, quotes, or timestamps. " +
                    "If the audio is silence or unintelligible background noise, return an empty string."
            )
            inlineData(wavBytes, "audio/wav")
        }

        val accumulated = StringBuilder()
        withTimeout(45_000L) {
            model.generateContentStream(prompt).collect { chunk ->
                val text = chunk.text.orEmpty()
                if (text.isNotEmpty()) {
                    accumulated.append(text)
                    emit(accumulated.toString().trim())
                }
            }
        }
    }

    suspend fun synthesizeSpeech(text: String, language: SpeechLanguage): Result<SynthesizedAudio> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return Result.failure(IllegalArgumentException("Nothing to speak."))
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(45_000L) {
                    val prompt = content {
                        text("Read aloud clearly and warmly in ${language.label}: $cleanText")
                    }
                    val response = ttsModel.generateContent(prompt)
                    val audioPart = response.candidates
                        .firstOrNull()
                        ?.content
                        ?.parts
                        ?.filterIsInstance<InlineDataPart>()
                        ?.firstOrNull { it.mimeType.startsWith("audio/") }

                    if (audioPart != null && audioPart.inlineData.isNotEmpty()) {
                        val bytes = audioPart.inlineData
                        val isWav = bytes.size >= 4 &&
                            bytes[0] == 'R'.code.toByte() &&
                            bytes[1] == 'I'.code.toByte() &&
                            bytes[2] == 'F'.code.toByte() &&
                            bytes[3] == 'F'.code.toByte()
                        val payload = if (isWav && bytes.size > 44) {
                            bytes.copyOfRange(44, bytes.size)
                        } else {
                            bytes
                        }
                        Result.success(
                            SynthesizedAudio(
                                audioBytes = payload,
                                isWav = isWav,
                                sampleRateHz = 24000
                            )
                        )
                    } else {
                        throw IllegalStateException("TTS response contained no audio payload.")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini TTS failed, falling back to Android TTS: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun speakWithSystemTtsFallback(text: String, language: SpeechLanguage) {
        val locale = if (language == SpeechLanguage.GUJARATI) Locale("gu", "IN") else Locale.US
        if (androidTts == null) {
            androidTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    androidTtsReady = true
                    androidTts?.language = locale
                    androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voxbridge_tts")
                }
            }
        } else if (androidTtsReady) {
            androidTts?.language = locale
            androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voxbridge_tts")
        }
    }

    suspend fun connectLiveSession(
        language: SpeechLanguage,
        activeProfile: VoiceModelProfile?
    ): LiveSession {
        return withTimeout(30_000L) {
            buildLiveModel(language, activeProfile).connect()
        }
    }

    fun release() {
        try {
            androidTts?.stop()
            androidTts?.shutdown()
        } catch (_: Exception) {
        }
        androidTts = null
        androidTtsReady = false
    }

    companion object {
        private const val TAG = "VoiceAiService"
    }
}
