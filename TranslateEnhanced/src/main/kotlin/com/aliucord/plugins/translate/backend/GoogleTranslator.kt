package com.aliucord.plugins.translate.backend

import com.aliucord.Http
import com.aliucord.plugins.translate.GOOGLE_TRANSLATE_API_URL
import com.aliucord.plugins.translate.TranslateResult
import com.aliucord.plugins.translate.USER_AGENT
import com.aliucord.plugins.translate.utils.TranslateUnescaper
import org.json.JSONArray

class GoogleTranslator : TranslatorBackend {

    override fun translate(
        text: String,
        sourceLang: String?,
        targetLang: String
    ): TranslateResult {
        val from = sourceLang?.ifEmpty { "auto" } ?: "auto"
        val url = Http.QueryBuilder(GOOGLE_TRANSLATE_API_URL).apply {
            append("client", "gtx")
            append("sl", from)
            append("tl", targetLang)
            append("dt", "t")
            append("q", text)
        }.toString()

        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("User-Agent", USER_AGENT)
                // 20 秒超时，避免请求长时间挂起
                setRequestTimeout(20_000)
            }.execute()
        } catch (e: Exception) {
            return TranslateResult.Error(
                errorText = "Google Translate request failed: ${e.message}"
            )
        }

        if (!response.ok()) {
            return TranslateResult.Error(
                errorCode = response.statusCode,
                errorText = when (response.statusCode) {
                    429 -> "Google Translate rate limited. Try again later."
                    else -> "Google Translate request failed."
                }
            )
        }

        val json: JSONArray
        val translated: String
        try {
            json = JSONArray(response.text())
            val parts = json.getJSONArray(0)
            translated = buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONArray(i).getString(0))
                }
            }.let { TranslateUnescaper.unescape(it) }
        } catch (e: Exception) {
            return TranslateResult.Error(
                errorText = "Google Translate response parse error: ${e.message}"
            )
        }

        return TranslateResult.Success(
            sourceLanguage    = json.optString(2, from),
            translatedLanguage = targetLang,
            sourceText        = text,
            translatedText    = translated
        )
    }
}
