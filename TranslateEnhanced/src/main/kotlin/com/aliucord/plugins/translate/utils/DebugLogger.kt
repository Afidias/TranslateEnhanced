package com.aliucord.plugins.translate.utils

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
 */
object DebugLogger {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (enabled) {
            log("========== Debug Mode Enabled ==========")
        }
    }

    fun isEnabled(): Boolean = enabled

    private fun write(level: String, message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] [$level] $message\n"

            val file = File(LOG_PATH)
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer ->
                writer.append(logLine)
            }
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
        try {
            val file = File(LOG_PATH)
            if (file.exists()) file.delete()
        } catch (_: Exception) { }
    }
}
