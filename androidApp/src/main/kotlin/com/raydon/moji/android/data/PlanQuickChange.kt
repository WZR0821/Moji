package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Patch only the edited fields on the latest stored plan, retaining live state. */
sealed interface PlanQuickChange {
    data class Content(val title: String, val note: String) : PlanQuickChange
    data class Date(val day: LocalDate) : PlanQuickChange
    data class Schedule(val kind: String, val time: LocalTime, val estimatedMinutes: Int?) : PlanQuickChange
    data class Category(val value: String) : PlanQuickChange
    data class Recurrence(val rule: String, val weekdays: Set<Int>) : PlanQuickChange
    data class Reminder(val minutes: Int?) : PlanQuickChange

    fun applyTo(original: CheckInItem, zone: ZoneId = ZoneId.systemDefault()): CheckInItem? {
        if (original.kind != "planned") return null
        val changed = when (this) {
            is Content -> if (title.isBlank()) return null else original.copy(title = title.trim(), note = note.trim())
            is Date -> {
                val time = Instant.parse(original.scheduledStart).atZone(zone).toLocalTime().withSecond(0).withNano(0)
                original.copy(scheduledStart = day.atTime(time).atZone(zone).toInstant().toString())
            }
            is Schedule -> {
                if (kind !in listOf("allDay", "morning", "afternoon", "evening", "exactTime")) return null
                val day = Instant.parse(original.scheduledStart).atZone(zone).toLocalDate()
                val start = if (kind == "exactTime") time.withSecond(0).withNano(0) else LocalTime.of(when (kind) {
                    "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0
                }, 0)
                original.copy(scheduleKind = kind, scheduledStart = day.atTime(start).atZone(zone).toInstant().toString(),
                    plannedDurationEnabled = estimatedMinutes != null,
                    plannedMinutes = estimatedMinutes?.coerceIn(1, 1440) ?: original.plannedMinutes)
            }
            is Category -> {
                if (value !in listOf("study", "work") && !(value.startsWith("custom:") && value.removePrefix("custom:").isNotBlank())) return null
                original.copy(category = value)
            }
            is Recurrence -> {
                val valid = weekdays.filter { it in 1..7 }.sorted()
                if (rule !in listOf("never", "daily", "weekdays", "weekly", "monthly", "customWeekdays") || (rule == "customWeekdays" && valid.isEmpty())) return null
                original.copy(repeatRule = rule, repeatWeekdays = if (rule == "customWeekdays") valid else emptyList())
            }
            is Reminder -> original.copy(reminderMinutesBefore = minutes?.coerceIn(0, 10080))
        }
        return changed.copy(detailsConfigured = true, plannedDurationEnabled = if (this is Schedule) changed.plannedDurationEnabled else
            original.plannedDurationEnabled ?: (original.detailsConfigured != false && (original.scheduleKind ?: "exactTime") == "exactTime"))
    }
}
