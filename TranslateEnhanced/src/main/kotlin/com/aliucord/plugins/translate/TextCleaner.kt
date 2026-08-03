package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.utils.safeIsBlank
import com.aliucord.plugins.translate.utils.toRealString

object TextCleaner {

    // 只清理 Discord 常见的提及/表情/时间戳和真正的 HTML 标签，
    // 避免误删普通文本中的尖括号表达式（如 "a < b > c"、"<3"）
    private val HTML_TAG_REGEX = Regex(
        "<(?:(?:@!?&?|#)\\d+|a?:\\w+:\\d+|t:\\d+[^>]*|/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>]*)?)>"
    )
    // URL 匹配：以非空白开头，但遇到 CJK/全角/其他"不可能出现在 URL 里"的字符立即截断，
    // 避免 "https://a.com。结束" 这类把后续文本吞进链接
    private val URL_REGEX = Regex(
        "https?://[^\\s" +
            "\\u2000-\\u206F" +   // 通用标点（…、“”等）
            "\\u3000-\\u303F" +   // CJK 标点（。、，！？「」等）
            "\\u3040-\\u30FF" +   // 日文假名
            "\\u3400-\\u9FFF" +   // CJK 统一汉字
            "\\uAC00-\\uD7A3" +   // 韩文
            "\\uF900-\\uFAFF" +   // CJK 兼容汉字
            "\\uFF00-\\uFFEF" +   // 全角字符
            "\\u0370-\\u03FF" +   // 希腊文
            "\\u0400-\\u04FF" +   // 西里尔文
            "\\u0590-\\u05FF" +   // 希伯来文
            "\\u0600-\\u06FF" +   // 阿拉伯文
            "\\u0900-\\u097F" +   // 天城文
            "\\u0E00-\\u0E7F" +   // 泰文
            "\\x{1F000}-\\x{1FAFF}" + // emoji（注意 \u 只吃 4 位十六进制，emoji 码点须用 \x{} 语法）
            "]+"
    )
    private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}]+")

    // URL 尾部常见的句子标点/闭合括号（如 "https://a.com/foo)." → "https://a.com/foo"）
    private val URL_TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', '\'', '"', ')', ']', '}', '>', '`'
    ).toHashSet()

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
        val urls: List<String>,
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

        // 先记录原始链接（完整链接，含裁剪后的边界），翻译后用于校验/补回
        val recordedUrls = collectUrls(result)

        // cleanUrl 开启 = 占位保护模式：链接不参与翻译，译文原位还原
        // 关闭（默认）= 链接随原文交给翻译引擎，翻译后校验补回
        if (settings.getBool(SETTINGS_KEY_CLEAN_URL, false)) {
            val urls = mutableListOf<String>()
            result = replaceWithPlaceholders(
                result, URL_REGEX, URL_PLACEHOLDER_PREFIX, urls, ::trimUrl
            )
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
        // 铁律：绝不对管线里的值调用 Kotlin 内联字符串扩展（isBlank/isNotBlank），
        // R8 内联后会把混淆的 d0.d0.b 强转为 IntIterator 崩溃，这里只用 safeIsBlank
        val withoutPlaceholders = PLACEHOLDER_REGEX.replace(trimmed, "")
        val withoutUrls = URL_REGEX.replace(withoutPlaceholders, "")
        val hasRealText = !safeIsBlank(withoutUrls)
        return CleanResult(trimmed, groups, recordedUrls, hasRealText)
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
     * 翻译后校验：确保每个原始链接都出现在译文中。
     *
     * cleanUrl 关闭时链接直接交给翻译引擎（LLM 提示词要求原样保留，Google 原生保留）；
     * 若某个链接仍被改写/丢失，追加到译文末尾，保证链接不丢。
     */
    fun ensureUrlsPresent(text: String, urls: List<String>): String {
        if (urls.isEmpty()) return text
        val missing = urls.filter { url -> url.length > 0 && text.indexOf(url) < 0 }
        return if (missing.isEmpty()) {
            text
        } else {
            text.trimEnd() + " " + missing.joinToString(" ")
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
        out: MutableList<String>,
        trimMatch: ((String) -> String)? = null
    ): String {
        val sb = StringBuilder()
        var searchFrom = 0
        var index = 0
        while (true) {
            val match = regex.find(input, searchFrom) ?: break
            val raw = match.value
            val kept = trimMatch?.invoke(raw) ?: raw
            val matchStart = match.range.first
            val matchEnd = match.range.last + 1
            sb.append(input.substring(searchFrom, matchStart))
            if (kept.length > 0) {
                sb.append(placeholder(prefix, index))
                out.add(kept)
                index++
                searchFrom = matchStart + kept.length
            } else {
                // 整段都是标点等无效内容（极端情况），原样保留不占位
                sb.append(raw)
                searchFrom = matchEnd
            }
        }
        sb.append(input.substring(searchFrom))
        return sb.toString()
    }

    /** 按当前 URL 规则记录一条消息里的所有完整链接（裁剪尾部标点）。 */
    private fun collectUrls(input: String): List<String> {
        val urls = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val match = URL_REGEX.find(input, searchFrom) ?: break
            val kept = trimUrl(match.value)
            if (kept.length > 0) {
                urls.add(kept)
            }
            searchFrom = match.range.last + 1
        }
        return urls
    }

    /** 去掉 URL 尾部常见的句子标点/闭合括号。 */
    private fun trimUrl(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in URL_TRAILING_PUNCTUATION) {
            end--
        }
        return url.substring(0, end)
    }

    private fun placeholder(prefix: String, index: Int): String =
        prefix + index + PLACEHOLDER_SUFFIX
}
