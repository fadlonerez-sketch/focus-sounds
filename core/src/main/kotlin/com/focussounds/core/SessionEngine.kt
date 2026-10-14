package com.focussounds.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StartRequest(
    val sound: FocusSound,
    val activityId: Long?,
    val activityName: String,
    /** Null for a manual (open-ended) session. */
    val plannedDurationMs: Long?,
)

enum class Phase { STARTING, PLAYING, PAUSED }

data class ActiveSession(
    val id: String,
    val sound: FocusSound,
    val activityId: Long?,
    val activityName: String,
    val plannedDurationMs: Long?,
    val startedAtWallMs: Long,
    /** Active time from completed playing intervals. */
    val accumulatedActiveMs: Long,
    /** Monotonic timestamp of when the current playing interval began; null when not playing. */
    val playingSinceElapsedMs: Long?,
    val phase: Phase,
    /** Set when [phase] is [Phase.PAUSED]. */
    val pauseCause: PauseCause?,
) {
    /** Active (actually playing) time, excluding paused time; capped at the planned duration. */
    fun activeMs(nowElapsedMs: Long): Long {
        val running = playingSinceElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0) } ?: 0
        val total = accumulatedActiveMs + running
        return plannedDurationMs?.let { minOf(total, it) } ?: total
    }

    fun remainingMs(nowElapsedMs: Long): Long? =
        plannedDurationMs?.let { (it - activeMs(nowElapsedMs)).coerceAtLeast(0) }
}

data class SessionResult(
    val id: String,
    val sound: FocusSound,
    val activityName: String,
    val activeMs: Long,
    val plannedDurationMs: Long?,
    val reason: EndReason,
    val detail: String? = null,
)

data class EngineState(
    val active: ActiveSession? = null,
    val lastResult: SessionResult? = null,
    val volume: Float = SessionEngine.DEFAULT_VOLUME,
    val playerAvailable: Boolean = false,
)

/**
 * Owns the lifecycle of focus sessions: start, pause/resume accounting, timed completion,
 * checkpoints, and the single final record per session.
 *
 * Active time is measured only from the player's real "is playing" signal, so pauses from any
 * source (app, notification, headset, audio focus loss, calls) are excluded automatically.
 *
 * Threading: every public method and every [scope] coroutine must run on one thread (Android: the
 * main thread, which is also the player's thread). Store writes are queued and executed in order.
 */
class SessionEngine(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val store: SessionStore,
    private val newId: () -> String,
    initialVolume: Float = DEFAULT_VOLUME,
    private val checkpointIntervalMs: Long = DEFAULT_CHECKPOINT_INTERVAL_MS,
    private val onStoreError: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow(EngineState(volume = initialVolume.coerceIn(0f, 1f)))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var port: PlaybackPort? = null
    private var completionJob: Job? = null
    private var checkpointJob: Job? = null
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val current: ActiveSession? get() = _state.value.active

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    onStoreError(t)
                }
            }
        }
        // Rows still ACTIVE at startup belong to a process that died. The queue is FIFO, so this
        // always completes before a new session of this process is inserted.
        enqueue { store.finishOrphans() }
    }

    fun attachPlayer(player: PlaybackPort) {
        check(port == null || port === player) { "A player is already attached; refusing a second one" }
        port = player
        player.setVolume(_state.value.volume)
        _state.update { it.copy(playerAvailable = true) }
    }

    /** Called when the platform service tears the player down. Ends any running session. */
    fun detachPlayer(player: PlaybackPort) {
        if (port !== player) return
        finish(EndReason.INTERRUPTED, detail = "Playback service stopped")
        port = null
        _state.update { it.copy(playerAvailable = false) }
    }

    /** Starts a new session, ending any running one first. Returns false if no player is attached. */
    fun start(request: StartRequest): Boolean {
        val player = port ?: return false
        require(request.plannedDurationMs == null || request.plannedDurationMs > 0) { "Planned duration must be positive" }
        if (current != null) finish(EndReason.STOPPED)

        val session = ActiveSession(
            id = newId(),
            sound = request.sound,
            activityId = request.activityId,
            activityName = request.activityName,
            plannedDurationMs = request.plannedDurationMs,
            startedAtWallMs = wallClock.nowMs(),
            accumulatedActiveMs = 0,
            playingSinceElapsedMs = null,
            phase = Phase.STARTING,
            pauseCause = null,
        )
        _state.update { it.copy(active = session, lastResult = null) }
        val record = NewSessionRecord(
            id = session.id,
            activityId = session.activityId,
            activityName = session.activityName,
            soundId = session.sound.id,
            startedAtWallMs = session.startedAtWallMs,
            plannedDurationMs = session.plannedDurationMs,
        )
        enqueue { store.insertActive(record) }

        player.setVolume(_state.value.volume)
        player.playLooping(request.sound, request.sound.displayName, subtitleFor(request))
        return true
    }

    fun pause() {
        if (current != null) port?.pause()
    }

    fun resume() {
        if (current != null) port?.play()
    }

    /** User-initiated stop from any surface (app, notification, system media controls). */
    fun stop() = finish(EndReason.STOPPED)

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        port?.setVolume(v)
        _state.update { it.copy(volume = v) }
    }

    fun dismissLastResult() = _state.update { it.copy(lastResult = null) }

    /** Reported by the player whenever its playing status or the reason for not playing changes. */
    fun onPlaybackStatusChanged(isPlaying: Boolean, pauseCause: PauseCause?) {
        val s = current ?: return
        val now = clock.elapsedMs()
        if (isPlaying) {
            if (s.playingSinceElapsedMs != null) return
            setActive(s.copy(playingSinceElapsedMs = now, phase = Phase.PLAYING, pauseCause = null))
            startTimers()
            return
        }
        val wasPlaying = s.playingSinceElapsedMs != null
        val updated = s.copy(
            accumulatedActiveMs = s.activeMs(now),
            playingSinceElapsedMs = null,
            phase = if (pauseCause == null) Phase.STARTING else Phase.PAUSED,
            pauseCause = pauseCause,
        )
        if (updated == s) return
        setActive(updated)
        if (wasPlaying) {
            stopTimers()
            if (updated.remainingMs(now) == 0L) {
                finish(EndReason.COMPLETED)
                return
            }
            checkpoint(updated)
        }
    }

    fun onPlaybackError(message: String) = finish(EndReason.INTERRUPTED, detail = message)

    private fun startTimers() {
        stopTimers()
        if (current?.plannedDurationMs != null) {
            completionJob = scope.launch {
                while (true) {
                    val s = current ?: return@launch
                    val remaining = s.remainingMs(clock.elapsedMs()) ?: return@launch
                    if (remaining <= 0) {
                        finish(EndReason.COMPLETED)
                        return@launch
                    }
                    // Re-checked against the monotonic clock after waking, so it can never end early.
                    delay(remaining)
                }
            }
        }
        checkpointJob = scope.launch {
            while (true) {
                delay(checkpointIntervalMs)
                current?.let { checkpoint(it) }
            }
        }
    }

    private fun stopTimers() {
        completionJob?.cancel()
        completionJob = null
        checkpointJob?.cancel()
        checkpointJob = null
    }

    private fun checkpoint(s: ActiveSession) {
        val activeMs = s.activeMs(clock.elapsedMs())
        val at = wallClock.nowMs()
        enqueue { store.checkpoint(s.id, activeMs, at) }
    }

    /** Ends the current session exactly once and records it. Safe to call repeatedly. */
    private fun finish(requested: EndReason, detail: String? = null) {
        val s = current ?: return
        stopTimers()
        val activeMs = s.activeMs(clock.elapsedMs())
        val reason = if (requested == EndReason.STOPPED && activeMs < MIN_ACTIVE_FOR_RECORDED_STOP_MS) {
            EndReason.CANCELLED
        } else {
            requested
        }
        val endedAt = wallClock.nowMs()
        // Clear the session before touching the player: callbacks fired by stopping are ignored.
        _state.update {
            it.copy(
                active = null,
                lastResult = SessionResult(s.id, s.sound, s.activityName, activeMs, s.plannedDurationMs, reason, detail),
            )
        }
        enqueue { store.finish(s.id, activeMs, endedAt, reason) }
        port?.stopAndClear()
    }

    private fun setActive(s: ActiveSession) = _state.update { it.copy(active = s) }

    private fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    private fun subtitleFor(r: StartRequest): String {
        val minutes = r.plannedDurationMs?.let { it / 60_000 }
        return if (minutes != null) "${r.activityName} · $minutes min timer" else "${r.activityName} · open session"
    }

    companion object {
        const val DEFAULT_VOLUME = 0.7f
        const val DEFAULT_CHECKPOINT_INTERVAL_MS = 15_000L

        /** Stopping before this much focus time is recorded as [EndReason.CANCELLED]. */
        const val MIN_ACTIVE_FOR_RECORDED_STOP_MS = 1_000L
    }
}
