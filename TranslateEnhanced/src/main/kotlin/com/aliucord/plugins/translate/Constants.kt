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

/**
 * 旧版配置 key 别名（用户早期手写 TranslateEnhanced.json 时使用的名字）。
 *
 * 这组名字与源码实际使用的 key 不一致，曾导致"用户写的配置读不到、
 * 保存时又被整体覆盖"的问题。ConfigStore 会同时识别两套名字。
 */
internal const val SETTINGS_KEY_BACKEND_ALIAS        = "translationBackend"
internal const val SETTINGS_KEY_LLM_BASE_URL_ALIAS   = "baseUrl"
internal const val SETTINGS_KEY_LLM_API_KEY_ALIAS    = "apiKey"
internal const val SETTINGS_KEY_LLM_MODEL_ALIAS      = "model"

/** 后端取值别名：旧配置里的 "openai" 等价于 "llm"。 */
internal const val BACKEND_VALUE_OPENAI = "openai"
internal const val BACKEND_VALUE_LLM    = "llm"
internal const val BACKEND_VALUE_GOOGLE = "google"
