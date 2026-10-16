package com.aliucord.plugins.translate.utils

import android.content.Context
import com.aliucord.plugins.translate.LOG_PATH
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug 日志工具
 *
 * 统一日志系统：所有日志（调试/警告/崩溃）写入同一个文件 [LOG_PATH]。
 * - DEBUG/INFO 级别仅在开启 Debug Mode 时写入
 * - WARN/ERROR 级别无条件写入（崩溃日志不会因开关而丢失）
 *
 * ## 双写入（Android 11+ 兼容）
 *
 * Android 11 起分区存储收紧，`/sdcard/Aliucord/` 是否可写取决于宿主应用
 * 是否持有 `MANAGE_EXTERNAL_STORAGE`。为了让崩溃日志在任何情况下都拿得到，
 * 日志**优先写入应用私有外部目录**（`Android/data/<pkg>/files/`，无需任何权限），
 * 同时尽力再写一份到 `/sdcard/Aliucord/translate.log` 保持对用户的可见性。
 *
 * 私有目录日志可用下面命令取出：
 * `adb pull /sdcard/Android/data/com.discord/files/translate.log`
 */
object DebugLogger {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var enabled = false

    /** 私有目录日志文件（主写入目标，null 表示尚未初始化）。 */
    @Volatile
    private var privateLogFile: File? = null

    /** 当前实际使用的日志路径（供设置页展示）。 */
    @Volatile
    private var activePath: String = LOG_PATH

    /**
     * 初始化日志目录。应在插件 `load(ctx)` 中尽早调用。
     *
     * 探测顺序：
     * 1. `/sdcard/Aliucord/translate.log` —— 用户熟悉、可用文件管理器直接取；
     * 2. 应用私有外部目录 `Android/data/<pkg>/files/translate.log` —— 无需权限。
     *
     * 两份日志始终**双写**，任一路径失败不影响另一路。
     */
    fun init(ctx: Context) {
        // 私有目录（兜底，必定可写）
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                privateLogFile = File(dir, "translate.log")
            }
        } catch (e: Exception) {
            // ignore
        }

        // /sdcard 路径可写性探测：决定设置页展示哪个路径
        val legacyWritable = try {
            val f = File(LOG_PATH)
            f.parentFile?.mkdirs()
            val probe = File(f.parentFile, ".te_log_probe")
            val ok = try {
                probe.writeText("ok", Charsets.UTF_8)
                true
            } catch (e: Exception) {
                false
            } finally {
                try { probe.delete() } catch (_: Exception) { }
            }
            ok
        } catch (e: Exception) {
            false
        }

        activePath = if (legacyWritable) LOG_PATH else (privateLogFile?.absolutePath ?: LOG_PATH)
    }

    /** 当前日志文件路径（设置页显示 / 排障用）。 */
    fun currentPath(): String = activePath

    fun setEnabled(value: Boolean) {
        enabled = value
        if (enabled) {
            log("========== Debug Mode Enabled ==========")
        }
    }

    fun isEnabled(): Boolean = enabled

    private fun write(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$level] $message\n"

        // 主：私有目录（无需权限，必定可写）
        privateLogFile?.let { f ->
            try {
                f.parentFile?.mkdirs()
                FileWriter(f, true).use { writer -> writer.append(logLine) }
            } catch (_: Exception) { }
        }

        // 备：/sdcard/Aliucord/translate.log（可能因分区存储失败，失败无妨）
        try {
            val file = File(LOG_PATH)
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer -> writer.append(logLine) }
        } catch (_: Exception) { }
    }

    fun log(tag: String, message: String) {
        if (enabled) write("DEBUG", "[$tag] $message")
    }

    fun log(message: String) {
        log("TranslateEnhanced", message)
    }

    /** 警告级别，无条件写入。 */
    fun warn(message: String) {
        write("WARN", message)
    }

    fun logTranslation(
        sourceText: String,
        cleanedText: String,
        sourceLang: String?,
        targetLang: String,
        backend: String,
        resultType: String,
        translatedText: String,
        errorText: String? = null
    ) {
        if (!enabled) return
        log("=== Translation Start ===")
        log("Source: $sourceText")
        log("Cleaned: $cleanedText")
        log("SourceLang: ${sourceLang ?: "auto"}")
        log("TargetLang: $targetLang")
        log("Backend: $backend")
        log("Result: $resultType")
        if (errorText != null) {
            log("Error: $errorText")
        } else {
            log("Translated: $translatedText")
            log("SameAsSource: ${sourceText == translatedText}")
            log("SameAsCleaned: ${cleanedText == translatedText}")
        }
        log("=== Translation End ===")
    }

    /**
     * 无条件记录崩溃堆栈（ERROR 级别，不受 debug 开关影响），
     * 与调试日志统一写入 translate.log，用于定位无法复现的环境问题。
     */
    fun logCrash(tag: String, e: Throwable) {
        write("ERROR", "[$tag] " + e.javaClass.simpleName + ": " + e.message)
        val stack = e.stackTrace
        if (stack != null) {
            val limit = Math.min(stack.size, 12)
            var i = 0
            while (i < limit) {
                write("ERROR", "    at " + stack[i].toString())
                i++
            }
        }
        write("ERROR", "")
    }

    fun clearLog() {
        // 清私有目录日志
        try {
            privateLogFile?.let { f -> if (f.exists()) f.delete() }
        } catch (_: Exception) { }
        // 清 /sdcard 上的日志（若可写）
        try {
            val file = File(LOG_PATH)
            if (file.exists()) file.delete()
        } catch (_: Exception) { }
    }
}
