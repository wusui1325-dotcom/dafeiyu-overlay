package com.dafeiyu.overlay.data

import java.util.UUID

enum class OverlayThemeMode(val label: String) {
    DARK("極簡黑"),
    LIGHT("純粹白"),
    AUTO("跟隨系統")
}

enum class InputInjectionMethod(val label: String) {
    AUTO("智能自動 (無障礙優先)"),
    ACCESSIBILITY_SET_TEXT("無障礙直接寫入"),
    CLIPBOARD_PASTE("剪貼簿貼上"),
    SHIZUKU("Shizuku Shell 輸入")
}

enum class ReasoningEffort(val apiValue: String, val label: String) {
    NONE("none", "無 (none)"),
    LOW("low", "低 (low)"),
    HIGH("high", "高 (high)"),
    MAX("max", "極限 (max)")
}

enum class LongPressAction(val label: String) {
    QUICK_ASK("快速看圖問答"),
    REGION_CROP("框選區域解析"),
    INPUT("輸入類"),
    CHOICE("選擇類"),
    AUTO_LOOP("全自動迴圈"),
    FREEFORM_AGENT("一句話指令"),
    SMART_REPLY("語氣智能回覆"),
    SPATIAL_TRANSLATE("原位翻譯層"),
    WEB_TRANSLATE("網頁精譯"),
    VOICE_AGENT("語音指揮螢幕"),
    HIDE("隱藏懸浮球")
}

data class EnvCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String
)

data class AppConfig(
    // Onboarding
    val onboardingCompleted: Boolean = false,

    // BYOK & DeepSeek API
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val modelId: String = DEFAULT_MODEL,
    val temperature: Float = 0.1f,
    val maxTokens: Int = 1024,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val cachedModels: List<String> = DEFAULT_PRESET_MODELS,

    // Local Encrypted Data Vault (Env Cards — values NEVER sent to DeepSeek)
    val envCards: List<EnvCard> = emptyList(),

    // App UI Theme
    val appDarkTheme: Boolean = false,

    // Floating Overlay Customization
    val overlaySizeDp: Int = 52,
    val overlayAlpha: Float = 0.92f,
    val overlayPosX: Int = 24,
    val overlayPosY: Int = 360,
    val snapToEdge: Boolean = true,
    val showPillLabel: Boolean = false,
    val overlayTheme: OverlayThemeMode = OverlayThemeMode.DARK,
    val hapticFeedback: Boolean = true,
    val orbLongPressAction: LongPressAction = LongPressAction.QUICK_ASK,
    val showTapPulse: Boolean = true,

    // Workflow & Execution Customization
    val screenshotHideDelayMs: Int = 140,
    val multiClickIntervalMs: Int = 260,
    val imageMaxWidth: Int = 1280,
    val imageJpegQuality: Int = 82,
    val inputMethod: InputInjectionMethod = InputInjectionMethod.AUTO,
    val showResultToast: Boolean = true,
    val defaultChoiceCount: Int = 4,

    // Experimental: Full Auto-Loop settings
    val autoLoopMaxRounds: Int = 20,
    val autoLoopMaxItemsPerPage: Int = 8,
    val autoLoopStepDelayMs: Int = 700,
    val autoLoopPageDelayMs: Int = 1300,
    val autoLoopSnapToNode: Boolean = true,

    // Custom System Prompts
    val inputSystemPrompt: String = DEFAULT_INPUT_SYSTEM_PROMPT,
    val choiceSystemPrompt: String = DEFAULT_CHOICE_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"

        val DEFAULT_PRESET_MODELS = listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
            "deepseek-chat",
            "deepseek-reasoner"
        )

        const val DEFAULT_INPUT_SYSTEM_PROMPT =
            "你是一個精準的螢幕填答輔助助手。請仔細觀察截圖中的題目或表單輸入需求，" +
            "只輸出應該直接輸入到輸入框中的最終答案文字。" +
            "嚴禁輸出任何解釋、前綴、引號、Markdown 格式或多餘換行。"

        const val DEFAULT_CHOICE_SYSTEM_PROMPT =
            "你是一個精準的選擇題螢幕輔助助手。截圖中有 {COUNT} 個選項，按「從上往下、從左往右」順序編號為 1 到 {COUNT}。" +
            "請判斷正確答案（視題目為單選或多選），只輸出對應的阿拉伯數字編號。" +
            "單選例如：2；多選用英文逗號分隔例如：1,3。" +
            "嚴禁輸出任何字母、文字解釋、標點句號或 Markdown。"
    }
}
