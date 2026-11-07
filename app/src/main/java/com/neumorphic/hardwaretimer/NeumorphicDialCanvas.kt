package com.neumorphic.hardwaretimer

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// --- EXACT HARDWARE COLOR PALETTE CONSTANTS ---
object HardwarePalette {
    val ScreenBackground = Color(0xFFE5E5E5)
    val ChassisBody = Color(0xFFDDD9CE)
    val ChassisRimHighlight = Color(0xFFEAE6DC)
    val ChassisDropShadow = Color(0xFFC4C0B5)
    val RecessedTrackWell = Color(0xFFCFCBC0)
    val RecessedInnerShadow = Color(0xFFB8B4A9)
    // Guardrail specification colors for inverted inner shadow
    val RecessedBevelTopLeft = Color(0xFFB5B1A6)
    val RecessedBevelBottomRight = Color(0xFFE8E4D9)
    val PlatterTopGradient = Color(0xFFE4E0D5)
    val PlatterBottomGradient = Color(0xFFD2CEC3)
    val CenterKnobTopFace = Color(0xFFE2DFD5)
    val CenterKnobSideWall = Color(0xFFC2BEB3)
    val CenterKnobShadow = Color(0xFF9E9A8F)
    val CenterTypography = Color(0xFF544D3C)
    val SecondaryLabels = Color(0xFF99958C)
    val LedDarkWell = Color(0xFF2B261D)
    val LedDarkDot = Color(0xFF9E8C60)
    val LedAmberCore = Color(0xFFFF9E1B)
    val LedAmberBorder = Color(0xFFD97200)
    val LedAmberGlow = Color(0x66FF9E1B)
    val LedAmberHighlight = Color(0xFFFFF2D4)
    val DialPointerOrange = Color(0xFFFF9500)
    val MinorRadialTick = Color(0xFFBFB9AC)
    val PillActiveFill = Color(0xFFF5B800)
    val PillActiveTopBevel = Color(0xFFC99300)
    val PillActiveBottomRim = Color(0xFFFCE068)
    val PillInactive = Color(0xFFC4C0B5)
}

enum class DeviceMode {
    TIMER,
    STOPWATCH
}

@Composable
fun NeumorphicDialCanvas(
    mode: DeviceMode,
    displayText: String,
    dialAngleDegrees: Float,
    isRunning: Boolean,
    isDone: Boolean,
    tactileEngine: TactileFeedbackEngine,
    onDialAngleChanged: (Float, Int) -> Unit,
    onKnobTapped: () -> Unit,
    onKnobLongPressed: () -> Unit,
    onStartAreaTapped: () -> Unit,
    onModeChanged: (DeviceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val localView = LocalView.current

    var isKnobPressed by remember { mutableStateOf(false) }

    val knobShadowOffsetDp by animateFloatAsState(
        targetValue = if (isKnobPressed) 4f else 10f,
        animationSpec = tween(durationMillis = 100),
        label = "knobDepression"
    )

    Box(
        modifier = modifier
            .size(320.dp)
            .pointerInput(mode, isRunning) {
                val chassisWidthPx = 320.dp.toPx()
                val chassisHeightPx = 320.dp.toPx()
                val cx = chassisWidthPx / 2f
                val cy = chassisHeightPx / 2f
                val knobRadiusPx = 52.dp.toPx()

                detectTapGestures(
                    onPress = { offset ->
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        if (sqrt(dx * dx + dy * dy) <= knobRadiusPx) {
                            isKnobPressed = true
                            tryAwaitRelease()
                            isKnobPressed = false
                        }
                    },
                    onTap = { offset ->
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val distFromCenter = sqrt(dx * dx + dy * dy)

                        // 1. Center Knob Tap
                        if (distFromCenter <= knobRadiusPx) {
                            tactileEngine.triggerDetentFeedback(localView)
                            onKnobTapped()
                            return@detectTapGestures
                        }

                        // 2. Top-Right "Start" Indicator Area Tap
                        val startAreaCenter = Offset(chassisWidthPx - 44.dp.toPx(), 40.dp.toPx())
                        if ((offset - startAreaCenter).getDistance() < 36.dp.toPx()) {
                            tactileEngine.triggerDetentFeedback(localView)
                            onStartAreaTapped()
                            return@detectTapGestures
                        }

                        // 3. Bottom Pill Buttons
                        val bottomY = chassisHeightPx - 32.dp.toPx()
                        val pillWidthPx = 28.dp.toPx()
                        val pillHeightPx = 10.dp.toPx()

                        val leftPillRect = Rect(
                            left = 48.dp.toPx() - pillWidthPx,
                            top = bottomY - pillHeightPx * 2,
                            right = 48.dp.toPx() + pillWidthPx,
                            bottom = bottomY + pillHeightPx * 2
                        )
                        if (leftPillRect.contains(offset)) {
                            tactileEngine.triggerDetentFeedback(localView)
                            onModeChanged(DeviceMode.TIMER)
                            return@detectTapGestures
                        }

                        val rightPillRect = Rect(
                            left = (chassisWidthPx - 48.dp.toPx()) - pillWidthPx,
                            top = bottomY - pillHeightPx * 2,
                            right = (chassisWidthPx - 48.dp.toPx()) + pillWidthPx,
                            bottom = bottomY + pillHeightPx * 2
                        )
                        if (rightPillRect.contains(offset)) {
                            tactileEngine.triggerDetentFeedback(localView)
                            onModeChanged(DeviceMode.STOPWATCH)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = { offset ->
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        if (sqrt(dx * dx + dy * dy) <= knobRadiusPx) {
                            tactileEngine.triggerDetentFeedback(localView)
                            onKnobLongPressed()
                        }
                    }
                )
            }
            .pointerInput(mode, isRunning) {
                val cx = 320.dp.toPx() / 2f
                val cy = 320.dp.toPx() / 2f
                val innerPlatterPx = 52.dp.toPx()
                val outerPlatterPx = 114.dp.toPx()

                detectDragGestures(
                    onDragStart = { offset ->
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist in innerPlatterPx..outerPlatterPx) {
                            tactileEngine.onTouchDown(offset.x, offset.y, cx, cy, dialAngleDegrees)
                        }
                    },
                    onDragEnd = { tactileEngine.onTouchUp() },
                    onDragCancel = { tactileEngine.onTouchUp() },
                    onDrag = { change, _ ->
                        val pos = change.position
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val dist = sqrt(dx * dx + dy * dy)

                        if (dist in (innerPlatterPx * 0.8f)..(outerPlatterPx * 1.25f)) {
                            change.consume()
                            val (newAngle, tickDiff) = tactileEngine.onRotaryDrag(
                                pos.x, pos.y, cx, cy, localView
                            )
                            if (tickDiff != 0) {
                                onDialAngleChanged(newAngle, tickDiff)
                            }
                        }
                    }
                )
            }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val chassisSize = size.width
            val cx = size.width / 2f
            val cy = size.height / 2f

            // 1. NEUMORPHIC CHASSIS (Square 320dp, CornerRadius 48dp)
            drawChassisNeumorphicHousing(chassisSize, 48.dp.toPx())

            // 2. TOP INDICATORS (Done on Left, Start on Right)
            drawTopIndicators(chassisSize, isRunning, isDone, textMeasurer)

            // 3. 12 RADIAL TICK MARKS (Radius 122dp to 128dp every 30 deg)
            drawRadialTicks(cx, cy, 122.dp.toPx(), 128.dp.toPx())

            // 4. RECESSED TRACK WELL (Outer Ring, Radius 112dp with inverted inner shadow bevel)
            drawRecessedTrackWell(cx, cy, 112.dp.toPx())

            // 5. MAIN ROTARY PLATTER (Middle Ring, Radius 104dp)
            drawRotaryPlatter(cx, cy, 104.dp.toPx(), dialAngleDegrees)

            // 6. CENTER KNOB CAP (Stationary / Pressable, Radius 52dp with dedicated BlurMaskFilter shadow)
            drawCenterKnob(cx, cy, 52.dp.toPx(), knobShadowOffsetDp.dp.toPx(), displayText, textMeasurer)

            // 7. BOTTOM MODE PILL BUTTONS (Timer & Stopwatch)
            drawBottomPills(chassisSize, chassisSize, mode)
        }
    }
}

private fun DrawScope.drawChassisNeumorphicHousing(chassisSize: Float, cornerRadius: Float) {
    val cr = CornerRadius(cornerRadius, cornerRadius)

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas

        val shadowPaint = AndroidPaint().apply {
            color = HardwarePalette.ChassisDropShadow.toArgb()
            isAntiAlias = true
            maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
            10f, 12f,
            chassisSize + 10f, chassisSize + 14f,
            cornerRadius, cornerRadius,
            shadowPaint
        )

        val highlightPaint = AndroidPaint().apply {
            color = HardwarePalette.ChassisRimHighlight.toArgb()
            isAntiAlias = true
            maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
            -8f, -8f,
            chassisSize - 8f, chassisSize - 8f,
            cornerRadius, cornerRadius,
            highlightPaint
        )
    }

    drawRoundRect(
        color = HardwarePalette.ChassisBody,
        topLeft = Offset(0f, 0f),
        size = Size(chassisSize, chassisSize),
        cornerRadius = cr
    )

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                HardwarePalette.ChassisRimHighlight,
                Color.Transparent,
                HardwarePalette.ChassisDropShadow.copy(alpha = 0.35f)
            ),
            start = Offset(0f, 0f),
            end = Offset(chassisSize, chassisSize)
        ),
        topLeft = Offset(0.5f, 0.5f),
        size = Size(chassisSize - 1f, chassisSize - 1f),
        cornerRadius = cr,
        style = Stroke(width = 1.5f)
    )
}

private fun DrawScope.drawTopIndicators(
    chassisWidth: Float,
    isRunning: Boolean,
    isDone: Boolean,
    textMeasurer: TextMeasurer
) {
    val leftCenterX = 44.dp.toPx()
    val topY = 40.dp.toPx()
    val rightCenterX = chassisWidth - 44.dp.toPx()
    val doneLedRadius = 6.dp.toPx()

    // --- TOP-LEFT: "Done" + Recessed Dark LED ---
    drawCircle(
        color = HardwarePalette.LedDarkWell,
        radius = doneLedRadius,
        center = Offset(leftCenterX, topY)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
            center = Offset(leftCenterX, topY),
            radius = doneLedRadius
        ),
        radius = doneLedRadius,
        center = Offset(leftCenterX, topY)
    )
    val centerDotColor = if (isDone) HardwarePalette.PillActiveBottomRim else HardwarePalette.LedDarkDot
    drawCircle(
        color = centerDotColor,
        radius = 1.8.dp.toPx(),
        center = Offset(leftCenterX, topY)
    )
    if (isDone) {
        drawCircle(
            color = HardwarePalette.DialPointerOrange.copy(alpha = 0.45f),
            radius = 10.dp.toPx(),
            center = Offset(leftCenterX, topY)
        )
    }

    val doneTextResult = textMeasurer.measure(
        text = "Done",
        style = TextStyle(
            color = HardwarePalette.SecondaryLabels,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif
        )
    )
    drawText(
        textLayoutResult = doneTextResult,
        topLeft = Offset(leftCenterX + 12.dp.toPx(), topY - (doneTextResult.size.height / 2f))
    )

    // --- TOP-RIGHT: "Start" + Glowing Amber LED ---
    val startTextResult = textMeasurer.measure(
        text = "Start",
        style = TextStyle(
            color = HardwarePalette.SecondaryLabels,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif
        )
    )
    drawText(
        textLayoutResult = startTextResult,
        topLeft = Offset(rightCenterX - 14.dp.toPx() - startTextResult.size.width, topY - (startTextResult.size.height / 2f))
    )

    val startLedRadius = 6.dp.toPx()
    if (isRunning) {
        drawCircle(
            color = HardwarePalette.LedAmberGlow,
            radius = 12.dp.toPx(),
            center = Offset(rightCenterX, topY)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    HardwarePalette.LedAmberCore,
                    HardwarePalette.LedAmberGlow,
                    Color.Transparent
                ),
                center = Offset(rightCenterX, topY),
                radius = 10.dp.toPx()
            ),
            radius = 10.dp.toPx(),
            center = Offset(rightCenterX, topY)
        )
        drawCircle(
            color = HardwarePalette.LedAmberBorder,
            radius = startLedRadius,
            center = Offset(rightCenterX, topY)
        )
        drawCircle(
            color = HardwarePalette.LedAmberCore,
            radius = 4.2.dp.toPx(),
            center = Offset(rightCenterX, topY)
        )
        drawCircle(
            color = HardwarePalette.LedAmberHighlight,
            radius = 1.6.dp.toPx(),
            center = Offset(rightCenterX - 1.2f, topY - 1.2f)
        )
    } else {
        drawCircle(
            color = HardwarePalette.LedAmberBorder.copy(alpha = 0.35f),
            radius = startLedRadius,
            center = Offset(rightCenterX, topY)
        )
        drawCircle(
            color = HardwarePalette.LedAmberCore.copy(alpha = 0.5f),
            radius = 3.5.dp.toPx(),
            center = Offset(rightCenterX, topY)
        )
    }
}

private fun DrawScope.drawRadialTicks(cx: Float, cy: Float, innerRadius: Float, outerRadius: Float) {
    for (i in 0 until 12) {
        val angleDeg = i * 30f
        val angleRad = (angleDeg - 90f) * (PI / 180.0)

        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()

        val start = Offset(cx + innerRadius * cosA, cy + innerRadius * sinA)
        val end = Offset(cx + outerRadius * cosA, cy + outerRadius * sinA)

        val isCardinal12 = (i == 0)
        val tickColor = if (isCardinal12) HardwarePalette.DialPointerOrange else HardwarePalette.MinorRadialTick
        val strokeWidth = if (isCardinal12) 2.5.dp.toPx() else 1.5.dp.toPx()

        drawLine(
            color = tickColor,
            start = start,
            end = end,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 4. Recessed Track Well:
 * Radius: 112dp from center.
 * Base color: #CFCBC0 with inner gradient.
 * GUARDRAIL FIX: Uses a Brush.linearGradient from #B5B1A6 (top-left) to #E8E4D9 (bottom-right)
 * on the outer ring stroke to create an authentic inverted inner shadow.
 */
private fun DrawScope.drawRecessedTrackWell(cx: Float, cy: Float, radius: Float) {
    // Trench base plate
    drawCircle(
        color = HardwarePalette.RecessedTrackWell,
        radius = radius,
        center = Offset(cx, cy)
    )

    // Inner top diffuse shadow
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                HardwarePalette.RecessedInnerShadow.copy(alpha = 0.95f),
                HardwarePalette.RecessedTrackWell,
                HardwarePalette.ChassisRimHighlight.copy(alpha = 0.5f)
            ),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius)
        ),
        radius = radius,
        center = Offset(cx, cy)
    )

    // Guardrail specification: Inverted inner shadow bevel stroke
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                HardwarePalette.RecessedBevelTopLeft,     // #B5B1A6 (top-left)
                HardwarePalette.RecessedBevelBottomRight  // #E8E4D9 (bottom-right)
            ),
            start = Offset(cx - radius, cy - radius),
            end = Offset(cx + radius, cy + radius)
        ),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 2.dp.toPx())
    )
}

private fun DrawScope.drawRotaryPlatter(cx: Float, cy: Float, radius: Float, dialAngleDegrees: Float) {
    drawCircle(
        color = Color(0x33000000),
        radius = radius + 1f,
        center = Offset(cx + 1.5f, cy + 3f)
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                HardwarePalette.PlatterTopGradient,
                HardwarePalette.PlatterBottomGradient
            ),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius)
        ),
        radius = radius,
        center = Offset(cx, cy)
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.6f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.25f)
            ),
            start = Offset(cx - radius, cy - radius),
            end = Offset(cx + radius, cy + radius)
        ),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f)
    )

    val pointerInnerRadius = 72.dp.toPx()
    val pointerOuterRadius = 98.dp.toPx()
    val pointerAngleRad = (dialAngleDegrees - 90f) * (PI / 180.0)

    val pCos = cos(pointerAngleRad).toFloat()
    val pSin = sin(pointerAngleRad).toFloat()

    val pStart = Offset(cx + pointerInnerRadius * pCos, cy + pointerInnerRadius * pSin)
    val pEnd = Offset(cx + pointerOuterRadius * pCos, cy + pointerOuterRadius * pSin)

    drawLine(
        color = HardwarePalette.DialPointerOrange.copy(alpha = 0.35f),
        start = pStart,
        end = pEnd,
        strokeWidth = 5.dp.toPx(),
        cap = StrokeCap.Round
    )

    drawLine(
        color = HardwarePalette.DialPointerOrange,
        start = pStart,
        end = pEnd,
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * 6. Center Stationary / Pressable Knob Cap:
 * Radius: 52dp.
 * GUARDRAIL FIX: Draws a dedicated drawCircle shadow layer shifted y + 10.dp with a BlurMaskFilter(24f, Blur.NORMAL)
 * inside drawIntoCanvas before drawing the knob cap.
 */
private fun DrawScope.drawCenterKnob(
    cx: Float,
    cy: Float,
    radius: Float,
    shadowOffsetPx: Float,
    displayText: String,
    textMeasurer: TextMeasurer
) {
    // 1. Guardrail specification: Dedicated drawCircle shadow layer shifted y + 10.dp with BlurMaskFilter(24f, Blur.NORMAL)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas

        val knobShadowPaint = AndroidPaint().apply {
            color = HardwarePalette.CenterKnobShadow.toArgb()
            isAntiAlias = true
            maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawCircle(
            cx,
            cy + shadowOffsetPx, // Animates to 10.dp resting offset
            radius + 1f,
            knobShadowPaint
        )
    }

    // 2. 3D Extruded cylinder side wall
    val extrusionHeight = shadowOffsetPx * 0.7f
    drawCircle(
        color = HardwarePalette.CenterKnobSideWall,
        radius = radius,
        center = Offset(cx, cy + extrusionHeight)
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                HardwarePalette.CenterKnobShadow.copy(alpha = 0.45f)
            ),
            start = Offset(cx, cy),
            end = Offset(cx, cy + extrusionHeight + radius)
        ),
        radius = radius,
        center = Offset(cx, cy + extrusionHeight)
    )

    // 3. Top face of knob
    val knobTopCenter = Offset(cx, cy)
    drawCircle(
        color = HardwarePalette.CenterKnobTopFace,
        radius = radius,
        center = knobTopCenter
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f),
                HardwarePalette.CenterKnobTopFace,
                HardwarePalette.CenterKnobSideWall.copy(alpha = 0.4f)
            ),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius)
        ),
        radius = radius,
        center = knobTopCenter
    )

    drawCircle(
        color = Color.White.copy(alpha = 0.35f),
        radius = radius - 0.5f,
        center = knobTopCenter,
        style = Stroke(width = 1f)
    )

    val fontSize = if (displayText.length > 4) 26.sp else 32.sp
    val textResult = textMeasurer.measure(
        text = displayText,
        style = TextStyle(
            color = HardwarePalette.CenterTypography,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.SansSerif
        )
    )
    val textTopLeft = Offset(
        cx - (textResult.size.width / 2f),
        cy - (textResult.size.height / 2f)
    )
    drawText(
        textLayoutResult = textResult,
        topLeft = textTopLeft
    )
}

private fun DrawScope.drawBottomPills(
    chassisWidth: Float,
    chassisHeight: Float,
    activeMode: DeviceMode
) {
    val pillWidth = 28.dp.toPx()
    val pillHeight = 6.dp.toPx()
    val pillCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    val pillY = chassisHeight - 32.dp.toPx()

    val leftPillX = 48.dp.toPx() - (pillWidth / 2f)
    drawSinglePill(
        topLeft = Offset(leftPillX, pillY),
        width = pillWidth,
        height = pillHeight,
        cornerRadius = pillCorner,
        isActive = (activeMode == DeviceMode.TIMER)
    )

    val rightPillX = (chassisWidth - 48.dp.toPx()) - (pillWidth / 2f)
    drawSinglePill(
        topLeft = Offset(rightPillX, pillY),
        width = pillWidth,
        height = pillHeight,
        cornerRadius = pillCorner,
        isActive = (activeMode == DeviceMode.STOPWATCH)
    )
}

private fun DrawScope.drawSinglePill(
    topLeft: Offset,
    width: Float,
    height: Float,
    cornerRadius: CornerRadius,
    isActive: Boolean
) {
    val pillSize = Size(width, height)

    if (isActive) {
        drawRoundRect(
            color = HardwarePalette.PillActiveFill,
            topLeft = topLeft,
            size = pillSize,
            cornerRadius = cornerRadius
        )

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(HardwarePalette.PillActiveTopBevel, Color.Transparent),
                startY = topLeft.y,
                endY = topLeft.y + height
            ),
            topLeft = topLeft,
            size = pillSize,
            cornerRadius = cornerRadius
        )

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, HardwarePalette.PillActiveBottomRim),
                startY = topLeft.y,
                endY = topLeft.y + height
            ),
            topLeft = topLeft,
            size = pillSize,
            cornerRadius = cornerRadius,
            style = Stroke(width = 1f)
        )
    } else {
        drawRoundRect(
            color = HardwarePalette.PillInactive,
            topLeft = topLeft,
            size = pillSize,
            cornerRadius = cornerRadius
        )

        drawRoundRect(
            color = HardwarePalette.RecessedInnerShadow.copy(alpha = 0.5f),
            topLeft = topLeft,
            size = pillSize,
            cornerRadius = cornerRadius,
            style = Stroke(width = 1f)
        )
    }
}
