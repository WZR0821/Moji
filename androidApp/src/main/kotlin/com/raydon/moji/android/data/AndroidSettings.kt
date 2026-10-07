package com.raydon.moji.android.data

import android.content.Context
import android.content.SharedPreferences
import com.raydon.moji.core.PlanPreferencesArchive
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.QuickPlanPresetArchive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    val phaseDurationSeconds: Int = 1500,
    val durationIsPlanOwned: Boolean = false,
    val pendingNextPhase: String? = null,
    val completedPhase: String? = null,
)

class AndroidSettings(context: Context) {
    private val prefs = context.getSharedPreferences("moji.settings", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0L)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision.value++ }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    var customCategories: List<String>
        get() = prefs.getStringSet("customCategories.local", emptySet()).orEmpty().sorted()
        set(value) { prefs.edit().putStringSet("customCategories.local", value.toSet()).apply() }
    private val _pomodoro = MutableStateFlow(readPomodoro())
    val pomodoro: StateFlow<PomodoroState> = _pomodoro

    var appearance: String
        get() = prefs.getString("appearance", "system") ?: "system"
        set(value) { prefs.edit().putString("appearance", value).apply() }
    var carriesOver: Boolean
        get() = prefs.getBoolean("carryOver", true)
        set(value) { prefs.edit().putBoolean("carryOver", value).apply() }
    fun countdownSort(scope: String): String = prefs.getString("countdown.sort.$scope", "manual") ?: "manual"
    fun setCountdownSort(scope: String, value: String) { prefs.edit().putString("countdown.sort.$scope", value).apply() }
    var defaultCategory: String
        get() = prefs.getString("defaultCategory", "study") ?: "study"
        set(value) { prefs.edit().putString("defaultCategory", value).apply() }
    var defaultScheduleKind: String
        get() = prefs.getString("defaultScheduleKind", "allDay") ?: "allDay"
        set(value) { prefs.edit().putString("defaultScheduleKind", value).apply() }
    var defaultPlannedDurationEnabled: Boolean
        get() = prefs.getBoolean("defaultPlannedDurationEnabled", false)
        set(value) { prefs.edit().putBoolean("defaultPlannedDurationEnabled", value).apply() }
    var defaultPlannedMinutes: Int
        get() = prefs.getInt("defaultPlannedMinutes", 25)
        set(value) { prefs.edit().putInt("defaultPlannedMinutes", value.coerceIn(5, 480)).apply() }
    var allDayReminderHour: Int
        get() = prefs.getInt("allDayReminderHour", 9)
        set(value) { prefs.edit().putInt("allDayReminderHour", value.coerceIn(0, 23)).apply() }
    var paperTextureEnabled: Boolean
        get() = prefs.getBoolean("paperTextureEnabled", true)
        set(value) { prefs.edit().putBoolean("paperTextureEnabled", value).apply() }
    var inkMotionLevel: String
        get() = prefs.getString("inkMotionLevel", "full") ?: "full"
        set(value) { prefs.edit().putString("inkMotionLevel", value).apply() }
    var focusMinutes: Int
        get() = prefs.getInt("focusMinutes", 25)
        set(value) { prefs.edit().putInt("focusMinutes", value.coerceIn(1, 120)).apply() }
    var shortBreakMinutes: Int
        get() = prefs.getInt("shortBreakMinutes", 5)
        set(value) { prefs.edit().putInt("shortBreakMinutes", value.coerceIn(1, 30)).apply() }
    var longBreakMinutes: Int
        get() = prefs.getInt("longBreakMinutes", 15)
        set(value) { prefs.edit().putInt("longBreakMinutes", value.coerceIn(1, 60)).apply() }
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
        set(value) { prefs.edit().putInt("backupRetentionDays", LocalRules.backupRetentionDays(value)).apply() }
    var lastBackupAt: Long
        get() = prefs.getLong("lastBackupAt", 0L)
        set(value) { prefs.edit().putLong("lastBackupAt", value).apply() }

    fun presetButton(index: Int): String = prefs.getString("preset.$index.button", listOf("待办", "学习", "工作").getOrElse(index) { "快速添加" }) ?: "快速添加"
    fun presetTitle(index: Int): String = prefs.getString("preset.$index.title", listOf("新计划", "学习计划", "工作计划").getOrElse(index) { "新计划" }) ?: "新计划"
    fun presetCategory(index: Int): String = prefs.getString("preset.$index.category", listOf("", "study", "work").getOrElse(index) { "" }) ?: ""
    fun setPreset(index: Int, button: String, title: String, category: String) {
        prefs.edit().putString("preset.$index.button", button).putString("preset.$index.title", title).putString("preset.$index.category", category).apply()
    }
    fun resetPresets() {
        prefs.edit().also { editor -> (0..2).forEach { index -> editor.remove("preset.$index.button").remove("preset.$index.title").remove("preset.$index.category") } }.apply()
    }

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
            .putInt("pomodoro.phaseDuration", state.phaseDurationSeconds)
            .putBoolean("pomodoro.durationIsPlanOwned", state.durationIsPlanOwned)
            .putString("pomodoro.pendingNextPhase", state.pendingNextPhase)
            .putString("pomodoro.completedPhase", state.completedPhase)
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
        pomodoroPhaseDuration = pomodoro.value.phaseDurationSeconds,
        pomodoroPhaseDurationIsPlanOwned = pomodoro.value.durationIsPlanOwned,
        planNotificationsEnabled = notificationsEnabled,
        notificationSoundEnabled = soundEnabled,
        hapticsEnabled = hapticsEnabled,
        liveActivitiesEnabled = true,
        autoStartBreaks = autoStartBreaks,
        autoStartFocus = autoStartFocus,
        keepScreenAwake = keepScreenAwake,
        appearanceMode = appearance,
        defaultCategory = defaultCategory,
        customCategories = MojiJson.codec.encodeToString(customCategories),
        quickPlanPresets = MojiJson.codec.encodeToString((0..2).map { index ->
            QuickPlanPresetArchive("android-$index", presetButton(index), presetTitle(index), presetCategory(index).takeIf { it.isNotBlank() })
        }),
        defaultScheduleKind = defaultScheduleKind,
        defaultPlannedDurationEnabled = defaultPlannedDurationEnabled,
        defaultPlannedMinutes = defaultPlannedMinutes,
        allDayReminderHour = allDayReminderHour,
        inkMotionLevel = inkMotionLevel,
        paperTextureEnabled = paperTextureEnabled,
        backupRetentionDays = backupRetentionDays,
        backupFolderName = backupTreeUri?.substringAfterLast('/'),
    )

    fun restore(archive: PlanPreferencesArchive) {
        // Parse nested JSON before changing any preference.
        val categories = archive.customCategories?.let { MojiJson.codec.decodeFromString<List<String>>(it) }
        val presets = archive.quickPlanPresets?.let { MojiJson.codec.decodeFromString<List<QuickPlanPresetArchive>>(it) }
        categories?.let { customCategories = it }
        if (presets != null) {
            resetPresets()
            presets.take(3).forEachIndexed { index, preset ->
                setPreset(index, preset.buttonTitle, preset.planTitle, preset.category ?: "")
            }
        }
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
        defaultCategory = archive.defaultCategory ?: "study"
        defaultScheduleKind = archive.defaultScheduleKind ?: "allDay"
        defaultPlannedDurationEnabled = archive.defaultPlannedDurationEnabled ?: false
        defaultPlannedMinutes = archive.defaultPlannedMinutes ?: 25
        allDayReminderHour = archive.allDayReminderHour ?: 9
        inkMotionLevel = archive.inkMotionLevel ?: "full"
        paperTextureEnabled = archive.paperTextureEnabled ?: true
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
                phaseDurationSeconds = archive.pomodoroPhaseDuration ?: durationFor(archive.pomodoroPhase),
                durationIsPlanOwned = archive.pomodoroPhaseDurationIsPlanOwned ?: false,
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
        phaseDurationSeconds = prefs.getInt("pomodoro.phaseDuration", durationFor(prefs.getString("pomodoro.phase", "focus") ?: "focus")),
        durationIsPlanOwned = prefs.getBoolean("pomodoro.durationIsPlanOwned", false),
        pendingNextPhase = prefs.getString("pomodoro.pendingNextPhase", null),
        completedPhase = prefs.getString("pomodoro.completedPhase", null),
    )
}
