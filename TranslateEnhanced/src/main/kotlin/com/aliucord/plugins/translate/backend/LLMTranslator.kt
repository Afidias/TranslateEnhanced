package com.aliucord.plugins.translate.backend

import com.aliucord.Http
import com.aliucord.plugins.translate.TranslateResult
import com.aliucord.plugins.translate.USER_AGENT
import org.json.JSONArray
import org.json.JSONObject

class LLMTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) : TranslatorBackend {

    override fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslateResult {
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            return TranslateResult.Error(
                errorText = "LLM API key or base URL not configured."
            )
        }

        val userPrompt = buildUserPrompt(text, sourceLang, targetLang)

        val requestBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 2048)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val url = buildUrl(baseUrl, "chat/completions")

        return try {
            val response = Http.Request(url, "POST").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $apiKey")
                setHeader("User-Agent", USER_AGENT)
            }.executeWithBody(requestBody.toString())

            if (!response.ok()) {
                return TranslateResult.Error(
                    errorCode = response.statusCode,
                    errorText = "LLM API request failed: ${response.text().take(200)}"
                )
            }

            val json  = JSONObject(response.text())
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            TranslateResult.Success(
                sourceLanguage    = sourceLang ?: "auto",
                translatedLanguage = targetLang,
                sourceText        = text,
                translatedText    = content
            )
        } catch (e: Exception) {
            TranslateResult.Error(errorText = "LLM request exception: ${e.message}")
        }
    }

    private fun buildUserPrompt(text: String, sourceLang: String?, targetLang: String): String {
        val langPart = if (sourceLang != null && sourceLang != "auto")
            "from $sourceLang " else ""
        return "Translate ${langPart}to $targetLang. " +
            "Only return the translated text, nothing else.\n\n$text"
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a professional translator. " +
            "Only output the translated text, no explanations."

        /**
         * 构建 API URL
         * 自动处理 Base URL 是否带 /v1 的情况：
         *   https://api.openai.com       → https://api.openai.com/v1/chat/completions
         *   https://api.openai.com/v1    → https://api.openai.com/v1/chat/completions
         *   https://api.openai.com/v1/   → https://api.openai.com/v1/chat/completions
         */
        fun buildUrl(baseUrl: String, path: String): String {
            var base = baseUrl.trimEnd('/')
            // 如果已经以 /v1 结尾，不再重复添加
            if (!base.endsWith("/v1")) {
                base += "/v1"
            }
            return "$base/$path"
        }
    }
}
