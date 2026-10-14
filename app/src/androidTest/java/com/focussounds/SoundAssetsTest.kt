package com.focussounds

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.FocusSound
import com.focussounds.playback.ExoPlaybackPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Decodes and plays every bundled sound with the real on-device decoder. */
@RunWith(AndroidJUnit4::class)
class SoundAssetsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun expectedDurationMs(s: FocusSound) = when (s) {
        FocusSound.BROWN_NOISE, FocusSound.WHITE_NOISE -> 60_000L
        else -> 90_000L
    }

    private class Probe : AnalyticsListener {
        @Volatile var error: PlaybackException? = null
        @Volatile var underruns = 0
        @Volatile var repeats = 0
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            this.error = error
        }
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            underruns++
        }
        override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeats++
        }
    }

    private fun newPlayer(sound: FocusSound, probe: Probe): ExoPlayer = onMain {
        ExoPlayer.Builder(ctx).build().apply {
            addAnalyticsListener(probe)
            volume = 0.05f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(ExoPlaybackPort.assetUri(sound)))
            prepare()
        }
    }

    @Test
    fun allFiveSoundsLoadDecodeAndPlay() {
        for (sound in FocusSound.entries) {
            val probe = Probe()
            val player = newPlayer(sound, probe)
            try {
                waitUntil(10_000, "${sound.id} ready") { player.playbackState == Player.STATE_READY || probe.error != null }
                assertNull("${sound.id}: ${probe.error}", probe.error)
                val format = onMain { player.audioFormat }!!
                assertEquals("${sound.id} sample rate", 48_000, format.sampleRate)
                assertEquals("${sound.id} channels", 2, format.channelCount)
                assertEquals("${sound.id} mime", "audio/flac", format.sampleMimeType)
                val duration = onMain { player.duration }
                assertTrue("${sound.id} duration $duration", kotlin.math.abs(duration - expectedDurationMs(sound)) <= 50)

                onMain { player.play() }
                Thread.sleep(1_200)
                val pos = onMain { player.currentPosition }
                assertTrue("${sound.id} should advance while playing, position=$pos", pos in 700..2_000)
                assertNull("${sound.id}: ${probe.error}", probe.error)
            } finally {
                onMain { player.release() }
            }
        }
    }

    @Test
    fun loopBoundaryContinuesWithoutUnderrunsOrErrors() {
        for (sound in listOf(FocusSound.BROWN_NOISE, FocusSound.OCEAN)) {
            val probe = Probe()
            val player = newPlayer(sound, probe)
            try {
                waitUntil(10_000, "${sound.id} ready") { player.playbackState == Player.STATE_READY }
                onMain { player.seekTo(player.duration - 1_500) }
                waitUntil(5_000, "${sound.id} ready after seek") { player.playbackState == Player.STATE_READY }
                onMain { player.play() }
                waitUntil(3_000, "${sound.id} playing") { player.isPlaying }
                Thread.sleep(300)
                val underrunsBefore = probe.underruns
                Thread.sleep(3_000) // crosses the loop boundary after ~1.2 s

                assertEquals("${sound.id} should repeat once", 1, probe.repeats)
                assertTrue("${sound.id} still playing", onMain { player.isPlaying })
                val pos = onMain { player.currentPosition }
                assertTrue("${sound.id} position after wrap $pos", pos in 1_000..3_000)
                assertEquals("${sound.id} audio underruns at loop boundary", underrunsBefore, probe.underruns)
                assertNull(probe.error)
            } finally {
                onMain { player.release() }
            }
        }
    }
}
