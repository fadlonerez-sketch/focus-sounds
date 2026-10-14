package com.focussounds

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.EndReason
import com.focussounds.core.NewSessionRecord
import com.focussounds.data.AppSettings
import com.focussounds.data.MotionLevel
import com.focussounds.data.RoomSessionStore
import com.focussounds.ui.AppRoot
import com.focussounds.ui.AppViewModels
import com.focussounds.ui.FocusViewModel
import com.focussounds.ui.screens.ActivitiesViewModel
import com.focussounds.ui.screens.HistoryViewModel
import com.focussounds.ui.screens.ProgressViewModel
import com.focussounds.ui.screens.SettingsViewModel
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders every destination at 2x font scale with real data: nothing may crash or disappear. */
@RunWith(AndroidJUnit4::class)
class LargeFontTest {
    @get:Rule
    val rule = createComposeRule()

    private val c get() = ApplicationProvider.getApplicationContext<FocusApp>().container

    @Before
    fun setUp() {
        check(FocusApp.storageSuffix == "_test")
        c.settings.update { AppSettings(motion = MotionLevel.OFF, haptics = false) }
        runBlocking {
            c.facts.clearFinished()
            val store = RoomSessionStore(c.database)
            val now = System.currentTimeMillis()
            listOf(0L, 1L, 3L, 9L).forEachIndexed { i, d ->
                val start = now - d * 86_400_000L - 3_600_000L
                store.insertActive(NewSessionRecord("big-$i", 1, "Studying", listOf("ocean", "forest", "stream", "white_noise")[i], start, 25 * 60_000L))
                store.finish("big-$i", (20 + i * 7) * 60_000L, start + 40 * 60_000L, if (i % 2 == 0) EndReason.COMPLETED else EndReason.STOPPED)
            }
        }
    }

    @Test
    fun everyScreenRendersAtDoubleFontScale() {
        rule.setContent {
            val vms = remember { AppViewModels(FocusViewModel(c), ProgressViewModel(c), HistoryViewModel(c), ActivitiesViewModel(c), SettingsViewModel(c)) }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                FocusTheme(AppSettings.DEFAULT_ACCENT, MotionLevel.OFF, hapticsEnabled = false) {
                    AppRoot(vms, c.messages, onStartRequested = {})
                }
            }
        }
        rule.onNodeWithTag("focus_idle").performScrollToNode(hasTestTag("start_button"))
        rule.onNodeWithTag("start_button").assertIsDisplayed()

        rule.onNodeWithTag("nav_progress").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("hero_card")).fetchSemanticsNodes().isNotEmpty() }
        for (tag in listOf("hero_card", "goals_card", "trend_card", "activities_card", "calendar_card", "sounds_card", "details_card")) {
            rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).assertIsDisplayed()
        }
        rule.onNodeWithTag("nav_history").performClick()
        rule.onNodeWithTag("history_list").assertIsDisplayed()
        rule.onNodeWithTag("nav_activities").performClick()
        rule.onNodeWithTag("add_activity").assertIsDisplayed()
        rule.onNodeWithTag("nav_settings").performClick()
        for (tag in listOf("appearance_card", "goals_settings", "focus_settings", "data_settings")) {
            rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(tag))
        }
    }
}
