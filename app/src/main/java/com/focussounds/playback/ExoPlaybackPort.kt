package com.focussounds.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.focussounds.core.FocusSound
import com.focussounds.core.PauseCause
import com.focussounds.core.PlaybackPort
import com.focussounds.core.SessionEngine

/** Adapts the service's single ExoPlayer to the engine and reports its status back. */
class ExoPlaybackPort(
    private val player: ExoPlayer,
    private val engine: SessionEngine,
) : PlaybackPort, Player.Listener {

    private var lastPlayWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    init {
        player.addListener(this)
    }

    override fun playLooping(sound: FocusSound, title: String, subtitle: String) {
        val item = MediaItem.Builder()
            .setMediaId(sound.id)
            .setUri(assetUri(sound))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(subtitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
        // A single item repeated forever: ExoPlayer pre-buffers the next repetition, and FLAC is
        // sample-exact, so the loop boundary is gapless.
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    fun release() = player.removeListener(this)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        lastPlayWhenReadyReason = reason
    }

    override fun onPlayerError(error: PlaybackException) {
        engine.onPlaybackError(error.errorCodeName)
    }

    // onEvents runs after the individual callbacks of the same batch, with final state.
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            engine.onPlaybackStatusChanged(player.isPlaying, pauseCause())
        }
    }

    private fun pauseCause(): PauseCause? = when {
        player.isPlaying -> null
        player.playWhenReady -> when (player.playbackSuppressionReason) {
            Player.PLAYBACK_SUPPRESSION_REASON_NONE -> null // still loading
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> PauseCause.TRANSIENT_INTERRUPTION
            else -> PauseCause.OTHER
        }
        else -> when (lastPlayWhenReadyReason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> PauseCause.USER
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseCause.AUDIO_FOCUS_LOSS
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PauseCause.HEADPHONES_DISCONNECTED
            else -> PauseCause.OTHER
        }
    }

    companion object {
        fun assetUri(sound: FocusSound): String = "asset:///${sound.assetPath}"
    }
}
