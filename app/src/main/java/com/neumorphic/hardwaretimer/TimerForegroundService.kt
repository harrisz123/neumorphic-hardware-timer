package com.neumorphic.hardwaretimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Precision hardware state model representing the exact state of the industrial dial.
 */
data class HardwareState(
    val mode: DeviceMode = DeviceMode.TIMER,
    val timerTotalSeconds: Int = 30 * 60, // Default 30m
    val timerRemainingSeconds: Int = 30 * 60,
    val stopwatchElapsedMillis: Long = 0L,
    val isRunning: Boolean = false,
    val isDone: Boolean = false,
    val dialAngleDegrees: Float = 0f
) {
    val displayText: String
        get() = when (mode) {
            DeviceMode.TIMER -> {
                if (!isRunning && !isDone && timerRemainingSeconds % 60 == 0 && timerRemainingSeconds >= 60) {
                    "${timerRemainingSeconds / 60}m"
                } else {
                    val m = timerRemainingSeconds / 60
                    val s = timerRemainingSeconds % 60
                    String.format("%02d:%02d", m, s)
                }
            }
            DeviceMode.STOPWATCH -> {
                val totalSeconds = (stopwatchElapsedMillis / 1000).toInt()
                val m = totalSeconds / 60
                val s = totalSeconds % 60
                String.format("%02d:%02d", m, s)
            }
        }
}

/**
 * Singleton repository holding reactive state shared across App, Service, and Widget.
 */
object HardwareStateRepository {
    private const val PREFS_NAME = "neumorphic_timer_prefs"
    private const val KEY_MODE = "device_mode"
    private const val KEY_TOTAL_SEC = "total_sec"
    private const val KEY_REM_SEC = "rem_sec"
    private const val KEY_STOPWATCH_MS = "stopwatch_ms"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_IS_DONE = "is_done"
    private const val KEY_DIAL_ANGLE = "dial_angle"

    private val _state = MutableStateFlow(HardwareState())
    val state: StateFlow<HardwareState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_MODE, DeviceMode.TIMER.name) ?: DeviceMode.TIMER.name
        val mode = try { DeviceMode.valueOf(modeStr) } catch (e: Exception) { DeviceMode.TIMER }
        val totalSec = prefs.getInt(KEY_TOTAL_SEC, 30 * 60)
        val remSec = prefs.getInt(KEY_REM_SEC, 30 * 60)
        val swMs = prefs.getLong(KEY_STOPWATCH_MS, 0L)
        val running = prefs.getBoolean(KEY_IS_RUNNING, false)
        val done = prefs.getBoolean(KEY_IS_DONE, false)
        val angle = prefs.getFloat(KEY_DIAL_ANGLE, 0f)

        _state.value = HardwareState(
            mode = mode,
            timerTotalSeconds = totalSec,
            timerRemainingSeconds = remSec,
            stopwatchElapsedMillis = swMs,
            isRunning = running,
            isDone = done,
            dialAngleDegrees = angle
        )
    }

    fun updateState(context: Context, transform: (HardwareState) -> HardwareState) {
        val newState = transform(_state.value)
        _state.value = newState
        persistState(context, newState)
        notifyWidgetUpdate(context)
    }

    private fun persistState(context: Context, state: HardwareState) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_MODE, state.mode.name)
            .putInt(KEY_TOTAL_SEC, state.timerTotalSeconds)
            .putInt(KEY_REM_SEC, state.timerRemainingSeconds)
            .putLong(KEY_STOPWATCH_MS, state.stopwatchElapsedMillis)
            .putBoolean(KEY_IS_RUNNING, state.isRunning)
            .putBoolean(KEY_IS_DONE, state.isDone)
            .putFloat(KEY_DIAL_ANGLE, state.dialAngleDegrees)
            .apply()
    }

    private fun notifyWidgetUpdate(context: Context) {
        scope.launch {
            try {
                TimerAppWidget().updateAll(context)
            } catch (e: Exception) {
                // Ignore during early initialization
            }
        }
    }
}

/**
 * Production Foreground Service providing:
 * 1. Monotonic zero-drift timing via SystemClock.elapsedRealtime().
 * 2. In-memory synthesized dual-tone mechanical chime on timer completion (zero external assets).
 * 3. Haptic alarm pulse sequence on completion.
 * 4. Ongoing system status notification with direct interactive actions.
 */
class TimerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "neumorphic_timer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_TOGGLE = "ACTION_TOGGLE"
        const val ACTION_RESET = "ACTION_RESET"
        const val ACTION_ADJUST_TIMER = "ACTION_ADJUST_TIMER"
        const val EXTRA_DELTA_SECONDS = "EXTRA_DELTA_SECONDS"

        fun startService(context: Context, action: String) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun adjustTimer(context: Context, deltaSeconds: Int) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_ADJUST_TIMER
                putExtra(EXTRA_DELTA_SECONDS, deltaSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var baseRealtimeMs: Long = 0L
    private var initialOffsetMs: Long = 0L

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val curState = HardwareStateRepository.state.value
            if (!curState.isRunning) return

            val now = SystemClock.elapsedRealtime()
            val elapsedSinceStart = (now - baseRealtimeMs) + initialOffsetMs

            when (curState.mode) {
                DeviceMode.TIMER -> {
                    val totalMs = curState.timerTotalSeconds * 1000L
                    val remainingMs = (totalMs - elapsedSinceStart).coerceAtLeast(0L)
                    val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()

                    if (remainingMs <= 0L) {
                        HardwareStateRepository.updateState(applicationContext) {
                            it.copy(
                                timerRemainingSeconds = 0,
                                isRunning = false,
                                isDone = true,
                                dialAngleDegrees = 0f
                            )
                        }
                        triggerCompletionAlarm()
                        updateNotification("Done", "00:00 - Timer Finished")
                        stopForeground(STOP_FOREGROUND_DETACH)
                        return
                    } else {
                        val fractionLeft = remainingMs.toFloat() / totalMs.toFloat().coerceAtLeast(1f)
                        val angle = fractionLeft * 360f

                        HardwareStateRepository.updateState(applicationContext) {
                            it.copy(
                                timerRemainingSeconds = remainingSeconds,
                                dialAngleDegrees = angle
                            )
                        }
                        updateNotification("Timer Running", curState.displayText)
                    }
                }
                DeviceMode.STOPWATCH -> {
                    val dialAngle = ((elapsedSinceStart % 60000L) / 60000f) * 360f
                    HardwareStateRepository.updateState(applicationContext) {
                        it.copy(
                            stopwatchElapsedMillis = elapsedSinceStart,
                            dialAngleDegrees = dialAngle
                        )
                    }
                    updateNotification("Stopwatch Running", curState.displayText)
                }
            }

            handler.postDelayed(this, 50L) // 20fps precision ticker
        }
    }

    override fun onCreate() {
        super.onCreate()
        HardwareStateRepository.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startRunning()
            ACTION_PAUSE -> pauseRunning()
            ACTION_TOGGLE -> toggleRunning()
            ACTION_RESET -> resetCurrentMode()
            ACTION_ADJUST_TIMER -> {
                val delta = intent?.getIntExtra(EXTRA_DELTA_SECONDS, 60) ?: 60
                adjustTimerBySeconds(delta)
            }
        }
        return START_STICKY
    }

    private fun startRunning() {
        val cur = HardwareStateRepository.state.value
        baseRealtimeMs = SystemClock.elapsedRealtime()

        if (cur.mode == DeviceMode.TIMER) {
            val startingRemaining = if (cur.timerRemainingSeconds <= 0 || cur.isDone) {
                cur.timerTotalSeconds
            } else {
                cur.timerRemainingSeconds
            }
            initialOffsetMs = (cur.timerTotalSeconds - startingRemaining) * 1000L
            HardwareStateRepository.updateState(applicationContext) {
                it.copy(
                    timerRemainingSeconds = startingRemaining,
                    isRunning = true,
                    isDone = false
                )
            }
        } else {
            initialOffsetMs = cur.stopwatchElapsedMillis
            HardwareStateRepository.updateState(applicationContext) {
                it.copy(isRunning = true)
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Hardware Dial Active", HardwareStateRepository.state.value.displayText))
        handler.removeCallbacks(tickerRunnable)
        handler.post(tickerRunnable)
    }

    private fun pauseRunning() {
        handler.removeCallbacks(tickerRunnable)
        HardwareStateRepository.updateState(applicationContext) {
            it.copy(isRunning = false)
        }
        val cur = HardwareStateRepository.state.value
        updateNotification("Paused", cur.displayText)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun toggleRunning() {
        if (HardwareStateRepository.state.value.isRunning) {
            pauseRunning()
        } else {
            startRunning()
        }
    }

    private fun resetCurrentMode() {
        handler.removeCallbacks(tickerRunnable)
        initialOffsetMs = 0L
        baseRealtimeMs = SystemClock.elapsedRealtime()

        HardwareStateRepository.updateState(applicationContext) {
            when (it.mode) {
                DeviceMode.TIMER -> it.copy(
                    timerRemainingSeconds = it.timerTotalSeconds,
                    isRunning = false,
                    isDone = false,
                    dialAngleDegrees = 0f
                )
                DeviceMode.STOPWATCH -> it.copy(
                    stopwatchElapsedMillis = 0L,
                    isRunning = false,
                    dialAngleDegrees = 0f
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun adjustTimerBySeconds(deltaSeconds: Int) {
        HardwareStateRepository.updateState(applicationContext) { cur ->
            val newTotal = (cur.timerTotalSeconds + deltaSeconds).coerceIn(15, 120 * 60)
            cur.copy(
                timerTotalSeconds = newTotal,
                timerRemainingSeconds = newTotal,
                isDone = false,
                dialAngleDegrees = 0f
            )
        }
    }

    /**
     * Synthesizes and plays a zero-asset hardware chime (880Hz / 1760Hz harmonious bell pulse)
     * and triggers a high-priority vibration alarm sequence when timer reaches 00:00.
     */
    private fun triggerCompletionAlarm() {
        // 1. Hardware vibration pulse sequence
        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 250, 150, 250, 150, 400)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 250, 150, 250, 150, 400), -1)
            }
        }

        // 2. Synthesized audio chime via AudioTrack
        Thread {
            try {
                val sampleRate = 44100
                val durationMs = 600
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val pcm = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // Dual-frequency acoustic chime: 880Hz (A5) fundamental + 1760Hz octave overtone
                    val tone = 0.7 * sin(2.0 * PI * 880.0 * t) + 0.3 * sin(2.0 * PI * 1760.0 * t)
                    // Natural exponential acoustic decay
                    val envelope = kotlin.math.exp(-t * 6.5)
                    pcm[i] = ((tone * envelope) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(700L)
                track.stop()
                track.release()
            } catch (e: Exception) {
                // Ignore audio chime recovery
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Neumorphic Hardware Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monotonic background precision countdown and stopwatch"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent: Toggle Start/Pause
        val toggleIntent = Intent(this, TimerForegroundService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent: Reset
        val resetIntent = Intent(this, TimerForegroundService::class.java).apply {
            action = ACTION_RESET
        }
        val resetPending = PendingIntent.getService(
            this, 2, resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isRunning = HardwareStateRepository.state.value.isRunning
        val toggleLabel = if (isRunning) "Pause" else "Start"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(isRunning)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_play, toggleLabel, togglePending)
            .addAction(android.R.drawable.ic_menu_revert, "Reset", resetPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickerRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
