package com.aliucord.plugins.translate.backend

import com.aliucord.plugins.translate.TranslateResult
import com.aliucord.plugins.translate.USER_AGENT
import com.aliucord.plugins.translate.utils.DebugLogger
import com.aliucord.plugins.translate.utils.toRealString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM 翻译后端
 *
 * 注意：构造函数参数类型为 Any 而非 String。
 * Aliucord SettingsAPI.getString() 运行时返回混淆类型 d0.d0.b，
 * 声明为 String 会让 R8 优化掉所有类型转换代码。
 * 用 Any 接收 + String.format() 转换可以绕过 R8 优化。
 *
 * 使用原生 HttpURLConnection 而非 Aliucord Http：
 * - Aliucord Http 在服务器返回 4xx/5xx 时读 getInputStream() 失败，
 *   抛出无意义的 "closed" 错误，掩盖了真实错误原因
 * - 原生 HttpURLConnection 能正确读取错误流拿到真实错误信息
 */
class LLMTranslator(
    baseUrl: Any,
    apiKey: Any,
    model: Any,
    systemPrompt: Any = DEFAULT_SYSTEM_PROMPT,
    forceRetranslate: Boolean = false
) : TranslatorBackend {

    private val baseUrlStr: String = baseUrl.toRealString()
    private val apiKeyStr: String = apiKey.toRealString()
    private val modelStr: String = model.toRealString()
    private val systemPromptStr: String = systemPrompt.toRealString()
    private val forceFlag: Boolean = forceRetranslate

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
            // 降低 max_tokens，某些服务商对 2048 有限制
            put("max_tokens", 1024)
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

        val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)

        // 失败自动重试一次
        var lastError: Exception? = null
        var lastStatusCode = -1
        var lastErrorText = ""

        for (attempt in 0..1) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKeyStr")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 120_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bodyBytes.size)

                conn.outputStream.use { out ->
                    out.write(bodyBytes)
                    out.flush()
                }

                val statusCode = conn.responseCode
                DebugLogger.log("[LLM] Attempt ${attempt + 1} status: $statusCode")

                val responseText = if (statusCode in 200..299) {
                    readStream(conn.inputStream)
                } else {
                    // 错误流：拿到真实错误信息
                    val errBody = conn.errorStream?.let { readStream(it) } ?: ""
                    DebugLogger.log("[LLM] Attempt ${attempt + 1} error body: ${errBody.take(300)}")
                    lastStatusCode = statusCode
                    lastErrorText = errBody.take(300)
                    if (attempt == 0) {
                        // 短暂等待后重试
                        try { Thread.sleep(1000) } catch (ie: InterruptedException) { }
                        continue
                    }
                    return TranslateResult.Error(
                        errorCode = lastStatusCode,
                        errorText = "LLM API request failed ($statusCode): $lastErrorText"
                    )
                }

                DebugLogger.log("[LLM] Response body: ${responseText.take(500)}")

                val json  = JSONObject(responseText)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                if (content.isEmpty()) {
                    DebugLogger.log("[LLM] Empty response content, treating as error")
                    return TranslateResult.Error(errorText = "LLM returned an empty response.")
                }

                DebugLogger.log("[LLM] Parsed content: $content")
                DebugLogger.log("[LLM] Same as input: ${content == text}")

                return TranslateResult.Success(
                    sourceLanguage    = sourceLang ?: "auto",
                    translatedLanguage = targetLang,
                    sourceText        = text,
                    translatedText    = content
                )
            } catch (e: Exception) {
                lastError = e
                DebugLogger.log("[LLM] Attempt ${attempt + 1} exception: ${e.message}")
                if (attempt == 0) {
                    // 短暂等待后重试
                    try { Thread.sleep(1000) } catch (ie: InterruptedException) { }
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }

        DebugLogger.log("[LLM] All attempts failed")
        return TranslateResult.Error(errorText = "LLM request exception: ${lastError?.message}")
    }

    /**
     * 批量翻译（自动翻译用）：把多条消息合并成一次 LLM 请求。
     *
     * 请求格式：JSON 数组 [{id, text}]；要求模型返回 {"translations":[{"id","text"}]}。
     * 整批失败时每条都返回 Error，由调用方逐条降级 Google。
     */
    override fun translateBatch(
        items: List<Pair<Int, String>>,
        sourceLang: String?,
        targetLang: String
    ): Map<Int, TranslateResult> {
        if (items.isEmpty()) return emptyMap()
        if (apiKeyStr == "" || apiKeyStr == "null" ||
            baseUrlStr == "" || baseUrlStr == "null") {
            val err = TranslateResult.Error(errorText = "LLM API key or base URL not configured.")
            return items.associate { (id, _) -> id to err }
        }

        val payload = JSONArray().apply {
            items.forEach { (id, text) ->
                put(JSONObject().apply {
                    put("id", id)
                    put("text", text)
                })
            }
        }
        val userPrompt = buildBatchPrompt(payload.toString(), sourceLang, targetLang)
        DebugLogger.log("[LLM] Batch items: ${items.size}, total chars: ${items.sumBy { it.second.length }}")

        val requestBody = JSONObject().apply {
            put("model", modelStr)
            put("temperature", 0.0)
            // 批量输出更大，放宽 token 上限（部分服务商仍限制，失败会逐条降级 Google）
            put("max_tokens", 4096)
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
        val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)

        var lastError: Exception? = null
        var lastStatusCode = -1
        var lastErrorText = ""

        for (attempt in 0..1) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKeyStr")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 120_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bodyBytes.size)

                conn.outputStream.use { out ->
                    out.write(bodyBytes)
                    out.flush()
                }

                val statusCode = conn.responseCode
                DebugLogger.log("[LLM] Batch attempt ${attempt + 1} status: $statusCode")

                if (statusCode !in 200..299) {
                    val errBody = conn.errorStream?.let { readStream(it) } ?: ""
                    DebugLogger.log("[LLM] Batch attempt ${attempt + 1} error body: ${errBody.take(300)}")
                    lastStatusCode = statusCode
                    lastErrorText = errBody.take(300)
                    if (attempt == 0) {
                        // 429 限流等待更久，其余等待 1s 后重试一次
                        try {
                            Thread.sleep(if (statusCode == 429) 2000 else 1000)
                        } catch (ie: InterruptedException) { }
                        continue
                    }
                    val err = TranslateResult.Error(
                        errorCode = lastStatusCode,
                        errorText = "LLM API request failed ($statusCode): $lastErrorText"
                    )
                    return items.associate { (id, _) -> id to err }
                }

                val responseText = readStream(conn.inputStream)
                val json = JSONObject(responseText)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                DebugLogger.log("[LLM] Batch response: ${content.take(500)}")

                val parsed = parseBatchResponse(content, items, sourceLang, targetLang)
                if (parsed.isNotEmpty()) {
                    return parsed
                }
                lastError = Exception("batch response not in expected JSON format")
            } catch (e: Exception) {
                lastError = e
                DebugLogger.log("[LLM] Batch attempt ${attempt + 1} exception: ${e.message}")
                if (attempt == 0) {
                    try { Thread.sleep(1000) } catch (ie: InterruptedException) { }
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }

        DebugLogger.log("[LLM] Batch all attempts failed: status=$lastStatusCode err=$lastErrorText msg=${lastError?.message}")
        val err = if (lastStatusCode != -1)
            TranslateResult.Error(errorCode = lastStatusCode, errorText = "LLM API request failed ($lastStatusCode): $lastErrorText")
        else
            TranslateResult.Error(errorText = "LLM request exception: ${lastError?.message}")
        return items.associate { (id, _) -> id to err }
    }

    private fun parseBatchResponse(
        content: String,
        items: List<Pair<Int, String>>,
        sourceLang: String?,
        targetLang: String
    ): Map<Int, TranslateResult> {
        val map = mutableMapOf<Int, TranslateResult>()
        val json = JSONObject(stripCodeFence(content))
        val arr = json.getJSONArray("translations")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getInt("id")
            val text = o.getString("text").trim()
            if (text.isEmpty()) continue
            map[id] = TranslateResult.Success(
                sourceLanguage = sourceLang ?: "auto",
                translatedLanguage = targetLang,
                sourceText = items.firstOrNull { it.first == id }?.second ?: "",
                translatedText = text
            )
        }
        items.forEach { (id, _) ->
            if (!map.containsKey(id)) {
                map[id] = TranslateResult.Error(errorText = "LLM batch missing translation for item $id")
            }
        }
        return map
    }

    /** 去掉模型可能夹带的 ```json 代码块包裹。 */
    private fun stripCodeFence(s: String): String {
        var r = s.trim()
        if (r.startsWith("```")) {
            val nl = r.indexOf('\n')
            r = if (nl != -1) r.substring(nl + 1) else r.substring(3)
            if (r.endsWith("```")) r = r.dropLast(3)
        }
        return r.trim()
    }

    private fun readStream(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val sb = StringBuilder()
        reader.use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                sb.append(line)
            }
        }
        return sb.toString()
    }

    private fun buildUserPrompt(text: String, sourceLang: String?, targetLang: String): String {
        val langPart = if (sourceLang != null && sourceLang != "auto")
            "from $sourceLang " else ""
        // 用户主动点击"重新翻译"时，提醒模型不要原样回显
        val forceNote = if (forceFlag)
            " Please translate the text even if the previous output was unchanged; " +
            "do not echo the source text back. If the source is already in the target language, " +
            "return it unchanged." else ""
        return "Translate ${langPart}to $targetLang. " +
            "Only return the translated text, nothing else. " +
            "Do not translate URLs (http/https links); preserve them verbatim. " +
            "Preserve any placeholder tokens like [[URL_0]], [[EMOJI_0]], [[TAG_0]] " +
            "exactly as they appear." +
            forceNote + "\n\n$text"
    }

    private fun buildBatchPrompt(messagesJson: String, sourceLang: String?, targetLang: String): String {
        val langPart = if (sourceLang != null && sourceLang != "auto")
            "from $sourceLang " else ""
        return "Translate the following messages ${langPart}to $targetLang. " +
            "Do not translate URLs (http/https links); preserve them verbatim. " +
            "Preserve any placeholder tokens like [[URL_0]], [[EMOJI_0]], [[TAG_0]] exactly as they appear. " +
            "Return ONLY a JSON object: " +
            "{\"translations\":[{\"id\":<original id>,\"text\":\"<translated text>\"}]}. " +
            "Do not add any explanations or code fences.\n\nMessages:\n$messagesJson"
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
