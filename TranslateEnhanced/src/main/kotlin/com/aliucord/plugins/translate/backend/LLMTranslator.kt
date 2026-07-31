package com.aliucord.plugins.translate.backend

import com.aliucord.Http
import com.aliucord.plugins.translate.TranslateResult
import com.aliucord.plugins.translate.USER_AGENT
import com.aliucord.plugins.translate.utils.DebugLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM 翻译后端
 *
 * 注意：构造函数参数类型为 Any 而非 String。
 * Aliucord SettingsAPI.getString() 运行时返回混淆类型 d0.d0.b，
 * 声明为 String 会让 R8 优化掉所有类型转换代码。
 * 用 Any 接收 + String.format() 转换可以绕过 R8 优化。
 */
class LLMTranslator(
    baseUrl: Any,
    apiKey: Any,
    model: Any,
    systemPrompt: Any = DEFAULT_SYSTEM_PROMPT
) : TranslatorBackend {

    private val baseUrlStr: String = String.format("%s", baseUrl)
    private val apiKeyStr: String = String.format("%s", apiKey)
    private val modelStr: String = String.format("%s", model)
    private val systemPromptStr: String = String.format("%s", systemPrompt)

    override fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslateResult {
        // 用 equals 代替 isBlank（equals 是 Object 方法，不会触发 IntIterator 转换）
        if (apiKeyStr == "" || apiKeyStr == "null" ||
            baseUrlStr == "" || baseUrlStr == "null") {
            return TranslateResult.Error(
                errorText = "LLM API key or base URL not configured."
            )
        }

        val userPrompt = buildUserPrompt(text, sourceLang, targetLang)

        DebugLogger.log("[LLM] System prompt: $systemPromptStr")
        DebugLogger.log("[LLM] User prompt: ${userPrompt.take(300)}")

        val requestBody = JSONObject().apply {
            put("model", modelStr)
            put("temperature", 0.0)
            put("max_tokens", 2048)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPromptStr)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val url = buildUrl(baseUrlStr, "chat/completions")
        DebugLogger.log("[LLM] Request URL: $url")
        DebugLogger.log("[LLM] Request body: ${requestBody.toString().take(500)}")

        return try {
            val response = Http.Request(url, "POST").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $apiKeyStr")
                setHeader("User-Agent", USER_AGENT)
            }.executeWithBody(requestBody.toString())

            DebugLogger.log("[LLM] Response status: ${response.statusCode}")
            DebugLogger.log("[LLM] Response body: ${response.text().take(500)}")

            if (!response.ok()) {
                DebugLogger.log("[LLM] Request failed!")
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

            DebugLogger.log("[LLM] Parsed content: $content")
            DebugLogger.log("[LLM] Same as input: ${content == text}")

            TranslateResult.Success(
                sourceLanguage    = sourceLang ?: "auto",
                translatedLanguage = targetLang,
                sourceText        = text,
                translatedText    = content
            )
        } catch (e: Exception) {
            DebugLogger.log("[LLM] Exception: ${e.message}")
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
         * 自动处理 Base URL 是否带 /v1 的情况
         */
        fun buildUrl(baseUrl: String, path: String): String {
            var base = baseUrl.trimEnd('/')
            if (!base.endsWith("/v1")) {
                base += "/v1"
            }
            return "$base/$path"
        }
    }
}
