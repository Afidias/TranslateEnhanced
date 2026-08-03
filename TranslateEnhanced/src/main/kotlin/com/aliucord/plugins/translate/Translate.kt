package com.aliucord.plugins.translate

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.plugins.translate.auto.AutoTranslateManager
import com.aliucord.plugins.translate.auto.LanguageDetector
import com.aliucord.plugins.translate.utils.forceRerenderMessage
import com.aliucord.plugins.translate.utils.getStrings
import com.aliucord.plugins.translate.utils.safeGetString
import com.aliucord.plugins.translate.utils.safeIsBlank
import com.aliucord.plugins.translate.utils.toRealString
import com.aliucord.plugins.translate.utils.DebugLogger
import com.discord.api.commands.ApplicationCommandType
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.stores.StoreMessageState
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import java.lang.reflect.Method

@AliucordPlugin
class Translate : Plugin() {
    private var pluginIcon: Drawable? = null
    private lateinit var controller: TranslateController
    private lateinit var autoManager: AutoTranslateManager
    private lateinit var strings: com.aliucord.plugins.translate.strings.IStrings
    private var chatList: WidgetChatList? = null

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)
        strings = ctx.getStrings()
        controller = TranslateController(settings, strings)
        autoManager = AutoTranslateManager(settings)

        // 初始化 debug 模式
        DebugLogger.setEnabled(settings.getBool(SETTINGS_KEY_DEBUG_MODE, false))
        DebugLogger.log("Plugin loaded. Debug mode: ${DebugLogger.isEnabled()}")
    }

    override fun start(context: Context) {
        patchChatList()
        patchProcessMessageText()
        patchMessageContextMenu()
        registerTranslateCommand()
    }

    override fun stop(context: Context?) {
        patcher.unpatchAll()
        controller.shutdown()
    }

    // ── Patches ────────────────────────────────────────────────────

    private fun patchChatList() {
        patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
            val list = it.thisObject as WidgetChatList
            chatList = list
            controller.attachChatList(list)
        })

        // 拦截新消息到达，触发自动翻译
        // 注意：Discord 126021 的 WidgetChatList 已经没有 onNewMessage（旧版遗留，patch 会静默失败），
        // 所有新消息（含私聊）统一走 StoreStream.handleMessageCreate(api Message)
        patcher.patch(
            StoreStream::class.java,
            "handleMessageCreate",
            arrayOf(com.discord.api.message.Message::class.java),
            Hook { hookParam ->
                val apiMessage = hookParam.args[0] as com.discord.api.message.Message
                val channelId = apiMessage.g()   // api Message 混淆方法：g() = channelId
                val messageId = apiMessage.o()   // o() = messageId
                DebugLogger.log("handleMessageCreate: channel=$channelId message=$messageId")
                maybeAutoTranslate(channelId, messageId)
            }
        )
    }

    /**
     * 自动翻译统一入口：新消息到达、历史消息渲染/滚动加载都会调用。
     * 前置检查在主线程完成（都是内存状态，开销极小），翻译任务提交到插件线程池。
     */
    private fun maybeAutoTranslate(channelId: Long, messageId: Long) {
        if (!autoManager.isEnabled(channelId)) {
            DebugLogger.log("auto skip: channel not enabled ($channelId)")
            return
        }
        if (autoManager.isPaused(channelId)) {
            DebugLogger.log("auto skip: channel paused ($channelId)")
            return
        }

        // 已在翻译中或已有译文的消息跳过，避免重复翻译
        if (controller.getCached(messageId) != null) {
            DebugLogger.log("auto skip: already translated ($messageId)")
            return
        }
        if (!controller.beginTranslate(messageId)) {
            DebugLogger.log("auto skip: already translating ($messageId)")
            return
        }

        // 异步读取消息并翻译（用插件自有线程池，避免每条消息新建线程）
        controller.submit {
            try {
                val message = StoreStream.getMessages().getMessage(channelId, messageId)
                val content = message?.content
                if (content == null) {
                    DebugLogger.log("auto skip: message not found in store ($messageId)")
                    return@submit
                }
                // 防御：混淆后的消息内容可能不是真实 String，先转换再做字符串操作
                val safeContent = content.toRealString()
                if (safeIsBlank(safeContent)) {
                    DebugLogger.log("auto skip: empty content ($messageId)")
                    return@submit
                }

                // 清理后没有可翻译文本（例如只有表情/链接/提及的消息）直接跳过，不计数失败
                if (!TextCleaner.clean(safeContent, settings).hasRealText) {
                    DebugLogger.log("auto skip: no real text after cleaning ($messageId)")
                    return@submit
                }

                // 跳过自己发送的消息
                val myId = StoreStream.getUsers().getMe().getId()
                if (message?.author?.id == myId) {
                    DebugLogger.log("auto skip: own message ($messageId)")
                    return@submit
                }

                val targetLang = settings.safeGetString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)

                // 语言检测：跳过目标语言的消息
                if (!LanguageDetector.shouldTranslate(safeContent, targetLang)) {
                    DebugLogger.log("auto skip: already target language ($messageId)")
                    return@submit
                }

                val result = controller.translateSync(
                    text = safeContent,
                    targetLang = targetLang,
                    channelId = channelId,
                    messageId = messageId
                )

                when (result) {
                    is TranslateResult.Success -> {
                        autoManager.recordSuccess(channelId)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            chatList?.let { list ->
                                list.forceRerenderMessage(messageId)
                            }
                        }
                    }
                    is TranslateResult.Error -> {
                        val justPaused = autoManager.recordFailure(channelId)
                        if (justPaused) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Utils.showToast(strings.toastAutoPaused, true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 自动翻译线程中的异常也计入失败并记录，避免被静默吞掉
                DebugLogger.log("Auto translate exception: ${e.message}")
                DebugLogger.log("at: " + (e.stackTrace?.take(8)?.joinToString(" <- ") { it.toString() } ?: "?"))
                DebugLogger.logCrash("auto", e)
                val justPaused = autoManager.recordFailure(channelId)
                if (justPaused) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Utils.showToast(strings.toastAutoPaused, true)
                    }
                }
            } finally {
                controller.endTranslate(messageId)
            }
        }
    }

    /**
     * 开启自动翻译时，立即翻译当前已加载的历史消息（含不可见但已加载的）。
     * 滚动加载的更多历史消息由 processMessageText 渲染时触发。
     */
    private fun translateChannelHistory(channelId: Long) {
        val list = chatList ?: return
        try {
            val adapter = WidgetChatList.`access$getAdapter$p`(list)
            val data = adapter.internalData
            var checked = 0
            for (entry in data) {
                if (entry !is MessageEntry) continue
                val msg = entry.message ?: continue
                if (msg.channelId != channelId) continue
                maybeAutoTranslate(channelId, msg.id)
                checked++
            }
            DebugLogger.log("translateChannelHistory: channel=$channelId checked=$checked")
        } catch (e: Exception) {
            DebugLogger.log("translateChannelHistory failed: ${e.message}")
        }
    }

    private fun patchProcessMessageText() {
        val mDraweeStringBuilder: Field =
            SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder")
        mDraweeStringBuilder.isAccessible = true

        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook {
                val messageEntry = it.args[1] as MessageEntry
                val message = messageEntry.message ?: return@Hook
                val id = message.id

                // 自动翻译开启的频道：渲染中的消息（历史消息/滚动加载）也触发翻译
                if (autoManager.isEnabled(message.channelId) && !autoManager.isPaused(message.channelId)) {
                    maybeAutoTranslate(message.channelId, id)
                }

                // 加载态：显示"翻译中..."
                if (controller.isLoading(id)) {
                    val textView = it.args[0] as SimpleDraweeSpanTextView
                    val builder = mDraweeStringBuilder[textView] as? DraweeSpanStringBuilder
                        ?: return@Hook
                    builder.replace(0, builder.length, strings.loadingText)
                    textView.setDraweeSpanStringBuilder(builder)
                    return@Hook
                }

                val data = controller.getCached(id) ?: return@Hook
                if (data.showingOriginal) return@Hook
                // 源文本内容变更检测：消息被编辑后清除旧译文缓存，显示原文
                // 两侧都转换（message.content 运行时可能是混淆类型）
                if (data.sourceText != message.content.toRealString()) {
                    controller.invalidate(id)
                    return@Hook
                }

                val textView = it.args[0] as SimpleDraweeSpanTextView
                if (mDraweeStringBuilder[textView] !is DraweeSpanStringBuilder) return@Hook
                val ctx = textView.context
                val adapter = it.thisObject as WidgetChatListAdapterItemMessage

                val translated = buildTranslatedBuilder(data, ctx, adapter, messageEntry, message)
                textView.setDraweeSpanStringBuilder(translated)
            }
        )
    }

    /**
     * 用 Discord 自己的解析器重渲染译文，恢复：
     * - URL 可点击（UrlSpan）
     * - emoji 图片 / 提及 / 频道 / 时间戳等原生 span
     *
     * 若解析器不可用（Discord 版本升级导致内部 API 变化），回退为纯文本显示。
     */
    private fun buildTranslatedBuilder(
        data: TranslateResult.Success,
        ctx: Context,
        adapter: WidgetChatListAdapterItemMessage,
        messageEntry: MessageEntry,
        message: Message
    ): DraweeSpanStringBuilder {
        val rendered = try {
            renderTranslatedText(data.translatedText, ctx, adapter, messageEntry, message)
        } catch (e: Exception) {
            DebugLogger.log("renderTranslatedText failed: ${e.message}")
            null
        }

        val builder = if (rendered is DraweeSpanStringBuilder) {
            rendered
        } else {
            DraweeSpanStringBuilder().apply { append(rendered ?: data.translatedText) }
        }

        val textEnd = builder.length
        val label = " (${strings.actionTranslate}: ${data.sourceLanguage} → ${data.translatedLanguage})"
        builder.append(label)
        builder.setSpan(RelativeSizeSpan(0.75f), textEnd, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            EditedMessageNode.Companion.`access$getForegroundColorSpan`(
                EditedMessageNode.Companion, ctx
            ),
            textEnd, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    // ── DiscordParser 内部方法（反射，缓存，避免每次渲染都查找）────
    private var getSpoilerClickHandlerM: Method? = null
    private var getMessageRenderContextM: Method? = null
    private var getMessagePreprocessorM: Method? = null

    private fun renderTranslatedText(
        text: String,
        ctx: Context,
        adapter: WidgetChatListAdapterItemMessage,
        messageEntry: MessageEntry,
        message: Message
    ): CharSequence? {
        if (getSpoilerClickHandlerM == null) {
            getSpoilerClickHandlerM = try {
                WidgetChatListAdapterItemMessage::class.java
                    .getDeclaredMethod("getSpoilerClickHandler", Message::class.java)
                    .apply { isAccessible = true }
            } catch (e: Exception) {
                DebugLogger.log("getSpoilerClickHandler not found: ${e.message}")
                null
            }
        }
        if (getMessageRenderContextM == null) {
            getMessageRenderContextM = try {
                WidgetChatListAdapterItemMessage::class.java
                    .getDeclaredMethod(
                        "getMessageRenderContext",
                        Context::class.java,
                        MessageEntry::class.java,
                        kotlin.jvm.functions.Function1::class.java
                    )
                    .apply { isAccessible = true }
            } catch (e: Exception) {
                DebugLogger.log("getMessageRenderContext not found: ${e.message}")
                null
            }
        }
        if (getMessagePreprocessorM == null) {
            getMessagePreprocessorM = try {
                WidgetChatListAdapterItemMessage::class.java
                    .getDeclaredMethod(
                        "getMessagePreprocessor",
                        Long::class.java,
                        Message::class.java,
                        StoreMessageState.State::class.java
                    )
                    .apply { isAccessible = true }
            } catch (e: Exception) {
                DebugLogger.log("getMessagePreprocessor not found: ${e.message}")
                null
            }
        }

        val spoiler = getSpoilerClickHandlerM ?: return null
        val renderCtxMethod = getMessageRenderContextM ?: return null
        val preprocessorMethod = getMessagePreprocessorM ?: return null

        val renderCtx = renderCtxMethod.invoke(
            adapter, ctx, messageEntry, spoiler.invoke(adapter, message)
        ) as MessageRenderContext
        val preprocessor = preprocessorMethod.invoke(
            adapter, adapter.adapter.data.userId, message, messageEntry.messageState
        ) as MessagePreprocessor

        return DiscordParser.parseChannelMessage(
            ctx,
            text,
            renderCtx,
            preprocessor,
            if (message.isWebhook()) DiscordParser.ParserOptions.ALLOW_MASKED_LINKS
            else DiscordParser.ParserOptions.DEFAULT,
            false
        )
    }

    private fun patchMessageContextMenu() {
        val viewId = View.generateViewId()
        val retranslateViewId = View.generateViewId()
        val menuClass = WidgetChatListActions::class.java
        val getBinding = menuClass.getDeclaredMethod("getBinding").apply { isAccessible = true }

        // ── configureUI：注入翻译/显示原文按钮 ─────────────────────
        patcher.patch(
            menuClass.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { hookParam ->
                val menu = hookParam.thisObject as WidgetChatListActions
                val binding = getBinding.invoke(menu) as WidgetChatListActionsBinding
                val btn = binding.a.findViewById<TextView>(viewId) ?: return@Hook

                btn.setOnClickListener { view ->
                    val message = (hookParam.args[0] as WidgetChatListActions.Model).message
                    val cached = controller.getCached(message.id)

                    if (cached == null) {
                        // 翻译
                        controller.translateWithLoading(
                            text = message.content,
                            channelId = message.channelId,
                            messageId = message.id
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post { menu.dismiss() }
                    } else {
                        // 切换原文/译文
                        controller.toggleOriginal(message.id)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { menu.dismiss() }
                    }
                }

                // ── 重新翻译按钮：仅在已有译文缓存且不在翻译中时显示 ──
                val retranslateBtn = binding.a.findViewById<TextView>(retranslateViewId)
                if (retranslateBtn != null) {
                    val message = (hookParam.args[0] as WidgetChatListActions.Model).message
                    val cached = controller.getCached(message.id)
                    retranslateBtn.visibility =
                        if (cached != null && !controller.isLoading(message.id)) View.VISIBLE else View.GONE
                    retranslateBtn.setOnClickListener {
                        // force = true：绕过旧译文，重新调用后端；
                        // 大模型后端会收到"不要原样回显"的额外提示
                        controller.translateWithLoading(
                            text = message.content,
                            channelId = message.channelId,
                            messageId = message.id,
                            force = true
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post { menu.dismiss() }
                    }
                }
            }
        )

        // ── onViewCreated：在菜单末尾插入翻译按钮 ───────────────────
        patcher.patch(
            menuClass,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { hookParam ->
                val scrollView = hookParam.args[0] as NestedScrollView
                val linearLayout = scrollView.getChildAt(0) as LinearLayout
                val ctx = linearLayout.context
                val messageId = WidgetChatListActions.`access$getMessageId$p`(
                    hookParam.thisObject as WidgetChatListActions
                )

                val cached = controller.getCached(messageId)
                val label = if (cached == null || cached.showingOriginal)
                    strings.actionTranslate else strings.actionShowOriginal

                linearLayout.addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = viewId
                    text = label
                    setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
                })

                // 重新翻译按钮：可见性由 configureUI 按当前消息的缓存状态控制
                linearLayout.addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = retranslateViewId
                    text = strings.actionRetranslate
                    setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
                })

                // 如果该频道支持自动翻译，在菜单加一个切换按钮
                val channelId = WidgetChatListActions.`access$getChannelId$p`(
                    hookParam.thisObject as WidgetChatListActions
                )
                if (channelId != 0L) {
                    val autoLabel = when {
                        autoManager.isPaused(channelId) -> strings.actionResumeAuto
                        autoManager.isEnabled(channelId) -> strings.actionDisableAuto
                        else -> strings.actionToggleAuto
                    }

                    linearLayout.addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        text = autoLabel
                        setOnClickListener {
                            // 暂停中的频道先恢复，而不是把它关掉
                            val nowOn = if (autoManager.isPaused(channelId)) {
                                autoManager.resume(channelId)
                                true
                            } else {
                                autoManager.toggle(channelId)
                            }
                            // 开启/恢复时立即翻译当前已加载的历史消息
                            if (nowOn) translateChannelHistory(channelId)
                            Utils.showToast(
                                if (nowOn) strings.toastAutoResumed else strings.toastAutoPaused
                            )
                            (hookParam.thisObject as WidgetChatListActions).dismiss()
                        }
                        setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
                    })
                }
            }
        )
    }

    private fun registerTranslateCommand() {
        commands.registerCommand(
            CMD_NAME,
            "Translate text using the configured backend",
            listOf(
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "Text to translate"),
                Utils.createCommandOption(ApplicationCommandType.STRING, "to",
                    "Target language code (default: zh-CN)",
                    choices = languageCodeChoices),
                Utils.createCommandOption(ApplicationCommandType.STRING, "from",
                    "Source language code (default: auto)",
                    choices = languageCodeChoices),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "send",
                    "Send the translated text to chat? (default true)")
            )
        ) { ctx ->
            val result = controller.translateSync(
                text       = ctx.getRequiredString("text"),
                sourceLang = ctx.getString("from"),
                targetLang = ctx.getString("to")
            )
            return@registerCommand when (result) {
                is TranslateResult.Error ->
                    CommandsAPI.CommandResult(result.toString(), null, false)
                is TranslateResult.Success ->
                    CommandsAPI.CommandResult(
                        result.translatedText,
                        null,
                        ctx.getBoolOrDefault("send", true)
                    )
            }
        }
    }
}
