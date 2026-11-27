package com.dafeiyu.overlay.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.dafeiyu.overlay.R
import com.dafeiyu.overlay.data.OverlayThemeMode

data class OverlayPalette(
    val bg: Int,
    val surface: Int,
    val fg: Int,
    val fgMuted: Int,
    val border: Int,
    val accentBg: Int,
    val accentFg: Int
)

object MonochromeOverlayViews {

    fun paletteFor(context: Context, mode: OverlayThemeMode): OverlayPalette {
        val isDark = when (mode) {
            OverlayThemeMode.DARK -> true
            OverlayThemeMode.LIGHT -> false
            OverlayThemeMode.AUTO -> {
                val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                uiMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
        return if (isDark) {
            OverlayPalette(
                bg = Color.parseColor("#F50A0A0A"),
                surface = Color.parseColor("#FF1A1A1C"),
                fg = Color.parseColor("#FFFFFFFF"),
                fgMuted = Color.parseColor("#FFA1A1AA"),
                border = Color.parseColor("#FF2E2E32"),
                accentBg = Color.parseColor("#FFFFFFFF"),
                accentFg = Color.parseColor("#FF0A0A0A")
            )
        } else {
            OverlayPalette(
                bg = Color.parseColor("#F8FFFFFF"),
                surface = Color.parseColor("#FFF2F2F4"),
                fg = Color.parseColor("#FF0A0A0A"),
                fgMuted = Color.parseColor("#FF636366"),
                border = Color.parseColor("#FFDCDCE0"),
                accentBg = Color.parseColor("#FF0A0A0A"),
                accentFg = Color.parseColor("#FFFFFFFF")
            )
        }
    }

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun roundedBg(
        context: Context,
        fillColor: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 1,
        radiusDp: Int = 24
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(dp(context, strokeDp), strokeColor)
            }
        }
    }

    fun buildIconButton(
        context: Context,
        @DrawableRes iconRes: Int,
        label: String,
        bgColor: Int,
        fgColor: Int,
        borderColor: Int? = null,
        compact: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg(context, bgColor, borderColor, 1, if (compact) 18 else 22)
            val hPad = dp(context, if (compact) 12 else 16)
            val vPad = dp(context, if (compact) 8 else 12)
            setPadding(hPad, vPad, hPad, vPad)

            val icon = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate())
                setColorFilter(fgColor)
                val size = dp(context, if (compact) 16 else 18)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (label.isNotEmpty()) marginEnd = dp(context, 6)
                }
            }
            addView(icon)

            if (label.isNotEmpty()) {
                val tv = TextView(context).apply {
                    text = label
                    setTextColor(fgColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                }
                addView(tv)
            }

            setOnClickListener { onClick() }
        }
    }

    fun buildPillTextButton(
        context: Context,
        label: String,
        bgColor: Int,
        fgColor: Int,
        borderColor: Int? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedBg(context, bgColor, borderColor, 1, 18)
            val hPad = dp(context, 14)
            val vPad = dp(context, 8)
            setPadding(hPad, vPad, hPad, vPad)
            setOnClickListener { onClick() }
        }
    }

    fun buildFloatingOrb(
        context: Context,
        sizeDp: Int,
        alpha: Float,
        showLabel: Boolean,
        palette: OverlayPalette
    ): LinearLayout {
        val sizePx = dp(context, sizeDp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            this.alpha = alpha
            background = roundedBg(
                context = context,
                fillColor = palette.bg,
                strokeColor = palette.border,
                strokeDp = 1,
                radiusDp = sizeDp / 2 + 4
            )
            elevation = dp(context, 6).toFloat()

            val iconSize = (sizePx * 0.50f).toInt().coerceAtLeast(dp(context, 18))
            val icon = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_fish)?.mutate())
                setColorFilter(palette.fg)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            addView(icon)

            if (showLabel) {
                setPadding(dp(context, 12), 0, dp(context, 14), 0)
                minimumHeight = sizePx
                val label = TextView(context).apply {
                    text = "大肥魚"
                    setTextColor(palette.fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(context, 6)
                    }
                }
                addView(label)
            } else {
                minimumWidth = sizePx
                minimumHeight = sizePx
                val pad = ((sizePx - iconSize) / 2).coerceAtLeast(dp(context, 6))
                setPadding(pad, pad, pad, pad)
            }
        }
    }

    class CoordinateCaptureCanvasView(
        context: Context,
        private val palette: OverlayPalette,
        private val onTapRecorded: (Float, Float) -> Unit
    ) : View(context) {

        private val points = mutableListOf<PointF>()
        private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.accentBg
        }
        private val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 2).toFloat()
            color = palette.accentFg
        }
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentFg
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                context.resources.displayMetrics
            )
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val scrimPaint = Paint().apply {
            color = Color.argb(28, 0, 0, 0)
        }
        private val markerRadius = dp(context, 16).toFloat()

        fun setRecordedPoints(newPoints: List<PointF>) {
            points.clear()
            points.addAll(newPoints)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val offsetX = loc[0].toFloat()
            val offsetY = loc[1].toFloat()

            points.forEachIndexed { index, pt ->
                val cx = pt.x - offsetX
                val cy = pt.y - offsetY
                canvas.drawCircle(cx, cy, markerRadius, circleFill)
                canvas.drawCircle(cx, cy, markerRadius, circleStroke)
                val textY = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
                canvas.drawText((index + 1).toString(), cx, textY, numberPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onTapRecorded(event.rawX, event.rawY)
                return true
            }
            return true
        }
    }

    class RegionSelectCanvasView(
        context: Context,
        private val palette: OverlayPalette,
        private val onRegionSelected: (Rect) -> Unit
    ) : View(context) {

        private var startX = 0f
        private var startY = 0f
        private var currX = 0f
        private var currY = 0f
        private var dragging = false

        private val scrimPaint = Paint().apply {
            color = Color.argb(95, 0, 0, 0)
        }
        private val clearFillPaint = Paint().apply {
            color = Color.argb(24, 255, 255, 255)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 2).toFloat()
            color = Color.WHITE
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (!dragging) {
                canvas.drawRect(0f, 0f, w, h, scrimPaint)
                return
            }
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()

            val left = minOf(startX, currX) - ox
            val top = minOf(startY, currY) - oy
            val right = maxOf(startX, currX) - ox
            val bottom = maxOf(startY, currY) - oy

            // Dim outside region
            canvas.drawRect(0f, 0f, w, top, scrimPaint)
            canvas.drawRect(0f, bottom, w, h, scrimPaint)
            canvas.drawRect(0f, top, left, bottom, scrimPaint)
            canvas.drawRect(right, top, w, bottom, scrimPaint)

            val rect = RectF(left, top, right, bottom)
            canvas.drawRect(rect, clearFillPaint)
            canvas.drawRect(rect, borderPaint)

            val r = dp(context, 4).toFloat()
            canvas.drawCircle(left, top, r, cornerPaint)
            canvas.drawCircle(right, top, r, cornerPaint)
            canvas.drawCircle(left, bottom, r, cornerPaint)
            canvas.drawCircle(right, bottom, r, cornerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    currX = startX
                    currY = startY
                    dragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    currX = event.rawX
                    currY = event.rawY
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    currX = event.rawX
                    currY = event.rawY
                    dragging = false
                    invalidate()
                    val left = minOf(startX, currX).toInt()
                    val top = minOf(startY, currY).toInt()
                    val right = maxOf(startX, currX).toInt()
                    val bottom = maxOf(startY, currY).toInt()
                    val minSize = dp(context, 28)
                    if (right - left >= minSize && bottom - top >= minSize) {
                        onRegionSelected(Rect(left, top, right, bottom))
                    }
                }
            }
            return true
        }
    }
}
