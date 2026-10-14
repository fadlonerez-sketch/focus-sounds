package com.focussounds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.EndReason
import com.focussounds.core.NewSessionRecord
import com.focussounds.core.analytics.AnalyticsSettings
import com.focussounds.core.analytics.ProgressAnalytics
import com.focussounds.core.analytics.RangePreset
import com.focussounds.data.ActivityEditResult
import com.focussounds.data.ActivityRepository
import com.focussounds.data.AppSettings
import com.focussounds.data.FocusDatabase
import com.focussounds.data.MotionLevel
import com.focussounds.data.RoomSessionStore
import com.focussounds.data.SessionFactsRepository
import com.focussounds.data.SessionDeleteResult
import com.focussounds.data.SettingsRepository
import com.focussounds.ui.screens.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class SettingsAndDataTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefsFile = "settings-test-${System.nanoTime()}"
    private lateinit var db: FocusDatabase

    @Before
    fun setUp() {
        db = FocusDatabase.inMemory(ctx)
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteSharedPreferences(prefsFile)
    }

    @Test
    fun defaultsAreDarkThemeAccentAndNoGoals() {
        val s = SettingsRepository(ctx, prefsFile).current
        assertEquals(AppSettings.DEFAULT_ACCENT, s.accentArgb)
        assertEquals(MotionLevel.FULL, s.motion)
        assertEquals(0, s.dailyGoalMinutes)
        assertEquals(0, s.weeklyGoalMinutes)
        assertTrue(s.rememberLastSelection)
    }

    @Test
    fun settingsPersistAcrossRestartsAndApplyImmediately() {
        val repo = SettingsRepository(ctx, prefsFile)
        repo.update {
            it.copy(accentArgb = 0xFF2DD4BF.toInt(), motion = MotionLevel.REDUCED, haptics = false, dailyGoalMinutes = 90,
                weeklyGoalMinutes = 600, countdownSeconds = 5, completionChime = false, defaultSoundId = "ocean", rememberLastSelection = false)
        }
        assertEquals("in-memory value updates immediately", 0xFF2DD4BF.toInt(), repo.settings.value.accentArgb)
        Thread.sleep(200) // apply() is asynchronous
        val reopened = SettingsRepository(ctx, prefsFile).current // simulates an app restart
        assertEquals(0xFF2DD4BF.toInt(), reopened.accentArgb)
        assertEquals(MotionLevel.REDUCED, reopened.motion)
        assertFalse(reopened.haptics)
        assertEquals(90, reopened.dailyGoalMinutes)
        assertEquals(600, reopened.weeklyGoalMinutes)
        assertEquals(5, reopened.countdownSeconds)
        assertFalse(reopened.completionChime)
        assertEquals("ocean", reopened.defaultSoundId)
        assertFalse(reopened.rememberLastSelection)
    }

    @Test
    fun resetToDefaultAndSanitizing() {
        val repo = SettingsRepository(ctx, prefsFile)
        repo.update { it.copy(accentArgb = 0x00123456, countdownSeconds = 7, dailyGoalMinutes = -5, defaultSoundId = "rain") }
        val s = repo.current
        assertEquals("accent is always opaque", 0xFF123456.toInt(), s.accentArgb)
        assertEquals(0, s.countdownSeconds)
        assertEquals(0, s.dailyGoalMinutes)
        assertEquals("brown_noise", s.defaultSoundId)
        repo.resetAccent()
        assertEquals(AppSettings.DEFAULT_ACCENT, repo.current.accentArgb)
    }

    @Test
    fun factsFollowRenamesAndKeepDeletedActivities() = runBlocking {
        val activities = ActivityRepository(db) { 0L }
        val store = RoomSessionStore(db)
        val guitar = (activities.add("Guitar") as ActivityEditResult.Ok).id
        val chess = (activities.add("Chess") as ActivityEditResult.Ok).id
        store.insertActive(NewSessionRecord("a", guitar, "Guitar", "ocean", 1_000, null))
        store.finish("a", 60_000, 70_000, EndReason.STOPPED)
        store.insertActive(NewSessionRecord("b", chess, "Chess", "forest", 2_000, 25 * 60_000L))
        store.finish("b", 25 * 60_000L, 2_000 + 25 * 60_000L, EndReason.COMPLETED)
        store.insertActive(NewSessionRecord("c", chess, "Chess", "forest", 3_000, null)) // still running

        activities.rename(guitar, "Music practice")
        activities.delete(chess)

        val facts = SessionFactsRepository(db).all.first().associateBy { it.id }
        assertEquals("Music practice", facts.getValue("a").activityName)
        assertEquals("id:$guitar", facts.getValue("a").activityKey)
        assertEquals("Chess", facts.getValue("b").activityName)
        assertTrue(facts.getValue("b").activityDeleted)
        assertEquals("name:chess", facts.getValue("b").activityKey)
        assertEquals(EndReason.COMPLETED, facts.getValue("b").outcome)
        assertNull("running rows have no outcome", facts.getValue("c").outcome)
    }

    @Test
    fun clearingHistoryNeverTouchesARunningSession() = runBlocking {
        val store = RoomSessionStore(db)
        store.insertActive(NewSessionRecord("done", null, "Studying", "ocean", 1_000, null))
        store.finish("done", 10_000, 20_000, EndReason.STOPPED)
        store.insertActive(NewSessionRecord("running", null, "Studying", "ocean", 30_000, null))
        assertEquals(1, SessionFactsRepository(db).clearFinished())
        val left = db.sessionDao().all()
        assertEquals(listOf("running"), left.map { it.id })
    }

    // ---------------------------------------------------------------- deleting one session

    private suspend fun finished(store: RoomSessionStore, id: String, activityId: Long?, startMs: Long, activeMs: Long, reason: EndReason = EndReason.STOPPED) {
        store.insertActive(NewSessionRecord(id, activityId, "Guitar", "ocean", startMs, null))
        store.finish(id, activeMs, startMs + activeMs, reason)
    }

    @Test
    fun deletingASessionRemovesOnlyThatSession() = runBlocking {
        val activities = ActivityRepository(db) { 0L }
        val store = RoomSessionStore(db)
        val facts = SessionFactsRepository(db)
        val guitar = (activities.add("Guitar") as ActivityEditResult.Ok).id
        finished(store, "a", guitar, 1_000, 60_000)
        finished(store, "b", guitar, 2_000, 120_000)
        store.insertActive(NewSessionRecord("running", guitar, "Guitar", "ocean", 3_000, null))

        assertEquals(SessionDeleteResult.Deleted, facts.deleteSession("a"))

        assertEquals(listOf("b", "running"), db.sessionDao().all().map { it.id })
        assertEquals("the activity is kept", listOf("Guitar"), activities.activities.first().map { it.name })
        assertEquals("other sessions keep their activity link", guitar, db.sessionDao().get("b")!!.activityId)
        assertEquals("the facts flow re-emits without the session", listOf("b", "running"), facts.all.first().map { it.id })
        assertEquals("deleting twice is harmless", SessionDeleteResult.NotFound, facts.deleteSession("a"))
        assertEquals(SessionDeleteResult.NotFound, facts.deleteSession("no-such-id"))
        assertEquals(2, db.sessionDao().all().size)
    }

    @Test
    fun aRunningSessionCanNeverBeDeleted() = runBlocking {
        val store = RoomSessionStore(db)
        val facts = SessionFactsRepository(db)
        store.insertActive(NewSessionRecord("running", null, "Studying", "ocean", 1_000, 25 * 60_000L))
        store.checkpoint("running", 30_000, 31_000)

        assertEquals(SessionDeleteResult.Running, facts.deleteSession("running"))
        assertEquals("the DAO refuses too, even when called directly", 0, db.sessionDao().deleteFinishedById("running"))
        val row = db.sessionDao().get("running")!!
        assertEquals("active", row.state)
        assertEquals(30_000, row.activeDurationMs)
        // The engine can still finish it normally afterwards.
        assertTrue(store.finish("running", 40_000, 41_000, EndReason.STOPPED))
        assertEquals(SessionDeleteResult.Deleted, facts.deleteSession("running"))
    }

    @Test
    fun deletionPersistsAcrossDatabaseReopen() = runBlocking {
        val name = "delete-persist-test.db"
        ctx.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(ctx, FocusDatabase::class.java, name).build()
        open().also { d ->
            val store = RoomSessionStore(d)
            finished(store, "keep", null, 1_000, 60_000)
            finished(store, "gone", null, 2_000, 60_000)
            assertEquals(SessionDeleteResult.Deleted, SessionFactsRepository(d).deleteSession("gone"))
            d.close()
        }
        open().also { d -> // simulates an app restart
            assertEquals(listOf("keep"), d.sessionDao().all().map { it.id })
            d.close()
        }
        ctx.deleteDatabase(name)
        Unit
    }

    @Test
    fun analyticsRefreshAfterDeletion() = runBlocking {
        val zone = ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 10, 14)
        val now = today.atTime(18, 0).toInstant(zone).toEpochMilli()
        fun at(daysAgo: Long) = today.minusDays(daysAgo).atTime(9, 0).toInstant(zone).toEpochMilli()
        val store = RoomSessionStore(db)
        val facts = SessionFactsRepository(db)
        finished(store, "d0", null, at(0), 30 * 60_000L, EndReason.COMPLETED)
        finished(store, "d1", null, at(1), 20 * 60_000L)
        finished(store, "d2", null, at(2), 40 * 60_000L)

        suspend fun report() = ProgressAnalytics(
            facts.all.first(),
            AnalyticsSettings(zone, now, java.time.DayOfWeek.MONDAY, dailyGoalMs = 25 * 60_000L, weeklyGoalMs = null),
        ).report(RangePreset.LAST_7_DAYS)

        val before = report()
        assertEquals(90 * 60_000L, before.overview.totalMs)
        assertEquals(3, before.overview.sessions)
        assertEquals(3, before.streaks.current)

        assertEquals(SessionDeleteResult.Deleted, facts.deleteSession("d1"))

        val after = report()
        assertEquals("totals drop by exactly the deleted session", 70 * 60_000L, after.overview.totalMs)
        assertEquals(2, after.overview.sessions)
        assertEquals("average follows", 35 * 60_000L, after.overview.averageSessionMs)
        assertEquals("the streak breaks at the now-empty day", 1, after.streaks.current)
        assertEquals(2, after.overview.focusDays)
        assertEquals("today's goal progress is unaffected", 30 * 60_000L, after.dailyGoal!!.achievedMs)
    }

    @Test
    fun csvExportContainsActiveTimeAndOutcomes() = runBlocking {
        val store = RoomSessionStore(db)
        store.insertActive(NewSessionRecord("x", null, "Deep, \"work\"", "white_noise", 0, 25 * 60_000L))
        store.finish("x", 20 * 60_000L, 30 * 60_000L, EndReason.COMPLETED)
        val csv = SettingsViewModel.buildCsv(SessionFactsRepository(db).all.first(), ZoneOffset.UTC)
        val lines = csv.trim().split("\r\n")
        assertEquals("session_id,activity,activity_deleted,sound,started,ended,planned_minutes,active_seconds,active_minutes,outcome", lines[0])
        assertEquals("x,\"Deep, \"\"work\"\"\",true,White Noise,1970-01-01 00:00:00,1970-01-01 00:30:00,25,1200,20.00,completed", lines[1])
    }
}
