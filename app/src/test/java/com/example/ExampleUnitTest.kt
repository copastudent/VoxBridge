package com.example

import com.example.ai.VoiceAiService
import com.example.audio.AudioStorageManager
import com.example.audio.SpeechAudioFilter
import com.example.firebase.AppCheckInstallMode
import com.example.firebase.resolveAppCheckMode
import com.example.local.LocalSpeechModelManager
import com.example.model.ModelStatus
import com.example.model.ModelTier
import com.example.model.SpeakingVoiceGender
import com.example.model.SpeechLanguage
import com.example.model.VoiceArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun `wav encoder writes valid RIFF header and PCM payload size`() {
        val pcm = ShortArray(1600) { idx -> (idx % 500).toShort() }
        val wav = AudioStorageManager.encodePcmToWav(pcm, sampleRateHz = 16000)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
    }

    @Test
    fun `domain enums resolve safely and group free paid and local models`() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.fromCode("unknown"))
        assertEquals(SpeechLanguage.GUJARATI, SpeechLanguage.fromCode("gu"))
        assertEquals(VoiceArchitecture.ADAPTIVE_FLASH, VoiceArchitecture.fromName("unknown"))
        assertEquals(VoiceArchitecture.PRO_TRANSCRIBE, VoiceArchitecture.fromName("PRECISION_TRANSCRIBE"))
        assertEquals(ModelStatus.READY, ModelStatus.fromName("unknown"))
        assertEquals(SpeakingVoiceGender.FEMALE, SpeakingVoiceGender.fromName("unknown"))
        assertEquals(SpeakingVoiceGender.MALE, SpeakingVoiceGender.fromName("male"))
        assertEquals("Kore", SpeakingVoiceGender.FEMALE.geminiVoiceName)
        assertEquals("Orus", SpeakingVoiceGender.MALE.geminiVoiceName)

        assertTrue(VoiceArchitecture.byTier(ModelTier.FREE).isNotEmpty())
        assertTrue(VoiceArchitecture.byTier(ModelTier.PAID).isNotEmpty())
        assertTrue(VoiceArchitecture.byTier(ModelTier.LOCAL).isNotEmpty())
        assertTrue(VoiceArchitecture.byTier(ModelTier.FREE).all { it.tier == ModelTier.FREE })
    }

    @Test
    fun `speech audio filter rejects pure silence and low-frequency background hum`() {
        val silence = ShortArray(16000) { 0 }
        val silenceResult = SpeechAudioFilter.processChunk(silence)
        assertFalse(silenceResult.hasSpeech)
        assertEquals(0, silenceResult.activeSpeechMs)

        // Low-level 55Hz electrical hum + soft hiss (~0.006 amplitude) should be gated out
        val humAndNoise = ShortArray(16000) { i ->
            val hum = 200.0 * sin(2.0 * PI * 55.0 * i / 16000.0)
            val hiss = if (i % 7 == 0) 80.0 else -60.0
            (hum + hiss).toInt().toShort()
        }
        val noiseResult = SpeechAudioFilter.processChunk(humAndNoise)
        assertFalse(noiseResult.hasSpeech)
    }

    @Test
    fun `speech audio filter detects clear vocal band frequencies`() {
        // Simulated voiced speech harmonic mixture (350Hz + 700Hz + 1200Hz) at moderate volume
        val voiced = ShortArray(16000) { i ->
            val t = i / 16000.0
            val signal = 7000.0 * sin(2.0 * PI * 350.0 * t) +
                3500.0 * sin(2.0 * PI * 700.0 * t) +
                1800.0 * sin(2.0 * PI * 1200.0 * t)
            signal.toInt().coerceIn(-32768, 32767).toShort()
        }
        val speechResult = SpeechAudioFilter.processChunk(voiced)
        assertTrue(speechResult.hasSpeech)
        assertTrue(speechResult.activeSpeechMs >= 120)
    }

    @Test
    fun `transcript sanitizer strips silence and background noise placeholders`() {
        assertEquals("", VoiceAiService.sanitizeTranscript("   "))
        assertEquals("", VoiceAiService.sanitizeTranscript("[silence]"))
        assertEquals("", VoiceAiService.sanitizeTranscript("(background noise)"))
        assertEquals("", VoiceAiService.sanitizeTranscript("No speech detected in audio."))
        assertEquals("", VoiceAiService.sanitizeTranscript("... --- ..."))
        assertEquals("Please pass the water", VoiceAiService.sanitizeTranscript("  Please pass the water  "))
    }

    @Test
    fun `local lexicon rescorer snaps close offline hypotheses to trained phrases`() {
        val trained = listOf(
            "Good morning, how are you today?",
            "Please call my doctor tomorrow",
            "Thank you for listening carefully"
        )
        assertEquals(
            "Good morning, how are you today?",
            LocalSpeechModelManager.rescoreWithTrainedLexicon("Good morning how are u today", trained)
        )
        assertEquals(
            "I want a glass of juice",
            LocalSpeechModelManager.rescoreWithTrainedLexicon("I want a glass of juice", trained)
        )
    }

    @Test
    fun `app check mode selects harness debug token when intent extra present`() {
        val mode = resolveAppCheckMode(
            isDebugBuild = true,
            intentDebugToken = "test-harness-uuid",
            hasHarnessDebugToken = false,
            storedDebugSecret = null,
            recaptchaSiteKey = "6Lez-site-key"
        )
        assertEquals(AppCheckInstallMode.HARNESS_DEBUG_TOKEN, mode)
    }

    @Test
    fun `app check mode uses recaptcha on standalone debug build without harness token`() {
        val mode = resolveAppCheckMode(
            isDebugBuild = true,
            intentDebugToken = null,
            hasHarnessDebugToken = false,
            storedDebugSecret = "stale-random-uuid",
            recaptchaSiteKey = "6Lez-site-key"
        )
        assertEquals(AppCheckInstallMode.RECAPTCHA, mode)
    }

    @Test
    fun `app check mode preserves harness debug provider on emulator relaunch`() {
        val mode = resolveAppCheckMode(
            isDebugBuild = true,
            intentDebugToken = null,
            hasHarnessDebugToken = true,
            storedDebugSecret = "test-harness-uuid",
            recaptchaSiteKey = "6Lez-site-key"
        )
        assertEquals(AppCheckInstallMode.HARNESS_DEBUG_TOKEN, mode)
    }

    @Test
    fun `app check mode always uses recaptcha in release builds`() {
        val mode = resolveAppCheckMode(
            isDebugBuild = false,
            intentDebugToken = "ignored-in-release",
            hasHarnessDebugToken = true,
            storedDebugSecret = "ignored",
            recaptchaSiteKey = "6Lez-site-key"
        )
        assertEquals(AppCheckInstallMode.RECAPTCHA, mode)
    }
}
