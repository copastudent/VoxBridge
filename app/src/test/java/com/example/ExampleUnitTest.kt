package com.example

import com.example.audio.AudioStorageManager
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
}
