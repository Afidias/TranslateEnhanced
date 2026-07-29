package com.aliucord.plugins.translate.backend

import com.aliucord.plugins.translate.TranslateResult

interface TranslatorBackend {
    /**
     * 翻译一段文本
     * @param text      原文
     * @param sourceLang 源语言代码，null 或 "auto" 表示自动检测
     * @param targetLang 目标语言代码
     * @return TranslateResult.Success 或 TranslateResult.Error
     */
    fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslateResult
}
