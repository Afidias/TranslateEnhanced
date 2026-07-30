package com.aliucord.plugins.translate.backend

import com.aliucord.Http
import com.aliucord.plugins.translate.USER_AGENT
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM API 辅助工具类
 *
 * 注意：Aliucord SettingsAPI.getString() 运行时返回混淆类型 d0.d0.b，
 * 不是真正的 String，所有 String 方法（isBlank, trimEnd 等）都会崩溃。
 * 因此本类中所有参数类型为 Any，不调用任何 String 方法，
 * 靠 try-catch 接住所有异常。
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
        return try {
            // 用 String.format 转换，如果失败会被 catch 接住
            val urlStr = String.format("%s", baseUrl)
            val keyStr = String.format("%s", apiKey)
            val modelStr = String.format("%s", model)

            // 检查是否为空（用 equals 而不是 isBlank，equals 是 Object 方法）
            if (urlStr == "" || keyStr == "") {
                return TestResult.Error(errorText = "API Key or Base URL not configured")
            }

            // 构建 URL（trimEnd 可能崩溃，用 try-catch 保护）
            val cleanUrl = try {
                urlStr.trimEnd('/')
            } catch (e: Exception) {
                urlStr
            }
            // 自动处理是否带 /v1：https://api.openai.com 或 https://api.openai.com/v1 都能正确拼接
            val base = if (cleanUrl.endsWith("/v1")) cleanUrl else "$cleanUrl/v1"
            val url = "$base/chat/completions"

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

            val response = Http.Request(url, "POST").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $keyStr")
                setHeader("User-Agent", USER_AGENT)
            }.executeWithBody(requestBody.toString())

            if (!response.ok()) {
                return TestResult.Error(
                    errorCode = response.statusCode,
                    errorText = "API request failed: ${response.text().take(200)}"
                )
            }

            val json = JSONObject(response.text())
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            TestResult.Success("Connection successful. Model responded: $content")
        } catch (e: Exception) {
            TestResult.Error(errorText = "Connection failed: ${e.message}")
        }
    }

    /**
     * 获取可用模型列表
     * 参数类型为 Any，避免对混淆类型调用 String 方法
     */
    fun fetchModels(baseUrl: Any, apiKey: Any): ModelsResult {
        return try {
            val urlStr = String.format("%s", baseUrl)
            val keyStr = String.format("%s", apiKey)

            if (urlStr == "" || keyStr == "") {
                return ModelsResult.Error(errorText = "API Key or Base URL not configured")
            }

            val cleanUrl = try {
                urlStr.trimEnd('/')
            } catch (e: Exception) {
                urlStr
            }
            val base = if (cleanUrl.endsWith("/v1")) cleanUrl else "$cleanUrl/v1"
            val url = "$base/models"

            val response = Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $keyStr")
                setHeader("User-Agent", USER_AGENT)
            }.execute()

            if (!response.ok()) {
                return ModelsResult.Error(
                    errorCode = response.statusCode,
                    errorText = "Failed to fetch models: ${response.text().take(200)}"
                )
            }

            val json = JSONObject(response.text())
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
        }
    }
}
