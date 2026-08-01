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
import com.aliucord.plugins.translate.utils.DebugLogger
import com.discord.api.commands.ApplicationCommandType
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.reflect.Field

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
        patcher.patch(
            WidgetChatList::class.java,
            "onNewMessage",
            arrayOf(Long::class.java, Long::class.java, Long::class.java),
            Hook { hookParam ->
                val channelId = hookParam.args[0] as Long
                val messageId = hookParam.args[2] as Long

                if (!autoManager.isEnabled(channelId)) return@Hook
                if (autoManager.isPaused(channelId)) return@Hook

                // 已在翻译中或已有译文的消息跳过，避免重复翻译
                if (controller.getCached(messageId) != null) return@Hook
                if (!controller.beginTranslate(messageId)) return@Hook

                // 异步读取消息并翻译（用插件自有线程池，避免每条消息新建线程）
                controller.submit {
                    try {
                        val message = com.discord.stores.StoreStream
                            .getMessages()
                            .getMessage(channelId, messageId)
                        val content = message?.content ?: return@submit
                        // 防御：混淆后的消息内容可能不是真实 String，先转换再做字符串操作
                        val safeContent = String.format("%s", content)
                        if (safeContent.isBlank()) return@submit

                        // 清理后为空（例如只有表情的消息）直接跳过，不计数失败
                        if (TextCleaner.clean(safeContent, settings).isBlank()) return@submit

                        // 跳过自己发送的消息
                        val myId = com.discord.stores.StoreStream.getUsers().getMe().getId()
                        if (message?.author?.id == myId) return@submit

                        val targetLang = settings.safeGetString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)

                        // 语言检测：跳过目标语言的消息
                        if (!LanguageDetector.shouldTranslate(safeContent, targetLang)) return@submit

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
        )
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
                if (data.sourceText != String.format("%s", message.content)) {
                    controller.invalidate(id)
                    return@Hook
                }

                val textView = it.args[0] as SimpleDraweeSpanTextView
                val builder = mDraweeStringBuilder[textView] as? DraweeSpanStringBuilder
                    ?: return@Hook
                val ctx = textView.context

                builder.applyTranslatedText(data, ctx)
                textView.setDraweeSpanStringBuilder(builder)
            }
        )
    }

    private fun DraweeSpanStringBuilder.applyTranslatedText(
        data: TranslateResult.Success,
        ctx: Context
    ) {
        replace(0, length, data.translatedText)
        val textEnd = length
        val label = " (${strings.actionTranslate}: ${data.sourceLanguage} → ${data.translatedLanguage})"
        append(label)
        setSpan(RelativeSizeSpan(0.75f), textEnd, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(
            EditedMessageNode.Companion.`access$getForegroundColorSpan`(
                EditedMessageNode.Companion, ctx
            ),
            textEnd, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun patchMessageContextMenu() {
        val viewId = View.generateViewId()
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
