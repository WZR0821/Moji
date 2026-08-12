package com.raydon.moji.android.data

import android.content.Context
import com.raydon.moji.core.PlanPreferencesArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PomodoroState(
    val phase: String = "focus",
    val running: Boolean = false,
    val remainingSeconds: Int = 1500,
    val targetEpochMillis: Long = 0,
    val accumulatedSeconds: Int = 0,
    val completedFocusCount: Int = 0,
    val title: String = "番茄专注",
    val category: String = "study",
    val linkedPlanId: String = "",
)

class AndroidSettings(context: Context) {
    private val prefs = context.getSharedPreferences("moji.settings", Context.MODE_PRIVATE)
    private val _pomodoro = MutableStateFlow(readPomodoro())
    val pomodoro: StateFlow<PomodoroState> = _pomodoro

    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(value) { prefs.edit().putString("appearance", value).apply() }
    var carriesOver: Boolean
        get() = prefs.getBoolean("carryOver", true)
        set(value) { prefs.edit().putBoolean("carryOver", value).apply() }
    var focusMinutes: Int
        get() = prefs.getInt("focusMinutes", 25)
        set(value) { prefs.edit().putInt("focusMinutes", value.coerceIn(1, 180)).apply() }
    var shortBreakMinutes: Int
        get() = prefs.getInt("shortBreakMinutes", 5)
        set(value) { prefs.edit().putInt("shortBreakMinutes", value.coerceIn(1, 60)).apply() }
    var longBreakMinutes: Int
        get() = prefs.getInt("longBreakMinutes", 15)
        set(value) { prefs.edit().putInt("longBreakMinutes", value.coerceIn(1, 90)).apply() }
    var longBreakInterval: Int
        get() = prefs.getInt("longBreakInterval", 4)
        set(value) { prefs.edit().putInt("longBreakInterval", value.coerceIn(1, 12)).apply() }
    var longBreakEnabled: Boolean
        get() = prefs.getBoolean("longBreakEnabled", true)
        set(value) { prefs.edit().putBoolean("longBreakEnabled", value).apply() }
    var autoStartBreaks: Boolean
        get() = prefs.getBoolean("autoStartBreaks", false)
        set(value) { prefs.edit().putBoolean("autoStartBreaks", value).apply() }
    var autoStartFocus: Boolean
        get() = prefs.getBoolean("autoStartFocus", false)
        set(value) { prefs.edit().putBoolean("autoStartFocus", value).apply() }
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notificationsEnabled", true)
        set(value) { prefs.edit().putBoolean("notificationsEnabled", value).apply() }
    var soundEnabled: Boolean
        get() = prefs.getBoolean("soundEnabled", true)
        set(value) { prefs.edit().putBoolean("soundEnabled", value).apply() }
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("hapticsEnabled", true)
        set(value) { prefs.edit().putBoolean("hapticsEnabled", value).apply() }
    var keepScreenAwake: Boolean
        get() = prefs.getBoolean("keepScreenAwake", false)
        set(value) { prefs.edit().putBoolean("keepScreenAwake", value).apply() }
    var backupTreeUri: String?
        get() = prefs.getString("backupTreeUri", null)
        set(value) { prefs.edit().putString("backupTreeUri", value).apply() }
    var backupRetentionDays: Int
        get() = prefs.getInt("backupRetentionDays", 180)
        set(value) { prefs.edit().putInt("backupRetentionDays", value.coerceIn(7, 3650)).apply() }

    fun updatePomodoro(state: PomodoroState) {
        prefs.edit()
            .putString("pomodoro.phase", state.phase)
            .putBoolean("pomodoro.running", state.running)
            .putInt("pomodoro.remaining", state.remainingSeconds)
            .putLong("pomodoro.target", state.targetEpochMillis)
            .putInt("pomodoro.accumulated", state.accumulatedSeconds)
            .putInt("pomodoro.completed", state.completedFocusCount)
            .putString("pomodoro.title", state.title)
            .putString("pomodoro.category", state.category)
            .putString("pomodoro.linkedPlanId", state.linkedPlanId)
            .apply()
        _pomodoro.value = state
    }

    fun toArchive(): PlanPreferencesArchive = PlanPreferencesArchive(
        focusMinutes = focusMinutes,
        shortBreakMinutes = shortBreakMinutes,
        longBreakMinutes = longBreakMinutes,
        longBreakInterval = longBreakInterval,
        longBreakEnabled = longBreakEnabled,
        pomodoroPhase = pomodoro.value.phase,
        pomodoroRunning = pomodoro.value.running,
        pomodoroRemaining = pomodoro.value.remainingSeconds,
        pomodoroTarget = pomodoro.value.targetEpochMillis / 1000.0,
        pomodoroAccumulated = pomodoro.value.accumulatedSeconds,
        pomodoroCompleted = pomodoro.value.completedFocusCount,
        pomodoroTitle = pomodoro.value.title,
        pomodoroCategory = pomodoro.value.category,
        linkedPlanID = pomodoro.value.linkedPlanId,
        pomodoroPhaseDuration = durationFor(pomodoro.value.phase),
        planNotificationsEnabled = notificationsEnabled,
        notificationSoundEnabled = soundEnabled,
        hapticsEnabled = hapticsEnabled,
        liveActivitiesEnabled = true,
        autoStartBreaks = autoStartBreaks,
        autoStartFocus = autoStartFocus,
        keepScreenAwake = keepScreenAwake,
        appearanceMode = appearance,
        backupRetentionDays = backupRetentionDays,
        backupFolderName = backupTreeUri?.substringAfterLast('/'),
    )

    fun restore(archive: PlanPreferencesArchive) {
        focusMinutes = archive.focusMinutes
        shortBreakMinutes = archive.shortBreakMinutes
        longBreakMinutes = archive.longBreakMinutes
        longBreakInterval = archive.longBreakInterval
        longBreakEnabled = archive.longBreakEnabled ?: true
        notificationsEnabled = archive.planNotificationsEnabled ?: true
        soundEnabled = archive.notificationSoundEnabled ?: true
        hapticsEnabled = archive.hapticsEnabled ?: true
        autoStartBreaks = archive.autoStartBreaks ?: false
        autoStartFocus = archive.autoStartFocus ?: false
        keepScreenAwake = archive.keepScreenAwake ?: false
        appearance = archive.appearanceMode ?: "system"
        backupRetentionDays = archive.backupRetentionDays ?: 180
        updatePomodoro(
            PomodoroState(
                phase = archive.pomodoroPhase,
                running = archive.pomodoroRunning,
                remainingSeconds = archive.pomodoroRemaining,
                targetEpochMillis = (archive.pomodoroTarget * 1000).toLong(),
                accumulatedSeconds = archive.pomodoroAccumulated,
                completedFocusCount = archive.pomodoroCompleted,
                title = archive.pomodoroTitle,
                category = archive.pomodoroCategory,
                linkedPlanId = archive.linkedPlanID,
            )
        )
    }

    fun durationFor(phase: String): Int = when (phase) {
        "shortBreak" -> shortBreakMinutes * 60
        "longBreak" -> longBreakMinutes * 60
        else -> focusMinutes * 60
    }

    private fun readPomodoro(): PomodoroState = PomodoroState(
        phase = prefs.getString("pomodoro.phase", "focus") ?: "focus",
        running = prefs.getBoolean("pomodoro.running", false),
        remainingSeconds = prefs.getInt("pomodoro.remaining", focusMinutes * 60),
        targetEpochMillis = prefs.getLong("pomodoro.target", 0),
        accumulatedSeconds = prefs.getInt("pomodoro.accumulated", 0),
        completedFocusCount = prefs.getInt("pomodoro.completed", 0),
        title = prefs.getString("pomodoro.title", "番茄专注") ?: "番茄专注",
        category = prefs.getString("pomodoro.category", "study") ?: "study",
        linkedPlanId = prefs.getString("pomodoro.linkedPlanId", "") ?: "",
    )
}
