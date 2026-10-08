package com.khcompany.lanedash.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.khcompany.lanedash.game.SfxEvent

private const val PREFS_NAME = "lane_dash_prefs"
private const val KEY_VIBRATION = "vibration_enabled"

/** Maps gameplay events to short vibrations; the on/off choice is persisted. */
class HapticsManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var enabled by mutableStateOf(prefs.getBoolean(KEY_VIBRATION, true))
        private set

    fun toggle(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(KEY_VIBRATION, value).apply()
        if (value) vibrate(30)
    }

    fun onEvent(event: SfxEvent) {
        when (event) {
            SfxEvent.CRASH -> vibrate(220)
            SfxEvent.FUEL_EMPTY -> vibrate(120)
            SfxEvent.NEAR_MISS, SfxEvent.COIN, SfxEvent.POWERUP, SfxEvent.SHIELD_HIT -> vibrate(25)
            SfxEvent.LANE_CHANGE, SfxEvent.FUEL_LOW_WARNING -> Unit
        }
    }

    private fun vibrate(millis: Long) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
