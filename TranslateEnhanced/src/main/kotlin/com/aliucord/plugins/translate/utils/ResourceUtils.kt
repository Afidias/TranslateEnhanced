package com.aliucord.plugins.translate.utils

import android.content.Context
import com.aliucord.plugins.translate.strings.IStrings
import com.aliucord.plugins.translate.strings.StringsEn
import com.aliucord.plugins.translate.strings.StringsZh

fun Context.getStrings(): IStrings =
    when (resources.configuration.locales[0].language) {
        "zh" -> StringsZh
        else -> StringsEn
    }
