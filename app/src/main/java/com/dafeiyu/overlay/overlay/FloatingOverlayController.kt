package com.dafeiyu.overlay.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dafeiyu.overlay.MainActivity
import com.dafeiyu.overlay.ui.web.WebTranslateActivity
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.data.HistoryStore
import com.dafeiyu.overlay.data.LongPressAction
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.network.AutoLoopAnalysis
import com.dafeiyu.overlay.network.SpatialTranslationBlock
import com.dafeiyu.overlay.system.PermissionHelper
import com.dafeiyu.overlay.system.CapturedScreen
import com.dafeiyu.overlay.system.DaFeiYuAccessibilityService
import com.dafeiyu.overlay.system.UiElementSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayController(
    private val context: Context,
    private val configStore: ConfigStore,
    private val historyStore: HistoryStore,
    private val onStopServiceRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var orbView: View? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var choiceCounterView: View? = null
    private var hudView: View? = null
    private var answerCardView: View? = null
    private var captureCanvasView: MonochromeOverlayViews.CoordinateCaptureCanvasView? = null
    private var regionSelectView: MonochromeOverlayViews.RegionSelectCanvasView? = null
    private var commandBarView: View? = null
    private var smartReplyView: View? = null
    private var spatialOverlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val pinnedScreenshots = mutableListOf<String>()
    private var longPressJob: Job? = null

    private var activeWorkflowJob: Job? = null
    private var hudAutoDismissJob: Job? = null
    private var configObserverJob: Job? = null
    private var orbHiddenByUser: Boolean = false
    private var lastAppliedAppearanceKey: String = ""

    fun start() {
        showOrb()
        configObserverJob?.cancel()
        configObserverJob = scope.launch {
            configStore.config.collectLatest { cfg ->
                val appearanceKey =
                    "${cfg.overlaySizeDp}|${cfg.overlayAlpha}|${cfg.showPillLabel}|${cfg.overlayTheme}|${cfg.overlayPosX}|${cfg.overlayPosY}"
                if (appearanceKey != lastAppliedAppearanceKey && !orbHiddenByUser) {
                    lastAppliedAppearanceKey = appearanceKey
                    refreshOrb(cfg)
                }
            }
        }
    }

    fun destroy() {
        activeWorkflowJob?.cancel()
        hudAutoDismissJob?.cancel()
        configObserverJob?.cancel()
        removeAllTransientViews()
        removeViewSafe(orbView)
        orbView = null
        scope.cancel()
    }

    fun onScreenConfigurationChanged() {
        val view = orbView ?: return
        val params = orbParams ?: return
        val dm = context.resources.displayMetrics
        val margin = MonochromeOverlayViews.dp(context, 12)
        val w = view.width.coerceAtLeast(MonochromeOverlayViews.dp(context, configStore.current.overlaySizeDp))
        val h = view.height.coerceAtLeast(MonochromeOverlayViews.dp(context, configStore.current.overlaySizeDp))
        val clampedX = if (configStore.current.snapToEdge) {
            if (params.x + w / 2 < dm.widthPixels / 2) margin
            else (dm.widthPixels - w - margin).coerceAtLeast(margin)
        } else {
            params.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        }
        val clampedY = params.y.coerceIn(
            MonochromeOverlayViews.dp(context, 32),
            (dm.heightPixels - h - MonochromeOverlayViews.dp(context, 48)).coerceAtLeast(32)
        )
        params.x = clampedX
        params.y = clampedY
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun toggleOrbVisibility() {
        orbHiddenByUser = !orbHiddenByUser
        if (orbHiddenByUser) {
            removeAllTransientViews()
            orbView?.visibility = View.GONE
        } else {
            if (orbView == null) {
                showOrb()
            } else {
                orbView?.visibility = View.VISIBLE
            }
        }
    }

    private fun overlayWindowType(): Int {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private fun showOrb() {
        removeViewSafe(orbView)
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        val view = MonochromeOverlayViews.buildFloatingOrb(
            context = context,
            sizeDp = cfg.overlaySizeDp,
            alpha = cfg.overlayAlpha,
            showLabel = cfg.showPillLabel,
            palette = palette
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cfg.overlayPosX
            y = cfg.overlayPosY
        }

        attachDragAndTapListener(view, params)
        orbView = view
        orbParams = params
        lastAppliedAppearanceKey =
            "${cfg.overlaySizeDp}|${cfg.overlayAlpha}|${cfg.showPillLabel}|${cfg.overlayTheme}|${cfg.overlayPosX}|${cfg.overlayPosY}"
        addViewSafe(view, params)
    }

    private fun refreshOrb(cfg: AppConfig) {
        if (orbView == null) {
            showOrb()
            return
        }
        val wasVisible = orbView?.visibility == View.VISIBLE
        showOrb()
        if (!wasVisible) {
            orbView?.visibility = View.INVISIBLE
        }
    }

    private fun attachDragAndTapListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var longPressTriggered = false
        val touchSlop = MonochromeOverlayViews.dp(context, 8)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    longPressTriggered = false
                    longPressJob?.cancel()
                    longPressJob = scope.launch {
                        delay(480L)
                        if (!moved) {
                            longPressTriggered = true
                            hapticTick()
                            triggerLongPressShortcut()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        longPressJob?.cancel()
                    }
                    if (moved) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    if (longPressTriggered) {
                        true
                    } else if (!moved && event.action == MotionEvent.ACTION_UP) {
                        hapticTick()
                        toggleModeMenu()
                    } else if (moved) {
                        val metrics = context.resources.displayMetrics
                        val cfg = configStore.current
                        val finalX = if (cfg.snapToEdge) {
                            val center = params.x + view.width / 2
                            val margin = MonochromeOverlayViews.dp(context, 12)
                            if (center < metrics.widthPixels / 2) margin
                            else (metrics.widthPixels - view.width - margin).coerceAtLeast(margin)
                        } else {
                            params.x.coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
                        }
                        val finalY = params.y.coerceIn(
                            MonochromeOverlayViews.dp(context, 32),
                            (metrics.heightPixels - view.height - MonochromeOverlayViews.dp(context, 48))
                                .coerceAtLeast(32)
                        )
                        params.x = finalX
                        params.y = finalY
                        runCatching { windowManager.updateViewLayout(view, params) }
                        configStore.update { it.copy(overlayPosX = finalX, overlayPosY = finalY) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleModeMenu() {
        if (menuView != null) {
            removeViewSafe(menuView)
            menuView = null
            return
        }
        removeAllTransientViews()
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 10).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 14)
            setPadding(pad, pad, pad, pad)
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bottomSpace = MonochromeOverlayViews.dp(context, 10)
            setPadding(MonochromeOverlayViews.dp(context, 4), 0, MonochromeOverlayViews.dp(context, 2), bottomSpace)
        }
        val fishIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_fish)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val title = TextView(context).apply {
            text = "大肥魚流程"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            compact = true
        ) {
            removeViewSafe(menuView)
            menuView = null
        }
        header.addView(fishIcon)
        header.addView(title)
        header.addView(closeBtn)
        container.addView(header)

        // Row 1: Core 大肥魚 (輸入類 | 選擇類)
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        val inputBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_edit,
            label = "輸入類",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startInputWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }
        val choiceBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_checklist,
            label = "選擇類",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            showChoiceCounterDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(inputBtn)
        row1.addView(choiceBtn)
        container.addView(row1)

        // Row 2: Full Auto-Loop (Experimental)
        val autoLoopBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_autorenew,
            label = "全自動迴圈 (實驗性)",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startAutoLoopWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        container.addView(autoLoopBtn)

        // Row 3: Revolutionary AI Tools (一句話指令 | 語氣回覆)
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        val agentBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_bolt,
            label = "一句話指令",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            showFreeformCommandBar()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }
        val replyBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_chat,
            label = "語氣回覆",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            showSmartReplyTonePicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row3.addView(agentBtn)
        row3.addView(replyBtn)
        container.addView(row3)

        // Row 4: Vision Tools (看圖問答 | 框選解析)
        val row4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        val quickAskBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_lightbulb,
            label = "看圖問答",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startQuickAskWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }
        val regionCropBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_crop,
            label = "框選解析",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startRegionCropWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row4.addView(quickAskBtn)
        row4.addView(regionCropBtn)
        container.addView(row4)

        // Row 4.5: Spatial AR Translation & Voice-to-Screen Agent
        val row45 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        val spatialBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_translate,
            label = "原位翻譯",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startSpatialTranslateWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }
        val voiceBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_mic,
            label = "語音指揮",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startVoiceAgentWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row45.addView(spatialBtn)
        row45.addView(voiceBtn)
        container.addView(row45)

        // Row 4.8: Full Webpage DeepSeek Translator
        val webTransBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_translate,
            label = "網頁全文精譯 (告別機翻)",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            startWebTranslateWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
        }
        container.addView(webTransBtn)

        // Row 5: Cross-Page Visual Memory Pinning
        val pinRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 10) }
        }
        val pinBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_pin,
            label = "釘選參考頁 (${pinnedScreenshots.size}/3)",
            bgColor = if (pinnedScreenshots.isNotEmpty()) palette.accentBg else palette.surface,
            fgColor = if (pinnedScreenshots.isNotEmpty()) palette.accentFg else palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(menuView); menuView = null
            pinCurrentScreen()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pinRow.addView(pinBtn)
        if (pinnedScreenshots.isNotEmpty()) {
            val clearPinBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_close,
                label = "清除",
                bgColor = palette.surface,
                fgColor = palette.fgMuted,
                borderColor = palette.border,
                compact = true
            ) {
                hapticTick()
                pinnedScreenshots.clear()
                removeViewSafe(menuView); menuView = null
                showStatusHud("已清除所有釘選參考頁", autoDismissMs = 1800L)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = MonochromeOverlayViews.dp(context, 6) }
            }
            pinRow.addView(clearPinBtn)
        }
        container.addView(pinRow)

                // Utility row: Settings, Hide, Stop
        val utilRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val settingsBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_settings,
            label = "設定",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            removeViewSafe(menuView)
            menuView = null
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }

        val hideBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_visibility_off,
            label = "隱藏",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            toggleOrbVisibility()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }

        val stopBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "結束",
            bgColor = palette.surface,
            fgColor = palette.fgMuted,
            borderColor = palette.border,
            compact = true
        ) {
            onStopServiceRequested()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        utilRow.addView(settingsBtn)
        utilRow.addView(hideBtn)
        utilRow.addView(stopBtn)
        container.addView(utilRow)

        val params = WindowManager.LayoutParams(
            MonochromeOverlayViews.dp(context, 276),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(menuView)
                menuView = null
                true
            } else false
        }
        menuView = container
        addViewSafe(container, params)
    }

    // =========================================================================
    // WORKFLOW 1: 輸入類 (Input Mode)
    // Tap input box -> immediately hide overlays & screenshot -> DeepSeek -> type
    // =========================================================================
    private fun startInputWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return

        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        // Full-screen transparent touch capture overlay
        val captureView = MonochromeOverlayViews.CoordinateCaptureCanvasView(
            context = context,
            palette = palette
        ) { rawX, rawY ->
            hapticTick()
            removeViewSafe(captureCanvasView)
            captureCanvasView = null

            activeWorkflowJob = scope.launch {
                val base64 = captureCleanScreenshot(cfg)
                if (base64 == null) {
                    showStatusHud("截圖失敗：請確認無障礙服務已啟用 (Android 11+)", autoDismissMs = 3500L)
                    return@launch
                }

                showStatusHud("DeepSeek 分析輸入內容中…", showCancel = true)
                val t0 = System.currentTimeMillis()
                when (val result = DeepSeekClient.solveInput(cfg, base64, pinnedScreenshots.toList())) {
                    is ApiResult.Success -> {
                        val solved = result.data
                        val finalAnswer = solved.finalText
                        val displayPreview = if (solved.substitutedCards.isNotEmpty()) {
                            "${solved.rawText}（已本機替換）"
                        } else {
                            finalAnswer
                        }
                        val a11y = DaFeiYuAccessibilityService.instance
                        if (a11y == null) {
                            showStatusHud("無障礙服務未連線", autoDismissMs = 3000L)
                            return@launch
                        }
                        showStatusHud("正在輸入：$displayPreview", showCancel = false)
                        showTapPulse(rawX, rawY)
                        val injected = a11y.tapAndInputText(rawX, rawY, finalAnswer, cfg.inputMethod)
                        historyStore.record("輸入類", cfg.modelId, System.currentTimeMillis() - t0, displayPreview, true)
                        if (cfg.showResultToast) {
                            val msg = if (injected) "已輸入：$displayPreview" else "已複製至剪貼簿：$displayPreview"
                            showStatusHud(
                                message = msg,
                                copyText = finalAnswer,
                                autoDismissMs = 4000L
                            )
                        } else {
                            removeViewSafe(hudView)
                            hudView = null
                        }
                    }
                    is ApiResult.Error -> {
                        showStatusHud(result.message, autoDismissMs = 4500L)
                    }
                }
            }
        }

        captureCanvasView = captureView
        addViewSafe(captureView, fullScreenOverlayParams())

        // Show top HUD banner AFTER captureView so HUD buttons remain clickable
        showStatusHud("輸入類：請點擊目標輸入框位置", showCancel = true)
    }

    // =========================================================================
    // WORKFLOW 2: 選擇類 (Choice Mode)
    // Quick counter (- / + / 2 / 4 / Confirm) -> immediately hide overlays &
    // screenshot & call DeepSeek in parallel WHILE user taps 1..N top-to-bottom,
    // left-to-right -> click answer(s)
    // =========================================================================
    private fun showChoiceCounterDialog() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return

        removeAllTransientViews()
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        var count = cfg.defaultChoiceCount.coerceIn(2, 10)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 10).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }

        // Title Row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 12))
        }
        val titleIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_checklist)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val titleTv = TextView(context).apply {
            text = "選擇類・選項數量"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        titleRow.addView(titleIcon)
        titleRow.addView(titleTv)
        container.addView(titleRow)

        // Counter Stepper Row: [-]  4  [+]
        val countTv = TextView(context).apply {
            text = count.toString()
            gravity = Gravity.CENTER
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stepperRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 12))
        }
        val minusBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_minus,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border
        ) {
            if (count > 2) {
                count--
                countTv.text = count.toString()
                hapticTick()
            }
        }
        val plusBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_plus,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border
        ) {
            if (count < 10) {
                count++
                countTv.text = count.toString()
                hapticTick()
            }
        }
        stepperRow.addView(minusBtn)
        stepperRow.addView(countTv)
        stepperRow.addView(plusBtn)
        container.addView(stepperRow)

        // Quick preset buttons: [2] & [4]
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 14))
        }
        val quick2 = MonochromeOverlayViews.buildPillTextButton(
            context = context,
            label = "快捷 2 項",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border
        ) {
            count = 2
            countTv.text = "2"
            hapticTick()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val quick4 = MonochromeOverlayViews.buildPillTextButton(
            context = context,
            label = "快捷 4 項",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border
        ) {
            count = 4
            countTv.text = "4"
            hapticTick()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        presetRow.addView(quick2)
        presetRow.addView(quick4)
        container.addView(presetRow)

        // Action Row: [✕ 取消] & [✓ 確定]
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "取消",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            removeViewSafe(choiceCounterView)
            choiceCounterView = null
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val confirmBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_check,
            label = "確定",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg,
            compact = true
        ) {
            hapticTick()
            removeViewSafe(choiceCounterView)
            choiceCounterView = null
            configStore.update { it.copy(defaultChoiceCount = count) }
            startChoiceWorkflow(count)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(cancelBtn)
        actionRow.addView(confirmBtn)
        container.addView(actionRow)

        val params = WindowManager.LayoutParams(
            MonochromeOverlayViews.dp(context, 268),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(choiceCounterView)
                choiceCounterView = null
                true
            } else false
        }
        choiceCounterView = container
        addViewSafe(container, params)
    }

    private fun startChoiceWorkflow(optionCount: Int) {
        val cfg = configStore.current
        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        activeWorkflowJob = scope.launch {
            // 1. Immediately hide floating orb & take clean screenshot
            val base64 = captureCleanScreenshot(cfg)
            if (base64 == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用 (Android 11+)", autoDismissMs = 3500L)
                return@launch
            }

            // 2. Fire DeepSeek Vision request immediately in parallel while user taps option positions!
            val aiDeferred: Deferred<ApiResult<List<Int>>> = async {
                DeepSeekClient.solveChoice(cfg, base64, optionCount, pinnedScreenshots.toList())
            }

            val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
            val collectedPoints = mutableListOf<PointF>()

            fun updateCollectorHud() {
                val nextIndex = collectedPoints.size + 1
                showStatusHud(
                    message = "由上往下、由左往右點擊選項 ($nextIndex / $optionCount)",
                    showCancel = true,
                    onUndo = if (collectedPoints.isNotEmpty()) {
                        {
                            collectedPoints.removeAt(collectedPoints.lastIndex)
                            captureCanvasView?.setRecordedPoints(collectedPoints)
                            updateCollectorHud()
                        }
                    } else null
                )
            }

            val captureView = MonochromeOverlayViews.CoordinateCaptureCanvasView(
                context = context,
                palette = palette
            ) { rawX, rawY ->
                hapticTick()
                collectedPoints.add(PointF(rawX, rawY))
                captureCanvasView?.setRecordedPoints(collectedPoints)

                if (collectedPoints.size < optionCount) {
                    updateCollectorHud()
                } else {
                    // All N coordinates recorded! Remove capture overlay and await AI answer
                    removeViewSafe(captureCanvasView)
                    captureCanvasView = null

                    scope.launch {
                        if (!aiDeferred.isCompleted) {
                            showStatusHud("等待 DeepSeek 回傳選項答案…", showCancel = true)
                        }
                        when (val result = aiDeferred.await()) {
                            is ApiResult.Success -> {
                                val indices = result.data
                                val a11y = DaFeiYuAccessibilityService.instance
                                if (a11y == null) {
                                    showStatusHud("無障礙服務未連線", autoDismissMs = 3000L)
                                    return@launch
                                }
                                // Brief pause so overlay removal settles before clicking
                                delay(120)
                                indices.forEachIndexed { step, oneBasedIdx ->
                                    val pt = collectedPoints.getOrNull(oneBasedIdx - 1)
                                    if (pt != null) {
                                        showTapPulse(pt.x, pt.y)
                                        a11y.performTap(pt.x, pt.y)
                                        if (step < indices.lastIndex) {
                                            delay(cfg.multiClickIntervalMs.toLong())
                                        }
                                    }
                                }
                                val label = indices.joinToString(", ") { "#$it" }
                                historyStore.record("選擇類", cfg.modelId, 0L, "已選擇：$label", true)
                                if (cfg.showResultToast) {
                                    showStatusHud("已選擇：$label", autoDismissMs = 3500L)
                                } else {
                                    removeViewSafe(hudView)
                                    hudView = null
                                }
                            }
                            is ApiResult.Error -> {
                                showStatusHud(result.message, autoDismissMs = 4500L)
                            }
                        }
                    }
                }
            }

            captureCanvasView = captureView
            addViewSafe(captureView, fullScreenOverlayParams())
            updateCollectorHud()
        }
    }

    // =========================================================================
    // WORKFLOW 3 (EXPERIMENTAL): 全自動迴圈 (逐步多道問題)
    // 每輪 5 步驟：
    //   1. 識別是否仍需繼續迴圈
    //   2. 截圖並識別題型、問題與跨裝置精準位置 (0..1000 歸一化 + 無障礙元件中心吸附)
    //   3. 按從上往下、從左往右順序完成一條尚未完成的項目
    //   4. 識別此頁面是否還有尚未完成（如有，重複步驟 3 → 步驟 4）
    //   5. 尋找並點擊前往下一題 / 下一頁 / 繼續按鈕
    // =========================================================================
    private fun startAutoLoopWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return

        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        activeWorkflowJob = scope.launch {
            var completedRounds = 0

            for (round in 1..cfg.autoLoopMaxRounds) {
                val a11y = DaFeiYuAccessibilityService.instance
                if (a11y == null) {
                    showStatusHud("無障礙服務已中斷，迴圈停止", autoDismissMs = 3500L)
                    return@launch
                }

                // Step 1 & Step 2: Capture clean screen + live Accessibility tree & analyze
                showStatusHud("第 $round 輪 · 步驟 1-2 分析頁面中…", showCancel = true, cancelLabel = "停止")
                val initialScan = scanScreenAndAnalyze(cfg, a11y, emptyList(), round)
                if (initialScan == null) {
                    return@launch
                }
                var (screen, elements, analysis) = initialScan

                // If soft keyboard is obscuring the screen, dismiss it and re-scan before deciding
                if ((analysis.keyboardBlocking || (!analysis.shouldContinue && analysis.nextButton == null)) &&
                    a11y.dismissSoftKeyboardIfVisible()
                ) {
                    delay(260)
                    showStatusHud("第 $round 輪 · 已收合鍵盤，重新識別頁面…", showCancel = true, cancelLabel = "停止")
                    val retryScan = scanScreenAndAnalyze(cfg, a11y, emptyList(), round) ?: return@launch
                    screen = retryScan.first
                    elements = retryScan.second
                    analysis = retryScan.third
                }

                // Step 1 check: Should we continue looping?
                if (!analysis.shouldContinue) {
                    val reason = analysis.stopReason.ifBlank { "未偵測到更多待處理題目" }
                    showStatusHud("迴圈結束（共 $completedRounds 輪）· $reason", autoDismissMs = 4500L)
                    return@launch
                }

                // Step 3 & Step 4 inner loop: complete unfinished items top-to-bottom, left-to-right
                val completedInRound = mutableListOf<String>()
                var pageSteps = 0

                while (analysis.pendingItems.isNotEmpty() && pageSteps < cfg.autoLoopMaxItemsPerPage) {
                    pageSteps++
                    val item = analysis.pendingItems.first()
                    showStatusHud(
                        "第 $round 輪 · 步驟 3 處理「${item.summary.take(16)}」",
                        showCancel = true,
                        cancelLabel = "停止"
                    )

                    if (item.type == "input") {
                        val target = item.targets.first()
                        val pt = a11y.resolvePreciseTapPoint(
                            elementId = target.elementId,
                            normX = target.x,
                            normY = target.y,
                            physicalWidth = screen.physicalWidth,
                            physicalHeight = screen.physicalHeight,
                            elements = elements,
                            snapToNode = cfg.autoLoopSnapToNode
                        )
                        val solved = DeepSeekClient.resolveEnvCards(
                            item.answerText,
                            cfg.envCards.filter { it.name.isNotBlank() }
                        )
                        if (solved.finalText.isNotBlank()) {
                            showTapPulse(pt.x, pt.y)
                            a11y.tapAndInputText(pt.x, pt.y, solved.finalText, cfg.inputMethod)
                        } else {
                            a11y.performTap(pt.x, pt.y)
                        }
                        // Dismiss soft keyboard so it never obscures lower items or the Next/Continue button
                        delay(200)
                        if (a11y.dismissSoftKeyboardIfVisible()) {
                            delay(240)
                        }
                        completedInRound.add("已填輸入框：${item.summary}")
                    } else {
                        // Choice item (single or multi-select)
                        item.targets.forEachIndexed { idx, target ->
                            val pt = a11y.resolvePreciseTapPoint(
                                elementId = target.elementId,
                                normX = target.x,
                                normY = target.y,
                                physicalWidth = screen.physicalWidth,
                                physicalHeight = screen.physicalHeight,
                                elements = elements,
                                snapToNode = cfg.autoLoopSnapToNode
                            )
                            showTapPulse(pt.x, pt.y)
                            a11y.performTap(pt.x, pt.y)
                            if (idx < item.targets.lastIndex) {
                                delay(cfg.multiClickIntervalMs.toLong())
                            }
                        }
                        completedInRound.add("已選選擇題：${item.summary}")
                    }

                    delay(cfg.autoLoopStepDelayMs.toLong())

                    // Step 4: Re-check if there are still unfinished items on this page
                    showStatusHud("第 $round 輪 · 步驟 4 複查本頁項目…", showCancel = true, cancelLabel = "停止")
                    val recheck = scanScreenAndAnalyze(cfg, a11y, completedInRound, round)
                    if (recheck == null) {
                        return@launch
                    }
                    screen = recheck.first
                    elements = recheck.second
                    analysis = recheck.third

                    if (!analysis.shouldContinue) {
                        completedRounds++
                        val reason = analysis.stopReason.ifBlank { "已完成全部題目" }
                        showStatusHud("全自動迴圈完成（共 $completedRounds 輪）：$reason", autoDismissMs = 4500L)
                        return@launch
                    }
                }

                // Step 5: Find and click Next / Continue / Submit button
                // If next button was hidden behind soft keyboard, dismiss keyboard and re-scan once
                if ((analysis.nextButton == null || analysis.keyboardBlocking) &&
                    a11y.dismissSoftKeyboardIfVisible()
                ) {
                    delay(280)
                    showStatusHud("第 $round 輪 · 已收合鍵盤，尋找繼續按鈕…", showCancel = true, cancelLabel = "停止")
                    val rescan = scanScreenAndAnalyze(cfg, a11y, completedInRound, round) ?: return@launch
                    screen = rescan.first
                    elements = rescan.second
                    analysis = rescan.third
                }

                // Auto-scroll down up to 2 times if page has scrollable content below the fold
                var scrollAttempts = 0
                while (analysis.nextButton == null && analysis.needScrollDown && scrollAttempts < 2) {
                    scrollAttempts++
                    showStatusHud("第 $round 輪 · 自動下滑尋找更多內容 ($scrollAttempts/2)…", showCancel = true, cancelLabel = "停止")
                    a11y.performScrollDown(screen.physicalWidth, screen.physicalHeight)
                    delay(550)
                    val scrolledScan = scanScreenAndAnalyze(cfg, a11y, completedInRound, round) ?: return@launch
                    screen = scrolledScan.first
                    elements = scrolledScan.second
                    analysis = scrolledScan.third
                    if (analysis.pendingItems.isNotEmpty()) {
                        break
                    }
                }
                if (analysis.pendingItems.isNotEmpty()) {
                    // New unfinished items revealed after scrolling -> continue to next loop iteration on same page
                    continue
                }

                val nextBtn = analysis.nextButton
                if (nextBtn == null) {
                    completedRounds++
                    historyStore.record("全自動迴圈", cfg.modelId, 0L, "共完成 $completedRounds 輪（無更多繼續按鈕）", true)
                    showStatusHud("迴圈結束（共 $completedRounds 輪）· 未找到繼續按鈕", autoDismissMs = 4500L)
                    return@launch
                }

                showStatusHud("第 $round 輪 · 步驟 5 點擊「${nextBtn.label}」", showCancel = true, cancelLabel = "停止")
                val nextPt = a11y.resolvePreciseTapPoint(
                    elementId = nextBtn.elementId,
                    normX = nextBtn.x,
                    normY = nextBtn.y,
                    physicalWidth = screen.physicalWidth,
                    physicalHeight = screen.physicalHeight,
                    elements = elements,
                    snapToNode = cfg.autoLoopSnapToNode
                )
                showTapPulse(nextPt.x, nextPt.y)
                a11y.performTap(nextPt.x, nextPt.y)
                completedRounds++

                // Wait for next question / page transition
                delay(cfg.autoLoopPageDelayMs.toLong())
            }

            showStatusHud("全自動迴圈已達上限（$completedRounds 輪）並自動停止", autoDismissMs = 4500L)
        }
    }

    private suspend fun scanScreenAndAnalyze(
        cfg: AppConfig,
        a11y: DaFeiYuAccessibilityService,
        completedInRound: List<String>,
        round: Int
    ): Triple<CapturedScreen, List<UiElementSnapshot>, AutoLoopAnalysis>? {
        val screen = captureCleanScreenWithMetrics(cfg)
        if (screen == null) {
            showStatusHud("截圖失敗：請確認無障礙服務已啟用 (Android 11+)", autoDismissMs = 3500L)
            return null
        }
        val elements = a11y.extractInteractiveElements(screen.physicalWidth, screen.physicalHeight)
        return when (
            val res = DeepSeekClient.analyzeAutoLoopPage(
                config = cfg,
                base64Jpeg = screen.base64Jpeg,
                elements = elements,
                completedInRound = completedInRound,
                roundNumber = round,
                pinnedImages = pinnedScreenshots.toList()
            )
        ) {
            is ApiResult.Success -> Triple(screen, elements, res.data)
            is ApiResult.Error -> {
                showStatusHud("迴圈中斷：${res.message}", autoDismissMs = 4500L)
                null
            }
        }
    }

    private suspend fun captureCleanScreenWithMetrics(cfg: AppConfig): CapturedScreen? {
        val a11y = DaFeiYuAccessibilityService.instance ?: return null

        val orbWasVisible = orbView?.visibility == View.VISIBLE
        val hudWasVisible = hudView?.visibility == View.VISIBLE
        orbView?.visibility = View.INVISIBLE
        menuView?.visibility = View.INVISIBLE
        choiceCounterView?.visibility = View.INVISIBLE
        hudView?.visibility = View.INVISIBLE
        answerCardView?.visibility = View.INVISIBLE
        commandBarView?.visibility = View.INVISIBLE
        smartReplyView?.visibility = View.INVISIBLE
        spatialOverlayView?.visibility = View.INVISIBLE
        captureCanvasView?.visibility = View.INVISIBLE

        delay(cfg.screenshotHideDelayMs.toLong())

        val screen = a11y.captureScreenWithMetrics(cfg.imageMaxWidth, cfg.imageJpegQuality)

        if (orbWasVisible && !orbHiddenByUser) {
            orbView?.visibility = View.VISIBLE
        }
        if (hudWasVisible) {
            hudView?.visibility = View.VISIBLE
        }
        return screen
    }

    private suspend fun captureCleanScreenshot(cfg: AppConfig): String? {
        val a11y = DaFeiYuAccessibilityService.instance ?: return null

        // Hide all overlay views cleanly before screenshot
        val orbWasVisible = orbView?.visibility == View.VISIBLE
        orbView?.visibility = View.INVISIBLE
        menuView?.visibility = View.INVISIBLE
        choiceCounterView?.visibility = View.INVISIBLE
        hudView?.visibility = View.INVISIBLE
        answerCardView?.visibility = View.INVISIBLE
        commandBarView?.visibility = View.INVISIBLE
        smartReplyView?.visibility = View.INVISIBLE
        spatialOverlayView?.visibility = View.INVISIBLE
        captureCanvasView?.visibility = View.INVISIBLE

        delay(cfg.screenshotHideDelayMs.toLong())

        val base64 = a11y.captureScreenshotBase64(cfg.imageMaxWidth, cfg.imageJpegQuality)

        // Restore floating orb
        if (orbWasVisible && !orbHiddenByUser) {
            orbView?.visibility = View.VISIBLE
        }
        return base64
    }

    private fun validateReadyForWorkflow(cfg: AppConfig): Boolean {
        if (cfg.apiKey.isBlank()) {
            showStatusHud("請先在設定中填寫 DeepSeek API Key", autoDismissMs = 3500L)
            return false
        }
        if (DaFeiYuAccessibilityService.instance == null) {
            showStatusHud("請先開啟「大肥魚」無障礙服務", autoDismissMs = 3500L)
            return false
        }
        return true
    }

    private fun showStatusHud(
        message: String,
        showCancel: Boolean = false,
        cancelLabel: String = "",
        onUndo: (() -> Unit)? = null,
        copyText: String? = null,
        autoDismissMs: Long? = null
    ) {
        hudAutoDismissJob?.cancel()
        removeViewSafe(hudView)

        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 8).toFloat()
            val hPad = MonochromeOverlayViews.dp(context, 14)
            val vPad = MonochromeOverlayViews.dp(context, 10)
            setPadding(hPad, vPad, hPad, vPad)
        }

        val icon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_fish)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 16)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        pill.addView(icon)

        val maxTextPx = (context.resources.displayMetrics.widthPixels - MonochromeOverlayViews.dp(context, 132))
            .coerceIn(MonochromeOverlayViews.dp(context, 140), MonochromeOverlayViews.dp(context, 280))
        val tv = TextView(context).apply {
            text = message
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            maxWidth = maxTextPx
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 6)
            }
        }
        pill.addView(tv)

        if (onUndo != null) {
            val undoBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_undo,
                label = "",
                bgColor = palette.surface,
                fgColor = palette.fg,
                compact = true
            ) { onUndo() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = MonochromeOverlayViews.dp(context, 4) }
            }
            pill.addView(undoBtn)
        }

        if (copyText != null) {
            val copyBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_copy,
                label = "複製",
                bgColor = palette.surface,
                fgColor = palette.fg,
                compact = true
            ) {
                DaFeiYuAccessibilityService.instance?.copyToClipboard(copyText)
                showStatusHud("已複製至剪貼簿", autoDismissMs = 1500L)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = MonochromeOverlayViews.dp(context, 4) }
            }
            pill.addView(copyBtn)
        }

        if (showCancel) {
            val cancelBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_close,
                label = cancelLabel,
                bgColor = if (cancelLabel.isNotEmpty()) palette.accentBg else palette.surface,
                fgColor = if (cancelLabel.isNotEmpty()) palette.accentFg else palette.fg,
                compact = true
            ) {
                activeWorkflowJob?.cancel()
                removeAllTransientViews()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = MonochromeOverlayViews.dp(context, 4) }
            }
            pill.addView(cancelBtn)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val statusBarRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarH = if (statusBarRes > 0) context.resources.getDimensionPixelSize(statusBarRes) else MonochromeOverlayViews.dp(context, 28)
            y = statusBarH + MonochromeOverlayViews.dp(context, 12)
        }

        hudView = pill
        addViewSafe(pill, params)

        if (autoDismissMs != null) {
            hudAutoDismissJob = scope.launch {
                delay(autoDismissMs)
                removeViewSafe(hudView)
                hudView = null
            }
        }
    }

    private fun fullScreenOverlayParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // =========================================================================
    // CREATIVE 1: Cross-Page Pinned Visual Memory (釘選參考頁)
    // =========================================================================
    private fun pinCurrentScreen() {
        val cfg = configStore.current
        if (DaFeiYuAccessibilityService.instance == null) {
            showStatusHud("請先開啟「大肥魚」無障礙服務", autoDismissMs = 3000L)
            return
        }
        activeWorkflowJob?.cancel()
        activeWorkflowJob = scope.launch {
            val base64 = captureCleanScreenshot(cfg)
            if (base64 == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用", autoDismissMs = 3000L)
                return@launch
            }
            if (pinnedScreenshots.size >= 3) {
                pinnedScreenshots.removeAt(0)
            }
            pinnedScreenshots.add(base64)
            showStatusHud("已釘選參考頁 (${pinnedScreenshots.size}/3)，切到題目頁即可跨頁作答", autoDismissMs = 3200L)
        }
    }

    // =========================================================================
    // CREATIVE 2: Natural Language Screen Agent (一句話指令操作)
    // =========================================================================
    private fun showFreeformCommandBar() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return
        removeAllTransientViews()

        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 12).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 14)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 8))
        }
        val hIcon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_bolt)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = MonochromeOverlayViews.dp(context, 8) }
        }
        val hTitle = TextView(context).apply {
            text = "一句話指令 AI 代理人"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            compact = true
        ) {
            removeViewSafe(commandBarView); commandBarView = null
        }
        header.addView(hIcon)
        header.addView(hTitle)
        header.addView(closeBtn)
        container.addView(header)

        val inputBox = EditText(context).apply {
            hint = "例如：填完這張表單、總結重點、勾選同意並繼續…"
            setHintTextColor(palette.fgMuted)
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = MonochromeOverlayViews.roundedBg(context, palette.surface, palette.border, 1, 16)
            val pH = MonochromeOverlayViews.dp(context, 12)
            val pV = MonochromeOverlayViews.dp(context, 10)
            setPadding(pH, pV, pH, pV)
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 10) }
        }
        container.addView(inputBox)

        // Quick 1-tap creative presets
        val presets = listOf("總結重點", "填完表單", "勾選同意並繼續")
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 10) }
        }
        presets.forEachIndexed { idx, preset ->
            val chip = MonochromeOverlayViews.buildPillTextButton(
                context = context,
                label = preset,
                bgColor = palette.surface,
                fgColor = palette.fg,
                borderColor = palette.border
            ) {
                hapticTick()
                removeViewSafe(commandBarView); commandBarView = null
                runFreeformAgent(preset)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < presets.lastIndex) marginEnd = MonochromeOverlayViews.dp(context, 6)
                }
            }
            presetRow.addView(chip)
        }
        container.addView(presetRow)

        val runBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_bolt,
            label = "立即執行指令",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg
        ) {
            val cmd = inputBox.text?.toString()?.trim().orEmpty()
            if (cmd.isEmpty()) return@buildIconButton
            hapticTick()
            removeViewSafe(commandBarView); commandBarView = null
            runFreeformAgent(cmd)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(runBtn)

        val widthPx = (context.resources.displayMetrics.widthPixels - MonochromeOverlayViews.dp(context, 40))
            .coerceIn(MonochromeOverlayViews.dp(context, 270), MonochromeOverlayViews.dp(context, 360))
        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(commandBarView); commandBarView = null
                true
            } else false
        }
        commandBarView = container
        addViewSafe(container, params)
    }

    private fun runFreeformAgent(instruction: String) {
        val cfg = configStore.current
        activeWorkflowJob?.cancel()
        activeWorkflowJob = scope.launch {
            val a11y = DaFeiYuAccessibilityService.instance
            if (a11y == null) {
                showStatusHud("無障礙服務未連線", autoDismissMs = 3000L)
                return@launch
            }
            // Dismiss command bar keyboard before taking clean screenshot
            if (a11y.dismissSoftKeyboardIfVisible()) delay(220)

            val screen = captureCleanScreenWithMetrics(cfg)
            if (screen == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用", autoDismissMs = 3500L)
                return@launch
            }
            val elements = a11y.extractInteractiveElements(screen.physicalWidth, screen.physicalHeight)
            showStatusHud("AI 代理人規劃中：「${instruction.take(14)}」", showCancel = true, cancelLabel = "停止")

            val t0 = System.currentTimeMillis()
            when (
                val res = DeepSeekClient.executeFreeformCommand(
                    config = cfg,
                    base64Jpeg = screen.base64Jpeg,
                    elements = elements,
                    instruction = instruction,
                    pinnedImages = pinnedScreenshots.toList()
                )
            ) {
                is ApiResult.Success -> {
                    val latency = System.currentTimeMillis() - t0
                    val plan = res.data
                    val validCards = cfg.envCards.filter { it.name.isNotBlank() }

                    plan.actions.forEachIndexed { idx, act ->
                        showStatusHud("執行步驟 ${idx + 1}/${plan.actions.size}：${act.description.take(18)}", showCancel = true, cancelLabel = "停止")
                        when (act.type) {
                            "tap" -> {
                                val pt = a11y.resolvePreciseTapPoint(
                                    act.elementId, act.x, act.y,
                                    screen.physicalWidth, screen.physicalHeight,
                                    elements, cfg.autoLoopSnapToNode
                                )
                                showTapPulse(pt.x, pt.y)
                                a11y.performTap(pt.x, pt.y)
                            }
                            "input" -> {
                                val pt = a11y.resolvePreciseTapPoint(
                                    act.elementId, act.x, act.y,
                                    screen.physicalWidth, screen.physicalHeight,
                                    elements, cfg.autoLoopSnapToNode
                                )
                                val solved = DeepSeekClient.resolveEnvCards(act.text, validCards)
                                showTapPulse(pt.x, pt.y)
                                a11y.tapAndInputText(pt.x, pt.y, solved.finalText, cfg.inputMethod)
                                delay(180)
                                a11y.dismissSoftKeyboardIfVisible()
                            }
                            "scroll_down" -> a11y.performScrollDown(screen.physicalWidth, screen.physicalHeight)
                            "scroll_up" -> a11y.performScrollUp(screen.physicalWidth, screen.physicalHeight)
                        }
                        if (idx < plan.actions.lastIndex) delay(450L)
                    }

                    val logSummary = plan.replyText.ifBlank { "已完成 ${plan.actions.size} 個操作步驟（$instruction）" }
                    historyStore.record("一句話指令", cfg.modelId, latency, logSummary, true)

                    if (plan.replyText.isNotBlank()) {
                        removeViewSafe(hudView); hudView = null
                        showAnswerCard(plan.replyText, latency, screen.base64Jpeg)
                    } else {
                        showStatusHud("指令執行完成（${plan.actions.size} 步 · ${latency}ms）", autoDismissMs = 3500L)
                    }
                }
                is ApiResult.Error -> {
                    historyStore.record("一句話指令", cfg.modelId, System.currentTimeMillis() - t0, res.message, false)
                    showStatusHud(res.message, autoDismissMs = 4500L)
                }
            }
        }
    }

    // =========================================================================
    // CREATIVE 3: Smart Contextual Reply (語氣智能回覆)
    // =========================================================================
    private fun showSmartReplyTonePicker() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return
        removeAllTransientViews()

        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 10).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 12))
        }
        val icon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_chat)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = MonochromeOverlayViews.dp(context, 8) }
        }
        val title = TextView(context).apply {
            text = "語氣智能回覆・選擇風格"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            compact = true
        ) {
            removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
        }
        header.addView(icon)
        header.addView(title)
        header.addView(closeBtn)
        container.addView(header)

        val tones = listOf("專業禮貌", "幽默風趣", "簡短高冷", "溫柔體貼", "婉拒拒絕")
        tones.forEach { tone ->
            val btn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_chat,
                label = tone,
                bgColor = palette.surface,
                fgColor = palette.fg,
                borderColor = palette.border,
                compact = true
            ) {
                hapticTick()
                removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
                runSmartReply(tone)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
            }
            container.addView(btn)
        }

        val params = WindowManager.LayoutParams(
            MonochromeOverlayViews.dp(context, 260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
                true
            } else false
        }
        smartReplyView = container
        addViewSafe(container, params)
    }

    private fun runSmartReply(tone: String) {
        val cfg = configStore.current
        activeWorkflowJob?.cancel()
        activeWorkflowJob = scope.launch {
            val base64 = captureCleanScreenshot(cfg)
            if (base64 == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用", autoDismissMs = 3500L)
                return@launch
            }
            showStatusHud("生成「$tone」回覆選項中…", showCancel = true, cancelLabel = "取消")
            val t0 = System.currentTimeMillis()
            when (val res = DeepSeekClient.solveSmartReply(cfg, base64, tone, pinnedScreenshots.toList())) {
                is ApiResult.Success -> {
                    val latency = System.currentTimeMillis() - t0
                    historyStore.record("語氣回覆 ($tone)", cfg.modelId, latency, res.data.firstOrNull().orEmpty(), true)
                    removeViewSafe(hudView); hudView = null
                    showSmartReplyOptionsCard(tone, res.data, latency)
                }
                is ApiResult.Error -> {
                    historyStore.record("語氣回覆 ($tone)", cfg.modelId, System.currentTimeMillis() - t0, res.message, false)
                    showStatusHud(res.message, autoDismissMs = 4500L)
                }
            }
        }
    }

    private fun showSmartReplyOptionsCard(tone: String, options: List<String>, latencyMs: Long) {
        removeViewSafe(smartReplyView)
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 12).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 10))
        }
        val title = TextView(context).apply {
            text = "$tone · 點擊自動填入輸入框 (${latencyMs}ms)"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "",
            bgColor = palette.surface,
            fgColor = palette.fg,
            compact = true
        ) {
            removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
        }
        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        options.forEach { replyText ->
            val optView = TextView(context).apply {
                text = replyText
                setTextColor(palette.fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = MonochromeOverlayViews.roundedBg(context, palette.surface, palette.border, 1, 16)
                val pH = MonochromeOverlayViews.dp(context, 12)
                val pV = MonochromeOverlayViews.dp(context, 10)
                setPadding(pH, pV, pH, pV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = MonochromeOverlayViews.dp(context, 8) }
                setOnClickListener {
                    hapticTick()
                    removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
                    scope.launch {
                        val a11y = DaFeiYuAccessibilityService.instance
                        val injected = a11y?.injectIntoBottomInputBox(replyText, cfg.inputMethod) == true
                        showStatusHud(
                            message = if (injected) "已自動填入回覆" else "已複製回覆至剪貼簿",
                            copyText = replyText,
                            autoDismissMs = 3000L
                        )
                    }
                }
            }
            card.addView(optView)
        }

        val widthPx = (context.resources.displayMetrics.widthPixels - MonochromeOverlayViews.dp(context, 44))
            .coerceIn(MonochromeOverlayViews.dp(context, 260), MonochromeOverlayViews.dp(context, 360))
        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        card.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
                true
            } else false
        }
        smartReplyView = card
        addViewSafe(card, params)
    }

    // =========================================================================
    // CREATIVE 4: Spatial AR In-Place Translation Overlay (原位翻譯層)
    // =========================================================================
    private fun startSpatialTranslateWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return
        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        activeWorkflowJob = scope.launch {
            val screen = captureCleanScreenWithMetrics(cfg)
            if (screen == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用", autoDismissMs = 3500L)
                return@launch
            }
            showStatusHud("原位 AR 翻譯掃描中…", showCancel = true, cancelLabel = "取消")
            val t0 = System.currentTimeMillis()
            when (val res = DeepSeekClient.solveSpatialTranslate(cfg, screen.base64Jpeg)) {
                is ApiResult.Success -> {
                    val latency = System.currentTimeMillis() - t0
                    historyStore.record("原位翻譯", cfg.modelId, latency, "已原位翻譯 ${res.data.size} 處外文區塊", true)
                    showSpatialOverlay(res.data, screen.physicalWidth, screen.physicalHeight, latency)
                }
                is ApiResult.Error -> {
                    historyStore.record("原位翻譯", cfg.modelId, System.currentTimeMillis() - t0, res.message, false)
                    showStatusHud(res.message, autoDismissMs = 4000L)
                }
            }
        }
    }

    private fun showSpatialOverlay(
        blocks: List<SpatialTranslationBlock>,
        physW: Int,
        physH: Int,
        latencyMs: Long
    ) {
        removeViewSafe(spatialOverlayView)
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        val root = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.argb(38, 0, 0, 0))
            setOnClickListener {
                removeViewSafe(spatialOverlayView); spatialOverlayView = null
                removeViewSafe(hudView); hudView = null
            }
        }

        val padH = MonochromeOverlayViews.dp(context, 8)
        val padV = MonochromeOverlayViews.dp(context, 4)

        blocks.forEach { b ->
            val leftPx = ((b.x1 / 1000f) * physW).toInt().coerceIn(8, physW - 80)
            val topPx = ((b.y1 / 1000f) * physH).toInt().coerceIn(8, physH - 60)
            val boxW = (((b.x2 - b.x1) / 1000f) * physW).toInt().coerceIn(MonochromeOverlayViews.dp(context, 72), physW - leftPx - 8)

            val badge = TextView(context).apply {
                text = b.translated
                setTextColor(palette.fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 12)
                setPadding(padH, padV, padH, padV)
                elevation = MonochromeOverlayViews.dp(context, 4).toFloat()
                maxWidth = boxW
                setOnClickListener {
                    hapticTick()
                    DaFeiYuAccessibilityService.instance?.copyToClipboard(b.translated)
                    showStatusHud("已複製：「${b.translated.take(20)}」", showCancel = true, cancelLabel = "關閉翻譯")
                }
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = leftPx
                topMargin = topPx
            }
            root.addView(badge, lp)
        }

        spatialOverlayView = root
        addViewSafe(root, fullScreenOverlayParams())
        showStatusHud("原位翻譯層 · ${blocks.size} 處 (${latencyMs}ms) · 點文字複製，點空白關閉", showCancel = true, cancelLabel = "關閉")
    }

    // =========================================================================
    // CREATIVE 5: Voice-to-Screen AI Agent (語音指揮螢幕)
    // =========================================================================
    private fun startVoiceAgentWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            showStatusHud("請先在「權限」頁授予麥克風權限以使用語音指揮", autoDismissMs = 4000L)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            showStatusHud("此裝置未安裝語音辨識服務，已切換文字指令條", autoDismissMs = 2500L)
            showFreeformCommandBar()
            return
        }

        activeWorkflowJob?.cancel()
        removeAllTransientViews()
        stopVoiceRecognizer()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                showStatusHud("🎤 聆聽中…請說出要操作螢幕的指令", showCancel = true, cancelLabel = "取消")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                showStatusHud("🎤 辨識語音中…", showCancel = true, cancelLabel = "取消")
            }
            override fun onError(error: Int) {
                stopVoiceRecognizer()
                showStatusHud("語音辨識未取得內容 (code=$error)，請重試", autoDismissMs = 3000L)
            }
            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                stopVoiceRecognizer()
                if (spoken.isEmpty()) {
                    showStatusHud("未辨識到語音指令", autoDismissMs = 2500L)
                } else {
                    hapticTick()
                    runFreeformAgent(spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    showStatusHud("🎤 $partial", showCancel = true, cancelLabel = "取消")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer.startListening(intent) }.onFailure {
            stopVoiceRecognizer()
            showStatusHud("啟動語音辨識失敗：${it.localizedMessage}", autoDismissMs = 3000L)
        }
    }

    private fun stopVoiceRecognizer() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun startWebTranslateWorkflow() {
        val a11y = DaFeiYuAccessibilityService.instance
        val detectedUrl = a11y?.extractBrowserUrl().orEmpty()
        val fallbackParagraphs = if (detectedUrl.isBlank()) {
            a11y?.extractVisiblePageParagraphs().orEmpty()
        } else {
            emptyList()
        }
        WebTranslateActivity.launch(context, detectedUrl, fallbackParagraphs)
    }

    private fun triggerLongPressShortcut() {
        when (configStore.current.orbLongPressAction) {
            LongPressAction.QUICK_ASK -> startQuickAskWorkflow()
            LongPressAction.REGION_CROP -> startRegionCropWorkflow()
            LongPressAction.INPUT -> startInputWorkflow()
            LongPressAction.CHOICE -> showChoiceCounterDialog()
            LongPressAction.AUTO_LOOP -> startAutoLoopWorkflow()
            LongPressAction.FREEFORM_AGENT -> showFreeformCommandBar()
            LongPressAction.SMART_REPLY -> showSmartReplyTonePicker()
            LongPressAction.SPATIAL_TRANSLATE -> startSpatialTranslateWorkflow()
            LongPressAction.WEB_TRANSLATE -> startWebTranslateWorkflow()
            LongPressAction.VOICE_AGENT -> startVoiceAgentWorkflow()
            LongPressAction.HIDE -> toggleOrbVisibility()
        }
    }

    private fun startQuickAskWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return

        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        activeWorkflowJob = scope.launch {
            val base64 = captureCleanScreenshot(cfg)
            if (base64 == null) {
                showStatusHud("截圖失敗：請確認無障礙服務已啟用 (Android 11+)", autoDismissMs = 3500L)
                return@launch
            }
            showStatusHud("快速看圖問答：DeepSeek 解析中…", showCancel = true, cancelLabel = "取消")
            val t0 = System.currentTimeMillis()
            when (val res = DeepSeekClient.solveQuickAsk(cfg, base64, pinnedScreenshots.toList())) {
                is ApiResult.Success -> {
                    val latency = System.currentTimeMillis() - t0
                    historyStore.record("看圖問答", cfg.modelId, latency, res.data, true)
                    removeViewSafe(hudView)
                    hudView = null
                    showAnswerCard(res.data, latency, base64)
                }
                is ApiResult.Error -> {
                    historyStore.record("看圖問答", cfg.modelId, System.currentTimeMillis() - t0, res.message, false)
                    showStatusHud(res.message, autoDismissMs = 4500L)
                }
            }
        }
    }

    private fun startRegionCropWorkflow() {
        val cfg = configStore.current
        if (!validateReadyForWorkflow(cfg)) return

        activeWorkflowJob?.cancel()
        removeAllTransientViews()

        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        val selector = MonochromeOverlayViews.RegionSelectCanvasView(
            context = context,
            palette = palette
        ) { rect ->
            hapticTick()
            removeViewSafe(regionSelectView)
            regionSelectView = null

            activeWorkflowJob = scope.launch {
                val a11y = DaFeiYuAccessibilityService.instance
                if (a11y == null) {
                    showStatusHud("無障礙服務未連線", autoDismissMs = 3000L)
                    return@launch
                }
                val orbWasVisible = orbView?.visibility == View.VISIBLE
                orbView?.visibility = View.INVISIBLE
                hudView?.visibility = View.INVISIBLE
                delay(cfg.screenshotHideDelayMs.toLong())

                val croppedB64 = a11y.captureCroppedRegionBase64(rect, cfg.imageMaxWidth, cfg.imageJpegQuality)
                if (orbWasVisible && !orbHiddenByUser) orbView?.visibility = View.VISIBLE

                if (croppedB64 == null) {
                    showStatusHud("區域截圖失敗：請確認無障礙服務已啟用", autoDismissMs = 3500L)
                    return@launch
                }
                showStatusHud("框選區域解析中…", showCancel = true, cancelLabel = "取消")
                val t0 = System.currentTimeMillis()
                when (val res = DeepSeekClient.solveQuickAsk(cfg, croppedB64, pinnedScreenshots.toList())) {
                    is ApiResult.Success -> {
                        val latency = System.currentTimeMillis() - t0
                        historyStore.record("框選解析", cfg.modelId, latency, res.data, true)
                        removeViewSafe(hudView)
                        hudView = null
                        showAnswerCard(res.data, latency, croppedB64)
                    }
                    is ApiResult.Error -> {
                        historyStore.record("框選解析", cfg.modelId, System.currentTimeMillis() - t0, res.message, false)
                        showStatusHud(res.message, autoDismissMs = 4500L)
                    }
                }
            }
        }

        regionSelectView = selector
        addViewSafe(selector, fullScreenOverlayParams())
        showStatusHud("框選解析：請拖曳框出目標區域", showCancel = true, cancelLabel = "取消")
    }

    private fun showAnswerCard(answerText: String, latencyMs: Long, sourceImageB64: String? = null) {
        removeViewSafe(answerCardView)
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MonochromeOverlayViews.roundedBg(context, palette.bg, palette.border, 1, 24)
            elevation = MonochromeOverlayViews.dp(context, 12).toFloat()
            val pad = MonochromeOverlayViews.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, MonochromeOverlayViews.dp(context, 10))
        }
        val icon = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_lightbulb)?.mutate())
            setColorFilter(palette.fg)
            val s = MonochromeOverlayViews.dp(context, 18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val title = TextView(context).apply {
            text = "快速看圖解答 · ${latencyMs}ms"
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(icon)
        header.addView(title)
        card.addView(header)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MonochromeOverlayViews.dp(context, 12)
            }
        }
        val bodyTv = TextView(context).apply {
            text = answerText
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(MonochromeOverlayViews.dp(context, 3).toFloat(), 1f)
            maxHeight = MonochromeOverlayViews.dp(context, 280)
        }
        scroll.addView(bodyTv)
        card.addView(scroll)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val copyBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_copy,
            label = "複製解答",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg,
            compact = true
        ) {
            DaFeiYuAccessibilityService.instance?.copyToClipboard(answerText)
            showStatusHud("已複製解答至剪貼簿", autoDismissMs = 1600L)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MonochromeOverlayViews.dp(context, 8)
            }
        }
        val closeBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_close,
            label = "關閉",
            bgColor = palette.surface,
            fgColor = palette.fg,
            borderColor = palette.border,
            compact = true
        ) {
            removeViewSafe(answerCardView)
            answerCardView = null
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(copyBtn)
        if (sourceImageB64 != null) {
            val transBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_autorenew,
                label = "翻繁中",
                bgColor = palette.surface,
                fgColor = palette.fg,
                borderColor = palette.border,
                compact = true
            ) {
                removeViewSafe(answerCardView)
                answerCardView = null
                activeWorkflowJob?.cancel()
                activeWorkflowJob = scope.launch {
                    showStatusHud("正在翻譯為繁體中文…", showCancel = true, cancelLabel = "取消")
                    val t0 = System.currentTimeMillis()
                    when (val tr = DeepSeekClient.solveTranslate(configStore.current, sourceImageB64)) {
                        is ApiResult.Success -> {
                            val lat = System.currentTimeMillis() - t0
                            historyStore.record("螢幕翻譯", configStore.current.modelId, lat, tr.data, true)
                            removeViewSafe(hudView)
                            hudView = null
                            showAnswerCard(tr.data, lat, null)
                        }
                        is ApiResult.Error -> showStatusHud(tr.message, autoDismissMs = 4000L)
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = MonochromeOverlayViews.dp(context, 8)
                }
            }
            actionRow.addView(transBtn)
        }
        actionRow.addView(closeBtn)
        card.addView(actionRow)

        // Inline Multi-Turn Visual Follow-Up Chat on the same screenshot!
        if (sourceImageB64 != null) {
            val chatHistory = mutableListOf("畫面解析" to answerText)
            val followRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = MonochromeOverlayViews.dp(context, 10) }
            }
            val followInput = EditText(context).apply {
                hint = "針對此畫面繼續追問…"
                setHintTextColor(palette.fgMuted)
                setTextColor(palette.fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = MonochromeOverlayViews.roundedBg(context, palette.surface, palette.border, 1, 16)
                val pH = MonochromeOverlayViews.dp(context, 10)
                val pV = MonochromeOverlayViews.dp(context, 8)
                setPadding(pH, pV, pH, pV)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = MonochromeOverlayViews.dp(context, 6)
                }
            }
            val sendBtn = MonochromeOverlayViews.buildIconButton(
                context = context,
                iconRes = R.drawable.ic_send,
                label = "",
                bgColor = palette.accentBg,
                fgColor = palette.accentFg,
                compact = true
            ) {
                val q = followInput.text?.toString()?.trim().orEmpty()
                if (q.isEmpty()) return@buildIconButton
                hapticTick()
                followInput.setText("")
                bodyTv.text = "${bodyTv.text}\n\n▶ $q\n思考中…"
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                activeWorkflowJob?.cancel()
                activeWorkflowJob = scope.launch {
                    val t0 = System.currentTimeMillis()
                    when (
                        val fRes = DeepSeekClient.solveVisualFollowUp(
                            config = configStore.current,
                            base64Jpeg = sourceImageB64,
                            priorTurns = chatHistory.toList(),
                            followUpQuestion = q
                        )
                    ) {
                        is ApiResult.Success -> {
                            chatHistory.add(q to fRes.data)
                            historyStore.record("視覺追問", configStore.current.modelId, System.currentTimeMillis() - t0, fRes.data, true)
                            val rebuilt = chatHistory.mapIndexed { i, pair ->
                                if (i == 0) pair.second else "▶ ${pair.first}\n${pair.second}"
                            }.joinToString("\n\n")
                            bodyTv.text = rebuilt
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                        is ApiResult.Error -> {
                            bodyTv.text = "${bodyTv.text}\n(${fRes.message})"
                        }
                    }
                }
            }
            followRow.addView(followInput)
            followRow.addView(sendBtn)
            card.addView(followRow)
        }

        val cardWidth = (context.resources.displayMetrics.widthPixels - MonochromeOverlayViews.dp(context, 48))
            .coerceIn(MonochromeOverlayViews.dp(context, 260), MonochromeOverlayViews.dp(context, 360))
        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        card.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                removeViewSafe(answerCardView)
                answerCardView = null
                true
            } else false
        }
        answerCardView = card
        addViewSafe(card, params)
    }

    private fun showTapPulse(x: Float, y: Float) {
        if (!configStore.current.showTapPulse) return
        val cfg = configStore.current
        val palette = MonochromeOverlayViews.paletteFor(context, cfg.overlayTheme)
        val sizePx = MonochromeOverlayViews.dp(context, 30)

        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(MonochromeOverlayViews.dp(context, 3), palette.accentBg)
            }
            alpha = 0.92f
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - sizePx / 2f).toInt()
            this.y = (y - sizePx / 2f).toInt()
        }
        addViewSafe(ring, params)
        scope.launch {
            delay(360L)
            removeViewSafe(ring)
        }
    }

    private fun removeAllTransientViews() {
        removeViewSafe(menuView); menuView = null
        removeViewSafe(choiceCounterView); choiceCounterView = null
        removeViewSafe(captureCanvasView); captureCanvasView = null
        removeViewSafe(regionSelectView); regionSelectView = null
        removeViewSafe(commandBarView); commandBarView = null
        removeViewSafe(smartReplyView); smartReplyView = null
        removeViewSafe(spatialOverlayView); spatialOverlayView = null
        stopVoiceRecognizer()
        removeViewSafe(hudView); hudView = null
        removeViewSafe(answerCardView); answerCardView = null
    }

    private fun addViewSafe(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.addView(view, params) }
    }

    private fun removeViewSafe(view: View?) {
        if (view != null) {
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun hapticTick() {
        if (!configStore.current.hapticFeedback) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
}
