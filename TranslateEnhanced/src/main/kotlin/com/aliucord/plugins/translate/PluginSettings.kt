package com.aliucord.plugins.translate

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
import com.aliucord.plugins.translate.utils.getStrings
import com.aliucord.plugins.translate.utils.safeGetString
import com.aliucord.plugins.translate.utils.DebugLogger
import com.discord.utilities.color.ColorCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    // 模型输入框引用，选择模型后直接更新，无需重建页面
    private var modelInput: TextInputEditText? = null

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        val ctx = requireContext()
        val strings = ctx.getStrings()
        val textColor = ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary)
        val bgColor = ColorCompat.getThemedColor(ctx, R.b.colorSurface)

        setActionBarTitle(strings.settingsTitle)

        // ── Backend selector (二选一：Google / 大模型) ───────────
        addView(sectionLabel(strings.settingsBackendLabel, textColor))

        val backendRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
        }

        // 存储每个后端选项的 (label, TextView)，用于动态刷新选中状态
        val backendLabels = mutableMapOf<String, String>()
        val backendTextViews = mutableMapOf<String, TextView>()

        // ── LLM settings (shown only when backend = llm) ─────────
        val llmSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }

        llmSection.addView(settingsInput(strings.settingsLLMBaseUrl, SETTINGS_KEY_LLM_BASE_URL, textColor, bgColor))
        llmSection.addView(settingsInput(strings.settingsLLMApiKey, SETTINGS_KEY_LLM_API_KEY, textColor, bgColor, isPassword = true))
        llmSection.addView(settingsInput(strings.settingsLLMModel, SETTINGS_KEY_LLM_MODEL, textColor, bgColor))

        // ── LLM API 测试按钮 ──────────────────────────────────────
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
        }

        // 测试连接按钮
        buttonRow.addView(button(strings.settingsTestConnection, textColor, bgColor) {
            testLLMConnection(strings)
        })

        // 获取模型按钮
        buttonRow.addView(button(strings.settingsFetchModels, textColor, bgColor) {
            fetchAvailableModels(strings)
        })

        llmSection.addView(buttonRow)

        // 刷新后端选项的选中样式和 LLM 区域可见性（定义在 llmSection 之后）
        fun refreshBackendSelection() {
            val backend = safeGetStr(SETTINGS_KEY_BACKEND, "google")
            backendLabels.forEach { (value, label) ->
                val selected = value == backend
                backendTextViews[value]?.apply {
                    text = if (selected) "✓  $label" else label
                    setTextColor(textColor)
                    setTypeface(
                        typeface,
                        if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                }
            }
            llmSection.visibility = if (backend == "llm") View.VISIBLE else View.GONE
        }

        fun createBackendOption(label: String, value: String) {
            backendLabels[value] = label
            val labelView = TextView(ctx)
            backendTextViews[value] = labelView
            CardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setCardBackgroundColor(bgColor)
                radius = 16f
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(20, 28, 20, 28)
                    addView(labelView)
                })
                setOnClickListener {
                    settings.setString(SETTINGS_KEY_BACKEND, value)
                    refreshBackendSelection()
                }
            }.let { backendRow.addView(it) }
        }

        createBackendOption(strings.settingsBackendGoogle, "google")
        createBackendOption(strings.settingsBackendLLM, "llm")
        addView(backendRow)
        addView(llmSection)

        // 初始化选中状态
        refreshBackendSelection()

        // ── Default language（点击弹出选择，动态更新文字）────────
        addView(sectionLabel(strings.settingsDefaultLanguage, textColor))

        val langRow = TextView(ctx).apply {
            setPadding(0, 15, 0, 15)
            setTextColor(textColor)
            setOnClickListener { showLanguagePicker(strings, this) }
        }

        fun refreshLangRow() {
            val name = languageCodes.entries.firstOrNull { it.value == safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG) }?.key
                ?: safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
            langRow.text = "➜  $name"
        }

        refreshLangRow()
        addView(langRow)
        addView(divider(bgColor))

        // ── Text cleaning toggles ────────────────────────────────
        addView(sectionLabel("Text cleaning", textColor))

        addView(switchRow(strings.settingsCleanHtml, textColor).apply {
            isChecked = settings.getBool(SETTINGS_KEY_CLEAN_HTML, true)
            setOnCheckedChangeListener { _, v -> settings.setBool(SETTINGS_KEY_CLEAN_HTML, v) }
        })
        addView(switchRow(strings.settingsCleanUrl, textColor).apply {
            isChecked = settings.getBool(SETTINGS_KEY_CLEAN_URL, true)
            setOnCheckedChangeListener { _, v -> settings.setBool(SETTINGS_KEY_CLEAN_URL, v) }
        })
        addView(switchRow(strings.settingsCleanEmoji, textColor).apply {
            isChecked = settings.getBool(SETTINGS_KEY_CLEAN_EMOJI, true)
            setOnCheckedChangeListener { _, v -> settings.setBool(SETTINGS_KEY_CLEAN_EMOJI, v) }
        })

        // ── Debug mode ───────────────────────────────────────────
        addView(sectionLabel(strings.settingsDebugMode, textColor))

        addView(switchRow(strings.settingsDebugModeDesc, textColor).apply {
            isChecked = settings.getBool(SETTINGS_KEY_DEBUG_MODE, false)
            setOnCheckedChangeListener { _, v ->
                settings.setBool(SETTINGS_KEY_DEBUG_MODE, v)
                DebugLogger.setEnabled(v)
                Utils.showToast(if (v) "Debug ON → ${DEBUG_LOG_PATH}" else "Debug OFF")
            }
        })

        addView(textRow(strings.settingsDebugLogPath, textColor) {
            Utils.showToast(strings.settingsDebugLogPath)
        })

        addView(button(strings.settingsDebugClearLog, textColor, bgColor) {
            DebugLogger.clearLog()
            Utils.showToast("Log cleared")
        })
    }

    /**
     * 安全读取 String 设置项（使用扩展函数 safeGetString）
     */
    private fun safeGetStr(key: String, default: String = ""): String =
        settings.safeGetString(key, default)

    /**
     * 测试 LLM 连接
     * 注意：不调用任何 String 方法（isBlank, isEmpty 等），
     * 因为 settings.getString() 返回混淆类型，String 方法会崩溃。
     * 直接传给 LLMApiHelper，由它用 String.format 转换。
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
                    Utils.showToast("Test failed: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 获取可用模型列表
     */
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
                    Utils.showToast("Failed to fetch models: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 显示模型选择对话框
     */
    private fun showModelSelectionDialog(models: List<String>, strings: IStrings) {
        try {
            val ctx = requireContext()
            val currentModel = safeGetStr(SETTINGS_KEY_LLM_MODEL)

            // 创建对话框
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsSelectModel)
                .setItems(models.toTypedArray()) { _, which ->
                    val selectedModel = models[which]
                    settings.setString(SETTINGS_KEY_LLM_MODEL, selectedModel)
                    Utils.showToast("Model set to: $selectedModel")
                    // 直接更新模型输入框，无需重建页面
                    modelInput?.setText(selectedModel)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Utils.showToast("Failed to show dialog: ${e.message}")
        }
    }

    /**
     * 显示目标语言选择对话框（单选框列表）
     * 选择后动态更新 langRow 文字，不重建页面
     */
    private fun showLanguagePicker(strings: IStrings, langRow: TextView? = null) {
        try {
            val ctx = requireContext()
            val names = languageCodes.keys.toTypedArray()
            val currentCode = safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
            val currentIndex = languageCodes.values.indexOf(currentCode)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsDefaultLanguage)
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    val code = languageCodes.values.elementAt(which)
                    settings.setString(SETTINGS_KEY_DEFAULT_LANG, code)
                    Utils.showToast(strings.settingsLanguageSaved)
                    dialog.dismiss()
                    // 动态更新语言行文字
                    val name = languageCodes.entries.firstOrNull { it.value == safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG) }?.key
                        ?: safeGetStr(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
                    langRow?.text = "➜  $name"
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Utils.showToast("Failed to show language picker: ${e.message}")
        }
    }

    private fun refreshUI() { /* UI refreshes via the listener callbacks above */ }

    // ── Helper views ─────────────────────────────────────────────

    private fun sectionLabel(text: String, textColor: Int): TextView =
        TextView(requireContext()).apply {
            this.text = text
            setPadding(0, 30, 0, 10)
            setTextColor(textColor)
            textSize = 14f
        }

    private fun switchRow(label: String, textColor: Int): SwitchMaterial =
        SwitchMaterial(requireContext()).apply {
            text = label
            setTextColor(textColor)
            setPadding(0, 12, 0, 12)
        }

    private fun cardRow(text: String, textColor: Int, bgColor: Int): CardView =
        CardView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(40, 40, 40, 40)
            setContentPadding(20, 20, 20, 20)
            setCardBackgroundColor(bgColor)
            radius = 20f
            addView(TextView(context).apply {
                this.text = text
                setTextColor(textColor)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
        }

    private fun settingsInput(
        hint: String,
        key: String,
        textColor: Int,
        bgColor: Int,
        isPassword: Boolean = false
    ): TextInputLayout =
        TextInputLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(0, 12, 0, 12)
            this.hint = hint
            addView(TextInputEditText(context).apply {
                setText(safeGetStr(key))
                setTextColor(textColor)
                if (key == SETTINGS_KEY_LLM_MODEL) modelInput = this
                if (isPassword) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        settings.setString(key, text.toString())
                    }
                }
            })
        }

    private fun button(text: String, textColor: Int, bgColor: Int, onClick: () -> Unit): CardView =
        CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
            setPadding(30, 30, 30, 30)
            setCardBackgroundColor(bgColor)
            radius = 16f
            addView(TextView(context).apply {
                this.text = text
                setTextColor(textColor)
                textSize = 14f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
            setOnClickListener { onClick() }
        }

    private fun textRow(text: String, textColor: Int, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            this.text = text
            setPadding(0, 15, 0, 15)
            setTextColor(textColor)
            setOnClickListener { onClick() }
        }

    private fun divider(bgColor: Int): View =
        View(requireContext()).apply {
            setPadding(0, 6, 0, 6)
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, 2)
            setBackgroundColor(bgColor)
        }
}
