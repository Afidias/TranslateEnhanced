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
import com.discord.utilities.color.ColorCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        val ctx = requireContext()
        val strings = ctx.getStrings()
        val textColor = ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary)
        val bgColor = ColorCompat.getThemedColor(ctx, R.b.colorSurface)

        setActionBarTitle(strings.settingsTitle)

        // ── Backend selector ──────────────────────────────────────
        addView(sectionLabel(strings.settingsBackendLabel, textColor))

        val backendGoogle = switchRow(strings.settingsBackendGoogle, textColor).apply {
            isChecked = settings.getString(SETTINGS_KEY_BACKEND, "google") == "google"
            setOnCheckedChangeListener { _, checked ->
                settings.setString(SETTINGS_KEY_BACKEND, if (checked) "google" else "llm")
                refreshUI()
            }
        }
        addView(backendGoogle)

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

        val isLLM = settings.getString(SETTINGS_KEY_BACKEND, "google") == "llm"
        llmSection.visibility = if (isLLM) View.VISIBLE else View.GONE
        addView(llmSection)

        backendGoogle.setOnCheckedChangeListener { _, checked ->
            settings.setString(SETTINGS_KEY_BACKEND, if (checked) "google" else "llm")
            llmSection.visibility = if (checked) View.GONE else View.VISIBLE
        }

        // ── Default language ──────────────────────────────────────
        addView(sectionLabel(strings.settingsDefaultLanguage, textColor))

        val currentLang = languageCodes.entries.firstOrNull { it.value == settings.getString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG) }?.key
            ?: settings.getString(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
        addView(cardRow("Default: $currentLang", textColor, bgColor))

        addView(sectionLabel(strings.settingsSupportedLanguages, textColor))

        languageCodes.forEach { (name, code) ->
            addView(textRow(name, textColor) {
                settings.setString(SETTINGS_KEY_DEFAULT_LANG, code)
                Utils.showToast(strings.settingsLanguageSaved)
                close()
            })
            addView(divider(bgColor))
        }

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
    }

    /**
     * 测试 LLM 连接
     */
    private fun testLLMConnection(strings: IStrings) {
        val baseUrl = settings.getString(SETTINGS_KEY_LLM_BASE_URL, "")
        val apiKey = settings.getString(SETTINGS_KEY_LLM_API_KEY, "")
        val model = settings.getString(SETTINGS_KEY_LLM_MODEL, "gpt-4o-mini")

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            Utils.showToast("Please fill in Base URL and API Key first")
            return
        }

        Utils.showToast(strings.settingsTesting)

        // 在后台线程执行测试
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

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            Utils.showToast("Please fill in Base URL and API Key first")
            return
        }

        Utils.showToast(strings.settingsFetchingModels)

        // 在后台线程执行获取
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
            val currentModel = settings.getString(SETTINGS_KEY_LLM_MODEL, "")

            // 创建对话框
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(strings.settingsSelectModel)
                .setItems(models.toTypedArray()) { _, which ->
                    val selectedModel = models[which]
                    settings.setString(SETTINGS_KEY_LLM_MODEL, selectedModel)
                    Utils.showToast("Model set to: $selectedModel")
                    // 刷新页面以显示新选择的模型
                    try {
                        onViewBound(null)
                    } catch (e: Exception) {
                        // 忽略刷新错误
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Utils.showToast("Failed to show dialog: ${e.message}")
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
                setText(settings.getString(key, ""))
                setTextColor(textColor)
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
