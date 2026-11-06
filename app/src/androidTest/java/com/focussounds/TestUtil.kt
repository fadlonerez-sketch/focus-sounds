package com.focussounds

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/** Runs [block] on the main thread (where the player and engine live) and returns its result. */
fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

/**
 * Presses BACK as a user would, but only once a window of this app has input focus.
 *
 * An injected key goes to whichever window Android has focused, not to the app under test. On a slow
 * (e.g. headless, CPU-starved) emulator another app's system "Application Not Responding" dialog —
 * typically Digital Wellbeing — can take focus and keep it across test runs, silently swallowing the
 * key. Such ANR/crash dialogs belong to other apps, so they are closed, and the key is sent only when
 * this app's window (activity, dialog or bottom sheet) is focused.
 */
fun pressBackInApp(timeoutMs: Long = 10_000) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ours = "${instrumentation.targetContext.packageName}/"
    val end = SystemClock.elapsedRealtime() + timeoutMs
    while (true) {
        val focus = currentFocusedWindow()
        if (focus == null || ours in focus) break // null: this dumpsys format is unknown, so it cannot be checked
        if ("Application Not Responding" in focus || "Application Error" in focus) {
            shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        }
        if (SystemClock.elapsedRealtime() > end) fail("BACK not sent: input focus stayed on another window ($focus)")
        Thread.sleep(100)
    }
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}

/** The window that receives key events, e.g. "mCurrentFocus=Window{… com.focussounds/…MainActivity}". */
private fun currentFocusedWindow(): String? =
    shell("dumpsys window displays").lineSequence().firstOrNull { "mCurrentFocus=" in it }?.trim()

private fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
    val end = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < end) {
        if (onMain(condition)) return
        Thread.sleep(20)
    }
    fail("Timed out after $timeoutMs ms waiting for: $what")
}
