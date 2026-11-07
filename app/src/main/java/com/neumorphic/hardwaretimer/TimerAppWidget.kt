package com.neumorphic.hardwaretimer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width

/**
 * Jetpack Glance 1.1.0 AppWidget implementation.
 * Renders the 1:1 hardware dial aesthetic via offscreen Bitmap (WidgetBitmapRenderer)
 * and overlays transparent click targets to circumvent RemoteViews gesture limitations.
 */
class TimerAppWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HardwareStateRepository.init(context)

        provideContent {
            WidgetContent(context)
        }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        val state = HardwareStateRepository.state.value
        val bitmap = WidgetBitmapRenderer.renderWidgetBitmap(state)

        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 1. Procedural 1:1 Hardware Dial Bitmap
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "Neumorphic Hardware Dial",
                modifier = GlanceModifier.fillMaxSize()
            )

            // 2. Interactive Transparent Tap Zones Overlaid on Canvas Elements
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top area: Quick Scrub Launcher
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clickable(actionStartActivity<QuickDialOverlayActivity>())
                ) {}

                // Middle dial row: Left (-1m), Center Knob (Start/Pause), Right (+1m)
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(130.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Left dial tap target: Quick -1m step
                    Box(
                        modifier = GlanceModifier
                            .fillMaxHeight()
                            .width(80.dp)
                            .clickable(actionRunCallback<AdjustTimerMinusCallback>())
                    ) {}

                    // Center Knob tap target: Toggle Start / Pause
                    Box(
                        modifier = GlanceModifier
                            .size(110.dp)
                            .clickable(actionRunCallback<ToggleRunActionCallback>())
                    ) {}

                    // Right dial tap target: Quick +1m step
                    Box(
                        modifier = GlanceModifier
                            .fillMaxHeight()
                            .width(80.dp)
                            .clickable(actionRunCallback<AdjustTimerPlusCallback>())
                    ) {}
                }

                // Bottom row: Mode switch pill buttons & scrub overlay trigger
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Left Pill: Timer Mode Switch
                    Box(
                        modifier = GlanceModifier
                            .width(80.dp)
                            .height(40.dp)
                            .clickable(actionRunCallback<SwitchTimerModeCallback>())
                    ) {}

                    // Center gap: Launch 60fps Continuous Scrub Overlay
                    Box(
                        modifier = GlanceModifier
                            .width(100.dp)
                            .height(40.dp)
                            .clickable(actionStartActivity<QuickDialOverlayActivity>())
                    ) {}

                    // Right Pill: Stopwatch Mode Switch
                    Box(
                        modifier = GlanceModifier
                            .width(80.dp)
                            .height(40.dp)
                            .clickable(actionRunCallback<SwitchStopwatchModeCallback>())
                    ) {}
                }
            }
        }
    }
}

class TimerAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimerAppWidget()
}

// --- GLANCE ACTION CALLBACKS ---

class ToggleRunActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tactile = TactileFeedbackEngine(context)
        tactile.triggerDetentFeedback()
        tactile.release()

        TimerForegroundService.startService(context, TimerForegroundService.ACTION_TOGGLE)
    }
}

class AdjustTimerMinusCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tactile = TactileFeedbackEngine(context)
        tactile.triggerDetentFeedback()
        tactile.release()

        TimerForegroundService.adjustTimer(context, -60)
    }
}

class AdjustTimerPlusCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tactile = TactileFeedbackEngine(context)
        tactile.triggerDetentFeedback()
        tactile.release()

        TimerForegroundService.adjustTimer(context, +60)
    }
}

class SwitchTimerModeCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tactile = TactileFeedbackEngine(context)
        tactile.triggerDetentFeedback()
        tactile.release()

        HardwareStateRepository.updateState(context) {
            it.copy(
                mode = DeviceMode.TIMER,
                isRunning = false,
                isDone = false,
                dialAngleDegrees = 0f
            )
        }
        TimerForegroundService.startService(context, TimerForegroundService.ACTION_PAUSE)
    }
}

class SwitchStopwatchModeCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tactile = TactileFeedbackEngine(context)
        tactile.triggerDetentFeedback()
        tactile.release()

        HardwareStateRepository.updateState(context) {
            it.copy(
                mode = DeviceMode.STOPWATCH,
                isRunning = false,
                isDone = false,
                dialAngleDegrees = 0f
            )
        }
        TimerForegroundService.startService(context, TimerForegroundService.ACTION_PAUSE)
    }
}
