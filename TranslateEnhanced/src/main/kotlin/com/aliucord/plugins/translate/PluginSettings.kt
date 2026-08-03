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

        fun refreshBackend() {
            val current = safeGetStr(SETTINGS_KEY_BACKEND, "google")
            googleRow.isChecked = current == "google"
            llmRow.isChecked = current == "llm"
            llmSection.visibility = if (current == "llm") View.VISIBLE else View.GONE
        }

        googleRow.setOnClickListener {
            if (!googleRow.isChecked) {
                settings.setString(SETTINGS_KEY_BACKEND, "google")
                refreshBackend()
            }
        }
        llmRow.setOnClickListener {
            if (!llmRow.isChecked) {
                settings.setString(SETTINGS_KEY_BACKEND, "llm")
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

        // ── 文本清理 ────────────────────────────────────────
        addHeader(ctx, strings.settingsCleaningLabel)
        addView(switchRow(ctx, strings.settingsCleanHtml, SETTINGS_KEY_CLEAN_HTML, true))
        addView(switchRow(ctx, strings.settingsCleanUrl, SETTINGS_KEY_CLEAN_URL, true))
        addView(switchRow(ctx, strings.settingsCleanEmoji, SETTINGS_KEY_CLEAN_EMOJI, true))

        // ── 调试 ────────────────────────────────────────────
        addHeader(ctx, strings.settingsSectionDebug)
        addView(switchRow(ctx, strings.settingsDebugMode, SETTINGS_KEY_DEBUG_MODE, false) { v ->
            DebugLogger.setEnabled(v)
            Utils.showToast(if (v) strings.settingsDebugOn + LOG_PATH else strings.settingsDebugOff)
        })
        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = strings.settingsDebugLogPath
            setOnClickListener { Utils.showToast(strings.settingsDebugLogPath) }
        })
        addView(clearLogButton(ctx, strings))

        refreshBackend()
        refreshLangRow(langRow, strings)
    }

    /** 安全读取 String 设置项（使用扩展函数 safeGetString） */
    private fun safeGetStr(key: String, default: String = ""): String =
        settings.safeGetString(key, default)

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
            val ctx = requireContext()
            val currentModel = safeGetStr(SETTINGS_KEY_LLM_MODEL)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsSelectModel)
                .setItems(models.toTypedArray()) { _, which ->
                    val selectedModel = models[which]
                    settings.setString(SETTINGS_KEY_LLM_MODEL, selectedModel)
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

    /** 显示目标语言选择对话框（单选框列表） */
    private fun showLanguagePicker(strings: IStrings, langRow: TextView? = null) {
        try {
            val ctx = requireContext()
            val names = languageCodes.keys.toTypedArray()
            val currentCode = safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
            val currentIndex = languageCodes.values.indexOf(currentCode)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsDefaultLanguage)
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    val code = languageCodes.values.elementAt(which)
                    settings.setString(SETTINGS_KEY_DEFAULT_LANG, code)
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
        onToggle: ((Boolean) -> Unit)? = null
    ): CheckedSetting =
        Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, label, null).apply {
            isChecked = settings.getBool(key, default)
            setOnCheckedListener { v ->
                settings.setBool(key, v)
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
                setText(safeGetStr(key))
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                setHintTextColor(ColorStateList.valueOf(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary)))
                if (key == SETTINGS_KEY_LLM_MODEL) modelInput = this
                if (isPassword) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        settings.setString(key, text.toString())
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

    /** 清除日志按钮（通栏） */
    private fun clearLogButton(ctx: Context, strings: IStrings): CardView =
        CardView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            radius = 8f
            setCardBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary))
            setContentPadding(16, 14, 16, 14)
            addView(TextView(ctx).apply {
                text = strings.settingsDebugClearLog
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                gravity = android.view.Gravity.CENTER
                textSize = 14f
            })
            setOnClickListener {
                DebugLogger.clearLog()
                Utils.showToast(strings.settingsLogCleared)
            }
        }
}
