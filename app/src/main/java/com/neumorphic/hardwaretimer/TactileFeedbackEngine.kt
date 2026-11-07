package com.neumorphic.hardwaretimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Self-contained tactile feedback engine providing:
 * 1. Polar angle tracking and continuous shortest-path angle unwrapping.
 * 2. 6-degree detent quantization (60 ticks / 360 degrees).
 * 3. High-fidelity hardware haptics (API 30+ primitive click compositions with fallbacks).
 * 4. Zero-asset, synthesized in-memory mechanical camera/dial detent click via low-latency static AudioTrack.
 */
class TactileFeedbackEngine(private val context: Context) {

    companion object {
        const val DEGREES_PER_TICK = 6.0f
        const val SAMPLE_RATE = 44100
        const val DURATION_MS = 4
        // 4ms at 44100Hz = 176 samples
        const val NUM_SAMPLES = (SAMPLE_RATE * (DURATION_MS / 1000.0)).toInt()
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var audioTrack: AudioTrack? = null
    private val pcmBuffer: ShortArray = ShortArray(NUM_SAMPLES)

    // Continuous touch angle state tracking
    private var lastRawAngleDegrees: Float? = null
    private var accumulatedAngleDegrees: Float = 0f
    private var lastTickIndex: Int = 0

    init {
        synthesizeMechanicalClickWaveform()
        initializeStaticAudioTrack()
    }

    /**
     * Synthesizes an exponentially decaying burst of bandpass-filtered white noise + 1800Hz sine transient.
     * Mimics an authentic industrial metal ball-bearing detent drop.
     */
    private fun synthesizeMechanicalClickWaveform() {
        val random = Random(1337L) // Deterministic seed for crisp, identical detent response

        // Resonant bandpass filter configuration (Center = 2800 Hz, Q = 2.4)
        val centerFreq = 2800.0
        val qFactor = 2.4
        val omega = 2.0 * PI * centerFreq / SAMPLE_RATE
        val alpha = sin(omega) / (2.0 * qFactor)

        val b0 = alpha
        val b1 = 0.0
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cos(omega)
        val a2 = 1.0 - alpha

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in 0 until NUM_SAMPLES) {
            val t = i.toDouble() / SAMPLE_RATE

            // Steep exponential envelope decaying to silence within 4ms
            val decay = exp(-t * 1250.0)

            // 1800 Hz sine wave for the high-frequency metallic impact transient
            val sineTransient = sin(2.0 * PI * 1800.0 * t)

            // Bandpass-filtered noise for the tactile spring friction
            val whiteNoise = (random.nextDouble() * 2.0 - 1.0)
            val filteredNoise = (b0 / a0) * whiteNoise + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2
            x2 = x1
            x1 = whiteNoise
            y2 = y1
            y1 = filteredNoise

            // Mix: 55% metallic transient + 45% textured noise
            val composite = (0.55 * sineTransient + 0.45 * filteredNoise) * decay
            pcmBuffer[i] = (composite * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun initializeStaticAudioTrack() {
        try {
            val bufferSizeBytes = pcmBuffer.size * 2
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmBuffer, 0, pcmBuffer.size)
            audioTrack = track
        } catch (e: Exception) {
            audioTrack = null
        }
    }

    /**
     * Plays the in-memory synthesized audio detent click with near-zero latency.
     */
    fun playTickAudio() {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.reloadStaticData()
                track.play()
            }
        } catch (e: Exception) {
            // AudioTrack state recovery
        }
    }

    /**
     * Dispatches hardware haptic feedback according to platform version and hardware support.
     */
    fun playTickHaptic(fallbackView: View? = null) {
        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    val composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                        .compose()
                    vib.vibrate(composition)
                    return
                } else {
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    return
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(8L, 90))
                return
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(8L)
                return
            }
        }

        fallbackView?.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /**
     * Triggers both physical haptics and synthesized mechanical audio simultaneously.
     */
    fun triggerDetentFeedback(fallbackView: View? = null) {
        playTickAudio()
        playTickHaptic(fallbackView)
    }

    /**
     * Computes the clock angle in degrees (0..360, where 0 deg is 12 o'clock, 90 deg is 3 o'clock).
     */
    fun calculateClockAngle(touchX: Float, touchY: Float, centerX: Float, centerY: Float): Float {
        val dx = touchX - centerX
        val dy = touchY - centerY
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90.0f
        if (deg < 0f) deg += 360.0f
        return deg % 360.0f
    }

    fun onTouchDown(touchX: Float, touchY: Float, centerX: Float, centerY: Float, currentDialAngle: Float) {
        lastRawAngleDegrees = calculateClockAngle(touchX, touchY, centerX, centerY)
        accumulatedAngleDegrees = currentDialAngle
        lastTickIndex = floor(accumulatedAngleDegrees / DEGREES_PER_TICK).toInt()
    }

    fun onTouchUp() {
        lastRawAngleDegrees = null
    }

    /**
     * Processes circular rotary drag coordinates.
     * GUARDRAIL FIX: Normalizes wrap-around jump across 359° to 0°:
     * var delta = newAngle - lastAngle; if (delta > 180) delta -= 360; if (delta < -180) delta += 360
     * Ensures moving from 359° to 0° adds +1 minute instead of subtracting 60 minutes.
     *
     * @return Pair(newDialAngleDegrees, tickDeltaCount)
     */
    fun onRotaryDrag(
        touchX: Float,
        touchY: Float,
        centerX: Float,
        centerY: Float,
        fallbackView: View? = null
    ): Pair<Float, Int> {
        val newAngle = calculateClockAngle(touchX, touchY, centerX, centerY)
        val lastAngle = lastRawAngleDegrees ?: newAngle
        lastRawAngleDegrees = newAngle

        // Exact Guardrail Specification: Normalize angular wrap-around
        var delta = newAngle - lastAngle
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        accumulatedAngleDegrees += delta
        val newTickIndex = floor(accumulatedAngleDegrees / DEGREES_PER_TICK).toInt()
        val tickDiff = newTickIndex - lastTickIndex

        if (tickDiff != 0) {
            triggerDetentFeedback(fallbackView)
            lastTickIndex = newTickIndex
        }

        val normalizedAngle = ((accumulatedAngleDegrees % 360f) + 360f) % 360f
        return Pair(normalizedAngle, tickDiff)
    }

    fun release() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
            audioTrack = null
        } catch (e: Exception) {
            // Ignore on teardown
        }
    }
}
