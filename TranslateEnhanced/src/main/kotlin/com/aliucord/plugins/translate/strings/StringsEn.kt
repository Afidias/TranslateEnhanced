package com.aliucord.plugins.translate.strings

object StringsEn : IStrings {
    override val pluginName           = "Translate Enhanced"

    override val actionTranslate      = "Translate message"
    override val actionShowOriginal   = "Show original"
    override val actionToggleAuto     = "Auto translate this channel"

    override val toastTranslated      = "Message translated"
    override val toastAutoPaused      = "Auto translate paused due to failures. Tap to resume."
    override val toastAutoResumed     = "Auto translate resumed"
    override val toastBackendFallback = "LLM failed, falling back to Google Translate"

    override val settingsTitle          = "Translate Enhanced"
    override val settingsBackendLabel   = "Translation backend"
    override val settingsBackendGoogle  = "Google Translate"
    override val settingsBackendLLM     = "LLM (OpenAI compatible)"
    override val settingsDefaultLanguage = "Default target language (tap to set)"
    override val settingsLanguageSaved   = "Language saved"
    override val settingsLLMBaseUrl     = "LLM Base URL"
    override val settingsLLMApiKey      = "LLM API Key"
    override val settingsLLMModel       = "LLM Model name"
    override val settingsCleanHtml      = "Clean HTML tags before translating"
    override val settingsCleanUrl       = "Clean URLs before translating"
    override val settingsCleanEmoji     = "Clean emoji before translating"
    override val settingsSupportedLanguages = "Supported languages:"

    override val loadingText            = "Translating..."
}
