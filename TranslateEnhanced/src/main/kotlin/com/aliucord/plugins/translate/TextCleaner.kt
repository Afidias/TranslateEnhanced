package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.utils.toRealString

object TextCleaner {

    // 只清理 Discord 常见的提及/表情/时间戳和真正的 HTML 标签，
    // 避免误删普通文本中的尖括号表达式（如 "a < b > c"、"<3"）
    private val HTML_TAG_REGEX = Regex(
        "<(?:(?:@!?&?|#)\\d+|a?:\\w+:\\d+|t:\\d+[^>]*|/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>]*)?)>"
    )
    private val URL_REGEX       = Regex("https?://\\S+")
    private val EMOJI_REGEX     = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}]+")

    private const val URL_PLACEHOLDER_PREFIX = "[[URL_"
    private const val URL_PLACEHOLDER_SUFFIX = "]]"

    /**
     * 清理结果。
     *
     * 开启 cleanUrl 时 URL 不会被直接删除，而是替换成 [[URL_n]] 占位符，
     * 翻译完成后用 [restoreUrls] 还原，保证译文保留原文链接。
     *
     * @property text 清理后的文本（URL 已替换为占位符）
     * @property urls 按出现顺序抽取的原始 URL 列表
     */
    data class CleanResult(
        val text: String,
        val urls: List<String>
    )

    fun clean(text: String, settings: SettingsAPI): CleanResult {
        // 防御：运行时消息内容可能是混淆类型（如 d0.d0.b）而非真实 String，
        // 通过 CharSequence 反射逐字符提取真实 String
        var result = text.toRealString()
        val urls = mutableListOf<String>()

        // 先抽 URL：用占位符替换而不是删除，翻译后可以按原位置还原
        if (settings.getBool(SETTINGS_KEY_CLEAN_URL, true)) {
            val sb = StringBuilder()
            var searchFrom = 0
            var index = 0
            // 注意：不用 findAll(result)（依赖默认参数的合成桥接，本项目编译环境下报
            // "No value passed for parameter 'p1'"），改用显式传参的 find(input, startIndex)
            while (true) {
                val match = URL_REGEX.find(result, searchFrom) ?: break
                sb.append(result.substring(searchFrom, match.range.first))
                sb.append(urlPlaceholder(index))
                urls.add(match.value)
                index++
                searchFrom = match.range.last + 1
            }
            sb.append(result.substring(searchFrom))
            result = sb.toString()
        }

        if (settings.getBool(SETTINGS_KEY_CLEAN_HTML, true)) {
            result = result.replace(HTML_TAG_REGEX, "")
        }
        if (settings.getBool(SETTINGS_KEY_CLEAN_EMOJI, true)) {
            result = result.replace(EMOJI_REGEX, "")
        }

        return CleanResult(result.trim(), urls)
    }

    /**
     * 把译文中的 [[URL_n]] 占位符还原成原始 URL。
     *
     * 如果翻译引擎改写/吞掉了某个占位符，则把缺失的 URL 追加到译文末尾，
     * 保证链接不会因为翻译而丢失。
     */
    fun restoreUrls(text: String, urls: List<String>): String {
        if (urls.isEmpty()) return text
        var result = text
        val missing = mutableListOf<String>()

        urls.forEachIndexed { i, url ->
            val token = urlPlaceholder(i)
            if (result.indexOf(token) >= 0) {
                result = result.replace(token, url)
            } else {
                missing.add(url)
            }
        }

        return if (missing.isEmpty()) {
            result
        } else {
            result.trimEnd() + " " + missing.joinToString(" ")
        }
    }

    private fun urlPlaceholder(index: Int): String =
        URL_PLACEHOLDER_PREFIX + index + URL_PLACEHOLDER_SUFFIX
}
