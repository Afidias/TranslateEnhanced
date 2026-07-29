package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI

object TextCleaner {

    private val HTML_TAG_REGEX  = Regex("<[^>]*>")
    private val URL_REGEX       = Regex("https?://\\S+")
    private val EMOJI_REGEX     = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}]+")

    fun clean(text: String, settings: SettingsAPI): String {
        var result = text

        if (settings.getBool(SETTINGS_KEY_CLEAN_HTML, true)) {
            result = result.replace(HTML_TAG_REGEX, "")
        }
        if (settings.getBool(SETTINGS_KEY_CLEAN_URL, true)) {
            result = result.replace(URL_REGEX, "")
        }
        if (settings.getBool(SETTINGS_KEY_CLEAN_EMOJI, true)) {
            result = result.replace(EMOJI_REGEX, "")
        }

        return result.trim()
    }
}
