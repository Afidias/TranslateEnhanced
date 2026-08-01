package com.aliucord.plugins.translate

import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.backend.GoogleTranslator
import com.aliucord.plugins.translate.backend.LLMTranslator
import com.aliucord.plugins.translate.backend.TranslatorBackend
import com.aliucord.plugins.translate.strings.IStrings
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.forceRerenderMessage
import com.aliucord.plugins.translate.utils.safeGetString
import com.discord.widgets.chat.list.WidgetChatList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 翻译控制器：调度后端、管理结果缓存、驱动 UI 刷新。
 *
 * 职责：
 * 1. 根据用户设置选择后端（Google / LLM）
 * 2. 协调 TextCleaner 预处理
 * 3. 缓存译文结果
 * 4. 降级策略：LLM 失败自动回退 Google
 * 5. 通知 UI 刷新消息
 *
 * 线程模型：
 * - 所有翻译任务提交到插件自有的有界线程池，避免每条消息新建 Thread
 * - 缓存使用同步的 LRU Map（容量上限 MAX_CACHE_SIZE），防止内存无限增长
 * - pendingMessages 记录正在翻译中的消息，避免同一消息重复翻译
 */
class TranslateController(
    private val settings: SettingsAPI,
    private val strings: IStrings,
    private val onShowToast: (String, Boolean) -> Unit = { msg, isLong -> Utils.showToast(msg, isLong) }
) {
    private val translatedMessages: MutableMap<Long, TranslateResult.Success> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, TranslateResult.Success>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Long, TranslateResult.Success>
                ): Boolean = size > MAX_CACHE_SIZE
            }
        )
    private val pendingMessages = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var executor: ExecutorService? = null

    private var chatList: WidgetChatList? = null

    fun attachChatList(list: WidgetChatList) { chatList = list }

    /** 获取指定消息的缓存译文，若没有则返回 null。 */
    fun getCached(messageId: Long): TranslateResult.Success? = translatedMessages[messageId]

    /** 使指定消息的缓存失效（例如消息被编辑后）。 */
    fun invalidate(messageId: Long) {
        translatedMessages.remove(messageId)
    }

    /** 标记一条消息开始翻译，返回 false 表示已在翻译中。 */
    fun beginTranslate(messageId: Long): Boolean = pendingMessages.add(messageId)

    /** 标记一条消息翻译结束。 */
    fun endTranslate(messageId: Long) {
        pendingMessages.remove(messageId)
    }

    /** 提交一个后台任务（翻译）到插件线程池。 */
    fun submit(task: () -> Unit) {
        getExecutor().execute(task)
    }

    /** 插件卸载时调用：停止线程池、清空缓存。 */
    fun shutdown() {
        executor?.shutdownNow()
        executor = null
        translatedMessages.clear()
        pendingMessages.clear()
        chatList = null
    }

    private fun getExecutor(): ExecutorService {
        val existing = executor
        if (existing != null && !existing.isShutdown) return existing
        synchronized(this) {
            val current = executor
            if (current != null && !current.isShutdown) return current
            val created = Executors.newFixedThreadPool(TRANSLATE_THREADS)
            executor = created
            return created
        }
    }

    /**
     * 切换译文/原文显示。
     */
    fun toggleOriginal(messageId: Long) {
        if (isLoading(messageId)) return
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
        // 防御：运行时消息内容可能是混淆类型而非真实 String（d0.d0.b），
        // String.format 强制转换后再做任何字符串操作
        val safeText = String.format("%s", text)
        val cleanedText = TextCleaner.clean(safeText, settings)
        val target = targetLang ?: settings.safeGetString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
        val backend = resolveBackend()
        val backendName = if (backend is GoogleTranslator) "Google" else "LLM"

        if (cleanedText.isBlank()) {
            DebugLogger.log("translateSync: nothing to translate after cleaning")
            return TranslateResult.Error(errorText = ERROR_EMPTY_AFTER_CLEAN)
        }

        DebugLogger.log("translateSync called: backend=$backendName, target=$target, sourceLang=${sourceLang ?: "auto"}")
        DebugLogger.log("Original text: ${safeText.take(100)}")
        DebugLogger.log("Cleaned text: ${cleanedText.take(100)}")

        var result: TranslateResult
        try {
            result = backend.translate(cleanedText, sourceLang, target)
        } catch (e: Exception) {
            DebugLogger.log("Backend threw exception: ${e.message}")
            result = TranslateResult.Error(errorText = ERROR_BACKEND_EXCEPTION + (e.message ?: "Unknown"))
        }

        // 空译文视为失败，避免消息永远卡在“翻译中...”
        if (result is TranslateResult.Success && result.translatedText.isBlank()) {
            DebugLogger.log("Backend returned an empty translation")
            result = TranslateResult.Error(errorText = ERROR_EMPTY_TRANSLATION)
        }

        // LLM 失败降级到 Google
        if (result is TranslateResult.Error && backend !is GoogleTranslator) {
            DebugLogger.log("LLM failed, falling back to Google: ${(result as TranslateResult.Error).errorText}")
            try {
                val fallback = GoogleTranslator()
                val fbResult = fallback.translate(cleanedText, sourceLang, target)
                if (fbResult is TranslateResult.Success && fbResult.translatedText.isNotBlank()) {
                    result = fbResult
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onShowToast(strings.toastBackendFallback, false)
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Google fallback threw exception: ${e.message}")
            }
        }

        // 记录翻译结果
        when (result) {
            is TranslateResult.Success -> {
                DebugLogger.logTranslation(
                    sourceText = safeText,
                    cleanedText = cleanedText,
                    sourceLang = sourceLang,
                    targetLang = target,
                    backend = backendName,
                    resultType = "Success",
                    translatedText = result.translatedText
                )
            }
            is TranslateResult.Error -> {
                DebugLogger.logTranslation(
                    sourceText = safeText,
                    cleanedText = cleanedText,
                    sourceLang = sourceLang,
                    targetLang = target,
                    backend = backendName,
                    resultType = "Error",
                    translatedText = "",
                    errorText = result.errorText
                )
            }
        }

        // 成功时缓存；sourceText 保存原始文本（未清理），供消息编辑检测使用
        if (result is TranslateResult.Success && messageId != null) {
            translatedMessages[messageId] = result.copy(sourceText = safeText)
        }

        return result
    }

    /**
     * 异步翻译：在线程池执行，完成后在主线程刷新 UI。
     */
    fun translateAsync(
        text: String,
        sourceLang: String? = null,
        targetLang: String? = null,
        channelId: Long? = null,
        messageId: Long,
        onComplete: ((TranslateResult) -> Unit)? = null
    ) {
        if (!pendingMessages.add(messageId)) return  // 已有一条翻译任务在跑

        getExecutor().execute {
            try {
                val result = translateSync(text, sourceLang, targetLang, channelId, messageId)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (result is TranslateResult.Success) {
                        rerender(messageId)
                    } else {
                        // 翻译失败：清除加载占位符，恢复原文显示
                        translatedMessages.remove(messageId)
                        rerender(messageId)
                        onShowToast(strings.toastTranslateFailed + (result as TranslateResult.Error).errorText, false)
                    }
                    onComplete?.invoke(result)
                }
            } catch (e: Exception) {
                // 整个翻译流程崩溃：清除占位符，显示错误
                DebugLogger.log("translateAsync exception: ${e.message}")
                DebugLogger.log("at: " + (e.stackTrace?.take(8)?.joinToString(" <- ") { it.toString() } ?: "?"))
                DebugLogger.logCrash("translateAsync", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    translatedMessages.remove(messageId)
                    rerender(messageId)
                    val frames = e.stackTrace
                    val caller = frames?.firstOrNull { it.className.startsWith("com.aliucord.plugins.translate") }
                    val top = frames?.firstOrNull()
                    val loc = caller?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                        ?: top?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                        ?: "?"
                    // 定位信息放最前面，避免被 Toast 截断
                    onShowToast(strings.toastTranslateError + "@" + loc, false)
                }
            } finally {
                pendingMessages.remove(messageId)
            }
        }
    }

    /**
     * 翻译并在缓存命中时显示“翻译中...”占位符。
     */
    fun translateWithLoading(
        text: String,
        sourceLang: String? = null,
        targetLang: String? = null,
        channelId: Long? = null,
        messageId: Long
    ) {
        if (pendingMessages.contains(messageId)) return

        // 防御转换：兼容运行时混淆类型
        val safeText = String.format("%s", text)

        // 先存一个占位符，让 processMessageText 渲染“翻译中...”
        translatedMessages[messageId] = TranslateResult.Success(
            sourceLanguage = "",
            translatedLanguage = "",
            sourceText = safeText,
            translatedText = "",
            showingOriginal = false
        )
        rerender(messageId)

        translateAsync(safeText, sourceLang, targetLang, channelId, messageId)
    }

    fun isLoading(messageId: Long): Boolean =
        translatedMessages[messageId]?.let {
            it.translatedText.isEmpty() && !it.showingOriginal
        } ?: false

    private fun resolveBackend(): TranslatorBackend {
        return try {
            val choice = settings.safeGetString(SETTINGS_KEY_BACKEND, "google")
            if (choice == "llm") {
                // 直接传 getString() 原始返回值给 LLMTranslator
                // LLMTranslator 用 Any 接收 + String.format 转换，R8 无法优化
                LLMTranslator(
                    baseUrl  = settings.getString(SETTINGS_KEY_LLM_BASE_URL, "") as Any,
                    apiKey   = settings.getString(SETTINGS_KEY_LLM_API_KEY, "") as Any,
                    model    = settings.getString(SETTINGS_KEY_LLM_MODEL, "gpt-4o-mini") as Any,
                    systemPrompt = settings.getString(
                        SETTINGS_KEY_LLM_SYSTEM_PROMPT,
                        LLMTranslator.DEFAULT_SYSTEM_PROMPT
                    ) as Any
                )
            } else {
                GoogleTranslator()
            }
        } catch (e: Exception) {
            DebugLogger.log("resolveBackend failed, falling back to Google: ${e.message}")
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

    companion object {
        private const val MAX_CACHE_SIZE = 300
        private const val TRANSLATE_THREADS = 2
        private const val ERROR_EMPTY_AFTER_CLEAN = "Nothing to translate after cleaning."
        private const val ERROR_EMPTY_TRANSLATION = "Backend returned an empty translation."
        private const val ERROR_BACKEND_EXCEPTION = "Translation backend error: "
    }
}
