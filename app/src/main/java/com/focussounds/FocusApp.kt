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
        container = AppContainer(this)
    }
}

/** Process-wide singletons. The engine outlives activities and the playback service. */
class AppContainer(app: Application) {
    /** Main-thread scope: the engine and ExoPlayer both live on the main thread. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wallClock = WallClock { System.currentTimeMillis() }

    val database = FocusDatabase.open(app)
    val prefs = Prefs(app)
    val activities = ActivityRepository(database, wallClock)
    val history = HistoryRepository(database)

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
