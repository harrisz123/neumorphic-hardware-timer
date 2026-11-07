package com.neumorphic.hardwaretimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * High-performance translucent activity that opens instantly over the Home Screen widget.
 * Bridges Jetpack Glance with the full Jetpack Compose Canvas touch pipeline,
 * allowing users to smoothly spin the dial at 60fps with real-time synthesized audio clicks
 * and hardware haptics without RemoteViews gesture limitations.
 */
class QuickDialOverlayActivity : ComponentActivity() {

    private val viewModel: TimerStopwatchViewModel by viewModels()
    private lateinit var tactileEngine: TactileFeedbackEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tactileEngine = TactileFeedbackEngine(this)

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            // Translucent dim backdrop - tapping outside dismisses the overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        finish()
                    },
                contentAlignment = Alignment.Center
            ) {
                // Fully interactive hardware dial composable
                NeumorphicDialCanvas(
                    mode = uiState.mode,
                    displayText = uiState.displayText,
                    dialAngleDegrees = uiState.dialAngleDegrees,
                    isRunning = uiState.isRunning,
                    isDone = uiState.isDone,
                    tactileEngine = tactileEngine,
                    onDialAngleChanged = { newAngle, tickDiff ->
                        viewModel.onDialAngleScrubbed(newAngle, tickDiff)
                    },
                    onKnobTapped = {
                        viewModel.toggleRunning()
                    },
                    onKnobLongPressed = {
                        viewModel.reset()
                    },
                    onStartAreaTapped = {
                        viewModel.toggleRunning()
                    },
                    onModeChanged = { newMode ->
                        viewModel.setMode(newMode)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tactileEngine.release()
    }
}
