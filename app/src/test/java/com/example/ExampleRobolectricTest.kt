package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PhraseAssetRepository
import com.example.model.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VoxBridge", appName)
    }

    @Test
    fun `load 100 English and 100 Gujarati phrases from assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = PhraseAssetRepository(context)
        val en = repo.getDefaultPhrases(SpeechLanguage.ENGLISH)
        val gu = repo.getDefaultPhrases(SpeechLanguage.GUJARATI)
        assertEquals(100, en.size)
        assertEquals(100, gu.size)
        assertTrue(en.all { it.text.length < 140 })
        assertTrue(gu.all { it.text.length < 140 })
    }
}
