package com.aliucord.plugins.translate

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.plugins.translate.backend.LLMApiHelper
import com.aliucord.plugins.translate.strings.IStrings
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.getStrings
import com.aliucord.plugins.translate.utils.safeGetString
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R

/**
 * 设置页：使用 Discord 原生组件（CheckedSetting 单选/开关、UiKit 标题与行样式），
 * 配色跟随 Discord 主题（colorBackgroundSecondary / colorHeaderPrimary 等）。
 */
class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    // 模型输入框引用，选择模型后直接更新，无需重建页面
    private var modelInput: TextInputEditText? = null

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        val ctx = requireContext()
        val strings = ctx.getStrings()

        setActionBarTitle(strings.settingsTitle)

        // ── 大模型配置（仅当后端为 llm 时显示）────────────────
        val llmSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        llmSection.addView(sectionHeader(ctx, strings.settingsSectionLLM))
        llmSection.addView(settingsInput(ctx, strings.settingsLLMBaseUrl, SETTINGS_KEY_LLM_BASE_URL))
        llmSection.addView(settingsInput(ctx, strings.settingsLLMApiKey, SETTINGS_KEY_LLM_API_KEY, isPassword = true))
        llmSection.addView(settingsInput(ctx, strings.settingsLLMModel, SETTINGS_KEY_LLM_MODEL))
        llmSection.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(actionButton(ctx, strings.settingsTestConnection) { testLLMConnection(strings) })
            addView(actionButton(ctx, strings.settingsFetchModels) { fetchAvailableModels(strings) })
        })

        // ── 翻译后端（Discord 原生单选行）────────────────────
        addHeader(ctx, strings.settingsBackendLabel)
        val googleRow = Utils.createCheckedSetting(
            ctx, CheckedSetting.ViewType.RADIO, strings.settingsBackendGoogle, null
        )
        val llmRow = Utils.createCheckedSetting(
            ctx, CheckedSetting.ViewType.RADIO, strings.settingsBackendLLM, null
        )

        // 防止 refreshBackend() 程序化设置 isChecked 时递归触发监听器
        var suppressBackendCallback = false

        fun refreshBackend() {
            val current = safeGetStr(SETTINGS_KEY_BACKEND, ConfigStore.DEFAULT_BACKEND)
            suppressBackendCallback = true
            googleRow.isChecked = current == "google"
            llmRow.isChecked = current == "llm"
            suppressBackendCallback = false
            llmSection.visibility = if (current == "llm") View.VISIBLE else View.GONE
        }

        // ⚠️ 不要用 setOnClickListener + if(!isChecked) 判断：
        // Discord 的 CheckedSetting 在 RADIO 模式下点击时会先自行把 isChecked 置为 true，
        // 导致 `if (!row.isChecked)` 恒为 false，回调体永不执行、设置永远写不进去，
        // 表现为"两个选项都无法选中 / 大模型配置区不出现"。
        // 正确做法是用 setOnCheckedListener（与 switchRow 一致），它只在用户点击时回调。
        googleRow.setOnCheckedListener { checked ->
            if (suppressBackendCallback) return@setOnCheckedListener
            if (checked) {
                putStr(SETTINGS_KEY_BACKEND, "google")
                refreshBackend()
            }
        }
        llmRow.setOnCheckedListener { checked ->
            if (suppressBackendCallback) return@setOnCheckedListener
            if (checked) {
                putStr(SETTINGS_KEY_BACKEND, "llm")
                refreshBackend()
            }
        }
        addView(googleRow)
        addView(llmRow)
        addView(llmSection)

        // ── 默认语言 ────────────────────────────────────────
        addHeader(ctx, strings.settingsDefaultLanguage)
        val langRow = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            setOnClickListener { showLanguagePicker(strings, this) }
        }
        addView(langRow)

        // ── 自动翻译 ────────────────────────────────────────
        addHeader(ctx, strings.settingsSectionAuto)
        addView(switchRow(
            ctx, strings.settingsAutoTranslateSelf, SETTINGS_KEY_AUTO_TRANSLATE_SELF, false,
            subtext = strings.settingsAutoTranslateSelfDesc
        ))

        // ── 频道配置（提示词/术语表/缓存管理子页面）──────────
        addHeader(ctx, strings.settingsChannelSection)
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = strings.settingsChannelConfigRow
            setOnClickListener {
                Utils.openPageWithProxy(requireContext(), ChannelConfigsPage(settings))
            }
        })

        // ── 文本清理 ────────────────────────────────────────
        addHeader(ctx, strings.settingsCleaningLabel)
        addView(switchRow(ctx, strings.settingsCleanHtml, SETTINGS_KEY_CLEAN_HTML, true))
        addView(switchRow(
            ctx, strings.settingsCleanUrl, SETTINGS_KEY_CLEAN_URL, false,
            subtext = strings.settingsCleanUrlDesc
        ))
        addView(switchRow(ctx, strings.settingsCleanEmoji, SETTINGS_KEY_CLEAN_EMOJI, true))

        // ── 翻译缓存 ────────────────────────────────────────
        addHeader(ctx, strings.settingsCacheSection)
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = strings.settingsCacheInfo
        })
        addView(fullButton(ctx, strings.settingsCacheClearAll) {
            TranslationCache.clearAll()
            Utils.showToast(strings.settingsCacheCleared)
        })

        // ── 调试 ────────────────────────────────────────────
        addHeader(ctx, strings.settingsSectionDebug)
        addView(switchRow(ctx, strings.settingsDebugMode, SETTINGS_KEY_DEBUG_MODE, false) { v ->
            DebugLogger.setEnabled(v)
            Utils.showToast(
                if (v) strings.settingsDebugOn + DebugLogger.currentPath() else strings.settingsDebugOff
            )
        })
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            // 显示真实日志路径（Android 11+ 下可能落在应用私有目录）
            text = DebugLogger.currentPath()
            setOnClickListener { Utils.showToast(DebugLogger.currentPath()) }
        })
        addView(fullButton(ctx, strings.settingsDebugClearLog) {
            DebugLogger.clearLog()
            Utils.showToast(strings.settingsLogCleared)
        })

        refreshBackend()
        refreshLangRow(langRow, strings)
    }

    /**
     * 安全读取 String 设置项。
     *
     * 优先读 ConfigStore（插件自有文件，用户手改立即生效）；
     * ConfigStore 没有该 key 时回落到 Aliucord SettingsAPI。
     */
    private fun safeGetStr(key: String, default: String = ""): String {
        if (ConfigStore.has(key)) {
            val v = ConfigStore.getString(key, default)
            if (!v.isEmpty()) return v
        }
        return settings.safeGetString(key, default)
    }

    /**
     * 写配置：同时写 ConfigStore（插件自有文件，不会被 Aliucord 覆盖）
     * 和 SettingsAPI（保持兼容，供其余代码 path 读取）。
     */
    private fun putStr(key: String, value: String) {
        ConfigStore.put(key, value)
        try {
            settings.setString(key, value)
        } catch (e: Exception) {
            DebugLogger.log("putStr settings failed: ${e.message}")
        }
    }

    /**
     * 测试 LLM 连接
     * 注意：不调用任何 String 方法（isBlank, isEmpty 等），
     * 因为 settings.getString() 返回混淆类型，String 方法会崩溃。
     * 直接传给 LLMApiHelper，由它用 toRealString 转换。
     */
    private fun testLLMConnection(strings: IStrings) {
        val baseUrl = settings.getString(SETTINGS_KEY_LLM_BASE_URL, "")
        val apiKey = settings.getString(SETTINGS_KEY_LLM_API_KEY, "")
        val model = settings.getString(SETTINGS_KEY_LLM_MODEL, "gpt-4o-mini")

        Utils.showToast(strings.settingsTesting)

        Thread {
            try {
                val result = LLMApiHelper.testConnection(baseUrl, apiKey, model)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    when (result) {
                        is LLMApiHelper.TestResult.Success -> {
                            Utils.showToast(strings.settingsTestSuccess)
                        }
                        is LLMApiHelper.TestResult.Error -> {
                            Utils.showToast(strings.settingsTestFailed + result.errorText)
                        }
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Utils.showToast(strings.settingsTestError + e.message)
                }
            }
        }.start()
    }

    /** 获取可用模型列表 */
    private fun fetchAvailableModels(strings: IStrings) {
        val baseUrl = settings.getString(SETTINGS_KEY_LLM_BASE_URL, "")
        val apiKey = settings.getString(SETTINGS_KEY_LLM_API_KEY, "")

        Utils.showToast(strings.settingsFetchingModels)

        Thread {
            try {
                val result = LLMApiHelper.fetchModels(baseUrl, apiKey)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    when (result) {
                        is LLMApiHelper.ModelsResult.Success -> {
                            showModelSelectionDialog(result.models, strings)
                        }
                        is LLMApiHelper.ModelsResult.Error -> {
                            Utils.showToast(strings.settingsTestFailed + result.errorText)
                        }
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Utils.showToast(strings.settingsFetchModelsError + e.message)
                }
            }
        }.start()
    }

    /** 显示模型选择对话框 */
    private fun showModelSelectionDialog(models: List<String>, strings: IStrings) {
        try {
            val ctx = dialogContext()
            val currentModel = safeGetStr(SETTINGS_KEY_LLM_MODEL)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsSelectModel)
                .setItems(models.toTypedArray()) { _, which ->
                    val selectedModel = models[which]
                    putStr(SETTINGS_KEY_LLM_MODEL, selectedModel)
                    Utils.showToast(strings.settingsModelSet + selectedModel)
                    // 直接更新模型输入框，无需重建页面
                    modelInput?.setText(selectedModel)
                }
                .setNegativeButton(strings.settingsCancel, null)
                .show()
        } catch (e: Exception) {
            Utils.showToast(strings.settingsDialogError + e.message)
        }
    }

    /**
     * 取用于弹窗的 Context。
     *
     * Android 15 上直接拿 Activity 的 Context 建 MaterialAlertDialog 可能因为主题
     * 无法解析 Material 属性而抛 IllegalStateException（设置页 Theme 继承自 Discord，
     * 不保证带 Material 的 dialogTheme）。这里用 appcompat 的 ContextThemeWrapper
     * 包一层，确保属性可解析。
     */
    private fun dialogContext(): Context {
        val base = requireContext()
        return try {
            androidx.appcompat.view.ContextThemeWrapper(
                base, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert
            )
        } catch (e: Exception) {
            // 主题资源不存在时退回原 Context，由外层 try-catch 兜底
            base
        }
    }

    /** 显示目标语言选择对话框（单选框列表） */
    private fun showLanguagePicker(strings: IStrings, langRow: TextView? = null) {
        try {
            val ctx = dialogContext()
            val names = languageCodes.keys.toTypedArray()
            val currentCode = safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
            val currentIndex = languageCodes.values.indexOf(currentCode)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsDefaultLanguage)
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    val code = languageCodes.values.elementAt(which)
                    putStr(SETTINGS_KEY_DEFAULT_LANG, code)
                    Utils.showToast(strings.settingsLanguageSaved)
                    dialog.dismiss()
                    refreshLangRow(langRow, strings)
                }
                .setNegativeButton(strings.settingsCancel, null)
                .show()
        } catch (e: Exception) {
            Utils.showToast("Failed to show language picker: ${e.message}")
        }
    }

    private fun refreshLangRow(langRow: TextView?, strings: IStrings) {
        val current = safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
        val name = languageCodes.entries.firstOrNull { it.value == current }?.key ?: current
        langRow?.text = "➜  $name"
    }

    // ── Discord 风格视图构造 ───────────────────────────────────

    /** 分区标题（Discord UiKit_Settings_Item_Header 样式） */
    private fun sectionHeader(ctx: Context, text: String): TextView =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            this.text = text
        }

    /** Discord 原生开关行 */
    private fun switchRow(
        ctx: Context,
        label: String,
        key: String,
        default: Boolean,
        subtext: CharSequence? = null,
        onToggle: ((Boolean) -> Unit)? = null
    ): CheckedSetting =
        Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, label, subtext).apply {
            // ConfigStore 优先：用户在 /sdcard/Aliucord/TranslateEnhanced.config.json
            // 里手改布尔项也能立刻生效
            isChecked = if (ConfigStore.has(key)) ConfigStore.getBool(key, default)
            else settings.getBool(key, default)
            setOnCheckedListener { v ->
                ConfigStore.put(key, v)   // 写入插件自有配置
                settings.setBool(key, v)  // 同步 Aliucord settings 保持兼容
                onToggle?.invoke(v)
            }
        }

    /** 文本输入（Discord 配色：二级背景 + 主色文字） */
    private fun settingsInput(
        ctx: Context,
        hint: String,
        key: String,
        isPassword: Boolean = false
    ): TextInputLayout =
        TextInputLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 8, 0, 8)
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_FILLED)
            setBoxBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary))
            setDefaultHintTextColor(ColorStateList.valueOf(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary)))
            this.hint = hint
            addView(TextInputEditText(ctx).apply {
                // safeGetStr 走反射 toString，返回的是真 String；
                // 直接 setText 混淆类型会在 Android 15 的文本渲染路径上崩溃
                setText(safeGetStr(key))
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                setHintTextColor(ColorStateList.valueOf(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary)))
                if (key == SETTINGS_KEY_LLM_MODEL) modelInput = this
                if (isPassword) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        // text 可能为 null；toString() 在 EditText 的真 String 上安全
                        putStr(key, text?.toString() ?: "")
                    }
                }
            })
        }

    /** 操作按钮（等宽并排） */
    private fun actionButton(ctx: Context, text: String, onClick: () -> Unit): CardView =
        CardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
            radius = 8f
            setCardBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary))
            setContentPadding(16, 14, 16, 14)
            addView(TextView(ctx).apply {
                this.text = text
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                gravity = android.view.Gravity.CENTER
                textSize = 14f
            })
            setOnClickListener { onClick() }
        }

    /** 通栏操作按钮（清除日志/清除缓存等） */
    private fun fullButton(ctx: Context, text: String, onClick: () -> Unit): CardView =
        CardView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            radius = 8f
            setCardBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary))
            setContentPadding(16, 14, 16, 14)
            addView(TextView(ctx).apply {
                this.text = text
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                gravity = android.view.Gravity.CENTER
                textSize = 14f
            })
            setOnClickListener { onClick() }
        }
}
