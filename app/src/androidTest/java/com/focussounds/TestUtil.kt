package com.focussounds

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/** Runs [block] on the main thread (where the player and engine live) and returns its result. */
fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
    val end = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < end) {
        if (onMain(condition)) return
        Thread.sleep(20)
    }
    fail("Timed out after $timeoutMs ms waiting for: $what")
}
