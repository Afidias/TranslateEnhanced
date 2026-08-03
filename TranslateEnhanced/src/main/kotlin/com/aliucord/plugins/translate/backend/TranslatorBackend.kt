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

    /**
     * 批量翻译（自动翻译合并请求用）。
     *
     * 默认实现逐条调用 [translate]；LLM 后端会重写为单次请求批量翻译。
     *
     * @param items 待翻译列表：(序号, 文本)，序号用于把结果映射回原消息
     */
    fun translateBatch(
        items: List<Pair<Int, String>>,
        sourceLang: String?,
        targetLang: String
    ): Map<Int, TranslateResult> =
        items.associate { (id, text) -> id to translate(text, sourceLang, targetLang) }
}
