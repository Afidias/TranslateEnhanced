package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI

object TextCleaner {

    // 只清理 Discord 常见的提及/表情/时间戳和真正的 HTML 标签，
    // 避免误删普通文本中的尖括号表达式（如 "a < b > c"、"<3"）
    private val HTML_TAG_REGEX = Regex(
        "<(?:(?:@!?&?|#)\\d+|a?:\\w+:\\d+|t:\\d+[^>]*|/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>]*)?)>"
    )
    private val URL_REGEX       = Regex("https?://\\S+")
    private val EMOJI_REGEX     = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}]+")

    fun clean(text: String, settings: SettingsAPI): String {
        // 防御：运行时消息内容可能是混淆类型（如 d0.d0.b）而非真实 String，
        // String.format 强制转换（R8 无法优化，会真实调用 toString）
        var result = String.format("%s", text)

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
