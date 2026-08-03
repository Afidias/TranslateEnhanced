package com.aliucord.plugins.translate

import android.view.View
import android.widget.TextView
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.plugins.translate.utils.getStrings
import com.lytefast.flexinput.R

/**
 * 设置子页面：按频道管理提示词/术语表/缓存。
 * 频道来源 = 已登记配置的频道 ∪ 有缓存条目的频道。
 */
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
                setPadding(24, 24, 24, 24)
            })
            return
        }

        addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = strings.channelConfigsHint
        })

        for (channelId in channels) {
            val configured = ChannelConfig.getPrompt(settings, channelId).isNotEmpty() ||
                ChannelConfig.getGlossaryText(settings, channelId).isNotEmpty()
            val cached = TranslationCache.countForChannel(channelId)
            addView(TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = strings.channelConfigsChannelPrefix + channelId +
                    (if (configured) strings.channelConfigsConfiguredBadge else "") +
                    (if (cached > 0)
                        strings.channelConfigsCachedPrefix + cached + strings.channelConfigsCachedSuffix
                    else "")
                setOnClickListener {
                    ChannelConfigUi.showEditor(ctx, settings, channelId)
                }
            })
        }
    }
}
