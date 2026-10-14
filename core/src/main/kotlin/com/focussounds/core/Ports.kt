package com.focussounds.core

/** Monotonic clock that keeps counting during device sleep (Android: SystemClock.elapsedRealtime). */
fun interface MonotonicClock {
    fun elapsedMs(): Long
}

/** Wall-clock time, used only for display timestamps (start/end), never for measuring durations. */
fun interface WallClock {
    fun nowMs(): Long
}

/**
 * The single audio player owned by the platform's playback service.
 *
 * Implementations report status changes back via [SessionEngine.onPlaybackStatusChanged] and
 * [SessionEngine.onPlaybackError] on the engine's thread.
 */
interface PlaybackPort {
    /** Loads [sound] as a seamless loop and starts playing it (requesting audio focus). */
    fun playLooping(sound: FocusSound, title: String, subtitle: String)
    fun play()
    fun pause()

    /** Stops playback, clears the loaded item, and lets the platform release audio focus. */
    fun stopAndClear()
    fun setVolume(volume: Float)
}

data class NewSessionRecord(
    val id: String,
    val activityId: Long?,
    val activityName: String,
    val soundId: String,
    val startedAtWallMs: Long,
    val plannedDurationMs: Long?,
)

/** Persistence used by the engine. All calls are made sequentially, never concurrently. */
interface SessionStore {
    suspend fun insertActive(record: NewSessionRecord)

    /** Saves progress of an active session so a later recovery never has to guess. */
    suspend fun checkpoint(id: String, activeMs: Long, atWallMs: Long)

    /**
     * Marks an ACTIVE session finished. Must be a no-op returning false if the session is not
     * ACTIVE, so a session can never be finished twice.
     */
    suspend fun finish(id: String, activeMs: Long, endedAtWallMs: Long, reason: EndReason): Boolean

    /**
     * Finishes every ACTIVE row left behind by a dead process as [EndReason.INTERRUPTED], using
     * the last checkpoint for both the active duration and the end time (no invented time).
     * Returns the number of rows recovered.
     */
    suspend fun finishOrphans(): Int
}
