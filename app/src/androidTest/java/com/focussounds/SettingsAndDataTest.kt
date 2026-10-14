package com.focussounds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.EndReason
import com.focussounds.core.NewSessionRecord
import com.focussounds.data.ActivityEditResult
import com.focussounds.data.ActivityRepository
import com.focussounds.data.AppSettings
import com.focussounds.data.FocusDatabase
import com.focussounds.data.MotionLevel
import com.focussounds.data.RoomSessionStore
import com.focussounds.data.SessionFactsRepository
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
