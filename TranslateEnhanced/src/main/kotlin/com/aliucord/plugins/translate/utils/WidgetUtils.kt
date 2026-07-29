package com.aliucord.plugins.translate.utils

import com.aliucord.CollectionUtils
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.entries.MessageEntry

/**
 * 自实现的 rerenderMessage，直接操作 adapter，不依赖 Aliucord 内部 API。
 * 用于翻译完成后强制刷新单条消息视图。
 */
fun WidgetChatList.forceRerenderMessage(messageId: Long) {
    try {
        val adapter = WidgetChatList.`access$getAdapter$p`(this)
        val data = adapter.internalData
        val idx = CollectionUtils.findIndex(data) { entry ->
            entry is MessageEntry && entry.message.id == messageId
        }
        if (idx != -1) adapter.notifyItemChanged(idx)
    } catch (_: Exception) {
        // 如果内部 API 变动，静默忽略，不影响主流程
    }
}
