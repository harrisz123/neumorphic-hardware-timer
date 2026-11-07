package com.neumorphic.hardwaretimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

/**
 * Main Studio Activity hosting the 1:1 industrial neumorphic hardware timer & stopwatch.
 * Fully wired to TactileFeedbackEngine, TimerStopwatchViewModel, and NeumorphicDialCanvas.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: TimerStopwatchViewModel by viewModels()
    private lateinit var tactileEngine: TactileFeedbackEngine

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Foreground service will post ongoing status updates
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tactileEngine = TactileFeedbackEngine(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            // Studio Screen Background: #E5E5E5 (Cool light studio grey)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HardwarePalette.ScreenBackground)
                    .systemBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                // 1:1 Neumorphic Hardware Dial Composable
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tactileEngine.release()
    }
}
