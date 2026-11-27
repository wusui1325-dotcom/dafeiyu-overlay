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
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.AppConfig
import com.dafeiyu.overlay.data.ConfigStore
import com.dafeiyu.overlay.network.ApiResult
import com.dafeiyu.overlay.network.DeepSeekClient
import com.dafeiyu.overlay.system.DaFeiYuAccessibilityService
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
    private val onStopServiceRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var orbView: View? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var choiceCounterView: View? = null
    private var hudView: View? = null
    private var captureCanvasView: MonochromeOverlayViews.CoordinateCaptureCanvasView? = null

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
        val touchSlop = MonochromeOverlayViews.dp(context, 8)

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                    }
                    if (moved) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        hapticTick()
                        toggleModeMenu()
                    } else {
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

        // Primary Mode Buttons: 輸入類 & 選擇類
        val inputBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_edit,
            label = "輸入類・點選輸入框",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg
        ) {
            hapticTick()
            removeViewSafe(menuView)
            menuView = null
            startInputWorkflow()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MonochromeOverlayViews.dp(context, 8)
            }
        }
        container.addView(inputBtn)

        val choiceBtn = MonochromeOverlayViews.buildIconButton(
            context = context,
            iconRes = R.drawable.ic_checklist,
            label = "選擇類・單選 / 多選",
            bgColor = palette.accentBg,
            fgColor = palette.accentFg
        ) {
            hapticTick()
            removeViewSafe(menuView)
            menuView = null
            showChoiceCounterDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = MonochromeOverlayViews.dp(context, 10)
            }
        }
        container.addView(choiceBtn)

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
            MonochromeOverlayViews.dp(context, 256),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
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
                when (val result = DeepSeekClient.solveInput(cfg, base64)) {
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
                        val injected = a11y.tapAndInputText(rawX, rawY, finalAnswer, cfg.inputMethod)
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
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
                DeepSeekClient.solveChoice(cfg, base64, optionCount)
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
                                        a11y.performTap(pt.x, pt.y)
                                        if (step < indices.lastIndex) {
                                            delay(cfg.multiClickIntervalMs.toLong())
                                        }
                                    }
                                }
                                if (cfg.showResultToast) {
                                    val label = indices.joinToString(", ") { "#$it" }
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

    private suspend fun captureCleanScreenshot(cfg: AppConfig): String? {
        val a11y = DaFeiYuAccessibilityService.instance ?: return null

        // Hide all overlay views cleanly before screenshot
        val orbWasVisible = orbView?.visibility == View.VISIBLE
        orbView?.visibility = View.INVISIBLE
        menuView?.visibility = View.INVISIBLE
        choiceCounterView?.visibility = View.INVISIBLE
        hudView?.visibility = View.INVISIBLE
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

        val tv = TextView(context).apply {
            text = message
            setTextColor(palette.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
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
                label = "",
                bgColor = palette.surface,
                fgColor = palette.fg,
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
            y = MonochromeOverlayViews.dp(context, 48)
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

    private fun removeAllTransientViews() {
        removeViewSafe(menuView); menuView = null
        removeViewSafe(choiceCounterView); choiceCounterView = null
        removeViewSafe(captureCanvasView); captureCanvasView = null
        removeViewSafe(hudView); hudView = null
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
