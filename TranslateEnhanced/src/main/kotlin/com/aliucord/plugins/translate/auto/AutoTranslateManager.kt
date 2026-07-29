package com.aliucord.plugins.translate.auto

import com.aliucord.api.SettingsAPI

/**
 * 管理自动翻译的状态：按频道开关、失败计数、暂停逻辑。
 *
 * 频道开关状态持久化在 SettingsAPI 中，key 格式：
 *   autoTranslate_enabled_{channelId}  -> Boolean
 *
 * 运行时失败计数在内存中，不持久化。
 */
class AutoTranslateManager(private val settings: SettingsAPI) {

    private val failCounts = mutableMapOf<Long, Int>()
    private val pausedChannels = mutableSetOf<Long>()

    /**
     * 用户是否对该频道开启了自动翻译。
     */
    fun isEnabled(channelId: Long): Boolean =
        settings.getBool("autoTranslate_enabled_$channelId", false)

    /**
     * 切换某个频道的自动翻译开关。
     */
    fun toggle(channelId: Long): Boolean {
        val now = !isEnabled(channelId)
        settings.setBool("autoTranslate_enabled_$channelId", now)
        if (now) {
            pausedChannels.remove(channelId)
            failCounts.remove(channelId)
        }
        return now
    }

    /**
     * 是否因连续失败而暂停。
     */
    fun isPaused(channelId: Long): Boolean = channelId in pausedChannels

    /**
     * 记录一次翻译失败，返回该频道是否刚被暂停。
     */
    fun recordFailure(channelId: Long, maxFailures: Int = MAX_FAILURES): Boolean {
        if (channelId in pausedChannels) return true
        val count = (failCounts[channelId] ?: 0) + 1
        failCounts[channelId] = count
        if (count >= maxFailures) {
            pausedChannels.add(channelId)
            return true
        }
        return false
    }

    /**
     * 记录一次翻译成功，重置失败计数。
     */
    fun recordSuccess(channelId: Long) {
        failCounts.remove(channelId)
    }

    /**
     * 手动恢复被暂停的频道。
     */
    fun resume(channelId: Long) {
        pausedChannels.remove(channelId)
        failCounts[channelId] = 0
    }

    companion object {
        private const val MAX_FAILURES = 3
    }
}
