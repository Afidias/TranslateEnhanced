package com.aliucord.plugins.translate

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.plugins.translate.utils.getStrings
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R

/** Settings sub-page listing every channel with config or cached translations. */
class ChannelConfigsPage(private val settings: SettingsAPI) : SettingsPage() {

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        val ctx = requireContext()
        val strings = ctx.getStrings()
        setActionBarTitle(strings.channelConfigsTitle)

        val channels = (ChannelConfig.getKnownChannels(settings) + TranslationCache.getKnownChannels())
            .distinct()
            .sortedDescending()

        if (channels.isEmpty()) {
            addView(TextView(ctx).apply {
                text = strings.channelConfigsEmpty
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary))
                setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24))
            })
            return
        }

        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = strings.channelConfigsHint
        })

        for (channelId in channels) addView(channelRow(ctx, channelId))
    }

    private fun channelRow(ctx: Context, channelId: Long): View {
        val strings = ctx.getStrings()
        val display = ChannelDisplayResolver.resolve(channelId)
        val configured = ChannelConfig.getPrompt(settings, channelId).isNotEmpty() ||
            ChannelConfig.getGlossaryText(settings, channelId).isNotEmpty()
        val cached = TranslationCache.countForChannel(channelId)
        val status = (if (configured) strings.channelConfigsConfiguredBadge else "") +
            (if (cached > 0) strings.channelConfigsCachedPrefix + cached + strings.channelConfigsCachedSuffix else "")

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)

            addView(SimpleDraweeView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)).apply {
                    marginEnd = dp(ctx, 12)
                }
                MGImages.setRoundingParams(this, dp(ctx, 22).toFloat(), false, null, null, 0f)
                if (display.iconUri != null) setImageURI(display.iconUri)
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = display.name
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderPrimary))
                })
                addView(TextView(ctx).apply {
                    text = display.subtitle + status
                    textSize = 12f
                    setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorHeaderSecondary))
                })
            })

            addView(TextView(ctx).apply {
                text = "›"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), ViewGroup.LayoutParams.MATCH_PARENT)
            })

            setOnClickListener {
                Utils.openPageWithProxy(ctx, ChannelConfigPage(settings, channelId))
            }
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
