package com.livetrans.app

import com.google.mlkit.nl.translate.TranslateLanguage

const val PREFS_NAME = "livetrans_prefs"
const val PREF_KEY_SOURCE_LANG = "source_lang"

enum class SourceLanguage(val modelName: String, val mlkitCode: String) {
    JAPANESE("vosk-model-small-ja-0.22", TranslateLanguage.JAPANESE),
    ENGLISH("vosk-model-small-en-us-0.15", TranslateLanguage.ENGLISH),
    KOREAN("vosk-model-small-ko-0.22", TranslateLanguage.KOREAN);

    val zipUrl: String get() = "https://alphacephei.com/vosk/models/$modelName.zip"
}
