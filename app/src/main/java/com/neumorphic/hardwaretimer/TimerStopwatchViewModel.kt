package com.neumorphic.hardwaretimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel managing reactive UI state, user rotary adjustments, and delegating
 * background timing operations to the precision TimerForegroundService.
 */
class TimerStopwatchViewModel(application: Application) : AndroidViewModel(application) {

    val uiState: StateFlow<HardwareState> = HardwareStateRepository.state

    init {
        HardwareStateRepository.init(application.applicationContext)
    }

    /**
     * Toggles between running and paused states.
     */
    fun toggleRunning() {
        TimerForegroundService.startService(
            getApplication(),
            TimerForegroundService.ACTION_TOGGLE
        )
    }

    /**
     * Starts the active timer or stopwatch ticker.
     */
    fun start() {
        TimerForegroundService.startService(
            getApplication(),
            TimerForegroundService.ACTION_START
        )
    }

    /**
     * Pauses the active timer or stopwatch ticker.
     */
    fun pause() {
        TimerForegroundService.startService(
            getApplication(),
            TimerForegroundService.ACTION_PAUSE
        )
    }

    /**
     * Resets the active mode back to initial state (Timer resets to total duration; Stopwatch resets to 00:00).
     */
    fun reset() {
        TimerForegroundService.startService(
            getApplication(),
            TimerForegroundService.ACTION_RESET
        )
    }

    /**
     * Switches between Timer and Stopwatch modes, pausing any currently running operation.
     */
    fun setMode(newMode: DeviceMode) {
        val cur = uiState.value
        if (cur.mode != newMode) {
            pause()
            HardwareStateRepository.updateState(getApplication()) {
                it.copy(
                    mode = newMode,
                    isRunning = false,
                    isDone = false,
                    dialAngleDegrees = 0f
                )
            }
        }
    }

    /**
     * Handles rotary dial scrubbing.
     * Timer Mode:
     * - Under 5 minutes (< 300s): Quantized 15-second adjustments per 6-degree detent.
     * - 5 minutes and above (>= 300s): Quantized 1-minute (60s) adjustments per detent.
     * - Clamped within 15 seconds to 120 minutes.
     * Stopwatch Mode:
     * - Updates indicator sweep angle.
     */
    fun onDialAngleScrubbed(newAngle: Float, tickDiff: Int) {
        val cur = uiState.value
        val normalizedAngle = ((newAngle % 360f) + 360f) % 360f

        when (cur.mode) {
            DeviceMode.TIMER -> {
                if (!cur.isRunning) {
                    val stepSeconds = if (cur.timerTotalSeconds < 300) 15 else 60
                    val deltaSec = tickDiff * stepSeconds
                    val updatedTotal = (cur.timerTotalSeconds + deltaSec).coerceIn(15, 120 * 60)

                    HardwareStateRepository.updateState(getApplication()) {
                        it.copy(
                            timerTotalSeconds = updatedTotal,
                            timerRemainingSeconds = updatedTotal,
                            dialAngleDegrees = normalizedAngle,
                            isDone = false
                        )
                    }
                } else {
                    HardwareStateRepository.updateState(getApplication()) {
                        it.copy(dialAngleDegrees = normalizedAngle)
                    }
                }
            }
            DeviceMode.STOPWATCH -> {
                HardwareStateRepository.updateState(getApplication()) {
                    it.copy(dialAngleDegrees = normalizedAngle)
                }
            }
        }
    }
}
