package com.aliucord.plugins.translate.backend

import com.aliucord.plugins.translate.USER_AGENT
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM API 辅助工具类
 *
 * 注意：Aliucord SettingsAPI.getString() 运行时返回混淆类型 d0.d0.b，
 * 不是真正的 String，所有 String 方法（isBlank, trimEnd 等）都会崩溃。
 * 因此本类中所有参数类型为 Any，不调用任何 String 方法，
 * 靠 try-catch 接住所有异常。
 *
 * 使用原生 HttpURLConnection 而非 Aliucord Http：
 * - 能正确读取错误流，拿到服务端真实错误信息
 * - 避免 Aliucord Http 在 4xx/5xx 时抛出无意义的 "closed" 错误
 */
object LLMApiHelper {

    sealed class TestResult {
        data class Success(val message: String) : TestResult()
        data class Error(val errorCode: Int? = null, val errorText: String) : TestResult()
    }

    sealed class ModelsResult {
        data class Success(val models: List<String>) : ModelsResult()
        data class Error(val errorCode: Int? = null, val errorText: String) : ModelsResult()
    }

    /**
     * 测试 LLM API 连接
     * 参数类型为 Any，避免对混淆类型调用 String 方法
     */
    fun testConnection(baseUrl: Any, apiKey: Any, model: Any): TestResult {
        var conn: HttpURLConnection? = null
        return try {
            // 用 String.format 转换，如果失败会被 catch 接住
            val urlStr = String.format("%s", baseUrl)
            val keyStr = String.format("%s", apiKey)
            val modelStr = String.format("%s", model)

            // 检查是否为空（用 equals 而不是 isBlank，equals 是 Object 方法）
            if (urlStr == "" || keyStr == "") {
                return TestResult.Error(errorText = "API Key or Base URL not configured")
            }

            val url = LLMTranslator.buildUrl(urlStr, "chat/completions")

            val requestBody = JSONObject().apply {
                put("model", modelStr)
                put("temperature", 0.0)
                put("max_tokens", 50)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Say 'OK' in one word.")
                    })
                })
            }

            val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)

            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $keyStr")
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
            val responseText = if (statusCode in 200..299) {
                readStream(conn.inputStream)
            } else {
                val errBody = conn.errorStream?.let { readStream(it) } ?: ""
                return TestResult.Error(
                    errorCode = statusCode,
                    errorText = "API request failed ($statusCode): ${errBody.take(200)}"
                )
            }

            val json = JSONObject(responseText)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            TestResult.Success("Connection successful. Model responded: $content")
        } catch (e: Exception) {
            TestResult.Error(errorText = "Connection failed: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 获取可用模型列表
     * 参数类型为 Any，避免对混淆类型调用 String 方法
     */
    fun fetchModels(baseUrl: Any, apiKey: Any): ModelsResult {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = String.format("%s", baseUrl)
            val keyStr = String.format("%s", apiKey)

            if (urlStr == "" || keyStr == "") {
                return ModelsResult.Error(errorText = "API Key or Base URL not configured")
            }

            val url = LLMTranslator.buildUrl(urlStr, "models")

            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $keyStr")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 60_000
            conn.readTimeout = 60_000

            val statusCode = conn.responseCode
            val responseText = if (statusCode in 200..299) {
                readStream(conn.inputStream)
            } else {
                val errBody = conn.errorStream?.let { readStream(it) } ?: ""
                return ModelsResult.Error(
                    errorCode = statusCode,
                    errorText = "Failed to fetch models ($statusCode): ${errBody.take(200)}"
                )
            }

            val json = JSONObject(responseText)
            val data = json.getJSONArray("data")
            val models = mutableListOf<String>()

            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i)
                val modelId = model.getString("id")
                models.add(modelId)
            }

            if (models.isEmpty()) {
                ModelsResult.Error(errorText = "No models found in response")
            } else {
                ModelsResult.Success(models.sorted())
            }
        } catch (e: Exception) {
            ModelsResult.Error(errorText = "Failed to fetch models: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
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
}
