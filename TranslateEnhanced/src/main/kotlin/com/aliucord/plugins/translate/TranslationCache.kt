package com.aliucord.plugins.translate

import com.aliucord.plugins.translate.utils.DebugLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * 持久化翻译缓存：写入 /sdcard/Aliucord/translate_cache.json。
 *
 * - 以 messageId 为键，sourceText 校验（消息被编辑后自动失效）
 * - 设置页可清除全部；频道配置对话框可清除指定频道
 * - put() 只标记脏数据，flush() 落盘（批量翻译完成后、手动翻译后、插件停止时调用）
 */
object TranslationCache {
    private const val CACHE_FILE = "/sdcard/Aliucord/translate_cache.json"
    private const val MAX_ENTRIES = 1000

    data class CachedEntry(
        val channelId: Long,
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val translatedLanguage: String
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Long, CachedEntry>()
    private var loaded = false
    private var dirty = false

    /** 命中缓存且原文未变时返回条目，否则 null。 */
    fun get(messageId: Long, sourceText: String): CachedEntry? = synchronized(lock) {
        ensureLoaded()
        val e = entries[messageId]
        if (e != null && e.sourceText == sourceText) e else null
    }

    fun put(messageId: Long, entry: CachedEntry) {
        synchronized(lock) {
            ensureLoaded()
            entries[messageId] = entry
            while (entries.size > MAX_ENTRIES) {
                val it = entries.keys.iterator()
                if (it.hasNext()) {
                    it.next().let { key -> entries.remove(key) }
                } else {
                    break
                }
            }
            dirty = true
        }
    }

    fun clearAll() {
        synchronized(lock) {
            ensureLoaded()
            entries.clear()
            dirty = true
        }
        flush()
    }

    fun clearChannel(channelId: Long) {
        synchronized(lock) {
            ensureLoaded()
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.channelId == channelId) it.remove()
            }
            dirty = true
        }
        flush()
    }

    /** 把脏数据写入文件。 */
    fun flush() {
        synchronized(lock) {
            ensureLoaded()
            if (!dirty) return
            try {
                val arr = JSONArray()
                for ((id, e) in entries) {
                    arr.put(JSONObject().apply {
                        put("messageId", id)
                        put("channelId", e.channelId)
                        put("sourceText", e.sourceText)
                        put("translatedText", e.translatedText)
                        put("sourceLanguage", e.sourceLanguage)
                        put("translatedLanguage", e.translatedLanguage)
                    })
                }
                val file = File(CACHE_FILE)
                file.parentFile?.mkdirs()
                FileWriter(file, false).use { writer ->
                    writer.write(arr.toString())
                }
                dirty = false
                DebugLogger.log("cache flushed: ${entries.size} entries")
            } catch (e: Exception) {
                DebugLogger.log("cache save failed: ${e.message}")
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val file = File(CACHE_FILE)
            if (!file.exists()) return
            val text = file.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("messageId")
                entries[id] = CachedEntry(
                    channelId = o.getLong("channelId"),
                    sourceText = o.getString("sourceText"),
                    translatedText = o.getString("translatedText"),
                    sourceLanguage = o.getString("sourceLanguage"),
                    translatedLanguage = o.getString("translatedLanguage")
                )
            }
            DebugLogger.log("cache loaded: ${entries.size} entries")
        } catch (e: Exception) {
            DebugLogger.log("cache load failed: ${e.message}")
        }
    }
}
