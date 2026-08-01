package com.aliucord.plugins.translate.utils

import com.aliucord.plugins.translate.CRASH_LOG_PATH
import com.aliucord.plugins.translate.DEBUG_LOG_PATH
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug 日志工具
 * 将插件运行日志写入文件，用于定位翻译问题
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

    fun log(tag: String, message: String) {
        if (!enabled) return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] [$tag] $message\n"

            val file = File(DEBUG_LOG_PATH)
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer ->
                writer.append(logLine)
            }
        } catch (_: Exception) { }
    }

    fun log(message: String) {
        log("TranslateEnhanced", message)
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
     * 无条件记录崩溃堆栈（不受 debug 开关影响），用于定位无法复现的环境问题。
     */
    fun logCrash(tag: String, e: Throwable) {
        try {
            val file = File(CRASH_LOG_PATH)
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer ->
                val line1 = "[$tag] " + (e.javaClass.simpleName) + ": " + (e.message) + "\n"
                writer.append(line1)
                val stack = e.stackTrace
                if (stack != null) {
                    val limit = Math.min(stack.size, 12)
                    var i = 0
                    while (i < limit) {
                        writer.append("    at " + stack[i].toString() + "\n")
                        i++
                    }
                }
                writer.append("\n")
            }
        } catch (_: Exception) { }
    }

    fun clearLog() {
        try {
            val file = File(DEBUG_LOG_PATH)
            if (file.exists()) file.delete()
        } catch (_: Exception) { }
    }
}
