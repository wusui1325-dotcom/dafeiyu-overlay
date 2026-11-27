package com.dafeiyu.overlay.data

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
    NONE("none", "關閉思考 (極速)"),
    LOW("low", "低強度"),
    HIGH("high", "高強度"),
    MAX("max", "最高強度")
}

data class AppConfig(
    // Onboarding
    val onboardingCompleted: Boolean = false,

    // BYOK & DeepSeek API
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val modelId: String = DEFAULT_MODEL,
    val temperature: Float = 0.1f,
    val maxTokens: Int = 512,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val cachedModels: List<String> = DEFAULT_PRESET_MODELS,

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

    // Workflow & Execution Customization
    val screenshotHideDelayMs: Int = 140,
    val multiClickIntervalMs: Int = 260,
    val imageMaxWidth: Int = 1280,
    val imageJpegQuality: Int = 82,
    val inputMethod: InputInjectionMethod = InputInjectionMethod.AUTO,
    val showResultToast: Boolean = true,
    val defaultChoiceCount: Int = 4,

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
