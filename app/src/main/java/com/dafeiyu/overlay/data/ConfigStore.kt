package com.dafeiyu.overlay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConfigStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createSecurePrefs(appContext)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val current: AppConfig
        get() = _config.value

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun loadConfig(): AppConfig {
        val modelsJson = prefs.getString(KEY_CACHED_MODELS, null)
        val cachedModels = if (!modelsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(modelsJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }.ifEmpty { AppConfig.DEFAULT_PRESET_MODELS }
            } catch (_: Throwable) {
                AppConfig.DEFAULT_PRESET_MODELS
            }
        } else {
            AppConfig.DEFAULT_PRESET_MODELS
        }

        val envCardsJson = prefs.getString(KEY_ENV_CARDS, null)
        val envCards = if (!envCardsJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(envCardsJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name").trim()
                        val value = obj.optString("value")
                        val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                        if (name.isNotEmpty()) {
                            add(EnvCard(id = id, name = name, value = value))
                        }
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val overlayTheme = prefs.getString(KEY_OVERLAY_THEME, OverlayThemeMode.DARK.name)
            ?.let { runCatching { OverlayThemeMode.valueOf(it) }.getOrNull() }
            ?: OverlayThemeMode.DARK

        val inputMethod = prefs.getString(KEY_INPUT_METHOD, InputInjectionMethod.AUTO.name)
            ?.let { runCatching { InputInjectionMethod.valueOf(it) }.getOrNull() }
            ?: InputInjectionMethod.AUTO

        val longPressAction = prefs.getString(KEY_LONG_PRESS_ACTION, LongPressAction.QUICK_ASK.name)
            ?.let { runCatching { LongPressAction.valueOf(it) }.getOrNull() }
            ?: LongPressAction.QUICK_ASK

        val reasoningEffort = prefs.getString(KEY_REASONING_EFFORT, ReasoningEffort.NONE.name)
            ?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            ?: ReasoningEffort.NONE

        return AppConfig(
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE_URL, AppConfig.DEFAULT_BASE_URL)
                ?.ifBlank { AppConfig.DEFAULT_BASE_URL } ?: AppConfig.DEFAULT_BASE_URL,
            modelId = prefs.getString(KEY_MODEL_ID, AppConfig.DEFAULT_MODEL)
                ?.ifBlank { AppConfig.DEFAULT_MODEL } ?: AppConfig.DEFAULT_MODEL,
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.1f).coerceIn(0f, 2f),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 1024).coerceIn(32, 4096),
            reasoningEffort = reasoningEffort,
            cachedModels = cachedModels,
            envCards = envCards,
            appDarkTheme = prefs.getBoolean(KEY_APP_DARK_THEME, false),
            overlaySizeDp = prefs.getInt(KEY_OVERLAY_SIZE_DP, 52).coerceIn(36, 84),
            overlayAlpha = prefs.getFloat(KEY_OVERLAY_ALPHA, 0.92f).coerceIn(0.2f, 1.0f),
            overlayPosX = prefs.getInt(KEY_OVERLAY_POS_X, 24),
            overlayPosY = prefs.getInt(KEY_OVERLAY_POS_Y, 360),
            snapToEdge = prefs.getBoolean(KEY_SNAP_TO_EDGE, true),
            showPillLabel = prefs.getBoolean(KEY_SHOW_PILL_LABEL, false),
            overlayTheme = overlayTheme,
            hapticFeedback = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true),
            orbLongPressAction = longPressAction,
            showTapPulse = prefs.getBoolean(KEY_SHOW_TAP_PULSE, true),
            screenshotHideDelayMs = prefs.getInt(KEY_SCREENSHOT_HIDE_DELAY_MS, 140).coerceIn(60, 500),
            multiClickIntervalMs = prefs.getInt(KEY_MULTI_CLICK_INTERVAL_MS, 260).coerceIn(100, 1200),
            imageMaxWidth = prefs.getInt(KEY_IMAGE_MAX_WIDTH, 1280).coerceIn(640, 2048),
            imageJpegQuality = prefs.getInt(KEY_IMAGE_JPEG_QUALITY, 82).coerceIn(45, 95),
            inputMethod = inputMethod,
            showResultToast = prefs.getBoolean(KEY_SHOW_RESULT_TOAST, true),
            defaultChoiceCount = prefs.getInt(KEY_DEFAULT_CHOICE_COUNT, 4).coerceIn(2, 10),
            autoLoopMaxRounds = prefs.getInt(KEY_AUTOLOOP_MAX_ROUNDS, 20).coerceIn(1, 50),
            autoLoopMaxItemsPerPage = prefs.getInt(KEY_AUTOLOOP_MAX_ITEMS, 8).coerceIn(1, 20),
            autoLoopStepDelayMs = prefs.getInt(KEY_AUTOLOOP_STEP_DELAY, 700).coerceIn(250, 3000),
            autoLoopPageDelayMs = prefs.getInt(KEY_AUTOLOOP_PAGE_DELAY, 1300).coerceIn(400, 5000),
            autoLoopSnapToNode = prefs.getBoolean(KEY_AUTOLOOP_SNAP_NODE, true),
            inputSystemPrompt = prefs.getString(KEY_INPUT_PROMPT, AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT)
                ?.ifBlank { AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT } ?: AppConfig.DEFAULT_INPUT_SYSTEM_PROMPT,
            choiceSystemPrompt = prefs.getString(KEY_CHOICE_PROMPT, AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT)
                ?.ifBlank { AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT } ?: AppConfig.DEFAULT_CHOICE_SYSTEM_PROMPT
        )
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        _config.value = next
        persist(next)
    }

    private fun persist(cfg: AppConfig) {
        val modelsArr = JSONArray().apply {
            cfg.cachedModels.distinct().forEach { put(it) }
        }
        val envCardsArr = JSONArray().apply {
            cfg.envCards.forEach { card ->
                put(
                    JSONObject().apply {
                        put("id", card.id)
                        put("name", card.name)
                        put("value", card.value)
                    }
                )
            }
        }
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, cfg.onboardingCompleted)
            .putString(KEY_API_KEY, cfg.apiKey)
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_MODEL_ID, cfg.modelId)
            .putFloat(KEY_TEMPERATURE, cfg.temperature)
            .putInt(KEY_MAX_TOKENS, cfg.maxTokens)
            .putString(KEY_REASONING_EFFORT, cfg.reasoningEffort.name)
            .putString(KEY_CACHED_MODELS, modelsArr.toString())
            .putString(KEY_ENV_CARDS, envCardsArr.toString())
            .putBoolean(KEY_APP_DARK_THEME, cfg.appDarkTheme)
            .putInt(KEY_OVERLAY_SIZE_DP, cfg.overlaySizeDp)
            .putFloat(KEY_OVERLAY_ALPHA, cfg.overlayAlpha)
            .putInt(KEY_OVERLAY_POS_X, cfg.overlayPosX)
            .putInt(KEY_OVERLAY_POS_Y, cfg.overlayPosY)
            .putBoolean(KEY_SNAP_TO_EDGE, cfg.snapToEdge)
            .putBoolean(KEY_SHOW_PILL_LABEL, cfg.showPillLabel)
            .putString(KEY_OVERLAY_THEME, cfg.overlayTheme.name)
            .putBoolean(KEY_HAPTIC_FEEDBACK, cfg.hapticFeedback)
            .putString(KEY_LONG_PRESS_ACTION, cfg.orbLongPressAction.name)
            .putBoolean(KEY_SHOW_TAP_PULSE, cfg.showTapPulse)
            .putInt(KEY_SCREENSHOT_HIDE_DELAY_MS, cfg.screenshotHideDelayMs)
            .putInt(KEY_MULTI_CLICK_INTERVAL_MS, cfg.multiClickIntervalMs)
            .putInt(KEY_IMAGE_MAX_WIDTH, cfg.imageMaxWidth)
            .putInt(KEY_IMAGE_JPEG_QUALITY, cfg.imageJpegQuality)
            .putString(KEY_INPUT_METHOD, cfg.inputMethod.name)
            .putBoolean(KEY_SHOW_RESULT_TOAST, cfg.showResultToast)
            .putInt(KEY_DEFAULT_CHOICE_COUNT, cfg.defaultChoiceCount)
            .putInt(KEY_AUTOLOOP_MAX_ROUNDS, cfg.autoLoopMaxRounds)
            .putInt(KEY_AUTOLOOP_MAX_ITEMS, cfg.autoLoopMaxItemsPerPage)
            .putInt(KEY_AUTOLOOP_STEP_DELAY, cfg.autoLoopStepDelayMs)
            .putInt(KEY_AUTOLOOP_PAGE_DELAY, cfg.autoLoopPageDelayMs)
            .putBoolean(KEY_AUTOLOOP_SNAP_NODE, cfg.autoLoopSnapToNode)
            .putString(KEY_INPUT_PROMPT, cfg.inputSystemPrompt)
            .putString(KEY_CHOICE_PROMPT, cfg.choiceSystemPrompt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dafeiyu_secure_prefs"
        private const val PREFS_FALLBACK_NAME = "dafeiyu_prefs_fallback"

        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_CACHED_MODELS = "cached_models"
        private const val KEY_ENV_CARDS = "env_cards"
        private const val KEY_APP_DARK_THEME = "app_dark_theme"
        private const val KEY_OVERLAY_SIZE_DP = "overlay_size_dp"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_OVERLAY_POS_X = "overlay_pos_x"
        private const val KEY_OVERLAY_POS_Y = "overlay_pos_y"
        private const val KEY_SNAP_TO_EDGE = "snap_to_edge"
        private const val KEY_SHOW_PILL_LABEL = "show_pill_label"
        private const val KEY_OVERLAY_THEME = "overlay_theme"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_LONG_PRESS_ACTION = "long_press_action"
        private const val KEY_SHOW_TAP_PULSE = "show_tap_pulse"
        private const val KEY_SCREENSHOT_HIDE_DELAY_MS = "screenshot_hide_delay_ms"
        private const val KEY_MULTI_CLICK_INTERVAL_MS = "multi_click_interval_ms"
        private const val KEY_IMAGE_MAX_WIDTH = "image_max_width"
        private const val KEY_IMAGE_JPEG_QUALITY = "image_jpeg_quality"
        private const val KEY_INPUT_METHOD = "input_method"
        private const val KEY_SHOW_RESULT_TOAST = "show_result_toast"
        private const val KEY_DEFAULT_CHOICE_COUNT = "default_choice_count"
        private const val KEY_AUTOLOOP_MAX_ROUNDS = "autoloop_max_rounds"
        private const val KEY_AUTOLOOP_MAX_ITEMS = "autoloop_max_items"
        private const val KEY_AUTOLOOP_STEP_DELAY = "autoloop_step_delay"
        private const val KEY_AUTOLOOP_PAGE_DELAY = "autoloop_page_delay"
        private const val KEY_AUTOLOOP_SNAP_NODE = "autoloop_snap_node"
        private const val KEY_INPUT_PROMPT = "input_prompt"
        private const val KEY_CHOICE_PROMPT = "choice_prompt"
    }
}
