package com.aliucord.plugins.translate.strings

interface IStrings {
    val pluginName: String

    val actionTranslate: String
    val actionShowOriginal: String
    val actionToggleAuto: String

    val toastTranslated: String
    val toastAutoPaused: String
    val toastAutoResumed: String
    val toastBackendFallback: String

    val settingsTitle: String
    val settingsBackendLabel: String
    val settingsBackendGoogle: String
    val settingsBackendLLM: String
    val settingsDefaultLanguage: String
    val settingsLanguageSaved: String
    val settingsLLMBaseUrl: String
    val settingsLLMApiKey: String
    val settingsLLMModel: String
    val settingsCleanHtml: String
    val settingsCleanUrl: String
    val settingsCleanEmoji: String
    val settingsSupportedLanguages: String

    val loadingText: String

    // LLM API 测试相关
    val settingsTestConnection: String
    val settingsFetchModels: String
    val settingsTesting: String
    val settingsFetchingModels: String
    val settingsTestSuccess: String
    val settingsTestFailed: String
    val settingsModelsFetched: String
    val settingsNoModelsFound: String
    val settingsSelectModel: String
}
