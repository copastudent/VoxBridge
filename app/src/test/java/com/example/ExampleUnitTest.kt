package com.example

import com.example.audio.AudioStorageManager
import com.example.firebase.AppCheckInstallMode
import com.example.firebase.resolveAppCheckMode
import com.example.model.ModelStatus
import com.example.model.SpeechLanguage
import com.example.model.VoiceArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `domain enums resolve safely on unknown values`() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.fromCode("unknown"))
        assertEquals(SpeechLanguage.GUJARATI, SpeechLanguage.fromCode("gu"))
        assertEquals(VoiceArchitecture.ADAPTIVE_GENERAL, VoiceArchitecture.fromName("unknown"))
        assertEquals(ModelStatus.READY, ModelStatus.fromName("unknown"))
        assertTrue(VoiceArchitecture.entries.size >= 4)
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
