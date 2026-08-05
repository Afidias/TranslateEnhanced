package com.aliucord.plugins.translate

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.plugins.translate.utils.getStrings
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R

/** Full settings sub-page for one channel's prompt, glossary and cache. */
class ChannelConfigPage(
    private val settings: SettingsAPI,
    private val channelId: Long
) : SettingsPage() {

    private data class TermRow(
        val container: LinearLayout,
        val source: TextInputEditText,
        val target: TextInputEditText
    )

    private val rows = mutableListOf<TermRow>()
    private lateinit var promptInput: TextInputEditText
    private lateinit var glossaryTable: LinearLayout

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        val ctx = requireContext()
        val strings = ctx.getStrings()
        val display = ChannelDisplayResolver.resolve(channelId)
        ChannelConfig.register(settings, channelId)

        setActionBarTitle(display.name)
        addView(channelHeader(ctx, display))
        addView(sectionHeader(ctx, strings.channelConfigPromptSection))

        promptInput = TextInputEditText(ctx).apply {
            setText(ChannelConfig.getPrompt(settings, channelId))
            hint = strings.channelConfigPromptHint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 7
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
            setHintTextColor(ColorStateList.valueOf(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary)))
        }
        addView(TextInputLayout(ctx).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColor(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
            addView(promptInput)
        })

        addView(sectionHeader(ctx, strings.channelConfigGlossarySection))
        addView(TextView(ctx).apply {
            text = strings.channelConfigGlossaryDescription
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary))
            textSize = 13f
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), dp(ctx, 10))
        })

        glossaryTable = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        }
        addView(glossaryTable)
        rebuildGlossary(ctx, ChannelConfig.getGlossary(settings, channelId))

        addView(actionCard(ctx, strings.channelConfigAddTerm) {
            addTermRow(ctx, "", "")
        })

        addView(sectionHeader(ctx, strings.channelConfigActionsSection))
        addView(actionCard(ctx, strings.channelConfigSave) { save() })
        addView(actionCard(ctx, strings.channelConfigGenerate) {
            ChannelConfigUi.generate(ctx, settings, channelId) { prompt, glossary ->
                if (prompt.isNotEmpty()) promptInput.setText(prompt)
                if (glossary.isNotEmpty()) rebuildGlossary(ctx, glossary)
            }
        })
        addView(actionCard(ctx, strings.channelConfigClearCache) {
            TranslationCache.clearChannel(channelId)
            Utils.showToast(strings.channelConfigCacheCleared)
        })
        addView(actionCard(ctx, strings.channelConfigClear, destructive = true) {
            ChannelConfig.clear(settings, channelId)
            promptInput.setText("")
            rebuildGlossary(ctx, emptyList())
            Utils.showToast(strings.channelConfigCleared)
        })
    }

    private fun channelHeader(ctx: Context, display: ChannelDisplayInfo): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))

            addView(SimpleDraweeView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)).apply {
                    marginEnd = dp(ctx, 12)
                }
                MGImages.setRoundingParams(this, dp(ctx, 24).toFloat(), false, null, null, 0f)
                if (display.iconUri != null) setImageURI(display.iconUri)
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = display.name
                    textSize = 17f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                })
                addView(TextView(ctx).apply {
                    text = display.subtitle
                    textSize = 12f
                    setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary))
                })
            })
        }
    }

    private fun rebuildGlossary(ctx: Context, glossary: List<Pair<String, String>>) {
        rows.clear()
        glossaryTable.removeAllViews()
        glossaryTable.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 6))
            addView(tableHeader(ctx, ctx.getStrings().channelConfigTermSource))
            addView(tableHeader(ctx, ctx.getStrings().channelConfigTermTarget))
            addView(TextView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), 1) })
        })
        for (term in glossary) addTermRow(ctx, term.first, term.second)
        if (glossary.isEmpty()) addTermRow(ctx, "", "")
    }

    private fun addTermRow(ctx: Context, sourceText: String, targetText: String) {
        val sourceInput = termInput(ctx, sourceText, ctx.getStrings().channelConfigTermSource)
        val targetInput = termInput(ctx, targetText, ctx.getStrings().channelConfigTermTarget)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
            addView(sourceInput)
            addView(targetInput)
        }
        val termRow = TermRow(row, sourceInput, targetInput)
        row.addView(TextView(ctx).apply {
            text = "×"
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 48))
            setOnClickListener {
                rows.remove(termRow)
                glossaryTable.removeView(row)
                if (rows.isEmpty()) addTermRow(ctx, "", "")
            }
        })
        rows.add(termRow)
        glossaryTable.addView(row)
    }

    private fun termInput(ctx: Context, value: String, hintText: String): TextInputEditText =
        TextInputEditText(ctx).apply {
            setText(value)
            hint = hintText
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
            setHintTextColor(ColorStateList.valueOf(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary)))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 48), 1f).apply {
                marginEnd = dp(ctx, 6)
            }
        }

    private fun tableHeader(ctx: Context, label: String): TextView = TextView(ctx).apply {
        text = label
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(ctx, 6)
        }
    }

    private fun sectionHeader(ctx: Context, label: String): TextView =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply { text = label }

    private fun actionCard(
        ctx: Context,
        label: String,
        destructive: Boolean = false,
        action: () -> Unit
    ): CardView = CardView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        radius = dp(ctx, 8).toFloat()
        setCardBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary))
        setContentPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        addView(TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(
                if (destructive) ColorCompat.getThemedColor(ctx, R.b.colorTextDanger)
                else ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary)
            )
        })
        setOnClickListener { action() }
    }

    private fun save() {
        val glossaryText = rows.mapNotNull {
            val source = it.source.text.toString().trim()
            val target = it.target.text.toString().trim()
            if (source.isNotEmpty() && target.isNotEmpty()) source + "=" + target else null
        }.joinToString("\n")
        ChannelConfig.setPrompt(settings, channelId, promptInput.text.toString())
        ChannelConfig.setGlossary(settings, channelId, glossaryText)
        Utils.showToast(requireContext().getStrings().channelConfigSaved)
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
