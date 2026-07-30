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
 * Aliucord SettingsAPI 运行时返回混淆类型，不是真正的 String
 * .toString() 和字符串模板 ${} 都会被 R8 优化掉
 * 必须用 StringBuilder.append() 强制转换，R8 无法优化
 */
fun SettingsAPI.safeGetString(key: String, default: String = ""): String {
    return try {
        val raw: Any? = getString(key, default)
        StringBuilder().append(raw).toString()
    } catch (e: Exception) {
        default
    }
}
