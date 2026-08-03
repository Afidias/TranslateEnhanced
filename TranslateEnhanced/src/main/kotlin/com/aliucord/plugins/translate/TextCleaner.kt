package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.utils.toRealString

object TextCleaner {

    // 只清理 Discord 常见的提及/表情/时间戳和真正的 HTML 标签，
    // 避免误删普通文本中的尖括号表达式（如 "a < b > c"、"<3"）
    private val HTML_TAG_REGEX = Regex(
        "<(?:(?:@!?&?|#)\\d+|a?:\\w+:\\d+|t:\\d+[^>]*|/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>]*)?)>"
    )
    private val URL_REGEX = Regex("https?://\\S+")
    private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}]+")

    private const val URL_PLACEHOLDER_PREFIX = "[[URL_"
    private const val EMOJI_PLACEHOLDER_PREFIX = "[[EMOJI_"
    private const val TAG_PLACEHOLDER_PREFIX = "[[TAG_"
    private const val PLACEHOLDER_SUFFIX = "]]"

    private val PLACEHOLDER_REGEX = Regex("\\[\\[(?:URL|EMOJI|TAG)_\\d+\\]\\]")

    /**
     * 清理结果。
     *
     * 所有被清洗的内容都不会被直接删除，而是替换成占位符：
     * - URL           → [[URL_n]]
     * - emoji         → [[EMOJI_n]]
     * - Discord 标记/HTML → [[TAG_n]]
     *
     * 翻译完成后用 [restoreAll] 还原，保证译文保留原文的链接、表情和标记。
     *
     * @property text 清理后的文本（各类型已替换为占位符）
     * @property groups 各类型占位符对应的原始内容（按出现顺序）
     * @property hasRealText 清理后是否还有可翻译的真实文本（纯 emoji/链接/标记的消息为 false）
     */
    data class CleanResult(
        val text: String,
        val groups: List<PlaceholderGroup>,
        val hasRealText: Boolean
    )

    /** 一类占位符：prefix 为占位符前缀（如 "[[URL_"），originals 为按出现顺序的原始文本。 */
    data class PlaceholderGroup(
        val prefix: String,
        val originals: List<String>
    )

    fun clean(text: String, settings: SettingsAPI): CleanResult {
        // 防御：运行时消息内容可能是混淆类型（如 d0.d0.b）而非真实 String，
        // 通过 CharSequence 反射逐字符提取真实 String
        var result = text.toRealString()
        val groups = mutableListOf<PlaceholderGroup>()

        if (settings.getBool(SETTINGS_KEY_CLEAN_URL, true)) {
            val urls = mutableListOf<String>()
            result = replaceWithPlaceholders(result, URL_REGEX, URL_PLACEHOLDER_PREFIX, urls)
            groups.add(PlaceholderGroup(URL_PLACEHOLDER_PREFIX, urls))
        }

        if (settings.getBool(SETTINGS_KEY_CLEAN_HTML, true)) {
            val tags = mutableListOf<String>()
            result = replaceWithPlaceholders(result, HTML_TAG_REGEX, TAG_PLACEHOLDER_PREFIX, tags)
            groups.add(PlaceholderGroup(TAG_PLACEHOLDER_PREFIX, tags))
        }

        if (settings.getBool(SETTINGS_KEY_CLEAN_EMOJI, true)) {
            val emojis = mutableListOf<String>()
            result = replaceWithPlaceholders(result, EMOJI_REGEX, EMOJI_PLACEHOLDER_PREFIX, emojis)
            groups.add(PlaceholderGroup(EMOJI_PLACEHOLDER_PREFIX, emojis))
        }

        val trimmed = result.trim()
        val hasRealText = PLACEHOLDER_REGEX.replace(trimmed, "").isNotBlank()
        return CleanResult(trimmed, groups, hasRealText)
    }

    /**
     * 把译文中的占位符还原成原始内容。
     *
     * 如果翻译引擎改写/吞掉了某个占位符，则把缺失的内容追加到译文末尾，
     * 保证链接/表情/标记不会因为翻译而丢失。
     */
    fun restoreAll(text: String, groups: List<PlaceholderGroup>): String {
        if (groups.isEmpty()) return text
        var result = text
        val missing = mutableListOf<String>()

        for (group in groups) {
            group.originals.forEachIndexed { i, original ->
                val token = placeholder(group.prefix, i)
                if (result.indexOf(token) >= 0) {
                    result = result.replace(token, original)
                } else {
                    missing.add(original)
                }
            }
        }

        return if (missing.isEmpty()) {
            result
        } else {
            result.trimEnd() + " " + missing.joinToString(" ")
        }
    }

    /**
     * 用占位符替换所有正则匹配，并把原文按顺序收集到 out 中。
     *
     * 注意：不用 findAll(input)（依赖默认参数的合成桥接，本项目编译环境下报
     * "No value passed for parameter 'p1'"），改用显式传参的 find(input, startIndex)。
     */
    private fun replaceWithPlaceholders(
        input: String,
        regex: Regex,
        prefix: String,
        out: MutableList<String>
    ): String {
        val sb = StringBuilder()
        var searchFrom = 0
        var index = 0
        while (true) {
            val match = regex.find(input, searchFrom) ?: break
            sb.append(input.substring(searchFrom, match.range.first))
            sb.append(placeholder(prefix, index))
            out.add(match.value)
            index++
            searchFrom = match.range.last + 1
        }
        sb.append(input.substring(searchFrom))
        return sb.toString()
    }

    private fun placeholder(prefix: String, index: Int): String =
        prefix + index + PLACEHOLDER_SUFFIX
}
