package com.aliucord.plugins.translate

import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.utils.safeGetString

/**
 * 频道级翻译配置：专属提示词 + 术语表（每行 "原词=译文"）。
 *
 * 存储 key：`channelPrompt_{channelId}` / `channelGlossary_{channelId}`
 * 配置会注入 LLM 的 user prompt，让大模型按频道话题与术语表翻译。
 */
object ChannelConfig {

    fun getPrompt(settings: SettingsAPI, channelId: Long): String =
        settings.safeGetString("channelPrompt_$channelId")

    fun setPrompt(settings: SettingsAPI, channelId: Long, prompt: String) {
        settings.setString("channelPrompt_$channelId", prompt)
    }

    /** 术语表原文（多行文本，用于编辑框展示）。 */
    fun getGlossaryText(settings: SettingsAPI, channelId: Long): String =
        settings.safeGetString("channelGlossary_$channelId")

    /** 解析后的术语表。 */
    fun getGlossary(settings: SettingsAPI, channelId: Long): List<Pair<String, String>> {
        val raw = getGlossaryText(settings, channelId)
        val result = mutableListOf<Pair<String, String>>()
        // 手动按行切分（不用 Kotlin split，避免默认参数桥接问题）
        var lineStart = 0
        while (true) {
            val nl = raw.indexOf('\n', lineStart)
            val line = if (nl < 0) raw.substring(lineStart) else raw.substring(lineStart, nl)
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val term = trimmed.substring(0, eq).trim()
                    val trans = trimmed.substring(eq + 1).trim()
                    if (term.isNotEmpty() && trans.isNotEmpty()) {
                        result.add(term to trans)
                    }
                }
            }
            if (nl < 0) break
            lineStart = nl + 1
        }
        return result
    }

    fun setGlossary(settings: SettingsAPI, channelId: Long, text: String) {
        settings.setString("channelGlossary_$channelId", text)
    }

    fun clear(settings: SettingsAPI, channelId: Long) {
        settings.setString("channelPrompt_$channelId", "")
        settings.setString("channelGlossary_$channelId", "")
    }
}
