package com.neumorphic.hardwaretimer

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1:1 Bitmap renderer for Jetpack Glance AppWidget incorporating all key technical guardrails:
 * 1. Dedicated drawCircle shadow layer shifted y + 10.dp with BlurMaskFilter(24f, Blur.NORMAL).
 * 2. Inverted inner shadow stroke using LinearGradient from #B5B1A6 to #E8E4D9.
 */
object WidgetBitmapRenderer {

    const val BITMAP_SIZE = 600
    private const val BASE_SIZE = 320f
    private const val SCALE = BITMAP_SIZE / BASE_SIZE // 1.875f

    private fun dpToPx(dp: Float): Float = dp * SCALE

    fun renderWidgetBitmap(state: HardwareState): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val size = BITMAP_SIZE.toFloat()
        val cx = size / 2f
        val cy = size / 2f
        val cornerRadius = dpToPx(48f)

        // 1. NEUMORPHIC CHASSIS BASE & SHADOWS
        drawChassis(canvas, size, cornerRadius)

        // 2. TOP INDICATORS ("Done" on left, "Start" on right)
        drawTopIndicators(canvas, size, state.isRunning, state.isDone)

        // 3. 12 RADIAL TICKS
        drawRadialTicks(canvas, cx, cy)

        // 4. RECESSED TRACK WELL WITH INVERTED INNER SHADOW STROKE (#B5B1A6 to #E8E4D9)
        drawRecessedWell(canvas, cx, cy)

        // 5. ROTARY PLATTER & DIAL POINTER
        drawRotaryPlatter(canvas, cx, cy, state.dialAngleDegrees)

        // 6. 3D CENTER KNOB WITH DEDICATED BLURMASKFILTER(24f) SHADOW
        drawCenterKnob(canvas, cx, cy, state.displayText)

        // 7. BOTTOM MODE PILLS
        drawBottomPills(canvas, size, state.mode)

        return bitmap
    }

    private fun drawChassis(canvas: Canvas, size: Float, cornerRadius: Float) {
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC4C0B5.toInt()
            maskFilter = BlurMaskFilter(dpToPx(12f), BlurMaskFilter.Blur.NORMAL)
        }
        val shadowRect = RectF(dpToPx(8f), dpToPx(10f), size + dpToPx(4f), size + dpToPx(6f))
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEAE6DC.toInt()
            maskFilter = BlurMaskFilter(dpToPx(10f), BlurMaskFilter.Blur.NORMAL)
        }
        val highlightRect = RectF(-dpToPx(6f), -dpToPx(6f), size - dpToPx(6f), size - dpToPx(6f))
        canvas.drawRoundRect(highlightRect, cornerRadius, cornerRadius, highlightPaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDDD9CE.toInt()
            style = Paint.Style.FILL
        }
        val chassisRect = RectF(0f, 0f, size, size)
        canvas.drawRoundRect(chassisRect, cornerRadius, cornerRadius, bodyPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size, size,
                intArrayOf(0xFFEAE6DC.toInt(), 0x00000000, 0x40C4C0B5.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1.5f)
        }
        canvas.drawRoundRect(chassisRect, cornerRadius, cornerRadius, rimPaint)
    }

    private fun drawTopIndicators(canvas: Canvas, size: Float, isRunning: Boolean, isDone: Boolean) {
        val leftX = dpToPx(44f)
        val topY = dpToPx(40f)
        val rightX = size - dpToPx(44f)
        val ledRadius = dpToPx(6f)

        // --- TOP-LEFT: "Done" + Dark Well LED ---
        val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B261D.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(leftX, topY, ledRadius, wellPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDone) 0xFFFCE068.toInt() else 0xFF9E8C60.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(leftX, topY, dpToPx(1.8f), dotPaint)

        if (isDone) {
            val doneGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80FF9500.toInt()
                maskFilter = BlurMaskFilter(dpToPx(8f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(leftX, topY, dpToPx(10f), doneGlowPaint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF99958C.toInt()
            textSize = dpToPx(13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val doneFontMetrics = textPaint.fontMetrics
        val textY = topY - (doneFontMetrics.ascent + doneFontMetrics.descent) / 2f
        canvas.drawText("Done", leftX + dpToPx(12f), textY, textPaint)

        // --- TOP-RIGHT: "Start" + Amber LED ---
        val startTextWidth = textPaint.measureText("Start")
        canvas.drawText("Start", rightX - dpToPx(14f) - startTextWidth, textY, textPaint)

        if (isRunning) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FF9E1B.toInt()
                maskFilter = BlurMaskFilter(dpToPx(12f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(rightX, topY, dpToPx(12f), glowPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFD97200.toInt()
            }
            canvas.drawCircle(rightX, topY, ledRadius, borderPaint)

            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF9E1B.toInt()
            }
            canvas.drawCircle(rightX, topY, dpToPx(4.5f), corePaint)

            val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFF2D4.toInt()
            }
            canvas.drawCircle(rightX - dpToPx(1.2f), topY - dpToPx(1.2f), dpToPx(1.5f), specPaint)
        } else {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66D97200.toInt()
            }
            canvas.drawCircle(rightX, topY, ledRadius, borderPaint)

            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80FF9E1B.toInt()
            }
            canvas.drawCircle(rightX, topY, dpToPx(3.5f), corePaint)
        }
    }

    private fun drawRadialTicks(canvas: Canvas, cx: Float, cy: Float) {
        val innerR = dpToPx(122f)
        val outerR = dpToPx(128f)

        for (i in 0 until 12) {
            val angleDeg = i * 30f
            val angleRad = (angleDeg - 90f) * (PI / 180.0)

            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val x1 = cx + innerR * cosA
            val y1 = cy + innerR * sinA
            val x2 = cx + outerR * cosA
            val y2 = cy + outerR * sinA

            val isCardinal12 = (i == 0)
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isCardinal12) 0xFFFF9500.toInt() else 0xFFBFB9AC.toInt()
                strokeWidth = if (isCardinal12) dpToPx(2.5f) else dpToPx(1.5f)
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }

    /**
     * 4. Recessed Well:
     * GUARDRAIL FIX: Linear gradient from #B5B1A6 (top-left) to #E8E4D9 (bottom-right)
     * on the outer ring stroke creating the inverted inner shadow.
     */
    private fun drawRecessedWell(canvas: Canvas, cx: Float, cy: Float) {
        val r = dpToPx(112f)
        val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCFCBC0.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, wellPaint)

        // Diffuse top shadow
        val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, cy - r, cx, cy + r,
                intArrayOf(0xFFA5A196.toInt(), 0xFFCFCBC0.toInt(), 0x66EAE6DC.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, innerShadowPaint)

        // Guardrail specification: Inverted inner shadow stroke
        val bevelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                0xFFB5B1A6.toInt(), 0xFFE8E4D9.toInt(),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }
        canvas.drawCircle(cx, cy, r, bevelStrokePaint)
    }

    private fun drawRotaryPlatter(canvas: Canvas, cx: Float, cy: Float, dialAngleDegrees: Float) {
        val r = dpToPx(104f)

        val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            maskFilter = BlurMaskFilter(dpToPx(4f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx + dpToPx(2f), cy + dpToPx(3f), r, dropPaint)

        val platterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, cy - r, cx, cy + r,
                0xFFE4E0D5.toInt(), 0xFFD2CEC3.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, platterPaint)

        val pInner = dpToPx(72f)
        val pOuter = dpToPx(98f)
        val rad = (dialAngleDegrees - 90f) * (PI / 180.0)

        val x1 = cx + pInner * cos(rad).toFloat()
        val y1 = cy + pInner * sin(rad).toFloat()
        val x2 = cx + pOuter * cos(rad).toFloat()
        val y2 = cy + pOuter * sin(rad).toFloat()

        val pointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FF9500.toInt()
            strokeWidth = dpToPx(5f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x1, y1, x2, y2, pointerGlow)

        val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF9500.toInt()
            strokeWidth = dpToPx(3f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x1, y1, x2, y2, pointerPaint)
    }

    /**
     * 6. Center Knob:
     * GUARDRAIL FIX: Dedicated drawCircle shadow layer shifted y + 10.dp with BlurMaskFilter(24f, Blur.NORMAL).
     */
    private fun drawCenterKnob(canvas: Canvas, cx: Float, cy: Float, displayText: String) {
        val r = dpToPx(52f)
        val shadowOffset = dpToPx(10f)

        // Guardrail specification: Dedicated drawCircle shadow layer shifted y + 10.dp with BlurMaskFilter(24f, Blur.NORMAL)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9E9A8F.toInt()
            maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy + shadowOffset, r, shadowPaint)

        // 3D side wall
        val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC2BEB3.toInt()
        }
        canvas.drawCircle(cx, cy + shadowOffset * 0.7f, r, sidePaint)

        // Top face
        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx, cy - r, cx, cy + r,
                0xFFECE9DF.toInt(), 0xFFE2DFD5.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, topPaint)

        // Typography
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF544D3C.toInt()
            textSize = if (displayText.length > 4) dpToPx(26f) else dpToPx(32f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(displayText, cx, textY, textPaint)
    }

    private fun drawBottomPills(canvas: Canvas, size: Float, activeMode: DeviceMode) {
        val pillW = dpToPx(28f)
        val pillH = dpToPx(6f)
        val pillY = size - dpToPx(32f)
        val cr = dpToPx(4f)

        val leftX = dpToPx(48f) - (pillW / 2f)
        drawPill(canvas, leftX, pillY, pillW, pillH, cr, activeMode == DeviceMode.TIMER)

        val rightX = (size - dpToPx(48f)) - (pillW / 2f)
        drawPill(canvas, rightX, pillY, pillW, pillH, cr, activeMode == DeviceMode.STOPWATCH)
    }

    private fun drawPill(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, cr: Float, isActive: Boolean) {
        val rect = RectF(x, y, x + w, y + h)
        if (isActive) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF5B800.toInt()
            }
            canvas.drawRoundRect(rect, cr, cr, fillPaint)

            val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x, y, x, y + h,
                    0xFFC99300.toInt(), 0x00000000,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, cr, cr, bevelPaint)

            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x, y, x, y + h,
                    0x00000000, 0xFFFCE068.toInt(),
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(1f)
            }
            canvas.drawRoundRect(rect, cr, cr, rimPaint)
        } else {
            val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFC4C0B5.toInt()
            }
            canvas.drawRoundRect(rect, cr, cr, dimPaint)
        }
    }
}
