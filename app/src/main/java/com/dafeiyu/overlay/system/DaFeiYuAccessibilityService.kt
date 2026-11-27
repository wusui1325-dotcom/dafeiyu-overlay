package com.dafeiyu.overlay.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dafeiyu.overlay.data.InputInjectionMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.hypot

data class CapturedScreen(
    val base64Jpeg: String,
    val physicalWidth: Int,
    val physicalHeight: Int
)

data class UiElementSnapshot(
    val id: Int,
    val kind: String,
    val label: String,
    val bounds: Rect,
    val normX: Int,
    val normY: Int,
    val normLeft: Int,
    val normTop: Int,
    val normRight: Int,
    val normBottom: Int,
    val isChecked: Boolean
)

class DaFeiYuAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener; actions are triggered on-demand by the user via overlay
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Captures the screen and crops strictly to the user-dragged physical rectangle
     * for faster, higher-accuracy Region Crop Ask.
     */
    suspend fun captureCroppedRegionBase64(
        cropRect: Rect,
        maxWidth: Int,
        jpegQuality: Int
    ): String? {
        val bitmap = captureScreenBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val left = cropRect.left.coerceIn(0, bitmap.width - 1)
                val top = cropRect.top.coerceIn(0, bitmap.height - 1)
                val right = cropRect.right.coerceIn(left + 1, bitmap.width)
                val bottom = cropRect.bottom.coerceIn(top + 1, bitmap.height)
                val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                bitmap.recycle()

                val scaled = if (cropped.width > maxWidth && maxWidth > 0) {
                    val ratio = maxWidth.toFloat() / cropped.width.toFloat()
                    val targetHeight = (cropped.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(cropped, maxWidth, targetHeight, true)
                } else {
                    cropped
                }
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(45, 95), stream)
                if (scaled !== cropped) scaled.recycle()
                cropped.recycle()
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (_: Throwable) {
                runCatching { bitmap.recycle() }
                null
            }
        }
    }

    suspend fun captureScreenshotBase64(maxWidth: Int, jpegQuality: Int): String? {
        return captureScreenWithMetrics(maxWidth, jpegQuality)?.base64Jpeg
    }

    /**
     * Captures the full physical display (matching dispatchGesture coordinate space 1:1)
     * and returns both the compressed Base64 JPEG and exact original physical dimensions.
     */
    suspend fun captureScreenWithMetrics(maxWidth: Int, jpegQuality: Int): CapturedScreen? {
        val bitmap = captureScreenBitmap() ?: return null
        val physW = bitmap.width
        val physH = bitmap.height
        return withContext(Dispatchers.Default) {
            try {
                val scaled = if (physW > maxWidth && maxWidth > 0) {
                    val ratio = maxWidth.toFloat() / physW.toFloat()
                    val targetHeight = (physH * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
                } else {
                    bitmap
                }
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(45, 95), stream)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                CapturedScreen(
                    base64Jpeg = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP),
                    physicalWidth = physW,
                    physicalHeight = physH
                )
            } catch (_: Throwable) {
                runCatching { bitmap.recycle() }
                null
            }
        }
    }

    suspend fun captureScreenBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = try {
                                val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                wrapped?.copy(Bitmap.Config.ARGB_8888, false)?.also {
                                    wrapped.recycle()
                                }
                            } catch (_: Throwable) {
                                null
                            } finally {
                                runCatching { hardwareBuffer.close() }
                            }
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Extracts visible interactive Accessibility nodes from the active foreground app,
     * normalized to a 0..1000 grid against the exact physical screen bounds, sorted
     * top-to-bottom, left-to-right for cross-device pixel-accurate tapping.
     */
    fun extractInteractiveElements(
        physicalWidth: Int,
        physicalHeight: Int,
        maxElements: Int = 45
    ): List<UiElementSnapshot> {
        if (physicalWidth <= 0 || physicalHeight <= 0) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() == packageName) return emptyList()

        data class RawCandidate(
            val kind: String,
            val label: String,
            val bounds: Rect,
            val isChecked: Boolean
        )

        val candidates = mutableListOf<RawCandidate>()
        val seenRects = HashSet<String>()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 22 || candidates.size >= 120) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val clipped = Rect(rect)
            if (!clipped.intersect(0, 0, physicalWidth, physicalHeight)) return
            val w = clipped.width()
            val h = clipped.height()
            if (w < 16 || h < 16) return
            // Skip full-screen container wrappers
            val isFullScreenWrapper = w > (physicalWidth * 0.96f) && h > (physicalHeight * 0.85f)

            val isInteractive = node.isEditable || node.isCheckable || node.isClickable
            if (isInteractive && !isFullScreenWrapper) {
                val text = collectNodeLabel(node)
                val kind = when {
                    node.isEditable -> "input"
                    node.isCheckable -> "option"
                    else -> "button"
                }
                val key = "${clipped.left / 8}_${clipped.top / 8}_${clipped.right / 8}_${clipped.bottom / 8}"
                if (seenRects.add(key)) {
                    candidates.add(
                        RawCandidate(
                            kind = kind,
                            label = text.take(48),
                            bounds = clipped,
                            isChecked = node.isChecked || node.isSelected
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)

        // Sort strictly top-to-bottom (row bucket = 3% of screen height), left-to-right
        val rowBucketPx = (physicalHeight * 0.03f).toInt().coerceAtLeast(24)
        val sorted = candidates.sortedWith(
            compareBy<RawCandidate> { it.bounds.centerY() / rowBucketPx }
                .thenBy { it.bounds.centerX() }
        ).take(maxElements)

        return sorted.mapIndexed { idx, c ->
            UiElementSnapshot(
                id = idx + 1,
                kind = c.kind,
                label = c.label,
                bounds = c.bounds,
                normX = ((c.bounds.exactCenterX() / physicalWidth) * 1000f).toInt().coerceIn(0, 1000),
                normY = ((c.bounds.exactCenterY() / physicalHeight) * 1000f).toInt().coerceIn(0, 1000),
                normLeft = ((c.bounds.left.toFloat() / physicalWidth) * 1000f).toInt().coerceIn(0, 1000),
                normTop = ((c.bounds.top.toFloat() / physicalHeight) * 1000f).toInt().coerceIn(0, 1000),
                normRight = ((c.bounds.right.toFloat() / physicalWidth) * 1000f).toInt().coerceIn(0, 1000),
                normBottom = ((c.bounds.bottom.toFloat() / physicalHeight) * 1000f).toInt().coerceIn(0, 1000),
                isChecked = c.isChecked
            )
        }
    }

    private fun collectNodeLabel(node: AccessibilityNodeInfo): String {
        val own = listOfNotNull(
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ).firstOrNull()
        if (!own.isNullOrBlank()) return own

        // Pull label from direct child text nodes (common in custom option cards/buttons)
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount.coerceAtMost(6)) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) parts.add(t)
        }
        return parts.joinToString(" ").trim()
    }

    /**
     * Resolves a target from DeepSeek (optional Accessibility elementId + 0..1000 normalized
     * coordinates) into exact physical screen tap coordinates adapted to any device/aspect ratio.
     */
    fun resolvePreciseTapPoint(
        elementId: Int?,
        normX: Int,
        normY: Int,
        physicalWidth: Int,
        physicalHeight: Int,
        elements: List<UiElementSnapshot>,
        snapToNode: Boolean
    ): PointF {
        // 1. Exact Accessibility node match by elementId -> guaranteed pixel-perfect center
        if (elementId != null && snapToNode) {
            val matched = elements.firstOrNull { it.id == elementId }
            if (matched != null) {
                return PointF(
                    matched.bounds.exactCenterX().coerceIn(2f, (physicalWidth - 2).toFloat()),
                    matched.bounds.exactCenterY().coerceIn(2f, (physicalHeight - 2).toFloat())
                )
            }
        }

        // 2. Map 0..1000 normalized coordinates to physical display pixels
        val rawX = (normX.coerceIn(1, 999) / 1000f) * physicalWidth
        val rawY = (normY.coerceIn(1, 999) / 1000f) * physicalHeight

        if (!snapToNode || elements.isEmpty()) {
            return PointF(rawX, rawY)
        }

        // 3. Snap to smallest interactive Accessibility node containing (rawX, rawY)
        val containing = elements
            .filter { it.bounds.contains(rawX.toInt(), rawY.toInt()) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
        if (containing != null) {
            return PointF(containing.bounds.exactCenterX(), containing.bounds.exactCenterY())
        }

        // 4. Gentle proximity snap (within 3% of screen width) if visual point slightly missed edge
        val maxSnapDist = (physicalWidth * 0.03f).coerceAtLeast(24f)
        val nearest = elements.minByOrNull {
            hypot(
                (it.bounds.exactCenterX() - rawX).toDouble(),
                (it.bounds.exactCenterY() - rawY).toDouble()
            )
        }
        if (nearest != null) {
            val dist = hypot(
                (nearest.bounds.exactCenterX() - rawX).toDouble(),
                (nearest.bounds.exactCenterY() - rawY).toDouble()
            )
            if (dist <= maxSnapDist) {
                return PointF(nearest.bounds.exactCenterX(), nearest.bounds.exactCenterY())
            }
        }

        return PointF(rawX, rawY)
    }

    suspend fun performScrollDown(physicalWidth: Int, physicalHeight: Int): Boolean {
        val cx = physicalWidth * 0.5f
        val startY = physicalHeight * 0.74f
        val endY = physicalHeight * 0.28f
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 320L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    suspend fun performTap(x: Float, y: Float, durationMs: Long = 55L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    suspend fun tapAndInputText(
        x: Float,
        y: Float,
        text: String,
        method: InputInjectionMethod
    ): Boolean {
        performTap(x, y)
        delay(240)
        // Extra brief settle for WebView / Compose inputs if focus hasn't attached yet
        if (findTargetInputNode(x, y) == null) {
            delay(160)
        }

        return when (method) {
            InputInjectionMethod.ACCESSIBILITY_SET_TEXT -> setTextViaAccessibility(x, y, text)
            InputInjectionMethod.CLIPBOARD_PASTE -> pasteViaClipboard(x, y, text)
            InputInjectionMethod.SHIZUKU -> {
                if (ShizukuHelper.isReady() && text.all { it.code < 128 }) {
                    ShizukuHelper.inputText(text)
                } else {
                    setTextViaAccessibility(x, y, text) || pasteViaClipboard(x, y, text)
                }
            }
            InputInjectionMethod.AUTO -> {
                if (setTextViaAccessibility(x, y, text)) {
                    true
                } else if (pasteViaClipboard(x, y, text)) {
                    true
                } else if (ShizukuHelper.isReady() && text.all { it.code < 128 }) {
                    ShizukuHelper.inputText(text)
                } else {
                    copyToClipboard(text)
                    false
                }
            }
        }
    }

    private fun findTargetInputNode(x: Float, y: Float): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        val root = rootInActiveWindow ?: return focused
        val atPoint = findEditableNodeAt(root, x.toInt(), y.toInt())
        if (atPoint != null) return atPoint
        return focused
    }

    private fun findEditableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findEditableNodeAt(child, x, y)
            if (match != null) return match
        }
        return if (node.isEditable) node else null
    }

    private fun setTextViaAccessibility(x: Float, y: Float, text: String): Boolean {
        val node = findTargetInputNode(x, y) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pasteViaClipboard(x: Float, y: Float, text: String): Boolean {
        copyToClipboard(text)
        val node = findTargetInputNode(x, y) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun isSoftKeyboardVisible(): Boolean {
        return try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Safely dismisses the Android soft keyboard ONLY when TYPE_INPUT_METHOD window
     * is confirmed visible, preventing accidental back-navigation out of the active app.
     */
    suspend fun dismissSoftKeyboardIfVisible(): Boolean {
        if (!isSoftKeyboardVisible()) return false

        // Primary: GLOBAL_ACTION_BACK closes the open IME first without leaving the app
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(280)
        if (!isSoftKeyboardVisible()) return true

        // Fallback: briefly hide IME via SoftKeyboardController then restore AUTO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                softKeyboardController.showMode = SHOW_MODE_HIDDEN
                delay(240)
                softKeyboardController.showMode = SHOW_MODE_AUTO
            }
            delay(180)
        }
        return !isSoftKeyboardVisible()
    }

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("DaFeiYu Answer", text))
    }

    companion object {
        @Volatile
        var instance: DaFeiYuAccessibilityService? = null
            private set
    }
}
