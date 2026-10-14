package com.focussounds.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Optional chime and/or vibration when a timed session completes. Process-wide (not owned by the
 * playback service) so the chime is not cut off when the service stops right after completion.
 *
 * The chime uses notification-event audio attributes: it follows the notification volume and
 * Do Not Disturb, and it plays after the focus sound has already released audio focus.
 */
class CompletionFeedback(private val context: Context) {
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false
    private var playWhenLoaded = false

    fun onSessionCompleted(chime: Boolean, vibrate: Boolean) {
        if (chime) playChime()
        if (vibrate) vibrate()
    }

    private fun playChime() {
        val p = pool ?: createPool().also { pool = it }
        if (loaded) p.play(soundId, 1f, 1f, 1, 0, 1f) else playWhenLoaded = true
    }

    private fun createPool(): SoundPool {
        val p = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        p.setOnLoadCompleteListener { sp, id, status ->
            loaded = status == 0
            if (loaded && playWhenLoaded) {
                playWhenLoaded = false
                sp.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
        runCatching {
            context.assets.openFd(CHIME_ASSET).use { fd -> soundId = p.load(fd, 1) }
        }.onFailure { Log.w("FocusSounds", "Chime unavailable", it) }
        return p
    }

    private fun vibrate() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        // A gentle double pulse, not an alarm.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 110, 70), intArrayOf(0, 150, 0, 110), -1))
    }

    companion object {
        const val CHIME_ASSET = "sounds/chime.flac"
    }
}
