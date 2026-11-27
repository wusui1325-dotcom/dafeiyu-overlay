package com.dafeiyu.overlay.network

import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.EnvCard
import com.dafeiyu.overlay.system.UiElementSnapshot
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

data class InputSolveResult(
    val finalText: String,
    val rawText: String,
    val substitutedCards: List<String>
)

data class AutoLoopTarget(
    val elementId: Int?,
    val x: Int,
    val y: Int
)

data class AutoLoopItem(
    val type: String, // "input" or "choice"
    val summary: String,
    val answerText: String,
    val targets: List<AutoLoopTarget>
) {
    val sortY: Int get() = targets.firstOrNull()?.y ?: 500
    val sortX: Int get() = targets.firstOrNull()?.x ?: 500
}

data class AutoLoopNextButton(
    val elementId: Int?,
    val x: Int,
    val y: Int,
    val label: String
)

data class AutoLoopAnalysis(
    val shouldContinue: Boolean,
    val stopReason: String,
    val pendingItems: List<AutoLoopItem>,
    val nextButton: AutoLoopNextButton?,
    val keyboardBlocking: Boolean = false,
    val needScrollDown: Boolean = false
)

data class ReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val apkUrl: String?
)

data class AgentAction(
    val type: String, // tap, input, scroll_down, scroll_up
    val elementId: Int?,
    val x: Int,
    val y: Int,
    val text: String,
    val description: String
)

data class AgentCommandResult(
    val actions: List<AgentAction>,
    val replyText: String
)

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

    suspend fun solveInput(config: AppConfig, base64Jpeg: String, pinnedImages: List<String> = emptyList()): ApiResult<InputSolveResult> {
        val baseSystemPrompt = config.inputSystemPrompt.ifBlank { AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT }
        val baseUserPrompt = "請觀察截圖中需要填寫的輸入框或題目，只輸出應該輸入的最終內容文字，不要任何解釋或額外符號。"

        val validCards = config.envCards.filter { it.name.isNotBlank() }
        val finalUserPrompt = baseUserPrompt + buildEnvCardsPromptSuffix(validCards)

        return when (val result = callVisionCompletion(config, baseSystemPrompt, finalUserPrompt, base64Jpeg, pinnedImages)) {
            is ApiResult.Success -> {
                val cleanedRaw = cleanInputAnswer(result.data)
                ApiResult.Success(resolveEnvCards(cleanedRaw, validCards))
            }
            is ApiResult.Error -> result
        }
    }

    private fun buildEnvCardsPromptSuffix(validCards: List<EnvCard>): String {
        if (validCards.isEmpty()) return ""
        val namesList = validCards.joinToString("、") { "[${it.name}]" }
        return "\n\n【本機資料卡片】可用卡片名稱：$namesList。" +
            "若輸入框需要填入上述個人資料，請直接在回覆中使用對應的 [卡片名字] 佔位符（例如 [${validCards.first().name}]），" +
            "系統會於本機自動將 [卡片名字] 替換為實際資料；切勿自行猜測或編造實際值。"
    }

    fun resolveEnvCards(rawText: String, cards: List<EnvCard>): InputSolveResult {
        if (cards.isEmpty()) {
            return InputSolveResult(finalText = rawText, rawText = rawText, substitutedCards = emptyList())
        }
        var replaced = rawText
        val used = mutableListOf<String>()
        cards.sortedByDescending { it.name.length }.forEach { card ->
            val asciiToken = "[${card.name}]"
            val fullWidthToken = "［${card.name}］"
            if (replaced.contains(asciiToken) || replaced.contains(fullWidthToken)) {
                replaced = replaced
                    .replace(asciiToken, card.value)
                    .replace(fullWidthToken, card.value)
                used.add(card.name)
            }
        }
        return InputSolveResult(
            finalText = replaced,
            rawText = rawText,
            substitutedCards = used
        )
    }

    suspend fun solveTranslate(config: AppConfig, base64Jpeg: String): ApiResult<String> {
        val systemPrompt =
            "你是精準的螢幕即時翻譯助手。請辨識圖片中的所有主要文字並自然流暢地翻譯為繁體中文，" +
                "保持原意與簡潔排版，不要輸出多餘解說。"
        val userPrompt = "請將圖片中的文字內容翻譯成繁體中文。"
        return callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg)
    }

    suspend fun solveQuickAsk(config: AppConfig, base64Jpeg: String, pinnedImages: List<String> = emptyList()): ApiResult<String> {
        val systemPrompt =
            "你是精煉高效的螢幕視覺助手。請根據截圖中的題目、文章、報錯或內容，" +
                "以繁體中文直接給出最核心的解答、重點摘要或翻譯。" +
                "請保持簡潔清晰（約 2 至 6 行），避免冗長廢話。"
        val userPrompt = "請快速解析這張截圖的核心內容或直接給出解答。"
        return callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg, pinnedImages)
    }

    suspend fun checkLatestRelease(): ApiResult<ReleaseInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/wusui1325-dotcom/dafeiyu-overlay/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DaFeiYuOverlay-Android")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Error("檢查更新失敗 (HTTP ${resp.code})", resp.code)
                }
                val root = JSONObject(body)
                val tag = root.optString("tag_name").trim()
                val htmlUrl = root.optString("html_url").trim()
                val assets = root.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url").trim().ifEmpty { null }
                            break
                        }
                    }
                }
                if (tag.isEmpty()) {
                    ApiResult.Error("尚未找到正式發佈版本")
                } else {
                    ApiResult.Success(ReleaseInfo(tagName = tag, htmlUrl = htmlUrl, apkUrl = apkUrl))
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("檢查更新連線失敗：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    suspend fun solveChoice(
        config: AppConfig,
        base64Jpeg: String,
        optionCount: Int,
        pinnedImages: List<String> = emptyList()
    ): ApiResult<List<Int>> {
        val template = config.choiceSystemPrompt.ifBlank { AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT }
        val systemPrompt = template.replace("{COUNT}", optionCount.toString())
        val userPrompt =
            "畫面上共有 $optionCount 個選項，請依「從上往下、從左往右」順序編號 1 至 $optionCount。" +
                "請判斷單選或多選正確答案，只輸出數字編號（多選以英文逗號分隔，例如 1,3）。"
        return when (val result = callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg, pinnedImages)) {
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

    /**
     * Natural Language Screen Agent: takes ANY freeform user instruction and returns
     * either executable cross-device GUI actions (tap/input/scroll) and/or a direct answer.
     */
    suspend fun executeFreeformCommand(
        config: AppConfig,
        base64Jpeg: String,
        elements: List<UiElementSnapshot>,
        instruction: String,
        pinnedImages: List<String> = emptyList()
    ): ApiResult<AgentCommandResult> {
        val validCards = config.envCards.filter { it.name.isNotBlank() }
        val elementsText = if (elements.isNotEmpty()) {
            buildString {
                append("\n【無障礙互動元件清單（0..1000 歸一化座標）】\n")
                elements.forEach { el ->
                    append("#${el.id} (${el.kind}) \"${el.label}\" center=(${el.normX},${el.normY})\n")
                }
            }
        } else ""

        val systemPrompt = """
你是 Android 螢幕自由指令 AI 代理人。使用者會下達一句自然語言指令。
請根據截圖與互動元件清單，只輸出嚴格合法的單一 JSON 物件（勿加 Markdown 圍欄）：
{
  "actions": [
    { "type": "tap | input | scroll_down | scroll_up", "element_id": 12, "x": 500, "y": 400, "text": "僅 input 填寫（可用 [卡片名字]）", "description": "簡短步驟說明" }
  ],
  "reply_text": "若使用者要求總結、翻譯、解答或分析，將完整回覆寫於此；若純粹操作介面則留空字串"
}
規則：
- 所有座標為 0..1000 歸一化整數；若對應互動元件清單務必填寫 element_id。
- 若指令只需回答問題／總結／翻譯，actions 回傳空陣列 [] 並將回答寫在 reply_text。
- 若指令要求點擊、填表或滑動，依執行順序填入 actions（最多 8 步）。
        """.trimIndent()

        val userPrompt = "使用者指令：$instruction" + elementsText + buildEnvCardsPromptSuffix(validCards)

        return when (val res = callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg, pinnedImages)) {
            is ApiResult.Success -> parseAgentCommandJson(res.data)
            is ApiResult.Error -> res
        }
    }

    private fun parseAgentCommandJson(raw: String): ApiResult<AgentCommandResult> {
        return try {
            var cleaned = raw.trim()
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.removePrefix("```")
                val nl = cleaned.indexOf('
')
                if (nl >= 0) cleaned = cleaned.substring(nl + 1)
                cleaned = cleaned.removeSuffix("```").trim()
            }
            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) {
                return ApiResult.Success(AgentCommandResult(emptyList(), cleaned))
            }
            val root = JSONObject(cleaned.substring(startIdx, endIdx + 1))
            val reply = if (root.isNull("reply_text")) "" else root.optString("reply_text", "").trim()
            val arr = root.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                for (i in 0 until arr.length().coerceAtMost(8)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val type = obj.optString("type", "").trim().lowercase()
                    if (type !in listOf("tap", "input", "scroll_down", "scroll_up")) continue
                    val elId = if (obj.isNull("element_id")) null else obj.optInt("element_id", -1).takeIf { it > 0 }
                    val x = obj.optInt("x", 500).coerceIn(0, 1000)
                    val y = obj.optInt("y", 500).coerceIn(0, 1000)
                    val text = if (obj.isNull("text")) "" else obj.optString("text", "").trim()
                    val desc = obj.optString("description", type).trim()
                    add(AgentAction(type = type, elementId = elId, x = x, y = y, text = text, description = desc))
                }
            }
            ApiResult.Success(AgentCommandResult(actions = actions, replyText = reply))
        } catch (e: Exception) {
            ApiResult.Error("解析指令回應失敗：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Smart Contextual Reply: reads current chat/email screenshot and generates 3 tailored replies
     * in the chosen tone.
     */
    suspend fun solveSmartReply(
        config: AppConfig,
        base64Jpeg: String,
        tone: String,
        pinnedImages: List<String> = emptyList()
    ): ApiResult<List<String>> {
        val systemPrompt = """
你是頂尖的高情商通訊回覆助手。請閱讀截圖中的聊天對話、留言或電郵上下文，
依照指定語氣生成 3 個不同角度、可直接發送的自然回覆選項（配合截圖語言，預設繁體中文）。
只輸出嚴格合法的 JSON 陣列（3 個字串），例如：["回覆一", "回覆二", "回覆三"]，不要任何解釋或 Markdown。
        """.trimIndent()
        val userPrompt = "請以「$tone」的語氣，針對畫面中的最新訊息生成 3 個可直接發送的回覆。"
        return when (val res = callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg, pinnedImages)) {
            is ApiResult.Success -> {
                val list = parseReplyList(res.data)
                if (list.isEmpty()) ApiResult.Error("無法解析回覆選項")
                else ApiResult.Success(list)
            }
            is ApiResult.Error -> res
        }
    }

    private fun parseReplyList(raw: String): List<String> {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```")
            val nl = cleaned.indexOf('
')
            if (nl >= 0) cleaned = cleaned.substring(nl + 1)
            cleaned = cleaned.removeSuffix("```").trim()
        }
        return try {
            val s = cleaned.indexOf('[')
            val e = cleaned.lastIndexOf(']')
            if (s >= 0 && e > s) {
                val arr = JSONArray(cleaned.substring(s, e + 1))
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }.take(3)
            } else {
                cleaned.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
                    .filter { it.isNotEmpty() }.take(3)
            }
        } catch (_: Throwable) {
            cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        }
    }

    /**
     * Experimental Full Auto-Loop analyzer.
     * Uses normalized 0..1000 coordinates + optional live Accessibility UI element IDs
     * so taps are pixel-accurate across all screen sizes, DPIs, notches, and aspect ratios.
     */
    suspend fun analyzeAutoLoopPage(
        config: AppConfig,
        base64Jpeg: String,
        elements: List<UiElementSnapshot>,
        completedInRound: List<String>,
        roundNumber: Int,
        pinnedImages: List<String> = emptyList()
    ): ApiResult<AutoLoopAnalysis> {
        val validCards = config.envCards.filter { it.name.isNotBlank() }

        val elementsText = if (elements.isNotEmpty()) {
            buildString {
                append("\n【無障礙偵測到的互動元件清單（座標為 0..1000 歸一化值，優先回傳對應 element_id）】\n")
                elements.forEach { el ->
                    val checkedTag = if (el.isChecked) " [已選]" else ""
                    append("#${el.id} (${el.kind})$checkedTag \"${el.label}\" center=(${el.normX},${el.normY}) box=[${el.normLeft},${el.normTop},${el.normRight},${el.normBottom}]\n")
                }
            }
        } else {
            "\n【無障礙元件清單為空，請完全依截圖視覺位置提供精準 0..1000 歸一化座標】\n"
        }

        val completedText = if (completedInRound.isNotEmpty()) {
            "\n【本輪此頁剛完成項目（勿重複列入 pending_items）】\n" +
                completedInRound.joinToString("\n") { "- $it" } + "\n"
        } else {
            ""
        }

        val systemPrompt = """
你是 Android 逐步答題／表單全自動迴圈視覺分析引擎。請仔細觀察截圖與互動元件清單，只輸出嚴格合法的單一 JSON 物件，不要任何 Markdown 圍欄或額外文字。

座標規範（極重要）：
- 所有 x、y 座標皆為 0 到 1000 的整數歸一化座標，(0,0) 為截圖左上角，(1000,1000) 為截圖右下角。
- 若目標對應到上方「互動元件清單」中的某元件，請務必填寫該元件的 "element_id"（整數）並填入其中心 (x, y)；若無對應元件則 element_id 填 null 並精準估算可點擊區域的視覺中心 (x, y)。

JSON 格式：
{
  "should_continue": true,
  "stop_reason": "",
  "keyboard_blocking": false,
  "need_scroll_down": false,
  "pending_items": [
    {
      "type": "input 或 choice",
      "summary": "簡短描述此題或欄位",
      "answer_text": "若為 input 填應輸入文字（可用 [卡片名字]）；choice 留空字串",
      "targets": [
        { "element_id": 12, "x": 500, "y": 420 }
      ]
    }
  ],
  "next_button": { "element_id": 25, "x": 500, "y": 910, "label": "下一題/繼續/提交" }
}

規則：
1. 若畫面已到達完成頁、結算頁、感謝頁或已無任何待填項目也無下一頁／繼續按鈕，設 "should_continue": false 並在 stop_reason 說明原因。
2. pending_items 僅列出「目前頁面上尚未完成」的題目／輸入欄／選項組，並嚴格按「從上往下、從左往右」排序；已填妥或已勾選的項目不要列入。
3. choice 類型的 targets 填入正確答案對應的所有選項位置（單選 1 個，多選可多個）。
4. input 類型的 targets 填入該輸入框的可點擊位置（1 個）。
5. next_button 填入畫面中「下一題 / 下一頁 / 繼續 / 確定 / 提交」按鈕位置；若無則填 null。
6. 若畫面下方有系統軟鍵盤遮擋（可能擋住「繼續／下一題」按鈕或下方欄位），請設 "keyboard_blocking": true 且保持 "should_continue": true，切勿因鍵盤遮擋而誤判結束。
7. 若頁面為長表單、本畫面找不到「下一題／提交」按鈕且下方似乎仍有未顯示內容，請設 "need_scroll_down": true。
        """.trimIndent()

        val userPrompt = "目前為第 $roundNumber 輪迴圈。請分析此頁面並回傳 JSON。" +
            elementsText +
            completedText +
            buildEnvCardsPromptSuffix(validCards)

        return when (val result = callVisionCompletion(config, systemPrompt, userPrompt, base64Jpeg, pinnedImages)) {
            is ApiResult.Success -> parseAutoLoopJson(result.data)
            is ApiResult.Error -> result
        }
    }

    private fun parseAutoLoopJson(raw: String): ApiResult<AutoLoopAnalysis> {
        return try {
            var cleaned = raw.trim()
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.removePrefix("```")
                val nl = cleaned.indexOf('\n')
                if (nl >= 0) cleaned = cleaned.substring(nl + 1)
                cleaned = cleaned.removeSuffix("```").trim()
            }
            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) {
                return ApiResult.Error("迴圈分析回傳非 JSON：「${cleaned.take(60)}」")
            }
            val root = JSONObject(cleaned.substring(startIdx, endIdx + 1))
            val shouldContinue = root.optBoolean("should_continue", true)
            val stopReason = root.optString("stop_reason", "").trim()

            val itemsArr = root.optJSONArray("pending_items") ?: JSONArray()
            val items = buildList {
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val type = obj.optString("type", "").trim().lowercase()
                    if (type != "input" && type != "choice") continue
                    val summary = obj.optString("summary", "").trim().ifEmpty { "未命名項目 #${i + 1}" }
                    val answerText = if (obj.isNull("answer_text")) "" else obj.optString("answer_text", "").trim()

                    val targetsArr = obj.optJSONArray("targets") ?: JSONArray()
                    val targets = buildList {
                        for (j in 0 until targetsArr.length()) {
                            val t = targetsArr.optJSONObject(j) ?: continue
                            val elId = if (t.isNull("element_id")) null else t.optInt("element_id", -1).takeIf { it > 0 }
                            val x = t.optInt("x", -1)
                            val y = t.optInt("y", -1)
                            if (elId != null || (x in 0..1000 && y in 0..1000)) {
                                add(
                                    AutoLoopTarget(
                                        elementId = elId,
                                        x = x.coerceIn(0, 1000),
                                        y = y.coerceIn(0, 1000)
                                    )
                                )
                            }
                        }
                    }
                    if (targets.isNotEmpty()) {
                        add(AutoLoopItem(type = type, summary = summary, answerText = answerText, targets = targets))
                    }
                }
            }.sortedWith(
                // Enforce top-to-bottom (row bucket 35/1000 = 3.5%), left-to-right ordering
                compareBy<AutoLoopItem> { it.sortY / 35 }.thenBy { it.sortX }
            )

            val nextObj = if (root.isNull("next_button")) null else root.optJSONObject("next_button")
            val nextButton = if (nextObj != null) {
                val elId = if (nextObj.isNull("element_id")) null else nextObj.optInt("element_id", -1).takeIf { it > 0 }
                val x = nextObj.optInt("x", -1)
                val y = nextObj.optInt("y", -1)
                val label = nextObj.optString("label", "下一題").trim().ifEmpty { "下一題" }
                if (elId != null || (x in 0..1000 && y in 0..1000)) {
                    AutoLoopNextButton(
                        elementId = elId,
                        x = x.coerceIn(0, 1000),
                        y = y.coerceIn(0, 1000),
                        label = label
                    )
                } else null
            } else null

            ApiResult.Success(
                AutoLoopAnalysis(
                    shouldContinue = shouldContinue,
                    stopReason = stopReason,
                    pendingItems = items,
                    nextButton = nextButton,
                    keyboardBlocking = root.optBoolean("keyboard_blocking", false),
                    needScrollDown = root.optBoolean("need_scroll_down", false)
                )
            )
        } catch (e: Exception) {
            ApiResult.Error("解析迴圈 JSON 失敗：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun callVisionCompletion(
        config: AppConfig,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String,
        pinnedImages: List<String> = emptyList()
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val cleanKey = config.apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext ApiResult.Error("請先設定 DeepSeek API Key")
        }

        val url = "${normalizedBaseUrl(config.baseUrl)}/chat/completions"
        val modelId = config.modelId.trim().ifEmpty { AppConfig.DEFAULT_MODEL }
        val initialMaxTokens = maxOf(config.maxTokens, 1024)

        // Official DeepSeek API contract:
        // "thinking": { "type": "disabled" } explicitly disables deep thinking mode
        // and takes precedence over reasoning_effort across ALL effort tiers.
        val firstAttempt = executeVisionRequest(
            url = url,
            apiKey = cleanKey,
            modelId = modelId,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            base64Jpeg = base64Jpeg,
            pinnedImages = pinnedImages,
            temperature = config.temperature.toDouble(),
            maxTokens = initialMaxTokens,
            reasoningEffort = config.reasoningEffort.apiValue,
            disableThinking = true
        )

        when (firstAttempt) {
            is AttemptOutcome.Success -> ApiResult.Success(firstAttempt.content)

            is AttemptOutcome.UnsupportedThinkingParam -> {
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
                        reasoningEffort = null,
                        disableThinking = false
                    )
                ) {
                    is AttemptOutcome.Success -> ApiResult.Success(retry.content)
                    is AttemptOutcome.EmptyContent -> ApiResult.Error(formatEmptyError(retry.finishReason))
                    is AttemptOutcome.HttpError -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.UnsupportedThinkingParam -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.NetworkError -> ApiResult.Error(retry.message)
                }
            }

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
                    reasoningEffort = "none",
                    disableThinking = true
                )
                when (retry) {
                    is AttemptOutcome.Success -> ApiResult.Success(retry.content)
                    is AttemptOutcome.EmptyContent -> ApiResult.Error(formatEmptyError(retry.finishReason))
                    is AttemptOutcome.HttpError -> ApiResult.Error(retry.message, retry.code)
                    is AttemptOutcome.UnsupportedThinkingParam -> ApiResult.Error(retry.message, retry.code)
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
        data class UnsupportedThinkingParam(val message: String, val code: Int) : AttemptOutcome()
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
        pinnedImages: List<String>,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String?,
        disableThinking: Boolean
    ): AttemptOutcome {
        val userContent = JSONArray().apply {
            pinnedImages.forEach { pinnedB64 ->
                put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$pinnedB64")
                            }
                        )
                    }
                )
            }
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
            val pinNote = if (pinnedImages.isNotEmpty()) {
                "【跨頁參考記憶】前面 ${pinnedImages.size} 張圖片為使用者釘選的參考頁面，最後一張為目前操作畫面，請結合參考頁資訊作答。\n\n"
            } else ""
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", pinNote + userPrompt)
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
            if (disableThinking) {
                put("thinking", JSONObject().apply { put("type", "disabled") })
            }
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
                        (disableThinking || reasoningEffort != null) &&
                        (body.contains("thinking", ignoreCase = true) ||
                            body.contains("reasoning_effort", ignoreCase = true))
                    ) {
                        return AttemptOutcome.UnsupportedThinkingParam(errMsg, resp.code)
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
