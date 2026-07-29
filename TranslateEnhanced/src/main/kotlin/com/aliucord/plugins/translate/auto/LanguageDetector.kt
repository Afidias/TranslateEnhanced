package com.aliucord.plugins.translate.auto

import java.util.Locale

/**
 * 简单的语言检测器，基于 Unicode 字符范围启发式判断。
 * 不依赖外部 API，覆盖主流语言的 CJK / Latin / Cyrillic / Arabic / Thai 等脚本。
 */
object LanguageDetector {

    /**
     * 返回推断的语言代码，null 表示无法判断。
     * 精度足够区分"这条消息不是我的目标语言"，不追求学术级准确。
     */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val stripped = text.replace(Regex("\\s+"), "")

        var cjk = 0; var latin = 0; var cyrillic = 0
        var arabic = 0; var thai = 0; var devanagari = 0
        var hiragana = 0; var katakana = 0

        for (ch in stripped) {
            val cp = ch.code
            when {
                cp in 0x3040..0x309F -> hiragana++
                cp in 0x30A0..0x30FF -> katakana++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF -> cjk++
                cp in 0xAC00..0xD7AF -> cjk++          // Korean Hangul
                cp in 0x0600..0x06FF -> arabic++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 0x0900..0x097F -> devanagari++   // Hindi
                cp in 0x0400..0x04FF -> cyrillic++
                cp in 0x0041..0x024F -> latin++
            }
        }

        val total = (cjk + latin + cyrillic + arabic + thai +
            devanagari + hiragana + katakana).coerceAtLeast(1)

        return when {
            (hiragana + katakana) > 0 && cjk > 0 -> "ja"
            cjk > total * 0.5 -> "zh"
            cyrillic > total * 0.5 -> "ru"
            arabic > total * 0.5 -> "ar"
            thai > total * 0.5 -> "th"
            devanagari > total * 0.5 -> "hi"
            latin > total * 0.5 -> "en"   // 粗略默认
            else -> null
        }
    }

    /**
     * 判断一条消息是否需要翻译（即：检测到的语言 != 目标语言）
     */
    fun shouldTranslate(text: String, targetLang: String): Boolean {
        val detected = detect(text) ?: return true   // 无法判断时默认翻译
        val shortTarget = targetLang.substringBefore("-").lowercase(Locale.ROOT)
        val shortDetected = detected.lowercase(Locale.ROOT)
        return shortDetected != shortTarget
    }
}
