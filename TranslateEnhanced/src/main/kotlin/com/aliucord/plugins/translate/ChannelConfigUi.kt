package com.aliucord.plugins.translate

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.backend.LLMTranslator
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.getStrings
import com.aliucord.plugins.translate.utils.safeGetString
import com.aliucord.plugins.translate.utils.toRealString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * 频道翻译配置 UI：菜单与设置子页面共用。
 * 提示词 + 术语表编辑、自动生成（生成后自动保存）、清除配置/本频道缓存。
 */
object ChannelConfigUi {

    fun showEditor(ctx: Context, settings: SettingsAPI, channelId: Long) {
        ChannelConfig.register(settings, channelId)
        val strings = ctx.getStrings()

        val promptInput = TextInputEditText(ctx).apply {
            setText(ChannelConfig.getPrompt(settings, channelId))
            hint = strings.channelConfigPromptHint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
        }
        val glossaryInput = TextInputEditText(ctx).apply {
            setText(ChannelConfig.getGlossaryText(settings, channelId))
            hint = strings.channelConfigGlossaryHint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(promptInput)
            addView(glossaryInput)
            addView(TextView(ctx).apply {
                text = strings.channelConfigClearCache
                setPadding(0, 16, 0, 0)
                setOnClickListener {
                    TranslationCache.clearChannel(channelId)
                    Utils.showToast(strings.channelConfigCacheCleared)
                }
            })
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(strings.channelConfigTitle + "：" + channelId)
            .setView(layout)
            .setPositiveButton(strings.channelConfigSave) { _, _ ->
                ChannelConfig.setPrompt(settings, channelId, promptInput.text.toString())
                ChannelConfig.setGlossary(settings, channelId, glossaryInput.text.toString())
                Utils.showToast(strings.channelConfigSaved)
            }
            .setNegativeButton(strings.channelConfigClear) { _, _ ->
                ChannelConfig.clear(settings, channelId)
                Utils.showToast(strings.channelConfigCleared)
            }
            .setNeutralButton(strings.channelConfigGenerate) { _, _ ->
                generate(ctx, settings, channelId, promptInput, glossaryInput)
            }
            .show()
    }

    /** 用大模型分析频道最近消息生成提示词+术语表，并**自动保存**（用户可继续编辑）。 */
    private fun generate(
        ctx: Context,
        settings: SettingsAPI,
        channelId: Long,
        promptInput: TextInputEditText,
        glossaryInput: TextInputEditText
    ) {
        val strings = ctx.getStrings()
        Utils.showToast(strings.channelConfigGenerating)
        Thread {
            try {
                val texts = collectChannelMessages(channelId)
                if (texts.isEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Utils.showToast(strings.channelConfigGenerateFailed + "no recent messages")
                    }
                    return@Thread
                }
                val targetLang = settings.safeGetString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
                val llm = LLMTranslator(
                    baseUrl = settings.getString(SETTINGS_KEY_LLM_BASE_URL, "") as Any,
                    apiKey = settings.getString(SETTINGS_KEY_LLM_API_KEY, "") as Any,
                    model = settings.getString(SETTINGS_KEY_LLM_MODEL, "gpt-4o-mini") as Any
                )
                val prompt = "You are building translation configuration for a Discord channel.\n" +
                    "Analyze the recent messages below and output EXACTLY this format:\n" +
                    "PROMPT|one sentence describing the channel's main topic and tone (in Chinese)\n" +
                    "TERM|original term = recommended translation in $targetLang\n" +
                    "TERM|... (up to 15 TERM lines; only include special terms/names likely to need consistent translation)\n" +
                    "If there is no clear topic, output: PROMPT|mixed general chat\n\nMessages:\n" + texts

                val result = llm.complete(prompt)
                if (result is TranslateResult.Error) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Utils.showToast(strings.channelConfigGenerateFailed + result.errorText)
                    }
                    return@Thread
                }

                val content = (result as TranslateResult.Success).translatedText
                var generatedPrompt = ""
                val glossaryLines = mutableListOf<String>()
                var lineStart = 0
                while (true) {
                    val nl = content.indexOf('\n', lineStart)
                    val line = (if (nl < 0) content.substring(lineStart) else content.substring(lineStart, nl)).trim()
                    if (line.startsWith("PROMPT|")) {
                        generatedPrompt = line.substring("PROMPT|".length).trim()
                    } else if (line.startsWith("TERM|")) {
                        val entry = line.substring("TERM|".length).trim()
                        if (entry.indexOf('=') > 0) glossaryLines.add(entry)
                    }
                    if (nl < 0) break
                    lineStart = nl + 1
                }

                // 自动保存：即使关闭对话框，重新打开设置子页面也能看到
                if (generatedPrompt.isNotEmpty()) {
                    ChannelConfig.setPrompt(settings, channelId, generatedPrompt)
                }
                if (glossaryLines.isNotEmpty()) {
                    ChannelConfig.setGlossary(settings, channelId, glossaryLines.joinToString("\n"))
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (generatedPrompt.isNotEmpty()) promptInput.setText(generatedPrompt)
                    if (glossaryLines.isNotEmpty()) glossaryInput.setText(glossaryLines.joinToString("\n"))
                    Utils.showToast(strings.channelConfigGenerated)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Utils.showToast(strings.channelConfigGenerateFailed + (e.message ?: "Unknown"))
                }
            }
        }.start()
    }

    /** 收集频道最近消息内容（最多 30 条 / 4000 字符），用于生成频道配置。 */
    private fun collectChannelMessages(channelId: Long): String {
        val list = Utils.widgetChatList ?: return ""
        val sb = StringBuilder()
        try {
            val adapter = com.discord.widgets.chat.list.WidgetChatList.`access$getAdapter$p`(list)
            val data = adapter.internalData
            var count = 0
            for (entry in data) {
                if (entry !is com.discord.widgets.chat.list.entries.MessageEntry) continue
                val msg = entry.message ?: continue
                if (msg.channelId != channelId) continue
                val content = msg.content.toRealString()
                if (content.isEmpty()) continue
                sb.append(content).append("\n---\n")
                count++
                if (count >= 30 || sb.length > 4000) break
            }
        } catch (e: Exception) {
            DebugLogger.log("collectChannelMessages failed: ${e.message}")
        }
        return sb.toString()
    }
}
