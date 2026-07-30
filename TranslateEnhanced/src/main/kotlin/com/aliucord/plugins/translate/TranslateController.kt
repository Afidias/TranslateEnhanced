package com.aliucord.plugins.translate

import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.backend.GoogleTranslator
import com.aliucord.plugins.translate.backend.LLMTranslator
import com.aliucord.plugins.translate.backend.TranslatorBackend
import com.aliucord.plugins.translate.utils.forceRerenderMessage
import com.discord.widgets.chat.list.WidgetChatList

/**
 * 翻译控制器：调度后端、管理结果缓存、驱动 UI 刷新。
 *
 * 职责：
 * 1. 根据用户设置选择后端（Google / LLM）
 * 2. 协调 TextCleaner 预处理
 * 3. 缓存译文结果
 * 4. 降级策略：LLM 失败自动回退 Google
 * 5. 通知 UI 刷新消息
 */
class TranslateController(
    private val settings: SettingsAPI,
    private val onShowToast: (String, Boolean) -> Unit = { msg, isLong -> Utils.showToast(msg, isLong) }
) {
    private val translatedMessages = mutableMapOf<Long, TranslateResult.Success>()
    private var chatList: WidgetChatList? = null

    fun attachChatList(list: WidgetChatList) { chatList = list }

    /**
     * 获取指定消息的缓存译文，若没有则返回 null。
     */
    fun getCached(messageId: Long): TranslateResult.Success? = translatedMessages[messageId]

    /**
     * 切换译文/原文显示。
     */
    fun toggleOriginal(messageId: Long) {
        translatedMessages[messageId]?.let {
            it.showingOriginal = !it.showingOriginal
            rerender(messageId)
        }
    }

    /**
     * 判断消息是否已翻译且当前显示译文。
     */
    fun isShowingTranslation(messageId: Long): Boolean =
        translatedMessages[messageId]?.showingOriginal == false

    /**
     * 同步翻译（调用前请已在后台线程）。
     * 返回 Success 或 Error。
     */
    fun translateSync(
        text: String,
        sourceLang: String? = null,
        targetLang: String? = null,
        channelId: Long? = null,
        messageId: Long? = null
    ): TranslateResult {
        val cleanedText = TextCleaner.clean(text, settings)
        val target = targetLang ?: settings.getString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)

        val backend = resolveBackend()
        var result = backend.translate(cleanedText, sourceLang, target)

        // LLM 失败降级到 Google
        if (result is TranslateResult.Error && backend !is GoogleTranslator) {
            val fallback = GoogleTranslator()
            val fbResult = fallback.translate(cleanedText, sourceLang, target)
            if (fbResult is TranslateResult.Success) {
                result = fbResult
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onShowToast("已降级到 Google Translate", false)
                }
            }
        }

        // 成功时缓存
        if (result is TranslateResult.Success && messageId != null) {
            translatedMessages[messageId] = result
        }

        return result
    }

    /**
     * 异步翻译：在后台线程执行，完成后在主线程刷新 UI。
     */
    fun translateAsync(
        text: String,
        sourceLang: String? = null,
        targetLang: String? = null,
        channelId: Long? = null,
        messageId: Long,
        onComplete: ((TranslateResult) -> Unit)? = null
    ) {
        Utils.threadPool.execute {
            val result = translateSync(text, sourceLang, targetLang, channelId, messageId)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (result is TranslateResult.Success) {
                    rerender(messageId)
                } else {
                    // 翻译失败：清除加载占位符，恢复原文显示
                    translatedMessages.remove(messageId)
                    rerender(messageId)
                    onShowToast("翻译失败: ${(result as TranslateResult.Error).errorText}", false)
                }
                onComplete?.invoke(result)
            }
        }
    }

    /**
     * 翻译并在缓存命中时显示"翻译中…"占位。
     */
    fun translateWithLoading(
        text: String,
        sourceLang: String? = null,
        targetLang: String? = null,
        channelId: Long? = null,
        messageId: Long
    ) {
        // 先存一个占位符，让 processMessageText 渲染"翻译中…"
        translatedMessages[messageId] = TranslateResult.Success(
            sourceLanguage = "",
            translatedLanguage = "",
            sourceText = text,
            translatedText = "",
            showingOriginal = false
        )
        rerender(messageId)

        translateAsync(text, sourceLang, targetLang, channelId, messageId)
    }

    fun isLoading(messageId: Long): Boolean =
        translatedMessages[messageId]?.let {
            it.translatedText.isEmpty() && !it.showingOriginal
        } ?: false

    private fun resolveBackend(): TranslatorBackend {
        val choice = settings.getString(SETTINGS_KEY_BACKEND, "google")
        return if (choice == "llm") {
            LLMTranslator(
                baseUrl  = settings.getString(SETTINGS_KEY_LLM_BASE_URL, ""),
                apiKey   = settings.getString(SETTINGS_KEY_LLM_API_KEY, ""),
                model    = settings.getString(SETTINGS_KEY_LLM_MODEL, "gpt-4o-mini"),
                systemPrompt = settings.getString(
                    SETTINGS_KEY_LLM_SYSTEM_PROMPT,
                    LLMTranslator.DEFAULT_SYSTEM_PROMPT
                )
            )
        } else {
            GoogleTranslator()
        }
    }

    private fun rerender(messageId: Long) {
        chatList?.let { list ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                list.forceRerenderMessage(messageId)
            }
        }
    }
}
