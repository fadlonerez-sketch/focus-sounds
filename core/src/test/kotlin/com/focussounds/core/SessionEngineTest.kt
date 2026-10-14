package com.focussounds.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

/** In-memory store with the same "finish only if ACTIVE" semantics as the Room implementation. */
class FakeStore : SessionStore {
    data class Row(
        val record: NewSessionRecord,
        var activeMs: Long = 0,
        var lastCheckpointWallMs: Long,
        var endedAtWallMs: Long? = null,
        var reason: EndReason? = null,
        var state: SessionRowState = SessionRowState.ACTIVE,
    )

    val rows = linkedMapOf<String, Row>()
    var finishCalls = 0

    override suspend fun insertActive(record: NewSessionRecord) {
        check(record.id !in rows) { "duplicate insert" }
        rows[record.id] = Row(record, lastCheckpointWallMs = record.startedAtWallMs)
    }

    override suspend fun checkpoint(id: String, activeMs: Long, atWallMs: Long) {
        val r = rows[id] ?: return
        if (r.state != SessionRowState.ACTIVE) return
        r.activeMs = activeMs
        r.lastCheckpointWallMs = atWallMs
    }

    override suspend fun finish(id: String, activeMs: Long, endedAtWallMs: Long, reason: EndReason): Boolean {
        finishCalls++
        val r = rows[id] ?: return false
        if (r.state != SessionRowState.ACTIVE) return false
        r.activeMs = activeMs
        r.endedAtWallMs = endedAtWallMs
        r.reason = reason
        r.state = SessionRowState.FINISHED
        return true
    }

    override suspend fun finishOrphans(): Int {
        var n = 0
        rows.values.filter { it.state == SessionRowState.ACTIVE }.forEach {
            it.state = SessionRowState.FINISHED
            it.reason = EndReason.INTERRUPTED
            it.endedAtWallMs = it.lastCheckpointWallMs
            n++
        }
        return n
    }
}

/** Fake player that behaves like ExoPlayer: reports status changes back to the engine. */
class FakePlayer(private val engine: () -> SessionEngine) : PlaybackPort {
    val calls = mutableListOf<String>()
    var loaded: FocusSound? = null
    var lastVolume = -1f
    var autoStart = true

    override fun playLooping(sound: FocusSound, title: String, subtitle: String) {
        calls += "playLooping:${sound.id}"
        loaded = sound
        if (autoStart) engine().onPlaybackStatusChanged(true, null)
    }

    override fun play() {
        calls += "play"
        engine().onPlaybackStatusChanged(true, null)
    }

    override fun pause() {
        calls += "pause"
        engine().onPlaybackStatusChanged(false, PauseCause.USER)
    }

    override fun stopAndClear() {
        calls += "stop"
        loaded = null
        engine().onPlaybackStatusChanged(false, PauseCause.OTHER)
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEngineTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val engineScope = CoroutineScope(dispatcher)
    private val store = FakeStore()
    private var ids = 0
    private val wallStart = 1_700_000_000_000L

    private fun now() = testScope.testScheduler.currentTime

    private fun newEngine(): SessionEngine = SessionEngine(
        scope = engineScope,
        clock = { now() },
        wallClock = { wallStart + now() },
        store = store,
        newId = { "s${++ids}" },
        checkpointIntervalMs = 15_000,
    )

    private lateinit var engine: SessionEngine
    private lateinit var player: FakePlayer

    private fun setUpEngine() {
        engine = newEngine()
        player = FakePlayer { engine }
        engine.attachPlayer(player)
        testScope.runCurrent()
    }

    @After
    fun tearDown() = engineScope.cancel()

    private fun timed(minutes: Long?) = StartRequest(FocusSound.BROWN_NOISE, 1, "Studying", minutes?.let { it * MIN })

    @Test
    fun timedSessionCompletesExactlyAtPlannedTimeAndIsRecordedOnce() {
        setUpEngine()
        assertTrue(engine.start(timed(25)))
        testScope.advanceTimeBy(25 * MIN - 1)
        testScope.runCurrent()
        assertNotNull("still running 1 ms before the end", engine.state.value.active)

        testScope.advanceTimeBy(2)
        testScope.runCurrent()
        assertNull(engine.state.value.active)
        val row = store.rows.values.single()
        assertEquals(EndReason.COMPLETED, row.reason)
        assertEquals(25 * MIN, row.activeMs)
        assertEquals(wallStart + 25 * MIN, row.endedAtWallMs)
        assertEquals(listOf("playLooping:brown_noise", "stop"), player.calls)

        // Nothing else happens afterwards: no second completion, no extra writes.
        testScope.advanceTimeBy(60 * MIN)
        testScope.runCurrent()
        assertEquals(1, store.finishCalls)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun pauseExcludesPausedTimeAndPreservesRemaining() {
        setUpEngine()
        engine.start(timed(25))
        testScope.advanceTimeBy(10 * MIN); testScope.runCurrent()
        engine.pause()
        assertEquals(15 * MIN, engine.state.value.active!!.remainingMs(now()))

        testScope.advanceTimeBy(30 * MIN); testScope.runCurrent()
        val paused = engine.state.value.active!!
        assertEquals(Phase.PAUSED, paused.phase)
        assertEquals(PauseCause.USER, paused.pauseCause)
        assertEquals("remaining frozen while paused", 15 * MIN, paused.remainingMs(now()))
        assertEquals("checkpoint written on pause", 10 * MIN, store.rows.values.single().activeMs)

        engine.resume()
        testScope.advanceTimeBy(15 * MIN - 1); testScope.runCurrent()
        assertNotNull(engine.state.value.active)
        testScope.advanceTimeBy(1); testScope.runCurrent()
        val row = store.rows.values.single()
        assertEquals(EndReason.COMPLETED, row.reason)
        assertEquals(25 * MIN, row.activeMs)
        assertEquals("ended 25 active + 30 paused minutes after start", wallStart + 55 * MIN, row.endedAtWallMs)
    }

    @Test
    fun manualSessionStopRecordsActiveTimeExcludingPauses() {
        setUpEngine()
        engine.start(timed(null))
        testScope.advanceTimeBy(7 * MIN); testScope.runCurrent()
        engine.onPlaybackStatusChanged(false, PauseCause.TRANSIENT_INTERRUPTION) // phone call
        testScope.advanceTimeBy(5 * MIN); testScope.runCurrent()
        engine.onPlaybackStatusChanged(true, null) // call ended, Android restores focus
        testScope.advanceTimeBy(3 * MIN); testScope.runCurrent()
        engine.stop()
        engine.stop() // double stop must be harmless
        testScope.runCurrent()

        val row = store.rows.values.single()
        assertEquals(EndReason.STOPPED, row.reason)
        assertEquals(10 * MIN, row.activeMs)
        assertEquals(1, store.finishCalls)
    }

    @Test
    fun audioFocusLossPausesAndDoesNotCountTime() {
        setUpEngine()
        engine.start(timed(45))
        testScope.advanceTimeBy(5 * MIN); testScope.runCurrent()
        engine.onPlaybackStatusChanged(false, PauseCause.AUDIO_FOCUS_LOSS)
        testScope.advanceTimeBy(120 * MIN); testScope.runCurrent()
        val s = engine.state.value.active!!
        assertEquals(PauseCause.AUDIO_FOCUS_LOSS, s.pauseCause)
        assertEquals(40 * MIN, s.remainingMs(now()))
        assertFalse("engine never resumes on its own", player.calls.contains("play"))
    }

    @Test
    fun loopingStatusRepeatsDoNotCreateRecordsOrDoubleCount() {
        setUpEngine()
        engine.start(timed(null))
        repeat(20) {
            testScope.advanceTimeBy(MIN); testScope.runCurrent()
            // A loop boundary may re-report "playing"; it must be ignored.
            engine.onPlaybackStatusChanged(true, null)
        }
        engine.stop(); testScope.runCurrent()
        assertEquals(1, store.rows.size)
        assertEquals(20 * MIN, store.rows.values.single().activeMs)
    }

    @Test
    fun bufferingAtStartIsNotCountedAsActiveTime() {
        setUpEngine()
        player.autoStart = false
        engine.start(timed(25))
        assertEquals(Phase.STARTING, engine.state.value.active!!.phase)
        testScope.advanceTimeBy(3_000); testScope.runCurrent()
        engine.onPlaybackStatusChanged(true, null)
        testScope.advanceTimeBy(25 * MIN); testScope.runCurrent()
        val row = store.rows.values.single()
        assertEquals(EndReason.COMPLETED, row.reason)
        assertEquals(wallStart + 3_000 + 25 * MIN, row.endedAtWallMs)
    }

    @Test
    fun stoppingBeforeAnyPlaybackIsCancelled() {
        setUpEngine()
        player.autoStart = false
        engine.start(timed(25))
        testScope.advanceTimeBy(500); testScope.runCurrent()
        engine.stop(); testScope.runCurrent()
        assertEquals(EndReason.CANCELLED, store.rows.values.single().reason)
        assertEquals(0, store.rows.values.single().activeMs)
    }

    @Test
    fun startingWhileActiveEndsPreviousSessionFirst() {
        setUpEngine()
        engine.start(timed(null))
        testScope.advanceTimeBy(2 * MIN); testScope.runCurrent()
        engine.start(StartRequest(FocusSound.OCEAN, 2, "Reading", null))
        testScope.advanceTimeBy(MIN); testScope.runCurrent()
        engine.stop(); testScope.runCurrent()

        val rows = store.rows.values.toList()
        assertEquals(2, rows.size)
        assertEquals(EndReason.STOPPED, rows[0].reason)
        assertEquals(2 * MIN, rows[0].activeMs)
        assertEquals(MIN, rows[1].activeMs)
        assertEquals("ocean", rows[1].record.soundId)
    }

    @Test
    fun checkpointsAreWrittenWhilePlaying() {
        setUpEngine()
        engine.start(timed(null))
        testScope.advanceTimeBy(46_000); testScope.runCurrent()
        val row = store.rows.values.single()
        assertEquals(45_000, row.activeMs)
        assertEquals(wallStart + 45_000, row.lastCheckpointWallMs)
        assertEquals(SessionRowState.ACTIVE, row.state)
    }

    @Test
    fun processDeathRecoveryUsesLastCheckpointAndNeverInventsTime() {
        setUpEngine()
        engine.start(timed(60))
        testScope.advanceTimeBy(40_000); testScope.runCurrent() // last checkpoint at 30 s
        // Simulate process death: the engine and its scope vanish without finishing anything.
        engineScope.cancel()
        assertEquals(SessionRowState.ACTIVE, store.rows.values.single().state)

        // Hours later a new process starts.
        testScope.advanceTimeBy(5 * 60 * MIN)
        val scope2 = CoroutineScope(dispatcher)
        SessionEngine(scope2, { now() }, { wallStart + now() }, store, { "new" })
        testScope.runCurrent()

        val row = store.rows.values.single()
        assertEquals(SessionRowState.FINISHED, row.state)
        assertEquals(EndReason.INTERRUPTED, row.reason)
        assertEquals(30_000, row.activeMs)
        assertEquals("end = last checkpoint, not restart time", wallStart + 30_000, row.endedAtWallMs)
        scope2.cancel()
    }

    @Test
    fun recoveryRunsBeforeNewSessionInsertAndDoesNotTouchIt() {
        // An orphan from a previous process.
        val old = NewSessionRecord("old", 1, "Studying", "ocean", wallStart - 10 * MIN, null)
        kotlinx.coroutines.runBlocking { store.insertActive(old) }

        setUpEngine() // recovery queued in init
        engine.start(timed(null)) // insert queued after recovery
        testScope.runCurrent()

        assertEquals(EndReason.INTERRUPTED, store.rows.getValue("old").reason)
        assertEquals(SessionRowState.ACTIVE, store.rows.getValue("s1").state)
    }

    @Test
    fun serviceTeardownInterruptsSessionWithAccurateTime() {
        setUpEngine()
        engine.start(timed(25))
        testScope.advanceTimeBy(4 * MIN); testScope.runCurrent()
        engine.detachPlayer(player)
        testScope.runCurrent()
        val row = store.rows.values.single()
        assertEquals(EndReason.INTERRUPTED, row.reason)
        assertEquals(4 * MIN, row.activeMs)
        assertFalse(engine.state.value.playerAvailable)
        assertFalse("cannot start without a player", engine.start(timed(25)))
    }

    @Test
    fun secondPlayerIsRejected() {
        setUpEngine()
        val other = FakePlayer { engine }
        val result = runCatching { engine.attachPlayer(other) }
        assertTrue(result.isFailure)
    }

    @Test
    fun playbackErrorInterruptsSession() {
        setUpEngine()
        engine.start(timed(25))
        testScope.advanceTimeBy(MIN); testScope.runCurrent()
        engine.onPlaybackError("ERROR_CODE_DECODING_FAILED")
        testScope.runCurrent()
        assertEquals(EndReason.INTERRUPTED, store.rows.values.single().reason)
        assertEquals("ERROR_CODE_DECODING_FAILED", engine.state.value.lastResult!!.detail)
    }

    @Test
    fun volumeIsClampedAndAppliedToPlayer() {
        setUpEngine()
        engine.setVolume(1.7f)
        assertEquals(1f, player.lastVolume)
        engine.setVolume(0.25f)
        assertEquals(0.25f, engine.state.value.volume)
        assertEquals(0.25f, player.lastVolume)
    }

    @Test
    fun activityNameValidation() {
        assertEquals("Deep work", cleanActivityName("  Deep   work "))
        assertNull(cleanActivityName("   "))
        assertNull(cleanActivityName("x".repeat(41)))
    }
}
