package com.focussounds

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.focussounds.core.SessionEngine
import com.focussounds.core.WallClock
import com.focussounds.data.ActivityRepository
import com.focussounds.data.FocusDatabase
import com.focussounds.data.HistoryRepository
import com.focussounds.data.Prefs
import com.focussounds.data.RoomSessionStore
import com.focussounds.data.SessionFactsRepository
import com.focussounds.data.SettingsRepository
import com.focussounds.playback.CompletionFeedback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class FocusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, storageSuffix)
    }

    companion object {
        /**
         * Set by the instrumented-test runner before onCreate so tests use their own database and
         * preferences and can never touch the user's real history.
         */
        @Volatile
        var storageSuffix: String = ""
    }
}

/** Process-wide singletons. The engine outlives activities and the playback service. */
class AppContainer(val app: Application, storageSuffix: String = "") {
    /** Main-thread scope: the engine and ExoPlayer both live on the main thread. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wallClock = WallClock { System.currentTimeMillis() }

    val database = FocusDatabase.open(app, "focus$storageSuffix.db")
    val prefs = Prefs(app, "focus_prefs$storageSuffix")
    val settings = SettingsRepository(app, "focus_settings$storageSuffix")
    val activities = ActivityRepository(database, wallClock)
    val history = HistoryRepository(database)
    val facts = SessionFactsRepository(database)
    val completionFeedback = CompletionFeedback(app)

    /** One-off messages shown as snackbars by the root screen. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val engine = SessionEngine(
        scope = appScope,
        clock = { SystemClock.elapsedRealtime() },
        wallClock = wallClock,
        store = RoomSessionStore(database),
        newId = { UUID.randomUUID().toString() },
        initialVolume = prefs.volume,
        onStoreError = { Log.e(TAG, "Failed to save session data", it) },
    )

    init {
        appScope.launch { activities.ensureBuiltIns() }
        appScope.launch {
            engine.state.map { it.volume }.distinctUntilChanged().drop(1).collect { prefs.volume = it }
        }
    }

    private companion object {
        const val TAG = "FocusSounds"
    }
}
