package com.aliucord.plugins.translate

import android.content.Context
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.safeIsBlank
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 独立配置存储（解决"手改 JSON 后重启被重置"的问题）。
 *
 * ## 为什么需要它
 *
 * 原实现把后端 / Base URL / API Key / 模型等配置全部存在 Aliucord 的 `SettingsAPI`
 * 里，也就是 `/sdcard/Aliucord/settings/TranslateEnhanced.json`。问题在于：
 *
 * 1. Aliucord 在插件 `load()` 阶段就把设置文件读进内存，之后所有读写都只操作内存副本；
 *    只在「插件重载 / 应用退出」等特定时机才整体写回磁盘。
 * 2. 插件启动时会为一批 key 写入默认值（例如 `backend=google`），
 *    这份内存副本一旦落盘，就会**整体覆盖**用户手动编辑过的文件。
 *
 * 结果就是：手改 JSON → 重启 → 被内存里的默认值冲掉。
 *
 * ## 本类的做法
 *
 * 把配置**同时**镜像一份到插件自己的文件里，这个文件完全由插件掌控：
 *
 * - 位置优先用 `getExternalFilesDir()`（`/sdcard/Android/data/<pkg>/files/`），
 *   这个路径属于应用私有外部目录，**不需要任何存储权限**，Android 11+ 分区存储下依然可读写；
 * - 外部目录不可用时退回 `getFilesDir()`（应用内部私有目录）。
 *
 * 关键保证（对应任务 2 的要求）：
 * - **绝不因为"读不到 / 解析失败 / 值为空"就重置文件**；
 * - 只有在文件**确实不存在**时才写入默认值；
 * - 文件存在但解析失败时，备份成 `.corrupt` 保留现场，再重建。
 */
object ConfigStore {

    private const val FILE_NAME = "TranslateEnhanced.config.json"

    /** Aliucord 的公共数据目录（用户可用文件管理器直接访问）。 */
    private const val LEGACY_BASE_DIR = "/sdcard/Aliucord"

    /** Aliucord 保存插件设置的子目录。 */
    private const val LEGACY_SETTINGS_DIR = LEGACY_BASE_DIR + "/settings"

    private const val BACKUP_SUFFIX = ".corrupt"

    /** 配置文件名（供设置页显示路径用）。 */
    @Volatile
    private var configFile: File? = null

    /** 上一次读取到的原始 JSON（解析失败时为 null）。 */
    @Volatile
    private var cached: JSONObject? = null

    @Volatile
    private var initialized = false

    private val lock = Any()

    // ── 默认值（任务 3：硬编码 DeepSeek 配置）────────────────────

    /** 默认翻译后端：大模型（兼容 OpenAI）。 */
    const val DEFAULT_BACKEND = "llm"

    /** 默认 Base URL：DeepSeek。 */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"

    /** 默认 API Key 占位符，用户需自行替换。 */
    const val DEFAULT_API_KEY = "sk-你的DeepSeek密钥"

    /** 默认模型（deepseek-chat 已下架，改用 flash）。 */
    const val DEFAULT_MODEL = "deepseek-flash"

    // ── 旧 key 兼容表 ────────────────────────────────────────────
    //
    // 用户早期手写配置时用的是另一套 key 名（见设备上的 TranslateEnhanced.json.bak）：
    //   translationBackend / baseUrl / apiKey / model
    // 而源码实际使用的是：
    //   backend / llmBaseUrl / llmApiKey / llmModel
    //
    // key 不匹配会导致"用户写的配置一个都读不到，保存时又被整体覆盖掉"。
    // 这里建立别名映射：读取时任一 key 命中即生效，写入时同步写齐两边，
    // 从而既兼容用户手改的旧格式，也不会再抹掉他们的配置。
    private val KEY_ALIASES: Map<String, List<String>> = mapOf(
        SETTINGS_KEY_BACKEND to listOf(SETTINGS_KEY_BACKEND, SETTINGS_KEY_BACKEND_ALIAS),
        SETTINGS_KEY_LLM_BASE_URL to listOf(SETTINGS_KEY_LLM_BASE_URL, SETTINGS_KEY_LLM_BASE_URL_ALIAS),
        SETTINGS_KEY_LLM_API_KEY to listOf(SETTINGS_KEY_LLM_API_KEY, SETTINGS_KEY_LLM_API_KEY_ALIAS),
        SETTINGS_KEY_LLM_MODEL to listOf(SETTINGS_KEY_LLM_MODEL, SETTINGS_KEY_LLM_MODEL_ALIAS)
    )

    /** 后端取值归一化：旧配置里的 "openai" 等价于源码里的 "llm"。 */
    private fun normalizeBackend(raw: String): String = when (raw) {
        BACKEND_VALUE_OPENAI, BACKEND_VALUE_LLM, "LLM", "OpenAI" -> BACKEND_VALUE_LLM
        BACKEND_VALUE_GOOGLE, "Google", "GOOGLE" -> BACKEND_VALUE_GOOGLE
        else -> raw
    }

    /** 该 key 对应的全部候选名（含旧别名）。 */
    private fun aliasesFor(key: String): List<String> = KEY_ALIASES[key] ?: listOf(key)

    // ── 初始化 ───────────────────────────────────────────────────

    /**
     * 在插件 `load(ctx)` 中调用一次。
     *
     * 只负责确定文件位置并加载；**不会**无条件写盘。
     */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true

            val dir = resolveDir(ctx)
            if (dir == null) {
                DebugLogger.warn("ConfigStore: no writable directory available")
                return
            }
            if (!dir.exists() && !dir.mkdirs()) {
                DebugLogger.warn("ConfigStore: cannot create dir ${dir.absolutePath}")
            }

            val f = File(dir, FILE_NAME)
            configFile = f
            DebugLogger.log("ConfigStore: file = ${f.absolutePath}")

            val existedBefore = f.exists()
            loadLocked(f)
            // 首次创建配置文件时，尝试从 Aliucord settings 目录迁移用户已有配置
            // （包括用户手改过的 .bak），避免要求用户重新填写 API Key。
            if (!existedBefore) tryImportLegacy()
        }
    }

    // ── 旧配置迁移 ───────────────────────────────────────────────

    private const val LEGACY_NAME = "TranslateEnhanced.json"

    /**
     * 从 Aliucord 的 settings 目录导入用户已有配置。
     *
     * 只在插件自有 config.json **首次创建**时执行一次。
     *
     * 读取顺序：`.bak` 优先（用户手改后往往被 Aliucord 备份到这里），再读正式文件。
     * 这些路径在 Android 11+ 上可能因分区存储不可读，读不到就静默跳过——
     * 不会清空任何东西，也不会影响新建的默认配置。
     */
    private fun tryImportLegacy() {
        val candidates = listOf(
            File(LEGACY_SETTINGS_DIR, "$LEGACY_NAME.bak"),
            File(LEGACY_SETTINGS_DIR, LEGACY_NAME)
        )
        val target = cached ?: return
        var imported = 0

        for (src in candidates) {
            try {
                if (!src.exists() || !src.canRead()) {
                    DebugLogger.log("ConfigStore: legacy not readable: ${src.absolutePath}")
                    continue
                }
                val text = src.readText(Charsets.UTF_8)
                if (safeIsBlank(text)) continue

                val obj = JSONObject(text)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    // 已有非默认值的 key 不覆盖，其余从遗留配置补齐
                    val incoming = obj.opt(k) ?: continue
                    if (incoming == JSONObject.NULL) continue
                    if (incoming is String && incoming.isEmpty()) continue
                    target.put(k, incoming)
                    imported++
                }
                DebugLogger.log("ConfigStore: imported $imported keys from ${src.name}")
                // 只认第一个可读的来源，避免后读的覆盖先读的
                if (imported > 0) break
            } catch (e: Exception) {
                DebugLogger.log("ConfigStore: legacy import failed (${src.name}): ${e.message}")
            }
        }

        if (imported > 0) {
            // 迁移完再补齐缺失的内置默认值（DeepSeek 等），保证首次加载即可用
            fillDefaults(target)
            writeLocked(target)
            DebugLogger.warn("ConfigStore: migrated $imported legacy keys into ${configFile?.name}")
        } else {
            // 没有任何可迁移的旧配置：补上内置默认值（DeepSeek）后落盘
            fillDefaults(target)
            writeLocked(target)
            DebugLogger.log("ConfigStore: no legacy config to import, seeded defaults")
        }
    }

    /** 把内置默认值补进 obj（只补缺失的 key，不覆盖已有值）。 */
    private fun fillDefaults(obj: JSONObject) {
        val defaults = defaultJson()
        val keys = defaults.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!obj.has(k)) obj.put(k, defaults.opt(k))
        }
    }

    /**
     * 选目录。
     *
     * 优先级：
     * 1. `/sdcard/Aliucord/` —— 用户熟悉、可用文件管理器直接手改。
     *    Aliucord（宿主）在 Android 13 上持有 READ/WRITE_EXTERNAL_STORAGE
     *    且 MANAGE_EXTERNAL_STORAGE 为 allow，因此这个目录通常可写。
     * 2. `getExternalFilesDir()` —— 应用私有外部目录，无需任何权限，必定可写，
     *    但在 Android 11+ 上普通文件管理器看不到，只适合作为兜底。
     * 3. `getFilesDir()` —— 应用内部私有目录，最后兜底。
     */
    private fun resolveDir(ctx: Context): File? {
        // 1) /sdcard/Aliucord/ —— 先验证确实可写再采用
        try {
            val legacy = File(LEGACY_BASE_DIR)
            if (legacy.exists() && legacy.isDirectory && legacy.canWrite()) {
                // 真实写一个探针文件，canWrite() 在部分 ROM 上不可靠
                val probe = File(legacy, ".te_write_probe")
                val ok = try {
                    probe.writeText("ok", Charsets.UTF_8)
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    try { probe.delete() } catch (_: Exception) { }
                }
                if (ok) {
                    DebugLogger.log("ConfigStore: using ${legacy.absolutePath}")
                    return legacy
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("ConfigStore: legacy dir check failed: ${e.message}")
        }

        // 2) 应用私有外部目录
        val ext = try {
            ctx.getExternalFilesDir(null)
        } catch (e: Exception) {
            DebugLogger.warn("ConfigStore: getExternalFilesDir failed: ${e.message}")
            null
        }
        if (ext != null) {
            if (ext.exists() || ext.mkdirs()) return ext
            DebugLogger.warn("ConfigStore: external dir not usable: ${ext.absolutePath}")
        }

        // 3) 内部私有目录
        return try {
            ctx.filesDir
        } catch (e: Exception) {
            DebugLogger.warn("ConfigStore: getFilesDir failed: ${e.message}")
            null
        }
    }

    /**
     * 加载文件。
     *
     * - 文件不存在 → 写入默认值（这是唯一会"创建"文件的路径）
     * - 文件存在且能解析 → 原样使用，**不做任何覆盖**
     * - 文件存在但解析失败 → 备份为 `.corrupt`，然后重建默认值
     */
    private fun loadLocked(f: File) {
        try {
            if (!f.exists()) {
                DebugLogger.log("ConfigStore: file missing, seeding defaults")
                cached = defaultJson()
                writeLocked(cached!!)
                return
            }

            val text = f.readText(Charsets.UTF_8)
            if (safeIsBlank(text)) {
                // 空文件：保留文件（可能是用户清空了等待填入），不重置
                DebugLogger.warn("ConfigStore: file is empty, keeping as-is")
                cached = JSONObject()
                return
            }

            cached = try {
                JSONObject(text)
            } catch (e: Exception) {
                // 解析失败：备份现场后重建，绝不静默丢数据
                val backup = File(f.absolutePath + BACKUP_SUFFIX)
                try {
                    f.copyTo(backup, overwrite = true)
                    DebugLogger.warn("ConfigStore: parse failed, backed up to ${backup.name}")
                } catch (e2: Exception) {
                    DebugLogger.warn("ConfigStore: backup failed: ${e2.message}")
                }
                val fresh = defaultJson()
                writeLocked(fresh)
                fresh
            }
            DebugLogger.log("ConfigStore: loaded ${cached?.length() ?: 0} keys")
        } catch (e: Exception) {
            DebugLogger.logCrash("ConfigStore.load", e)
        }
    }

    /** 默认配置 JSON（任务 3 的硬编码 DeepSeek 值）。 */
    private fun defaultJson(): JSONObject = JSONObject().apply {
        put(SETTINGS_KEY_BACKEND, DEFAULT_BACKEND)
        put(SETTINGS_KEY_LLM_BASE_URL, DEFAULT_BASE_URL)
        put(SETTINGS_KEY_LLM_API_KEY, DEFAULT_API_KEY)
        put(SETTINGS_KEY_LLM_MODEL, DEFAULT_MODEL)
        put(SETTINGS_KEY_DEFAULT_LANG, DEFAULT_TARGET_LANG)
        put(SETTINGS_KEY_CLEAN_HTML, true)
        put(SETTINGS_KEY_CLEAN_URL, false)
        put(SETTINGS_KEY_CLEAN_EMOJI, true)
        put(SETTINGS_KEY_AUTO_TRANSLATE_SELF, false)
        put(SETTINGS_KEY_DEBUG_MODE, false)
    }

    private fun writeLocked(obj: JSONObject) {
        val f = configFile ?: return
        try {
            f.parentFile?.mkdirs()
            FileOutputStream(f, false).use { out ->
                out.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
            }
            DebugLogger.log("ConfigStore: written ${f.absolutePath}")
        } catch (e: Exception) {
            DebugLogger.logCrash("ConfigStore.write", e)
        }
    }

    // ── 读写 ─────────────────────────────────────────────────────

    /**
     * 读字符串（自动兼容旧 key 别名）。
     *
     * [fallback] 只在"所有别名都查不到"时使用；**不会**因为值为空字符串就替换，
     * 以免用户故意清空某个字段时被默认值填回去。
     */
    fun getString(key: String, fallback: String): String {
        synchronized(lock) {
            val obj = cached ?: return fallback
            for (name in aliasesFor(key)) {
                try {
                    if (!obj.has(name)) continue
                    val v = obj.optString(name, "")
                    // optString 对 JSON null 会返回 "null"，显式挡掉
                    if (v == "null" || v.isEmpty()) continue
                    return if (key == SETTINGS_KEY_BACKEND) normalizeBackend(v) else v
                } catch (e: Exception) {
                    // 换下一个别名继续
                }
            }
            return fallback
        }
    }

    /**
     * 读字符串；值为空/缺失时回落到 [defaultIfEmpty]。
     * 用于 DeepSeek 配置这类"空就等于没配"的场景。
     */
    fun getStringOr(key: String, defaultIfEmpty: String): String {
        val v = getString(key, "")
        return if (safeIsBlank(v)) defaultIfEmpty else v
    }

    /** 该 key（或其任一旧别名）是否存在且非空。 */
    fun has(key: String): Boolean {
        synchronized(lock) {
            val obj = cached ?: return false
            for (name in aliasesFor(key)) {
                try {
                    if (obj.has(name) && !obj.optString(name, "").isEmpty()) return true
                } catch (e: Exception) {
                    // ignore
                }
            }
            return false
        }
    }

    fun getBool(key: String, fallback: Boolean): Boolean {
        synchronized(lock) {
            val obj = cached ?: return fallback
            return try {
                if (!obj.has(key)) fallback else obj.optBoolean(key, fallback)
            } catch (e: Exception) {
                fallback
            }
        }
    }

    /**
     * 写一个 key 并立即落盘。
     *
     * 有别名映射的 key 会**同步写齐所有别名**，这样用户下次手改任意一种写法都能生效，
     * 且插件保存时不会把另一种写法抹掉。
     */
    fun put(key: String, value: Any?) {
        synchronized(lock) {
            val obj = cached ?: JSONObject().also { cached = it }
            try {
                for (name in aliasesFor(key)) {
                    obj.put(name, value ?: JSONObject.NULL)
                }
                writeLocked(obj)
            } catch (e: Exception) {
                DebugLogger.logCrash("ConfigStore.put", e)
            }
        }
    }

    /** 当前配置文件路径（设置页展示 / 排查用）。 */
    fun filePath(): String = configFile?.absolutePath ?: "(unavailable)"

    /** 当前配置文件是否已被初始化。 */
    fun isReady(): Boolean = configFile != null
}
