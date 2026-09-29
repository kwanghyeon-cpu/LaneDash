package com.khcompany.lanedash.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.khcompany.lanedash.R
import com.khcompany.lanedash.game.SfxEvent

/**
 * Short SFX play through a pooled [SoundPool] for low latency; the looping background track
 * uses a plain [MediaPlayer]. Call [release] when the owning screen leaves composition.
 */
class SoundManager(context: Context) {
    private val appContext = context.applicationContext

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds: Map<SfxEvent, Int> = mapOf(
        SfxEvent.COIN to soundPool.load(appContext, R.raw.sfx_coin, 1),
        SfxEvent.CRASH to soundPool.load(appContext, R.raw.sfx_crash, 1),
        SfxEvent.FUEL_EMPTY to soundPool.load(appContext, R.raw.sfx_gameover, 1),
        SfxEvent.LANE_CHANGE to soundPool.load(appContext, R.raw.sfx_lane, 1),
        SfxEvent.FUEL_LOW_WARNING to soundPool.load(appContext, R.raw.sfx_fuel_low, 1),
    )
    private val newBestSoundId = soundPool.load(appContext, R.raw.sfx_newbest, 1)

    private var bgmPlayer: MediaPlayer? = null

    fun play(event: SfxEvent) {
        val id = soundIds[event] ?: return
        soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun playNewBest() {
        soundPool.play(newBestSoundId, 1f, 1f, 1, 0, 1f)
    }

    fun startBgm() {
        if (bgmPlayer != null) return
        bgmPlayer = MediaPlayer.create(appContext, R.raw.bgm_loop)?.apply {
            isLooping = true
            setVolume(0.85f, 0.85f)
            start()
        }
    }

    fun stopBgm() {
        bgmPlayer?.apply {
            stop()
            release()
        }
        bgmPlayer = null
    }

    fun release() {
        stopBgm()
        soundPool.release()
    }
}
