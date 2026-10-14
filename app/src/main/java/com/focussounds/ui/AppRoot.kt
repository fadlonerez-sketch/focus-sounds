package com.focussounds.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.screens.ActivitiesScreen
import com.focussounds.ui.screens.ActivitiesViewModel
import com.focussounds.ui.screens.FocusScreen
import com.focussounds.ui.screens.HistoryScreen
import com.focussounds.ui.screens.HistoryViewModel
import com.focussounds.ui.screens.ProgressScreen
import com.focussounds.ui.screens.ProgressViewModel
import com.focussounds.ui.screens.SettingsScreen
import com.focussounds.ui.screens.SettingsViewModel
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.flow.SharedFlow

enum class Destination(val label: String, val icon: ImageVector) {
    FOCUS("Focus", AppIcons.Focus),
    PROGRESS("Progress", AppIcons.Progress),
    HISTORY("History", AppIcons.History),
    ACTIVITIES("Activities", AppIcons.Activities),
    SETTINGS("Settings", AppIcons.Settings),
}

class AppViewModels(
    val focus: FocusViewModel,
    val progress: ProgressViewModel,
    val history: HistoryViewModel,
    val activities: ActivitiesViewModel,
    val settings: SettingsViewModel,
)

@Composable
fun AppRoot(vms: AppViewModels, messages: SharedFlow<String>, onStartRequested: () -> Unit) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val haptics = FocusTheme.haptics
    var dest by rememberSaveable { mutableStateOf(Destination.FOCUS) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = dest != Destination.FOCUS) { dest = Destination.FOCUS }
    // Keeps each tab's scroll position and local state while switching tabs.
    val holder = rememberSaveableStateHolder()

    Scaffold(
        containerColor = c.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = c.surface1, tonalElevation = 0.dp, modifier = Modifier.testTag("bottom_nav")) {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == dest,
                        onClick = {
                            if (d != dest) haptics.tick()
                            dest = d
                        },
                        icon = { Icon(d.icon, null) },
                        label = { Text(d.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.accentContent,
                            selectedTextColor = c.accentContent,
                            indicatorColor = c.accentContainer,
                            unselectedIconColor = c.textTertiary,
                            unselectedTextColor = c.textTertiary,
                        ),
                        modifier = Modifier.testTag("nav_${d.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            dest,
            transitionSpec = { fadeIn(motion.spec(220)) togetherWith fadeOut(motion.spec(150)) },
            label = "destination",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { d ->
            holder.SaveableStateProvider(d.name) {
                Box(Modifier.fillMaxSize().testTag("screen_${d.name.lowercase()}")) {
                    when (d) {
                        Destination.FOCUS -> FocusScreen(vms.focus, onStartRequested, onOpenProgress = { dest = Destination.PROGRESS })
                        Destination.PROGRESS -> ProgressScreen(vms.progress, onStartFocus = { dest = Destination.FOCUS })
                        Destination.HISTORY -> HistoryScreen(vms.history, onStartFocus = { dest = Destination.FOCUS })
                        Destination.ACTIVITIES -> ActivitiesScreen(vms.activities, vms.focus)
                        Destination.SETTINGS -> SettingsScreen(vms.settings)
                    }
                }
            }
        }
    }
}
