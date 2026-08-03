package com.aliucord.plugins.translate.strings

interface IStrings {
    val pluginName: String

    val actionTranslate: String
    val actionShowOriginal: String
    val actionRetranslate: String
    val actionToggleAuto: String
    val actionDisableAuto: String
    val actionResumeAuto: String
    val actionChannelConfig: String

    val toastTranslated: String
    val toastAutoPaused: String
    val toastAutoResumed: String
    val toastAutoDisabled: String
    val toastAutoBatchStart: String
    val toastAutoBatchRequesting: String
    val toastAutoBatchRequestingEnd: String
    val toastAutoBatchDonePrefix: String
    val toastAutoBatchDoneSuffix: String
    val toastAutoBatchDoneFallbackPrefix: String
    val toastAutoBatchDoneFallbackMid: String
    val toastBackendFallback: String
    val toastTranslateFailed: String
    val toastTranslateError: String

    val settingsTitle: String
    val settingsBackendLabel: String
    val settingsSectionLLM: String
    val settingsSectionAuto: String
    val settingsSectionDebug: String
    val settingsBackendGoogle: String
    val settingsBackendLLM: String
    val settingsDefaultLanguage: String
    val settingsLanguageSaved: String
    val settingsLLMBaseUrl: String
    val settingsLLMApiKey: String
    val settingsLLMModel: String
    val settingsCleanHtml: String
    val settingsCleanUrl: String
    val settingsCleanUrlDesc: String
    val settingsCleanEmoji: String
    val settingsAutoTranslateSelf: String
    val settingsAutoTranslateSelfDesc: String
    val settingsCacheSection: String
    val settingsCacheInfo: String
    val settingsCacheClearAll: String
    val settingsCacheCleared: String
    val settingsSupportedLanguages: String
    val settingsCleaningLabel: String
    val settingsCancel: String

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
    val settingsTestError: String
    val settingsFetchModelsError: String
    val settingsDialogError: String
    val settingsModelSet: String

    // Debug mode
    val settingsDebugMode: String
    val settingsDebugModeDesc: String
    val settingsDebugClearLog: String
    val settingsDebugLogPath: String
    val settingsDebugOn: String
    val settingsDebugOff: String
    val settingsLogCleared: String

    // 频道翻译配置
    val channelConfigTitle: String
    val channelConfigPromptHint: String
    val channelConfigGlossaryHint: String
    val channelConfigGenerate: String
    val channelConfigSave: String
    val channelConfigClear: String
    val channelConfigClearCache: String
    val channelConfigSaved: String
    val channelConfigCleared: String
    val channelConfigGenerating: String
    val channelConfigGenerated: String
    val channelConfigGenerateFailed: String
    val channelConfigCacheCleared: String
}
