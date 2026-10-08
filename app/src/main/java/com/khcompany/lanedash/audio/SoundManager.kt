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

    private class Cue(val soundId: Int, val rate: Float = 1f, val volume: Float = 1f)

    private val coinId = soundPool.load(appContext, R.raw.sfx_coin, 1)
    private val crashId = soundPool.load(appContext, R.raw.sfx_crash, 1)
    private val laneId = soundPool.load(appContext, R.raw.sfx_lane, 1)

    // The newer events reuse the existing clips at a different pitch instead of shipping
    // new audio files; mirrors SFX_CUES in the web build's audio.js.
    private val cues: Map<SfxEvent, Cue> = mapOf(
        SfxEvent.COIN to Cue(coinId),
        SfxEvent.CRASH to Cue(crashId),
        SfxEvent.FUEL_EMPTY to Cue(soundPool.load(appContext, R.raw.sfx_gameover, 1)),
        SfxEvent.LANE_CHANGE to Cue(laneId),
        SfxEvent.FUEL_LOW_WARNING to Cue(soundPool.load(appContext, R.raw.sfx_fuel_low, 1)),
        SfxEvent.NEAR_MISS to Cue(laneId, rate = 1.7f),
        SfxEvent.POWERUP to Cue(coinId, rate = 0.7f),
        SfxEvent.SHIELD_HIT to Cue(crashId, rate = 1.5f, volume = 0.6f),
    )
    private val newBestSoundId = soundPool.load(appContext, R.raw.sfx_newbest, 1)

    private var bgmPlayer: MediaPlayer? = null

    fun play(event: SfxEvent) {
        val cue = cues[event] ?: return
        soundPool.play(cue.soundId, cue.volume, cue.volume, 1, 0, cue.rate)
    }

    fun pauseBgm() {
        bgmPlayer?.takeIf { it.isPlaying }?.pause()
    }

    fun resumeBgm() {
        bgmPlayer?.takeIf { !it.isPlaying }?.start()
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
