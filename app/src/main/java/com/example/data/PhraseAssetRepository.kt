package com.example.data

import android.content.Context
import com.example.model.CustomPhrase
import com.example.model.PhraseItem
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording

class PhraseAssetRepository(private val context: Context) {

    private val cachedDefaultPhrases = mutableMapOf<SpeechLanguage, List<PhraseItem>>()

    fun getDefaultPhrases(language: SpeechLanguage): List<PhraseItem> {
        return cachedDefaultPhrases.getOrPut(language) {
            val lines = context.assets.open(language.assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            lines.mapIndexed { index, text ->
                PhraseItem(
                    key = "${language.code}_$index",
                    text = text,
                    language = language,
                    category = categorizePhrase(index, language)
                )
            }
        }
    }

    fun buildMergedPhraseList(
        language: SpeechLanguage,
        customPhrases: List<CustomPhrase>,
        recordings: List<SpeechRecording>
    ): List<PhraseItem> {
        val countsByKey = recordings.groupingBy { it.phraseKey }.eachCount()
        val defaults = getDefaultPhrases(language).map { item ->
            item.copy(recordedCount = countsByKey[item.key] ?: 0)
        }
        val customs = customPhrases
            .filter { it.language == language.code }
            .map { custom ->
                val key = "custom_${custom.id}"
                PhraseItem(
                    key = key,
                    text = custom.text,
                    language = language,
                    category = custom.category,
                    customDocId = custom.id,
                    recordedCount = countsByKey[key] ?: 0
                )
            }
        return customs + defaults
    }

    private fun categorizePhrase(index: Int, language: SpeechLanguage): String {
        val group = index / 20
        return when (language) {
            SpeechLanguage.ENGLISH -> when (group) {
                0 -> "Stops & Aspirates"
                1 -> "Fricatives & Affricates"
                2 -> "Liquids & Nasals"
                3 -> "Vowels & Diphthongs"
                else -> "Clusters & Allophones"
            }
            SpeechLanguage.GUJARATI -> when (group) {
                0 -> "વ્યંજન અને મહાપ્રાણ"
                1 -> "સ્વર અને અનુસ્વાર"
                2 -> "મૂર્ધન્ય અને દંત્ય"
                3 -> "સંયુક્તાક્ષર અને ઘર્ષક"
                else -> "રોજિંદા વાક્યો"
            }
        }
    }
}
