package com.focussounds.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.Csv
import com.focussounds.core.FocusSound
import com.focussounds.core.analytics.SessionFact
import com.focussounds.data.ActivityEntity
import com.focussounds.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings
    val activities: StateFlow<List<ActivityEntity>> = c.activities.activities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessionActive: StateFlow<Boolean> = c.engine.state.map { it.active != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val finishedSessions: StateFlow<Int> = c.facts.all.map { l -> l.count { it.outcome != null } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val versionName: String = runCatching {
        c.app.packageManager.getPackageInfo(c.app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun update(transform: (AppSettings) -> AppSettings) = c.settings.update(transform)
    fun setAccent(argb: Int) = c.settings.update { it.copy(accentArgb = argb) }
    fun resetAccent() = c.settings.resetAccent()

    /** Writes every finished session to the document the user picked (Storage Access Framework). */
    fun exportCsv(uri: Uri) = viewModelScope.launch {
        val facts = c.facts.all.first().filter { it.outcome != null }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val csv = buildCsv(facts, ZoneId.systemDefault())
                c.app.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for spreadsheet apps
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
            }
        }
        c.messages.tryEmit(
            if (result.isSuccess) "Exported ${facts.size} ${if (facts.size == 1) "session" else "sessions"}"
            else "Export failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
        )
    }

    /** Deletes finished sessions only (never a running one). Activities and settings are kept. */
    fun clearHistory() = viewModelScope.launch {
        val n = c.facts.clearFinished()
        c.messages.tryEmit("Deleted $n ${if (n == 1) "session" else "sessions"} from this device")
    }

    companion object {
        private val ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        fun buildCsv(facts: List<SessionFact>, zone: ZoneId): String {
            fun t(ms: Long?) = ms?.let { ISO_LOCAL.format(Instant.ofEpochMilli(it).atZone(zone)) }
            return Csv.build(
                listOf("session_id", "activity", "activity_deleted", "sound", "started", "ended", "planned_minutes", "active_seconds", "active_minutes", "outcome"),
                facts.sortedBy { it.startedAtMs }.map { f ->
                    listOf(
                        f.id,
                        f.activityName,
                        f.activityDeleted.toString(),
                        FocusSound.fromId(f.soundId)?.displayName ?: f.soundId,
                        t(f.startedAtMs),
                        t(f.endedAtMs),
                        f.plannedDurationMs?.let { (it / 60_000).toString() },
                        (f.activeMs / 1000).toString(),
                        String.format(Locale.ROOT, "%.2f", f.activeMs / 60_000.0),
                        f.outcome?.key ?: "in_progress",
                    )
                },
            )
        }

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { SettingsViewModel(c) } }
    }
}
