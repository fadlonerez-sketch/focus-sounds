package com.focussounds

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.FocusSound
import com.focussounds.core.PauseCause
import com.focussounds.core.Phase
import com.focussounds.core.SessionEngine
import com.focussounds.core.StartRequest
import com.focussounds.data.FocusDatabase
import com.focussounds.data.RoomSessionStore
import com.focussounds.data.SessionEntity
import com.focussounds.playback.ExoPlaybackPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The real engine driving a real ExoPlayer configured exactly like PlaybackService (audio focus
 * handled by the player), persisting into an in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var db: FocusDatabase
    private lateinit var engine: SessionEngine
    private lateinit var exo: ExoPlayer
    private lateinit var port: ExoPlaybackPort

    @Before
    fun setUp() {
        db = FocusDatabase.inMemory(ctx)
        onMain {
            exo = ExoPlayer.Builder(ctx)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    true,
                )
                .build()
            engine = SessionEngine(
                scope = scope,
                clock = { SystemClock.elapsedRealtime() },
                wallClock = { System.currentTimeMillis() },
                store = RoomSessionStore(db),
                newId = { UUID.randomUUID().toString() },
                initialVolume = 0.05f,
                checkpointIntervalMs = 1_000,
            )
            port = ExoPlaybackPort(exo, engine)
            engine.attachPlayer(port)
        }
    }

    @After
    fun tearDown() {
        onMain {
            engine.detachPlayer(port)
            port.release()
            exo.release()
        }
        scope.cancel()
        db.close()
    }

    private fun rows(): List<SessionEntity> {
        Thread.sleep(300) // let queued writes land
        return runBlocking { db.sessionDao().all() }
    }

    private fun phase() = engine.state.value.active?.phase
    private fun remaining() = onMain { engine.state.value.active!!.remainingMs(SystemClock.elapsedRealtime())!! }

    private fun start(plannedMs: Long?, sound: FocusSound = FocusSound.BROWN_NOISE) {
        assertTrue(onMain { engine.start(StartRequest(sound, null, "Test", plannedMs)) })
        waitUntil(5_000, "playing") { phase() == Phase.PLAYING }
    }

    @Test
    fun timedSessionFinishesOnTimeAndIsRecordedOnce() {
        start(3_000)
        val t0 = SystemClock.elapsedRealtime()
        waitUntil(8_000, "session end") { engine.state.value.active == null }
        val took = SystemClock.elapsedRealtime() - t0
        assertTrue("finished after $took ms", took in 2_900..3_700)
        assertTrue("player stopped", onMain { !exo.isPlaying && exo.mediaItemCount == 0 })
        val row = rows().single()
        assertEquals("completed", row.endReason)
        assertEquals(3_000, row.activeDurationMs)
    }

    @Test
    fun pauseExcludesPausedTimeAndResumeKeepsRemaining() {
        start(4_000)
        Thread.sleep(1_500)
        onMain { engine.pause() }
        waitUntil(2_000, "paused") { phase() == Phase.PAUSED }
        val r1 = remaining()
        assertTrue("remaining $r1", r1 in 2_200..2_700)
        Thread.sleep(2_000)
        assertEquals("remaining frozen while paused", r1, remaining())
        assertTrue("player really paused", onMain { !exo.isPlaying })

        val resumedAt = SystemClock.elapsedRealtime()
        onMain { engine.resume() }
        waitUntil(6_000, "session end") { engine.state.value.active == null }
        val tail = SystemClock.elapsedRealtime() - resumedAt
        assertTrue("finished $tail ms after resume (expected ~$r1)", tail in (r1 - 100)..(r1 + 800))
        val row = rows().single()
        assertEquals("completed", row.endReason)
        assertEquals(4_000, row.activeDurationMs)
    }

    @Test
    fun loopingAcrossTheBoundaryDoesNotCreateRecords() {
        start(null)
        onMain { exo.seekTo(exo.duration - 1_500) }
        Thread.sleep(3_500)
        assertEquals(Phase.PLAYING, onMain { phase() })
        val during = rows()
        assertEquals(1, during.size)
        assertEquals("active", during.single().state)
        onMain { engine.stop() }
        val row = rows().single()
        assertEquals("stopped", row.endReason)
        assertTrue("active ${row.activeDurationMs}", row.activeDurationMs in 3_000..5_000)
    }

    private fun focusRequest(gain: Int) = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    @Test
    fun permanentAudioFocusLossPausesAndDoesNotAutoResume() {
        val am = ctx.getSystemService(AudioManager::class.java)
        start(null)
        val other = focusRequest(AudioManager.AUDIOFOCUS_GAIN) // another "music app" starts
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am.requestAudioFocus(other))
        waitUntil(3_000, "paused by focus loss") { phase() == Phase.PAUSED }
        assertEquals(PauseCause.AUDIO_FOCUS_LOSS, onMain { engine.state.value.active!!.pauseCause })
        am.abandonAudioFocusRequest(other)
        Thread.sleep(2_000)
        assertEquals("must not resume by itself", Phase.PAUSED, onMain { phase() })

        onMain { engine.resume() } // user resumes: focus is requested again
        waitUntil(3_000, "playing again") { phase() == Phase.PLAYING }
        onMain { engine.stop() }
        assertEquals(1, rows().size)
    }

    @Test
    fun transientInterruptionPausesThenResumesAndExcludesTheGap() {
        val am = ctx.getSystemService(AudioManager::class.java)
        start(null)
        Thread.sleep(1_000)
        val call = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) // e.g. a phone call
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, am.requestAudioFocus(call))
        waitUntil(3_000, "paused by transient loss") { phase() == Phase.PAUSED }
        assertEquals(PauseCause.TRANSIENT_INTERRUPTION, onMain { engine.state.value.active!!.pauseCause })
        Thread.sleep(3_000)
        am.abandonAudioFocusRequest(call)
        waitUntil(3_000, "resumed after interruption") { phase() == Phase.PLAYING }
        Thread.sleep(1_000)
        onMain { engine.stop() }
        val row = rows().single()
        // ~2 s of playing; the 3 s interruption must not be counted.
        assertTrue("active ${row.activeDurationMs}", row.activeDurationMs in 1_700..3_200)
    }
}
