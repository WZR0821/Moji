package com.raydon.moji.core

import kotlinx.serialization.Serializable

@Serializable
data class CheckInItem(
    val id: String,
    val title: String,
    val category: String = "study",
    val kind: String = "planned",
    val scheduledStart: String,
    val plannedMinutes: Int = 25,
    val note: String = "",
    val status: String = "planned",
    val actualStartDate: String? = null,
    val actualEndDate: String? = null,
    val completedAt: String? = null,
    val calendarSyncEnabled: Boolean = false,
    val calendarEventIdentifier: String? = null,
    val createdAt: String,
    val scheduleKind: String? = "exactTime",
    val detailsConfigured: Boolean? = true,
    val repeatRule: String? = "never",
    val repeatWeekdays: List<Int>? = emptyList(),
    val seriesID: String? = null,
    val reminderMinutesBefore: Int? = null,
    val generatedFromOccurrenceID: String? = null,
    val plannedDurationEnabled: Boolean? = null,
    val isArchived: Boolean? = false,
    val sourceMemoID: String? = null,
    val sourceMemoChecklistItemID: String? = null,
)

@Serializable
data class TimeRecord(
    val id: String,
    val title: String,
    val category: String = "study",
    val startDate: String,
    val endDate: String,
    val note: String = "",
    val checkInItemID: String? = null,
    val scheduleKind: String? = "exactTime",
)

@Serializable
data class ActiveSession(
    val id: String,
    val title: String,
    val category: String = "study",
    val startedAt: String,
    val checkInItemID: String? = null,
    val plannedDurationMinutes: Int? = null,
)

@Serializable
data class CountdownEvent(
    val id: String,
    val title: String,
    val targetDate: String,
    val symbolName: String = "flag.fill",
    val colorName: String = "ink",
    val isPinned: Boolean = false,
    val createdAt: String,
    val repeatRule: String? = "never",
    val includesToday: Boolean? = false,
    val calendarSyncEnabled: Boolean? = false,
    val calendarEventIdentifier: String? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class MemoChecklistItem(
    val id: String,
    val text: String = "",
    val isCompleted: Boolean = false,
)

@Serializable
data class MemoItem(
    val id: String,
    val title: String = "",
    val content: String = "",
    val mode: String = "note",
    val checklistItems: List<MemoChecklistItem> = emptyList(),
    val isPinned: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PlanSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val records: List<TimeRecord> = emptyList(),
    val checkInItems: List<CheckInItem> = emptyList(),
    val countdowns: List<CountdownEvent> = emptyList(),
    val memos: List<MemoItem> = emptyList(),
    val planTemplates: List<PlanTemplate> = emptyList(),
    val weeklyGoals: List<WeeklyGoal> = emptyList(),
    val weeklyReflections: List<WeeklyReflection> = emptyList(),
    val activeSession: ActiveSession? = null,
    val lastUpdated: String = "1970-01-01T00:00:00Z",
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 13
    }
}

@Serializable
data class PlanTemplate(
    val id: String,
    val title: String,
    val category: String,
    val note: String = "",
    val scheduleKind: String = "allDay",
    val hour: Int = 0,
    val minute: Int = 0,
    val plannedMinutes: Int = 25,
    val plannedDurationEnabled: Boolean = false,
    val reminderMinutesBefore: Int? = null,
    val createdAt: String,
)

@Serializable
data class WeeklyGoal(
    val id: String,
    val weekStart: String,
    val category: String? = null,
    val targetCount: Int? = null,
    val targetMinutes: Int? = null,
    val updatedAt: String,
)

@Serializable
data class WeeklyReflection(
    val id: String,
    val weekStart: String,
    val note: String = "",
    val nextFocus: String = "",
    val updatedAt: String,
)

@Serializable
data class PlanPreferencesArchive(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longBreakInterval: Int = 4,
    val longBreakEnabled: Boolean? = true,
    val pomodoroPhase: String = "focus",
    val pomodoroRunning: Boolean = false,
    val pomodoroRemaining: Int = 1500,
    val pomodoroTarget: Double = 0.0,
    val pomodoroSegmentStart: Double = 0.0,
    val pomodoroAccumulated: Int = 0,
    val pomodoroCompleted: Int = 0,
    val pomodoroTitle: String = "番茄专注",
    val pomodoroCategory: String = "study",
    val linkedPlanID: String = "",
    val pomodoroPhaseDuration: Int? = 1500,
    val pomodoroPhaseDurationIsPlanOwned: Boolean? = false,
    val defaultCategory: String? = "study",
    val customCategories: String? = "[]",
    val quickPlanPresets: String? = null,
    val defaultScheduleKind: String? = "allDay",
    val defaultPlannedDurationEnabled: Boolean? = false,
    val defaultPlannedMinutes: Int? = 25,
    val allDayReminderHour: Int? = 9,
    val planNotificationsEnabled: Boolean? = true,
    val notificationSoundEnabled: Boolean? = true,
    val hapticsEnabled: Boolean? = true,
    val liveActivitiesEnabled: Boolean? = true,
    val autoStartBreaks: Boolean? = false,
    val autoStartFocus: Boolean? = false,
    val keepScreenAwake: Boolean? = false,
    val appearanceMode: String? = "system",
    val inkMotionLevel: String? = "full",
    val paperTextureEnabled: Boolean? = true,
    val backupRetentionDays: Int? = 180,
    val backupFolderName: String? = null,
)

@Serializable
data class PlanBackupArchive(
    val formatVersion: Int = 1,
    val appIdentifier: String = "com.raydon.moji",
    val appVersion: String,
    val exportedAt: String,
    val snapshot: PlanSnapshot,
    val preferences: PlanPreferencesArchive? = null,
)

@Serializable
data class QuickPlanPresetArchive(
    val id: String,
    val buttonTitle: String,
    val planTitle: String,
    val category: String? = null,
)
