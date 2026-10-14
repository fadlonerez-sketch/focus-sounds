package com.focussounds.data

import android.content.Context
import com.focussounds.core.FocusSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MotionLevel(val label: String) { FULL("Full"), REDUCED("Reduced"), OFF("Off") }

/** Every user-facing preference. Each field is read by real behavior somewhere in the app. */
data class AppSettings(
    val accentArgb: Int = DEFAULT_ACCENT,
    val motion: MotionLevel = MotionLevel.FULL,
    val haptics: Boolean = true,
    /** 0 = no goal. */
    val dailyGoalMinutes: Int = 0,
    /** 0 = no goal. */
    val weeklyGoalMinutes: Int = 0,
    val goalCelebrations: Boolean = true,
    /** When true the Focus screen opens with the last used activity/sound/timer; else with the defaults below. */
    val rememberLastSelection: Boolean = true,
    /** 0 = no timer. */
    val defaultTimerMinutes: Int = 25,
    val defaultSoundId: String = FocusSound.BROWN_NOISE.id,
    /** -1 = first activity. */
    val defaultActivityId: Long = -1,
    /** 0 = start immediately. */
    val countdownSeconds: Int = 0,
    val completionChime: Boolean = true,
    val completionVibration: Boolean = true,
) {
    companion object {
        const val DEFAULT_ACCENT = 0xFF8B8CF8.toInt()
        val COUNTDOWN_CHOICES = listOf(0, 3, 5, 10)
    }
}

/**
 * Observable settings backed by SharedPreferences (the app's existing preference store; no new
 * storage framework). Writes are applied immediately in memory, so a new accent color reaches
 * every screen at once, and persisted asynchronously.
 */
class SettingsRepository(context: Context, fileName: String) {
    private val sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = sanitize(transform(_settings.value))
        if (next == _settings.value) return
        write(next)
        _settings.value = next
    }

    fun resetAccent() = update { it.copy(accentArgb = AppSettings.DEFAULT_ACCENT) }

    private fun sanitize(s: AppSettings) = s.copy(
        accentArgb = s.accentArgb or 0xFF000000.toInt(),
        dailyGoalMinutes = s.dailyGoalMinutes.coerceIn(0, 24 * 60),
        weeklyGoalMinutes = s.weeklyGoalMinutes.coerceIn(0, 7 * 24 * 60),
        defaultTimerMinutes = s.defaultTimerMinutes.coerceIn(0, 600),
        defaultSoundId = FocusSound.fromId(s.defaultSoundId)?.id ?: FocusSound.BROWN_NOISE.id,
        countdownSeconds = s.countdownSeconds.takeIf { it in AppSettings.COUNTDOWN_CHOICES } ?: 0,
    )

    private fun read(): AppSettings {
        val d = AppSettings()
        return sanitize(
            AppSettings(
                accentArgb = sp.getInt(K_ACCENT, d.accentArgb),
                motion = runCatching { MotionLevel.valueOf(sp.getString(K_MOTION, d.motion.name)!!) }.getOrDefault(d.motion),
                haptics = sp.getBoolean(K_HAPTICS, d.haptics),
                dailyGoalMinutes = sp.getInt(K_DAILY_GOAL, d.dailyGoalMinutes),
                weeklyGoalMinutes = sp.getInt(K_WEEKLY_GOAL, d.weeklyGoalMinutes),
                goalCelebrations = sp.getBoolean(K_CELEBRATE, d.goalCelebrations),
                rememberLastSelection = sp.getBoolean(K_REMEMBER, d.rememberLastSelection),
                defaultTimerMinutes = sp.getInt(K_DEF_TIMER, d.defaultTimerMinutes),
                defaultSoundId = sp.getString(K_DEF_SOUND, d.defaultSoundId)!!,
                defaultActivityId = sp.getLong(K_DEF_ACTIVITY, d.defaultActivityId),
                countdownSeconds = sp.getInt(K_COUNTDOWN, d.countdownSeconds),
                completionChime = sp.getBoolean(K_CHIME, d.completionChime),
                completionVibration = sp.getBoolean(K_VIBRATE, d.completionVibration),
            ),
        )
    }

    private fun write(s: AppSettings) {
        sp.edit()
            .putInt(K_ACCENT, s.accentArgb)
            .putString(K_MOTION, s.motion.name)
            .putBoolean(K_HAPTICS, s.haptics)
            .putInt(K_DAILY_GOAL, s.dailyGoalMinutes)
            .putInt(K_WEEKLY_GOAL, s.weeklyGoalMinutes)
            .putBoolean(K_CELEBRATE, s.goalCelebrations)
            .putBoolean(K_REMEMBER, s.rememberLastSelection)
            .putInt(K_DEF_TIMER, s.defaultTimerMinutes)
            .putString(K_DEF_SOUND, s.defaultSoundId)
            .putLong(K_DEF_ACTIVITY, s.defaultActivityId)
            .putInt(K_COUNTDOWN, s.countdownSeconds)
            .putBoolean(K_CHIME, s.completionChime)
            .putBoolean(K_VIBRATE, s.completionVibration)
            .apply()
    }

    private companion object {
        const val K_ACCENT = "s_accent"
        const val K_MOTION = "s_motion"
        const val K_HAPTICS = "s_haptics"
        const val K_DAILY_GOAL = "s_daily_goal_min"
        const val K_WEEKLY_GOAL = "s_weekly_goal_min"
        const val K_CELEBRATE = "s_celebrate"
        const val K_REMEMBER = "s_remember_last"
        const val K_DEF_TIMER = "s_default_timer_min"
        const val K_DEF_SOUND = "s_default_sound"
        const val K_DEF_ACTIVITY = "s_default_activity"
        const val K_COUNTDOWN = "s_countdown_s"
        const val K_CHIME = "s_completion_chime"
        const val K_VIBRATE = "s_completion_vibrate"
    }
}
