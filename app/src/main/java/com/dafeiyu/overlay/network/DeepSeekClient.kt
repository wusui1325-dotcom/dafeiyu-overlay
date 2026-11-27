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
        val modelId = config.modelId.trim().ifEmpty { AppConfig.DEFAULT_MODEL }

        // DeepSeek V4 defaults to thinking=high when reasoning_effort is omitted.
        // Always pass reasoning_effort explicitly (including "none"), and ensure sufficient
        // max_tokens headroom whenever thinking mode is enabled so reasoning_content never
        // exhausts the budget before message.content is emitted.
        val isThinkingRequested = config.reasoningEffort != ReasoningEffort.NONE ||
            modelId.contains("reasoner", ignoreCase = true)
        val initialMaxTokens = if (isThinkingRequested) {
            maxOf(config.maxTokens, 2048)
        } else {
            maxOf(config.maxTokens, 512)
        }

        val firstAttempt = executeVisionRequest(
            url = url,
            apiKey = cleanKey,
            modelId = modelId,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            base64Jpeg = base64Jpeg,
            temperature = config.temperature.toDouble(),
            maxTokens = initialMaxTokens,
            reasoningEffort = config.reasoningEffort.apiValue
        )

        when (firstAttempt) {
            is AttemptOutcome.Success -> ApiResult.Success(firstAttempt.content)

            // Some third-party OpenAI-compatible gateways reject unknown "reasoning_effort".
            is AttemptOutcome.UnsupportedReasoningParam -> {
                when (
                    val retry = executeVisionRequest(
                        url = url,
                        apiKey = cleanKey,
                        modelId = modelId,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        base64Jpeg = base64Jpeg,
                        temperature = config.temperature.toDouble(),
                        maxTokens = maxOf(initialMaxTokens, 2048),
                        reasoningEffort = null
                    )
                ) {
                    is AttemptOutcome.Success -> ApiResult.Success(retry.content)
                    is AttemptOutcome.EmptyContent -> ApiResult.Error(formatEmptyError(retry.finishReason))
                    is AttemptOutcome.HttpError -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.UnsupportedReasoningParam -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.NetworkError -> ApiResult.Error(retry.message)
                }
            }

            // If empty content occurred (e.g., finish_reason=length or transient blank output),
            // automatically retry once with reasoning_effort="none" and generous max_tokens.
            is AttemptOutcome.EmptyContent -> {
                val retry = executeVisionRequest(
                    url = url,
                    apiKey = cleanKey,
                    modelId = modelId,
                    systemPrompt = systemPrompt,
                    userPrompt = "$userPrompt\n請務必直接給出最終答案，不要留空。",
                    base64Jpeg = base64Jpeg,
                    temperature = config.temperature.toDouble().coerceAtMost(0.3),
                    maxTokens = maxOf(config.maxTokens, 2048),
                    reasoningEffort = ReasoningEffort.NONE.apiValue
                )
                when (retry) {
                    is AttemptOutcome.Success -> ApiResult.Success(retry.content)
                    is AttemptOutcome.EmptyContent -> ApiResult.Error(formatEmptyError(retry.finishReason))
                    is AttemptOutcome.HttpError -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.UnsupportedReasoningParam -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.NetworkError -> ApiResult.Error(retry.message)
                }
            }

            is AttemptOutcome.HttpError -> ApiResult.Error(firstAttempt.message, firstAttempt.code)
            is AttemptOutcome.NetworkError -> ApiResult.Error(firstAttempt.message)
        }
    }

    private sealed class AttemptOutcome {
        data class Success(val content: String) : AttemptOutcome()
        data class EmptyContent(val finishReason: String) : AttemptOutcome()
        data class UnsupportedReasoningParam(val message: String, val code: Int) : AttemptOutcome()
        data class HttpError(val message: String, val code: Int) : AttemptOutcome()
        data class NetworkError(val message: String) : AttemptOutcome()
    }

    private fun executeVisionRequest(
        url: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String?
    ): AttemptOutcome {
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
            put("model", modelId)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (reasoningEffort != null) {
                put("reasoning_effort", reasoningEffort)
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = parseErrorMessage(body, resp.code)
                    if (resp.code == 400 &&
                        reasoningEffort != null &&
                        body.contains("reasoning_effort", ignoreCase = true)
                    ) {
                        return AttemptOutcome.UnsupportedReasoningParam(errMsg, resp.code)
                    }
                    return AttemptOutcome.HttpError(errMsg, resp.code)
                }

                val root = JSONObject(body)
                val first = root.optJSONArray("choices")?.optJSONObject(0)
                val finishReason = first?.optString("finish_reason").orEmpty()
                val message = first?.optJSONObject("message")
                val content = extractMessageContent(message)

                if (content.isNotEmpty()) {
                    AttemptOutcome.Success(content)
                } else {
                    AttemptOutcome.EmptyContent(finishReason)
                }
            }
        } catch (e: Exception) {
            AttemptOutcome.NetworkError("API 請求錯誤：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun extractMessageContent(message: JSONObject?): String {
        if (message == null || message.isNull("content")) return ""
        val raw = message.opt("content") ?: return ""
        return when (raw) {
            is String -> raw.trim()
            is JSONArray -> {
                buildString {
                    for (i in 0 until raw.length()) {
                        val part = raw.optJSONObject(i) ?: continue
                        if (!part.isNull("text")) {
                            val text = part.optString("text", "")
                            if (text.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(text.trim())
                            }
                        }
                    }
                }.trim()
            }
            else -> ""
        }
    }

    private fun formatEmptyError(finishReason: String): String {
        return if (finishReason.isNotBlank()) {
            "DeepSeek 回傳空白內容 (finish_reason=$finishReason)"
        } else {
            "DeepSeek 回傳空白內容"
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
        val numbers = Regex("\\d+")
            .findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..optionCount }
            .distinct()
            .toList()
        if (numbers.isNotEmpty()) return numbers

        val letters = Regex("[A-Ja-j]")
            .findAll(cleaned)
            .map { it.value.uppercase()[0] - 'A' + 1 }
            .filter { it in 1..optionCount }
            .distinct()
            .toList()
        return letters
    }
}
