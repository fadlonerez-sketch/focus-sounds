package com.focussounds.data

import android.content.Context
import androidx.room.withTransaction
import com.focussounds.core.BuiltInActivity
import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import com.focussounds.core.NewSessionRecord
import com.focussounds.core.SessionEngine
import com.focussounds.core.SessionRowState
import com.focussounds.core.SessionStore
import com.focussounds.core.WallClock
import com.focussounds.core.cleanActivityName
import com.focussounds.core.ActiveSession
import com.focussounds.core.analytics.SessionFact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomSessionStore(private val db: FocusDatabase) : SessionStore {
    private val dao = db.sessionDao()

    override suspend fun insertActive(record: NewSessionRecord) = dao.insert(
        SessionEntity(
            id = record.id,
            activityId = record.activityId,
            activityName = record.activityName,
            soundId = record.soundId,
            startedAtMs = record.startedAtWallMs,
            endedAtMs = null,
            plannedDurationMs = record.plannedDurationMs,
            activeDurationMs = 0,
            state = SessionRowState.ACTIVE.key,
            endReason = null,
            lastCheckpointMs = record.startedAtWallMs,
        ),
    )

    override suspend fun checkpoint(id: String, activeMs: Long, atWallMs: Long) {
        dao.checkpoint(id, activeMs, atWallMs)
    }

    override suspend fun finish(id: String, activeMs: Long, endedAtWallMs: Long, reason: EndReason): Boolean =
        dao.finish(id, activeMs, endedAtWallMs, reason.key) == 1

    override suspend fun finishOrphans(): Int = dao.finishOrphans()
}

sealed interface ActivityEditResult {
    data class Ok(val id: Long) : ActivityEditResult
    data object InvalidName : ActivityEditResult
    data object DuplicateName : ActivityEditResult
    data object NotEditable : ActivityEditResult
}

class ActivityRepository(private val db: FocusDatabase, private val wallClock: WallClock) {
    private val dao = db.activityDao()

    val activities: Flow<List<ActivityEntity>> = dao.observeAll()

    /** Inserts any missing built-in activities; existing rows (by stable key) are left untouched. */
    suspend fun ensureBuiltIns() {
        val now = wallClock.nowMs()
        dao.insertIgnoringExisting(BuiltInActivity.entries.map { ActivityEntity(name = it.displayName, builtInKey = it.key, createdAtMs = now) })
    }

    suspend fun add(rawName: String): ActivityEditResult {
        val name = cleanActivityName(rawName) ?: return ActivityEditResult.InvalidName
        return db.withTransaction {
            if (dao.countWithName(name) > 0) return@withTransaction ActivityEditResult.DuplicateName
            ActivityEditResult.Ok(dao.insert(ActivityEntity(name = name, builtInKey = null, createdAtMs = wallClock.nowMs())))
        }
    }

    suspend fun rename(id: Long, rawName: String): ActivityEditResult {
        val name = cleanActivityName(rawName) ?: return ActivityEditResult.InvalidName
        return db.withTransaction {
            if (dao.countWithName(name, excludeId = id) > 0) return@withTransaction ActivityEditResult.DuplicateName
            if (dao.renameCustom(id, name) == 1) ActivityEditResult.Ok(id) else ActivityEditResult.NotEditable
        }
    }

    /** Deletes a custom activity. Its past sessions remain, keeping the name they were recorded with. */
    suspend fun delete(id: Long): Boolean = db.withTransaction {
        val activity = dao.get(id) ?: return@withTransaction false
        if (activity.isBuiltIn) return@withTransaction false
        // Explicit, so history is preserved even if foreign-key enforcement were off.
        db.sessionDao().detachActivity(id)
        dao.deleteCustom(id) == 1
    }
}

data class HistoryItem(
    val id: String,
    val activityName: String,
    val activityDeleted: Boolean,
    val soundName: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val plannedDurationMs: Long?,
    val activeDurationMs: Long,
    val endReason: EndReason?,
    val inProgress: Boolean,
)

class HistoryRepository(db: FocusDatabase) {
    val items: Flow<List<HistoryItem>> = db.sessionDao().observeHistory().map { rows ->
        rows.map { r ->
            val s = r.session
            HistoryItem(
                id = s.id,
                // Current name if the activity still exists (follows renames), else the snapshot.
                activityName = r.currentActivityName ?: s.activityName,
                activityDeleted = s.activityId == null,
                soundName = FocusSound.fromId(s.soundId)?.displayName ?: s.soundId,
                startedAtMs = s.startedAtMs,
                endedAtMs = s.endedAtMs,
                plannedDurationMs = s.plannedDurationMs,
                activeDurationMs = s.activeDurationMs,
                endReason = EndReason.fromKey(s.endReason),
                inProgress = s.state == SessionRowState.ACTIVE.key,
            )
        }
    }
}

/** Small UI preferences (last selections, volume). Session data never goes here. */
class Prefs(context: Context, fileName: String = "focus_prefs") {
    private val sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    var volume: Float
        get() = sp.getFloat("volume", SessionEngine.DEFAULT_VOLUME)
        set(v) = sp.edit().putFloat("volume", v).apply()

    var lastActivityId: Long
        get() = sp.getLong("activity_id", -1)
        set(v) = sp.edit().putLong("activity_id", v).apply()

    var lastSoundId: String?
        get() = sp.getString("sound_id", null)
        set(v) = sp.edit().putString("sound_id", v).apply()

    /** 0 = manual (no timer). */
    var timerMinutes: Int
        get() = sp.getInt("timer_minutes", 25)
        set(v) = sp.edit().putInt("timer_minutes", v).apply()

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(v) = sp.edit().putBoolean("asked_notifications", v).apply()
}

enum class SessionDeleteResult { Deleted, Running, NotFound }

/** Sessions as analytics facts. Mapping runs off the main thread. */
class SessionFactsRepository(private val db: FocusDatabase) {
    /** Every persisted session, oldest first. Rows still marked active carry outcome = null. */
    val all: Flow<List<SessionFact>> = db.sessionDao().observeFactRows()
        .map { rows -> rows.map(::toFact) }
        .flowOn(Dispatchers.Default)

    suspend fun clearFinished(): Int = db.sessionDao().deleteFinished()

    /**
     * Deletes the single session [id]. Running sessions are refused; nothing else (other sessions,
     * activities) is touched. Every Flow over the sessions table re-emits afterwards.
     */
    suspend fun deleteSession(id: String): SessionDeleteResult = db.withTransaction {
        val dao = db.sessionDao()
        val row = dao.get(id) ?: return@withTransaction SessionDeleteResult.NotFound
        if (row.state != SessionRowState.FINISHED.key) return@withTransaction SessionDeleteResult.Running
        if (dao.deleteFinishedById(id) == 1) SessionDeleteResult.Deleted else SessionDeleteResult.NotFound
    }

    companion object {
        fun toFact(r: FactRow) = SessionFact(
            id = r.id,
            activityKey = SessionFact.activityKeyFor(r.activityId, r.activityName),
            activityName = r.currentActivityName ?: r.activityName,
            activityDeleted = r.activityId == null,
            soundId = r.soundId,
            startedAtMs = r.startedAtMs,
            endedAtMs = r.endedAtMs,
            plannedDurationMs = r.plannedDurationMs,
            activeMs = r.activeDurationMs,
            outcome = if (r.state == SessionRowState.ACTIVE.key) null else EndReason.fromKey(r.endReason),
        )

        /** The running session as a fact, with its live active time (never read from a stale checkpoint). */
        fun liveFact(s: ActiveSession, nowElapsedMs: Long) = SessionFact(
            id = s.id,
            activityKey = SessionFact.activityKeyFor(s.activityId, s.activityName),
            activityName = s.activityName,
            activityDeleted = s.activityId == null,
            soundId = s.sound.id,
            startedAtMs = s.startedAtWallMs,
            endedAtMs = null,
            plannedDurationMs = s.plannedDurationMs,
            activeMs = s.activeMs(nowElapsedMs),
            outcome = null,
        )
    }
}
