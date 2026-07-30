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
