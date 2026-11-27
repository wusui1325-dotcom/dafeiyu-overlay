package com.dafeiyu.overlay.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dafeiyu.overlay.data.InputInjectionMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

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

    suspend fun captureScreenshotBase64(maxWidth: Int, jpegQuality: Int): String? {
        val bitmap = captureScreenBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scaled = if (bitmap.width > maxWidth && maxWidth > 0) {
                    val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
                    val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
                } else {
                    bitmap
                }
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(45, 95), stream)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
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
        // 1. Tap target input box to ensure focus
        performTap(x, y)
        delay(240)

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
