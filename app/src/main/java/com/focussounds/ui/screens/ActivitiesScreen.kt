package com.focussounds.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import com.focussounds.ui.components.PillShape
import com.focussounds.ui.components.ScreenHeader
import com.focussounds.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.Durations
import com.focussounds.core.analytics.SessionFact
import com.focussounds.data.ActivityEntity
import com.focussounds.ui.FocusViewModel
import com.focussounds.ui.components.ActivityBadge
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.FocusCard
import com.focussounds.ui.components.HairlineDivider
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class ActivityRow(val entity: ActivityEntity, val totalMs: Long, val sessions: Int)

class ActivitiesViewModel(c: AppContainer) : ViewModel() {
    /** Activities with their all-time focus (counted sessions linked to the activity id). */
    val rows: StateFlow<List<ActivityRow>> = combine(c.activities.activities, c.facts.all) { activities, facts ->
        val byKey = facts.filter { it.counted }.groupBy { it.activityKey }
        activities.map { a ->
            val list = byKey[SessionFact.activityKeyFor(a.id, a.name)].orEmpty()
            ActivityRow(a, list.sumOf { it.activeMs }, list.size)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { ActivitiesViewModel(c) } }
    }
}

@Composable
fun ActivitiesScreen(vm: ActivitiesViewModel, focusVm: FocusViewModel) {
    val c = FocusTheme.colors
    val rows by vm.rows.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ActivityEntity?>(null) }
    var deleting by remember { mutableStateOf<ActivityRow?>(null) }
    val builtIn = rows.filter { it.entity.isBuiltIn }
    val custom = rows.filter { !it.entity.isBuiltIn }

    LazyColumn(
        Modifier.fillMaxSize().testTag("activities_list"),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader("Activities", subtitle = "What you focus on. Renaming or deleting never changes recorded history.") {
                Row(
                    Modifier.clip(PillShape).background(c.accent).clickable(role = Role.Button) { adding = true }
                        .heightIn(min = 44.dp).padding(start = 12.dp, end = 16.dp).testTag("add_activity")
                        .semantics(mergeDescendants = true) { contentDescription = "Add activity" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Plus, null, Modifier.size(18.dp), tint = c.onAccent)
                    Spacer(Modifier.width(6.dp))
                    Text("New", style = MaterialTheme.typography.labelLarge, color = c.onAccent, maxLines = 1)
                }
            }
        }
        item { SectionLabel("Your activities") }
        item {
            FocusCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                if (custom.isEmpty()) {
                    Text("Add your own, like “Guitar practice” or “Thesis”. They appear on the Focus screen next to the built-in ones.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = 14.dp))
                }
                custom.forEachIndexed { i, r ->
                    if (i > 0) HairlineDivider(Modifier.padding(start = 56.dp))
                    ActivityRowView(r, editable = true, onRename = { renaming = r.entity }, onDelete = { deleting = r })
                }
            }
        }
        item { SectionLabel("Built-in") }
        item {
            FocusCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                builtIn.forEachIndexed { i, r ->
                    if (i > 0) HairlineDivider(Modifier.padding(start = 56.dp))
                    ActivityRowView(r, editable = false, onRename = {}, onDelete = {})
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Lock, null, Modifier.size(14.dp), tint = c.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text("Built-in activities are always available and can't be renamed or deleted.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }

    if (adding) NameDialog("New activity", "", onConfirm = { focusVm.addActivity(it, select = false) { adding = false } }, onDismiss = { adding = false })
    renaming?.let { a ->
        NameDialog("Rename activity", a.name, onConfirm = { focusVm.renameActivity(a.id, it) { renaming = null } }, onDismiss = { renaming = null })
    }
    deleting?.let { r ->
        ConfirmDialog(
            title = "Delete “${r.entity.name}”?",
            message = if (r.sessions > 0) {
                "Its ${r.sessions} recorded ${if (r.sessions == 1) "session stays" else "sessions stay"} in History and Progress under the name “${r.entity.name}” (${Durations.format(r.totalMs)})."
            } else {
                "This activity has no recorded sessions."
            },
            confirmLabel = "Delete",
            onConfirm = {
                focusVm.deleteActivity(r.entity.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ActivityRowView(r: ActivityRow, editable: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = FocusTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ActivityBadge(r.entity.name, AppIcons.forBuiltIn(r.entity.builtInKey), size = 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.entity.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (r.sessions == 0) "No sessions yet" else sessionsLabel(r.sessions),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
        }
        if (r.sessions > 0) {
            Spacer(Modifier.width(8.dp))
            Text(Durations.format(r.totalMs), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1)
        }
        if (editable) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRename) { Icon(AppIcons.Edit, "Rename ${r.entity.name}", Modifier.size(20.dp), tint = c.textSecondary) }
            IconButton(onClick = onDelete) { Icon(AppIcons.Trash, "Delete ${r.entity.name}", Modifier.size(20.dp), tint = c.textSecondary) }
        }
    }
}
