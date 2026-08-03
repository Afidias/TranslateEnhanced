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
import com.aliucord.plugins.translate.utils.safeIsBlank
import com.aliucord.plugins.translate.utils.toRealString
import com.discord.widgets.chat.list.WidgetChatList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    private val onShowToast: (String, Boolean) -> Unit = { msg, isLong -> Utils.showToast(msg, isLong) },
    private val onAutoResult: (channelId: Long, messageId: Long, success: Boolean) -> Unit = { _, _, _ -> }
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

    /** 自动翻译批量队列：消息先合并，再一次性发给 LLM，降低请求并发。 */
    private val autoBatchQueue = ConcurrentLinkedQueue<AutoBatchItem>()
    @Volatile
    private var batchFlushScheduled = false
    @Volatile
    private var batchScheduler: ScheduledExecutorService? = null

    private var chatList: WidgetChatList? = null

    /** 自动翻译批量条目（清理后的文本 + 原始文本 + 还原所需数据）。 */
    data class AutoBatchItem(
        val messageId: Long,
        val channelId: Long,
        val original: String,
        val text: String,
        val groups: List<TextCleaner.PlaceholderGroup>,
        val urls: List<String>,
        val targetLang: String
    )

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
        batchScheduler?.shutdownNow()
        batchScheduler = null
        autoBatchQueue.clear()
        batchFlushScheduled = false
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
        messageId: Long? = null,
        force: Boolean = false
    ): TranslateResult {
        // 防御：运行时消息内容可能是混淆类型而非真实 String（d0.d0.b），
        // 用 CharSequence 反射提取真实 String 后再做任何字符串操作
        val safeText = text.toRealString()
        val safeSource = sourceLang?.toRealString()
        val safeTarget = targetLang?.toRealString()
        val cleaned = TextCleaner.clean(safeText, settings)
        val cleanedText = cleaned.text
        val target = safeTarget ?: settings.safeGetString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
        val backend = resolveBackend(force)
        val backendName = if (backend is GoogleTranslator) "Google" else "LLM"

        if (!cleaned.hasRealText) {
            DebugLogger.log("translateSync: nothing to translate after cleaning")
            return TranslateResult.Error(errorText = ERROR_EMPTY_AFTER_CLEAN)
        }

        DebugLogger.log("translateSync called: backend=$backendName, target=$target, sourceLang=${safeSource ?: "auto"}")
        DebugLogger.log("Original text: " + safeText.substring(0, Math.min(100, safeText.length)))
        DebugLogger.log("Cleaned text: " + cleanedText.substring(0, Math.min(100, cleanedText.length)))

        var result: TranslateResult
        try {
            result = backend.translate(cleanedText, safeSource, target)
        } catch (e: Exception) {
            DebugLogger.log("Backend threw exception: ${e.message}")
            result = TranslateResult.Error(errorText = ERROR_BACKEND_EXCEPTION + (e.message ?: "Unknown"))
        }

        // 空译文视为失败，避免消息永远卡在“翻译中...”
        if (result is TranslateResult.Success && safeIsBlank(result.translatedText)) {
            DebugLogger.log("Backend returned an empty translation")
            result = TranslateResult.Error(errorText = ERROR_EMPTY_TRANSLATION)
        }

        // LLM 失败降级到 Google
        if (result is TranslateResult.Error && backend !is GoogleTranslator) {
            DebugLogger.log("LLM failed, falling back to Google: ${(result as TranslateResult.Error).errorText}")
            try {
                val fallback = GoogleTranslator()
                val fbResult = fallback.translate(cleanedText, safeSource, target)
                if (fbResult is TranslateResult.Success && !safeIsBlank(fbResult.translatedText)) {
                    result = fbResult
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onShowToast(strings.toastBackendFallback, false)
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Google fallback threw exception: ${e.message}")
            }
        }

        // 翻译成功后把占位符还原成原始内容（emoji/Discord 标记等）
        // （如果翻译引擎吞掉了占位符，restoreAll 会把缺失内容追加到译文末尾）
        if (result is TranslateResult.Success && cleaned.groups.isNotEmpty()) {
            result = result.copy(
                translatedText = TextCleaner.restoreAll(result.translatedText, cleaned.groups)
            )
        }
        // 链接校验补回：cleanUrl 关闭时链接随原文交给翻译引擎，
        // 若某个链接被改写/丢失，这里把原始链接补到译文末尾
        if (result is TranslateResult.Success && cleaned.urls.isNotEmpty()) {
            result = result.copy(
                translatedText = TextCleaner.ensureUrlsPresent(result.translatedText, cleaned.urls)
            )
        }

        // 排查辅助：译文与原文完全相同（"原样返回"）时记录日志，
        // 便于区分是大模型回显还是文本本就在目标语言
        if (result is TranslateResult.Success && result.translatedText == safeText) {
            DebugLogger.log("WARNING: translated text is identical to the source text")
        }

        // 记录翻译结果
        when (result) {
            is TranslateResult.Success -> {
                DebugLogger.logTranslation(
                    sourceText = safeText,
                    cleanedText = cleanedText,
                    sourceLang = safeSource,
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
                    sourceLang = safeSource,
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
        onComplete: ((TranslateResult) -> Unit)? = null,
        force: Boolean = false
    ) {
        if (!pendingMessages.add(messageId)) return  // 已有一条翻译任务在跑

        getExecutor().execute {
            try {
                val result = translateSync(text, sourceLang, targetLang, channelId, messageId, force)
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
        messageId: Long,
        force: Boolean = false
    ) {
        if (pendingMessages.contains(messageId)) return

        // 防御转换：兼容运行时混淆类型
        val safeText = text.toRealString()

        // 先存一个占位符，让 processMessageText 渲染“翻译中...”
        translatedMessages[messageId] = TranslateResult.Success(
            sourceLanguage = "",
            translatedLanguage = "",
            sourceText = safeText,
            translatedText = "",
            showingOriginal = false
        )
        rerender(messageId)

        translateAsync(safeText, sourceLang, targetLang, channelId, messageId, force = force)
    }

    private fun getBatchScheduler(): ScheduledExecutorService {
        val existing = batchScheduler
        if (existing != null && !existing.isShutdown) return existing
        synchronized(this) {
            val current = batchScheduler
            if (current != null && !current.isShutdown) return current
            val created = Executors.newSingleThreadScheduledExecutor()
            batchScheduler = created
            return created
        }
    }

    /**
     * 自动翻译入队：消息先进入批量队列，延迟 [AUTO_BATCH_DELAY_MS] 后合并成一次 LLM 请求。
     * 只有自动翻译路径使用；手动翻译保持单条即时。
     */
    fun enqueueAutoTranslate(item: AutoBatchItem) {
        autoBatchQueue.add(item)
        if (!batchFlushScheduled) {
            batchFlushScheduled = true
            try {
                getBatchScheduler().schedule({
                    batchFlushScheduled = false
                    flushAutoBatch()
                }, AUTO_BATCH_DELAY_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // 调度器不可用：立即单条处理，避免消息卡在队列
                batchFlushScheduled = false
                autoBatchQueue.remove(item)
                finalizeAutoItem(item, TranslateResult.Error(errorText = "batch scheduler unavailable"))
            }
        }
    }

    private fun flushAutoBatch() {
        if (autoBatchQueue.isEmpty()) return
        val items = ArrayList<AutoBatchItem>()
        while (items.size < AUTO_BATCH_MAX_ITEMS && !autoBatchQueue.isEmpty()) {
            val item = autoBatchQueue.poll() ?: break
            items.add(item)
        }
        if (items.isEmpty()) return
        DebugLogger.log("auto batch: flushing ${items.size} messages")

        val backend = try {
            resolveBackend(false)
        } catch (e: Exception) {
            GoogleTranslator()
        }

        for ((targetLang, langItems) in items.groupBy { it.targetLang }) {
            val normal = langItems.filter { it.text.length <= AUTO_BATCH_MAX_ITEM_CHARS }
            val long = langItems.filter { it.text.length > AUTO_BATCH_MAX_ITEM_CHARS }

            if (backend is LLMTranslator) {
                if (normal.isNotEmpty()) {
                    val results = backend.translateBatch(
                        normal.mapIndexed { i, item -> i to item.text },
                        null,
                        targetLang
                    )
                    normal.forEachIndexed { i, item ->
                        finalizeAutoItem(
                            item,
                            results[i] ?: TranslateResult.Error(errorText = "batch missing result")
                        )
                    }
                }
                // 超长消息不合并，单条走 LLM
                long.forEach { item ->
                    val r = try {
                        backend.translate(item.text, null, targetLang)
                    } catch (e: Exception) {
                        TranslateResult.Error(errorText = "LLM request exception: ${e.message}")
                    }
                    finalizeAutoItem(item, r)
                }
            } else {
                // Google 后端：逐条（与旧行为一致）
                langItems.forEach { item ->
                    val r = try {
                        backend.translate(item.text, null, targetLang)
                    } catch (e: Exception) {
                        TranslateResult.Error(errorText = "Translation backend error: ${e.message}")
                    }
                    finalizeAutoItem(item, r)
                }
            }
        }

        // 一次 flush 最多处理 AUTO_BATCH_MAX_ITEMS 条；还有积压则安排下一次 flush
        if (!autoBatchQueue.isEmpty() && !batchFlushScheduled) {
            batchFlushScheduled = true
            try {
                getBatchScheduler().schedule({
                    batchFlushScheduled = false
                    flushAutoBatch()
                }, AUTO_BATCH_DELAY_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                batchFlushScheduled = false
            }
        }
    }

    /**
     * 批量结果收尾：还原占位符/链接、降级 Google、缓存、刷新 UI、记录成功/失败、释放 pending。
     */
    private fun finalizeAutoItem(item: AutoBatchItem, result: TranslateResult) {
        var r = result
        if (r is TranslateResult.Success) {
            if (safeIsBlank(r.translatedText)) {
                r = TranslateResult.Error(errorText = "Backend returned an empty translation.")
            } else {
                r = r.copy(translatedText = TextCleaner.restoreAll(r.translatedText, item.groups))
                r = r.copy(translatedText = TextCleaner.ensureUrlsPresent(r.translatedText, item.urls))
            }
        }

        // 降级 Google（与 translateSync 行为一致）
        if (r is TranslateResult.Error) {
            try {
                val fb = GoogleTranslator().translate(item.text, null, item.targetLang)
                if (fb is TranslateResult.Success && !safeIsBlank(fb.translatedText)) {
                    r = fb.copy(translatedText = TextCleaner.restoreAll(fb.translatedText, item.groups))
                    r = r.copy(translatedText = TextCleaner.ensureUrlsPresent(r.translatedText, item.urls))
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onShowToast(strings.toastBackendFallback, false)
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Auto Google fallback threw exception: ${e.message}")
            }
        }

        if (r is TranslateResult.Success) {
            translatedMessages[item.messageId] = r.copy(sourceText = item.original)
            DebugLogger.log("auto batch item ok: msg=${item.messageId}")
            rerender(item.messageId)
            onAutoResult(item.channelId, item.messageId, true)
        } else {
            DebugLogger.log("auto batch item failed: msg=${item.messageId} err=${(r as TranslateResult.Error).errorText}")
            onAutoResult(item.channelId, item.messageId, false)
        }
        endTranslate(item.messageId)
    }

    fun isLoading(messageId: Long): Boolean =
        translatedMessages[messageId]?.let {
            it.translatedText.isEmpty() && !it.showingOriginal
        } ?: false

    private fun resolveBackend(force: Boolean = false): TranslatorBackend {
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
                    ) as Any,
                    forceRetranslate = force
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
        private const val AUTO_BATCH_DELAY_MS = 1000L
        private const val AUTO_BATCH_MAX_ITEMS = 10
        private const val AUTO_BATCH_MAX_ITEM_CHARS = 1500
        private const val ERROR_EMPTY_AFTER_CLEAN = "Nothing to translate after cleaning."
        private const val ERROR_EMPTY_TRANSLATION = "Backend returned an empty translation."
        private const val ERROR_BACKEND_EXCEPTION = "Translation backend error: "
    }
}
