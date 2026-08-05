package com.aliucord.plugins.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.backend.LLMTranslator
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.getStrings
import com.aliucord.plugins.translate.utils.safeGetString
import com.aliucord.plugins.translate.utils.toRealString

/** Shared navigation and LLM generation logic for per-channel translation config. */
object ChannelConfigUi {

    fun showEditor(ctx: Context, settings: SettingsAPI, channelId: Long) {
        ChannelConfig.register(settings, channelId)
        Utils.openPageWithProxy(ctx, ChannelConfigPage(settings, channelId))
    }

    /**
     * Analyze recent messages and persist a generated prompt and glossary.
     * [onGenerated] is always invoked on the main thread after persistence succeeds.
     */
    fun generate(
        ctx: Context,
        settings: SettingsAPI,
        channelId: Long,
        onGenerated: (String, List<Pair<String, String>>) -> Unit
    ) {
        val strings = ctx.getStrings()
        Utils.showToast(strings.channelConfigGenerating)
        Thread {
            try {
                val texts = collectChannelMessages(channelId)
                if (texts.isEmpty()) {
                    Handler(Looper.getMainLooper()).post {
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
                val request = "You are building translation configuration for a Discord channel.\n" +
                    "Analyze the recent messages below and output EXACTLY this format:\n" +
                    "PROMPT|one sentence describing the channel's main topic and tone (in Chinese)\n" +
                    "TERM|original term = recommended translation in $targetLang\n" +
                    "TERM|... (up to 15 TERM lines; only include special terms/names likely to need consistent translation)\n" +
                    "If there is no clear topic, output: PROMPT|mixed general chat\n\nMessages:\n" + texts

                val result = llm.complete(request)
                if (result is TranslateResult.Error) {
                    Handler(Looper.getMainLooper()).post {
                        Utils.showToast(strings.channelConfigGenerateFailed + result.errorText)
                    }
                    return@Thread
                }

                val content = (result as TranslateResult.Success).translatedText
                var generatedPrompt = ""
                val glossary = mutableListOf<Pair<String, String>>()
                var lineStart = 0
                while (true) {
                    val nl = content.indexOf('\n', lineStart)
                    val line = (if (nl < 0) content.substring(lineStart) else content.substring(lineStart, nl)).trim()
                    if (line.startsWith("PROMPT|")) {
                        generatedPrompt = line.substring("PROMPT|".length).trim()
                    } else if (line.startsWith("TERM|")) {
                        val entry = line.substring("TERM|".length).trim()
                        val eq = entry.indexOf('=')
                        if (eq > 0) {
                            val source = entry.substring(0, eq).trim()
                            val target = entry.substring(eq + 1).trim()
                            if (source.isNotEmpty() && target.isNotEmpty()) glossary.add(source to target)
                        }
                    }
                    if (nl < 0) break
                    lineStart = nl + 1
                }

                if (generatedPrompt.isNotEmpty()) ChannelConfig.setPrompt(settings, channelId, generatedPrompt)
                if (glossary.isNotEmpty()) {
                    val glossaryText = glossary.joinToString("\n") { it.first + "=" + it.second }
                    ChannelConfig.setGlossary(settings, channelId, glossaryText)
                }

                Handler(Looper.getMainLooper()).post {
                    onGenerated(generatedPrompt, glossary)
                    Utils.showToast(strings.channelConfigGenerated)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Utils.showToast(strings.channelConfigGenerateFailed + (e.message ?: "Unknown"))
                }
            }
        }.start()
    }

    /** Collect at most 30 recent messages / 4000 characters from the current chat list. */
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
            DebugLogger.log("collectChannelMessages failed: " + (e.message ?: "Unknown"))
        }
        return sb.toString()
    }
}
