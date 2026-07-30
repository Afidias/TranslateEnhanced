package com.aliucord.plugins.translate.backend

import com.aliucord.Http
import com.aliucord.plugins.translate.USER_AGENT
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM API 辅助工具类
 * 提供测试连接和获取可用模型列表的功能
 */
object LLMApiHelper {

    /**
     * 测试连接结果
     */
    sealed class TestResult {
        data class Success(val message: String) : TestResult()
        data class Error(val errorCode: Int? = null, val errorText: String) : TestResult()
    }

    /**
     * 获取模型列表结果
     */
    sealed class ModelsResult {
        data class Success(val models: List<String>) : ModelsResult()
        data class Error(val errorCode: Int? = null, val errorText: String) : ModelsResult()
    }

    /**
     * 测试 LLM API 连接
     * 发送一个简单的翻译请求来验证配置是否正确
     */
    fun testConnection(baseUrl: String, apiKey: String, model: String): TestResult {
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            return TestResult.Error(errorText = "API Key or Base URL not configured")
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 50)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Say 'OK' in one word.")
                })
            })
        }

        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"

        return try {
            val response = Http.Request(url, "POST").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $apiKey")
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
     * 调用 /v1/models 端点获取模型列表
     */
    fun fetchModels(baseUrl: String, apiKey: String): ModelsResult {
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            return ModelsResult.Error(errorText = "API Key or Base URL not configured")
        }

        val url = baseUrl.trimEnd('/') + "/v1/models"

        return try {
            val response = Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("Authorization", "Bearer $apiKey")
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
