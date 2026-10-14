package com.focussounds

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.NewSessionRecord
import com.focussounds.data.AppSettings
import com.focussounds.data.MotionLevel
import com.focussounds.data.RoomSessionStore
import com.focussounds.ui.MainActivity
import com.focussounds.ui.theme.FocusColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * End-to-end UI tests on the real app, using the isolated "_test" database and preferences
 * (see FocusTestRunner). Animations are turned off so the UI is deterministic.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    private val app: FocusApp = ApplicationProvider.getApplicationContext()
    private val c get() = app.container

    @get:Rule(order = 0)
    val reset = object : org.junit.rules.ExternalResource() {
        override fun before() = resetState()
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun resetState() {
        check(FocusApp.storageSuffix == "_test") { "UI tests must run with FocusTestRunner (isolated storage)" }
        onMain { if (c.engine.state.value.active != null) c.engine.stop() }
        runBlocking {
            c.facts.clearFinished()
            // Remove custom activities from previous tests.
            c.database.openHelper.writableDatabase.execSQL("DELETE FROM activities WHERE builtInKey IS NULL")
        }
        c.prefs.askedNotificationPermission = true
        c.settings.update { AppSettings(motion = MotionLevel.OFF, haptics = false, completionChime = false, completionVibration = false) }
    }

    @Before
    fun waitForApp() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Ready to focus").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun go(tab: String) {
        rule.onNodeWithTag("nav_$tab").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen_$tab").assertIsDisplayed()
    }

    private fun seed(daysAgo: Long, minutes: Long, sound: String = "brown_noise", activityId: Long = 1, name: String = "Studying",
                     outcome: EndReason = EndReason.STOPPED, id: String = "seed-$daysAgo-$minutes-$sound") {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(daysAgo).atTime(LocalTime.of(0, 30)).atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            val store = RoomSessionStore(c.database)
            store.insertActive(NewSessionRecord(id, activityId, name, sound, start, null))
            store.finish(id, minutes * 60_000, start + minutes * 60_000, outcome)
        }
    }

    // ---------------------------------------------------------------- navigation & session

    @Test
    fun navigatesBetweenAllFiveDestinations() {
        for (tab in listOf("progress", "history", "activities", "settings", "focus")) go(tab)
    }

    @Test
    fun activeSessionSurvivesTabChangesAndControlsWork() {
        rule.waitUntil(10_000) { c.engine.state.value.playerAvailable }
        rule.onNodeWithTag("start_button").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Playing", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val id = c.engine.state.value.active!!.id

        for (tab in listOf("progress", "history", "activities", "settings")) go(tab)
        go("focus")
        rule.onNodeWithTag("focus_active").assertIsDisplayed()
        assertEquals("same session, no new one", id, c.engine.state.value.active?.id)

        rule.onNodeWithTag("pause_resume_button").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Paused", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("pause_resume_button").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Playing", substring = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)
        rule.onNodeWithTag("stop_button").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("feedback_banner").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Session stopped").assertIsDisplayed()

        rule.waitUntil(3_000) { runBlocking { c.database.sessionDao().all() }.any { it.id == id && it.state == "finished" } }
        val rows = runBlocking { c.database.sessionDao().all() }
        assertEquals(1, rows.size)
        assertEquals("stopped", rows.single().endReason)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTag(tag: String) =
        onAllNodes(hasTestTag(tag))

    // ---------------------------------------------------------------- theme

    private fun Bitmap.hasPixelNear(color: Int, tolerance: Int = 6): Boolean {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        return pixels.any { p ->
            abs(((p shr 16) and 0xFF) - ((color shr 16) and 0xFF)) <= tolerance &&
                abs(((p shr 8) and 0xFF) - ((color shr 8) and 0xFF)) <= tolerance &&
                abs((p and 0xFF) - (color and 0xFF)) <= tolerance
        }
    }

    @Test
    fun accentColorPropagatesAcrossScreens() {
        val coral = 0xFFFF7A6B.toInt()
        onMain { c.settings.update { it.copy(accentArgb = coral) } }
        rule.waitForIdle()
        val tokens = FocusColors.from(coral)
        val accent = tokens.accent.toArgb()
        val container = tokens.accentContainer.toArgb()

        val start = rule.onNodeWithTag("start_button").captureToImage().asAndroidBitmap()
        assertTrue("Start button uses the accent", start.hasPixelNear(accent))
        assertTrue("selected nav item uses the accent container", rule.onNodeWithTag("bottom_nav").captureToImage().asAndroidBitmap().hasPixelNear(container))
        assertTrue("selected sound card uses the accent", rule.onNodeWithTag("sound_brown_noise").captureToImage().asAndroidBitmap().hasPixelNear(accent))

        go("progress")
        assertTrue("selected range uses the accent", rule.onNodeWithTag("range_selector").captureToImage().asAndroidBitmap().hasPixelNear(accent))
        go("settings")
        assertTrue("settings swatch shows the accent", rule.onNodeWithTag("accent_row").captureToImage().asAndroidBitmap().hasPixelNear(coral))
    }

    @Test
    fun colorPickerValidatesHexAndApplies() {
        go("settings")
        rule.onNodeWithTag("accent_row").performClick()
        rule.onNodeWithTag("color_picker").assertIsDisplayed()
        rule.onNodeWithTag("hex_field").performTextReplacement("#12G")
        rule.onNodeWithText("Use 6 hex digits, e.g. #8B8CF8").assertIsDisplayed()
        rule.onNodeWithTag("apply_color").assertIsNotEnabled()
        assertEquals("nothing applied before Apply", AppSettings.DEFAULT_ACCENT, c.settings.current.accentArgb)

        rule.onNodeWithTag("hex_field").performTextReplacement("#2DD4BF")
        rule.onNodeWithTag("apply_color").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(0xFF2DD4BF.toInt(), c.settings.current.accentArgb)

        rule.onNodeWithText("Reset to default").performClick()
        rule.waitForIdle()
        assertEquals(AppSettings.DEFAULT_ACCENT, c.settings.current.accentArgb)
    }

    // ---------------------------------------------------------------- progress

    @Test
    fun emptyProgressShowsHonestEmptyState() {
        go("progress")
        rule.onNodeWithText("Your progress will appear here").assertIsDisplayed()
    }

    @Test
    fun progressReflectsRealDataAndRangeChanges() {
        seed(0, 25, outcome = EndReason.COMPLETED)
        seed(2, 40, sound = "ocean")
        seed(20, 60, sound = "forest")
        go("progress")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("hero_total")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hero_total").assertTextEquals(Durations.format(65 * 60_000L))

        rule.onNodeWithText("30D").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(Durations.format(125 * 60_000L))).fetchSemanticsNodes().isNotEmpty() }

        // Charts render and respond to touch without errors.
        rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag("trend_card"))
        rule.onNode(hasContentDescription("Focus trend", substring = true)).performTouchInput { click(center) }
        rule.onNode(hasContentDescription("Focus trend", substring = true)).performTouchInput { swipeLeft() }
        rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag("activities_card"))
        rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag("calendar_card"))
        rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag("sounds_card"))
        rule.onNodeWithText("Ocean").assertIsDisplayed()
        rule.onNodeWithTag("progress_list").performScrollToNode(hasTestTag("details_card"))
        rule.onNodeWithText("More breakdowns").performClick()
        rule.onNodeWithTag("progress_list").performScrollToNode(hasText("Session outcomes"))
    }

    // ---------------------------------------------------------------- history

    @Test
    fun historyFiltersBySound() {
        seed(0, 10, sound = "ocean")
        seed(1, 15, sound = "forest")
        go("history")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(" Forest ·", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Any sound").performClick()
        rule.onNodeWithText("Ocean").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(" Forest ·", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertTrue(rule.onAllNodes(hasText(" Ocean ·", substring = true)).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("1 matching session", substring = true).assertIsDisplayed()
    }
}
