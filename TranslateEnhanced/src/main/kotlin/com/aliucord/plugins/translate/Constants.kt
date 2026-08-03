package com.aliucord.plugins.translate

internal const val GOOGLE_TRANSLATE_API_URL =
    "https://translate.googleapis.com/translate_a/single"

internal const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

internal const val DEFAULT_TARGET_LANG = "zh-CN"

internal const val SETTINGS_KEY_DEFAULT_LANG    = "defaultLanguage"
internal const val SETTINGS_KEY_CLEAN_HTML       = "cleanHtml"
internal const val SETTINGS_KEY_CLEAN_URL        = "cleanUrl"
internal const val SETTINGS_KEY_CLEAN_EMOJI      = "cleanEmoji"
internal const val SETTINGS_KEY_BACKEND          = "backend"
internal const val SETTINGS_KEY_LLM_BASE_URL     = "llmBaseUrl"
internal const val SETTINGS_KEY_LLM_API_KEY      = "llmApiKey"
internal const val SETTINGS_KEY_LLM_MODEL        = "llmModel"
internal const val SETTINGS_KEY_LLM_SYSTEM_PROMPT = "llmSystemPrompt"
internal const val SETTINGS_KEY_DEBUG_MODE        = "debugMode"
internal const val SETTINGS_KEY_AUTO_TRANSLATE_SELF = "autoTranslateSelf"

internal const val CMD_NAME = "translate"

internal const val LOG_PATH = "/sdcard/Aliucord/translate.log"
