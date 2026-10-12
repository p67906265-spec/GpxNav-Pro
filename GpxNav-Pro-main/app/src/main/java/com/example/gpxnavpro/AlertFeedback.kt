package com.example.gpxnavpro

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AlertFeedback(context: Context) {
    private val appContext = context.applicationContext
    private val toneGenerator: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
    private val notifiedRouteAlerts = mutableSetOf<String>()
    private var offRouteActive = false

    fun reset() {
        notifiedRouteAlerts.clear()
        offRouteActive = false
    }

    fun routeAlert(alert: ActiveRouteAlert) {
        val type = alert.alert.type
        if (type != RouteAlertType.TV && type != RouteAlertType.DANGER) return

        val key = "${type.name}:${alert.alert.progressMeters.toInt()}"
        if (!notifiedRouteAlerts.add(key)) return

        when (type) {
            RouteAlertType.DANGER -> {
                vibrate(longArrayOf(0, 180, 120, 180, 120, 260))
                playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
            }
            RouteAlertType.TV -> {
                vibrate(longArrayOf(0, 140, 100, 140))
                playTone(ToneGenerator.TONE_PROP_BEEP2, 450)
            }
            else -> Unit
        }
    }

    fun offRoute(isOffRoute: Boolean) {
        if (isOffRoute && !offRouteActive) {
            vibrate(longArrayOf(0, 220, 120, 220, 120, 220))
            playTone(ToneGenerator.TONE_SUP_ERROR, 650)
        }
        offRouteActive = isOffRoute
    }

    fun release() {
        runCatching { toneGenerator?.release() }
    }

    private fun playTone(tone: Int, durationMs: Int) {
        runCatching { toneGenerator?.startTone(tone, durationMs) }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
