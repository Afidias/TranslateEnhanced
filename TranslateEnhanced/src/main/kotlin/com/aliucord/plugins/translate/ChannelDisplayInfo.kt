package com.aliucord.plugins.translate

import com.aliucord.plugins.translate.utils.toRealString
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.isDM
import com.aliucord.wrappers.ChannelWrapper.Companion.isGuild
import com.aliucord.wrappers.ChannelWrapper.Companion.recipients
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils

data class ChannelDisplayInfo(
    val name: String,
    val subtitle: String,
    val iconUri: String?
)

/** Resolves display metadata from Discord's live stores without persisting personal data. */
object ChannelDisplayResolver {
    fun resolve(channelId: Long): ChannelDisplayInfo {
        val channel = StoreStream.getChannels().getChannel(channelId)
            ?: return ChannelDisplayInfo(channelId.toString(), "", null)

        return try {
            val recipients = channel.recipients.orEmpty()
            when {
                channel.isDM() && recipients.size == 1 -> {
                    val user = CoreUser(recipients.first())
                    ChannelDisplayInfo(
                        user.username.toRealString(),
                        "DM · " + channelId,
                        IconUtils.getForUser(user)
                    )
                }
                channel.isDM() -> {
                    var name = channel.name.toRealString()
                    if (name.isEmpty()) {
                        name = recipients.joinToString(", ") { CoreUser(it).username.toRealString() }
                    }
                    ChannelDisplayInfo(
                        if (name.isEmpty()) channelId.toString() else name,
                        "Group DM · " + channelId,
                        IconUtils.getForChannel(channel, 256)
                    )
                }
                channel.isGuild() -> {
                    val guild = StoreStream.getGuilds().getGuild(channel.guildId)
                    val channelName = channel.name.toRealString()
                    ChannelDisplayInfo(
                        if (channelName.isEmpty()) channelId.toString() else "#" + channelName,
                        (guild?.name.toRealString().takeIf { it.isNotEmpty() } ?: "Server") + " · " + channelId,
                        guild?.let { IconUtils.getForGuild(it.id, it.icon, it.icon, false) }
                    )
                }
                else -> ChannelDisplayInfo(channel.name.toRealString().ifEmpty { channelId.toString() }, channelId.toString(), null)
            }
        } catch (e: Throwable) {
            ChannelDisplayInfo(channelId.toString(), "", null)
        }
    }
}
