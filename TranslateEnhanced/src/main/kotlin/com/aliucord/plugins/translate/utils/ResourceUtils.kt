package com.aliucord.plugins.translate.utils

import android.content.Context
import com.aliucord.api.SettingsAPI
import com.aliucord.plugins.translate.strings.IStrings
import com.aliucord.plugins.translate.strings.StringsEn
import com.aliucord.plugins.translate.strings.StringsZh

fun Context.getStrings(): IStrings =
    when (resources.configuration.locales[0].language) {
        "zh" -> StringsZh
        else -> StringsEn
    }

/**
 * 把任意运行时值转换为真实 String。
 *
 * Aliucord 中部分"String"（如 Message.content、设置值）在运行时是混淆后的
 * CharSequence 包装类（如 d0.d0.b），对它们调用 Kotlin 字符串扩展（isBlank 等）
 * 会在应用侧标准库中触发 IntIterator 强转崩溃。直接调用 toString() 或
 * String.format 也可能被 R8 基于声明类型优化掉。
 *
 * 这里通过 CharSequence 接口的反射方法（length/charAt）逐字符提取：
 * - 反射调用 R8 无法优化，且按运行时类型分派，对真实 String 和混淆包装类都有效
 * - 混淆包装类必定实现 CharSequence（否则应用自身也无法使用）
 */
fun Any?.toRealString(): String {
    if (this == null) return ""
    val inputClass = this.javaClass.name
    val result = try {
        val cs = java.lang.CharSequence::class.java
        val lengthMethod = cs.getMethod("length")
        val charAtMethod = cs.getMethod("charAt", Integer.TYPE)
        val len = lengthMethod.invoke(this) as Int
        val sb = StringBuilder(len.coerceAtLeast(0))
        var i = 0
        while (i < len) {
            sb.append(charAtMethod.invoke(this, i) as Char)
            i++
        }
        sb.toString()
    } catch (e: Exception) {
        // 兜底：反射调用 toString
        try {
            val m = Any::class.java.getMethod("toString")
            val r = m.invoke(this)
            if (r != null) {
                val baos = java.io.ByteArrayOutputStream()
                java.io.PrintStream(baos).print(r)
                baos.toString("UTF-8")
            } else ""
        } catch (e2: Exception) {
            ""
        }
    }
    if (inputClass != "java.lang.String") {
        DebugLogger.logCrash(
            "toRealString-class",
            ClassCastException("input: " + inputClass + ", output: " + result.javaClass.name)
        )
    }
    return result
}

/**
 * 安全读取 String 设置项
 *
 * Aliucord SettingsAPI 的 getString() 声明返回 String，
 * 但运行时实际返回混淆类型 d0.d0.b（不是 String）。
 *
 * R8 会基于声明类型做优化：
 * - .toString() → 优化掉（因为"已经是 String"）
 * - "${}" 字符串模板 → 优化掉
 * - StringBuilder.append() → 优化掉
 *
 * 只有反射调用 toString() R8 无法优化。
 */
/**
 * 安全的空白判断：不调用应用侧 Kotlin 标准库的 CharSequence 扩展（isBlank 等）。
 *
 * 应用内被 R8 处理过的 isBlank 对插件传入的值会触发 IntIterator 强转崩溃，
 * 因此这里只用：
 * - 反射 String.isInstance（R8 无法优化，按运行时类型判断）
 * - String 成员 length/charAt（框架类，行为固定）
 * - Character.isWhitespace（框架 API）
 */
fun safeIsBlank(value: Any?): Boolean {
    return try {
        if (value == null) {
            true
        } else if (!String::class.java.isInstance(value)) {
            DebugLogger.logCrash(
                "safeIsBlank-notString",
                ClassCastException("value class: " + value.javaClass.name)
            )
            true
        } else {
            val s = value as String
            val n = s.length
            if (n == 0) {
                true
            } else {
                var blank = true
                var i = 0
                while (i < n) {
                    if (!Character.isWhitespace(s[i])) {
                        blank = false
                        break
                    }
                    i++
                }
                blank
            }
        }
    } catch (e: Throwable) {
        // 任何意外都按“空”处理并记录，绝不把崩溃抛给调用方
        DebugLogger.logCrash("safeIsBlank-crash", e)
        true
    }
}

fun SettingsAPI.safeGetString(key: String, default: String = ""): String {
    return try {
        val raw = getString(key, default)
        // 通过反射调用 toString()，R8 无法优化反射调用
        val toString = Any::class.java.getMethod("toString")
        val result = toString.invoke(raw)
        // result 是 Any? 类型，R8 无法确定其实际类型，不会优化
        if (result != null) {
            // 用 ByteArrayOutputStream 做二次保险
            val baos = java.io.ByteArrayOutputStream()
            java.io.PrintStream(baos).print(result)
            baos.toString("UTF-8")
        } else {
            default
        }
    } catch (e: Exception) {
        default
    }
}
