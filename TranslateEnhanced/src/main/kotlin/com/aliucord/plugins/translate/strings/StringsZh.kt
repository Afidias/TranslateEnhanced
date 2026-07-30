package com.aliucord.plugins.translate.strings

object StringsZh : IStrings {
    override val pluginName           = "增强翻译"

    override val actionTranslate      = "翻译此消息"
    override val actionShowOriginal   = "显示原文"
    override val actionToggleAuto     = "自动翻译此频道"

    override val toastTranslated      = "消息已翻译"
    override val toastAutoPaused      = "翻译失败次数过多，已暂停自动翻译。点击恢复。"
    override val toastAutoResumed     = "已恢复自动翻译"
    override val toastBackendFallback = "大模型翻译失败，已自动切换到 Google Translate"

    override val settingsTitle          = "增强翻译"
    override val settingsBackendLabel   = "翻译后端"
    override val settingsBackendGoogle  = "Google Translate"
    override val settingsBackendLLM     = "大模型（兼容 OpenAI）"
    override val settingsDefaultLanguage = "默认目标语言（点击设置）"
    override val settingsLanguageSaved   = "语言已保存"
    override val settingsLLMBaseUrl     = "大模型 Base URL"
    override val settingsLLMApiKey      = "大模型 API Key"
    override val settingsLLMModel       = "大模型名称"
    override val settingsCleanHtml      = "翻译前去除 HTML 标签"
    override val settingsCleanUrl       = "翻译前去除 URL 链接"
    override val settingsCleanEmoji     = "翻译前去除 Emoji 表情"
    override val settingsSupportedLanguages = "支持的语言："

    override val loadingText            = "翻译中..."

    // LLM API 测试相关
    override val settingsTestConnection = "测试连接"
    override val settingsFetchModels    = "获取可用模型"
    override val settingsTesting        = "正在测试连接..."
    override val settingsFetchingModels = "正在获取模型列表..."
    override val settingsTestSuccess    = "连接成功！"
    override val settingsTestFailed     = "连接失败："
    override val settingsModelsFetched  = "模型列表获取成功"
    override val settingsNoModelsFound  = "未找到可用模型"
    override val settingsSelectModel    = "选择模型"
}
