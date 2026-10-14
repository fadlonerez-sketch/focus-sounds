package com.focussounds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.BuiltInActivity
import com.focussounds.core.EndReason
import com.focussounds.core.NewSessionRecord
import com.focussounds.data.ActivityEditResult
import com.focussounds.data.ActivityRepository
import com.focussounds.data.FocusDatabase
import com.focussounds.data.HistoryRepository
import com.focussounds.data.RoomSessionStore
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

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: FocusDatabase
    private lateinit var store: RoomSessionStore
    private lateinit var activities: ActivityRepository
    private val t0 = 1_760_000_000_000L

    @Before
    fun setUp() {
        db = FocusDatabase.inMemory(ctx)
        store = RoomSessionStore(db)
        activities = ActivityRepository(db) { t0 }
    }

    @After
    fun tearDown() = db.close()

    private fun record(id: String, activityId: Long?, name: String) =
        NewSessionRecord(id, activityId, name, "brown_noise", t0, 25 * 60_000L)

    @Test
    fun finishIsRecordedExactlyOnce() = runBlocking {
        store.insertActive(record("a", null, "Studying"))
        assertTrue(store.finish("a", 1_500_000, t0 + 1_500_000, EndReason.COMPLETED))
        assertFalse("second finish must be rejected", store.finish("a", 9_999_999, t0 + 9_999_999, EndReason.STOPPED))
        store.checkpoint("a", 1, t0 + 1) // late checkpoint must not alter a finished row
        val row = db.sessionDao().get("a")!!
        assertEquals("completed", row.endReason)
        assertEquals(1_500_000, row.activeDurationMs)
        assertEquals(t0 + 1_500_000, row.endedAtMs)
        assertEquals(1, db.sessionDao().all().size)
    }

    @Test
    fun orphanRecoveryUsesLastCheckpointOnly() = runBlocking {
        store.insertActive(record("a", null, "Studying"))
        store.checkpoint("a", 30_000, t0 + 31_000)
        assertEquals(1, store.finishOrphans())
        assertEquals(0, store.finishOrphans())
        val row = db.sessionDao().get("a")!!
        assertEquals("finished", row.state)
        assertEquals("interrupted", row.endReason)
        assertEquals(30_000, row.activeDurationMs)
        assertEquals(t0 + 31_000, row.endedAtMs)
    }

    @Test
    fun builtInsAreSeededOnceAndProtected() = runBlocking {
        activities.ensureBuiltIns()
        activities.ensureBuiltIns()
        val list = activities.activities.first()
        assertEquals(BuiltInActivity.entries.map { it.displayName }, list.map { it.name })
        val studying = list.first()
        assertEquals(ActivityEditResult.NotEditable, activities.rename(studying.id, "Exams"))
        assertFalse(activities.delete(studying.id))
        assertEquals(6, activities.activities.first().size)
    }

    @Test
    fun duplicateAndInvalidNamesAreRejected() = runBlocking {
        activities.ensureBuiltIns()
        assertEquals(ActivityEditResult.DuplicateName, activities.add("  reading "))
        assertEquals(ActivityEditResult.InvalidName, activities.add("   "))
        assertTrue(activities.add("Guitar") is ActivityEditResult.Ok)
        assertEquals(ActivityEditResult.DuplicateName, activities.add("GUITAR"))
    }

    @Test
    fun renamingKeepsHistoryLinkedAndReadable() = runBlocking {
        val id = (activities.add("Guitar") as ActivityEditResult.Ok).id
        store.insertActive(record("s", id, "Guitar"))
        store.finish("s", 60_000, t0 + 60_000, EndReason.STOPPED)
        assertTrue(activities.rename(id, "Music practice") is ActivityEditResult.Ok)
        val item = HistoryRepository(db).items.first().single()
        assertEquals("Music practice", item.activityName)
        assertFalse(item.activityDeleted)
        assertEquals("Guitar", db.sessionDao().get("s")!!.activityName) // snapshot kept
    }

    @Test
    fun deletingKeepsHistoryWithOriginalName() = runBlocking {
        val id = (activities.add("Chess") as ActivityEditResult.Ok).id
        store.insertActive(record("s", id, "Chess"))
        store.finish("s", 120_000, t0 + 130_000, EndReason.STOPPED)
        assertTrue(activities.delete(id))
        val item = HistoryRepository(db).items.first().single()
        assertEquals("Chess", item.activityName)
        assertTrue(item.activityDeleted)
        assertEquals(120_000, item.activeDurationMs)
        assertEquals(EndReason.STOPPED, item.endReason)
        assertNull(db.sessionDao().get("s")!!.activityId)
    }

    @Test
    fun customActivitiesPersistAcrossDatabaseReopen() = runBlocking {
        val name = "persist-test.db"
        ctx.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(ctx, FocusDatabase::class.java, name).build()
        open().also { d ->
            ActivityRepository(d) { t0 }.add("Side project")
            d.close()
        }
        open().also { d ->
            assertEquals(listOf("Side project"), ActivityRepository(d) { t0 }.activities.first().map { it.name })
            d.close()
        }
        ctx.deleteDatabase(name)
        Unit
    }
}
