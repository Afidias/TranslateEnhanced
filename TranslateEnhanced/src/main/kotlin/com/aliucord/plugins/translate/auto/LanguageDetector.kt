package com.aliucord.plugins.translate.auto

import java.util.Locale

/**
 * 简单的语言检测器，基于 Unicode 字符范围启发式判断。
 * 不依赖外部 API，覆盖主流语言的 CJK / Latin / Cyrillic / Arabic / Thai 等脚本。
 */
object LanguageDetector {

    // 常见繁体专用字（简体文本中基本不会出现），用于区分简繁
    private val TRADITIONAL_CHARS = charArrayOf(
        '裡','為','國','體','門','東','發','學','會','後',
        '時','說','來','對','這','個','與','無','麼','點',
        '號','專','員','經','過','間','問','題','讓','們',
        '從','將','覺','愛','請','謝','歡','關','開','區',
        '風','飛','馬','魚','鳥','電','話','語','論','讀',
        '寫','車','長','見','觀','輕','較','確','認','雙',
        '萬','億','數','據','網','頁','準','備','資','訊'
    ).toHashSet()

    /**
     * 返回推断的语言代码，null 表示无法判断。
     * 中文会区分简繁：简体 -> zh-CN，繁体 -> zh-TW。
     */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val stripped = text.replace(Regex("\\s+"), "")

        var cjk = 0; var latin = 0; var cyrillic = 0
        var arabic = 0; var thai = 0; var devanagari = 0
        var hiragana = 0; var katakana = 0; var korean = 0
        var traditional = 0

        for (ch in stripped) {
            val cp = ch.code
            when {
                cp in 0x3040..0x309F -> hiragana++
                cp in 0x30A0..0x30FF -> katakana++
                cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F -> korean++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF -> {
                    cjk++
                    if (ch in TRADITIONAL_CHARS) traditional++
                }
                cp in 0x0600..0x06FF -> arabic++
                cp in 0x0E00..0x0E7F -> thai++
                cp in 0x0900..0x097F -> devanagari++
                cp in 0x0400..0x04FF -> cyrillic++
                cp in 0x0041..0x024F -> latin++
            }
        }

        val total = (cjk + latin + cyrillic + arabic + thai +
            devanagari + hiragana + katakana + korean).coerceAtLeast(1)

        return when {
            (hiragana + katakana) > 0 -> "ja"
            korean > total * 0.5 -> "ko"
            cjk > total * 0.5 ->
                if (traditional > 0 && traditional * 3 >= cjk) "zh-TW" else "zh-CN"
            cyrillic > total * 0.5 -> "ru"
            arabic > total * 0.5 -> "ar"
            thai > total * 0.5 -> "th"
            devanagari > total * 0.5 -> "hi"
            latin > total * 0.5 -> "en"   // 粗略默认
            else -> null
        }
    }

    /**
     * 判断一条消息是否需要翻译（即：检测到的语言 != 目标语言）。
     * 中文按简繁区分：目标 zh-TW/zh-HK 时，简体中文消息仍需要翻译，反之亦然。
     */
    fun shouldTranslate(text: String, targetLang: String): Boolean {
        val detected = detect(text) ?: return true   // 无法判断时默认翻译
        val targetBase = targetLang.substringBefore("-").lowercase(Locale.ROOT)
        val detectedBase = detected.substringBefore("-").lowercase(Locale.ROOT)
        if (detectedBase != targetBase) return true
        // 同为中文时按完整语言代码比较（zh-CN vs zh-TW），简繁互不相同
        return detectedBase == "zh" && detected != targetLang
    }
}
