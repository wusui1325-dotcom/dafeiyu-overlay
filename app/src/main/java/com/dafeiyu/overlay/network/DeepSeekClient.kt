package com.dafeiyu.overlay.network

import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
}

object DeepSeekClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun normalizedBaseUrl(raw: String): String {
        return raw.trim().trimEnd('/').ifEmpty { AppConfig.DEFAULT_BASE_URL }
    }

    suspend fun fetchModels(apiKey: String, baseUrl: String): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim()
            if (cleanKey.isEmpty()) {
                return@withContext ApiResult.Error("尚未設定 DeepSeek API Key")
            }
            val url = "${normalizedBaseUrl(baseUrl)}/models"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $cleanKey")
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext ApiResult.Error(
                            parseErrorMessage(body, resp.code),
                            resp.code
                        )
                    }
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: JSONArray()
                    val ids = buildList {
                        for (i in 0 until data.length()) {
                            val obj = data.optJSONObject(i) ?: continue
                            val id = obj.optString("id").trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }.distinct()

                    if (ids.isEmpty()) {
                        ApiResult.Success(AppConfig.DEFAULT_PRESET_MODELS)
                    } else {
                        ApiResult.Success(ids)
                    }
                }
            } catch (e: Exception) {
                ApiResult.Error("連線失敗：${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

    suspend fun solveInput(config: AppConfig, base64Jpeg: String): ApiResult<String> {
        val systemPrompt = config.inputSystemPrompt.ifBlank { AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT }
        val userPrompt = "請觀察截圖中需要填寫的輸入框或題目，只輸出應該輸入的最終內容文字，不要任何解釋或額外符號。"
        return callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg).let { result ->
            when (result) {
                is ApiResult.Success -> ApiResult.Success(cleanInputAnswer(result.data))
                is ApiResult.Error -> result
            }
        }
    }

    suspend fun solveChoice(
        config: AppConfig,
        base64Jpeg: String,
        optionCount: Int
    ): ApiResult<List<Int>> {
        val template = config.choiceSystemPrompt.ifBlank { AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT }
        val systemPrompt = template.replace("{COUNT}", optionCount.toString())
        val userPrompt =
            "畫面上共有 $optionCount 個選項，請依「從上往下、從左往右」順序編號 1 至 $optionCount。" +
                "請判斷單選或多選正確答案，只輸出數字編號（多選以英文逗號分隔，例如 1,3）。"
        return when (val result = callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg)) {
            is ApiResult.Success -> {
                val parsed = parseChoiceIndices(result.data, optionCount)
                if (parsed.isEmpty()) {
                    ApiResult.Error("模型回傳無法解析為選項編號：「${result.data.take(60)}」")
                } else {
                    ApiResult.Success(parsed)
                }
            }
            is ApiResult.Error -> result
        }
    }

    private suspend fun callVisionCompletion(
        config: AppConfig,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val cleanKey = config.apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext ApiResult.Error("請先設定 DeepSeek API Key")
        }

        val url = "${normalizedBaseUrl(config.baseUrl)}/chat/completions"

        val userContent = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Jpeg")
                        }
                    )
                }
            )
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", userPrompt)
                }
            )
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                }
            )
        }

        val payload = JSONObject().apply {
            put("model", config.modelId.trim().ifEmpty { AppConfig.DEFAULT_MODEL })
            put("messages", messages)
            put("temperature", config.temperature.toDouble())
            put("max_tokens", config.maxTokens)
            put("stream", false)
            if (config.reasoningEffort != ReasoningEffort.NONE) {
                put("reasoning_effort", config.reasoningEffort.apiValue)
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Error(
                        parseErrorMessage(body, resp.code),
                        resp.code
                    )
                }
                val root = JSONObject(body)
                val choices = root.optJSONArray("choices")
                val first = choices?.optJSONObject(0)
                val message = first?.optJSONObject("message")
                val content = message?.optString("content").orEmpty().trim()
                if (content.isEmpty()) {
                    ApiResult.Error("DeepSeek 回傳空白內容")
                } else {
                    ApiResult.Success(content)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("API 請求錯誤：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            val msg = err?.optString("message").orEmpty().ifBlank { obj.optString("message") }
            if (msg.isNotBlank()) "HTTP $code：$msg" else "HTTP $code 請求失敗"
        } catch (_: Throwable) {
            "HTTP $code 請求失敗"
        }
    }

    private fun cleanInputAnswer(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1)
            }
            text = text.removeSuffix("```").trim()
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) ||
            (text.startsWith("「") && text.endsWith("」"))
        ) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    fun parseChoiceIndices(raw: String, optionCount: Int): List<Int> {
        val cleaned = raw.trim()
        // 1. Try numeric indices first (e.g., "2", "1, 3", "1、3")
        val numbers = Regex("\\d+")
            .findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..optionCount }
            .distinct()
            .toList()
        if (numbers.isNotEmpty()) return numbers

        // 2. Fallback: parse letters A-J if the model answered with letters
        val letters = Regex("[A-Ja-j]")
            .findAll(cleaned)
            .map { it.value.uppercase()[0] - 'A' + 1 }
            .filter { it in 1..optionCount }
            .distinct()
            .toList()
        return letters
    }
}
