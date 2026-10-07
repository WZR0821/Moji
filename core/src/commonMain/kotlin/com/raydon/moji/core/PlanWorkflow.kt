package com.raydon.moji.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class PlanWorkflowUndo(val before: List<CheckInItem>, val after: List<CheckInItem>) {
    fun apply(state: PlanSnapshot, blockedId: String? = null): PlanSnapshot = state.copy(checkInItems = state.checkInItems.map { current ->
        val previous = before.firstOrNull { it.id == current.id }
        if (previous != null && after.firstOrNull { it.id == current.id } == current &&
            state.activeSession?.checkInItemID != current.id && current.id != blockedId) previous else current
    })
}

object PlanWorkflow {
    fun weekStart(date: LocalDate): LocalDate = date.plus(-(date.dayOfWeek.ordinal), DateTimeUnit.DAY)

    fun convertMemo(state: PlanSnapshot, memoId: String, itemId: String?, newId: String,
                    scheduledStart: String, now: String, category: String): PlanSnapshot {
        if (state.checkInItems.any { it.sourceMemoID == memoId && it.sourceMemoChecklistItemID == itemId }) return state
        val memo = state.memos.firstOrNull { it.id == memoId } ?: return state
        val body = if (memo.mode == "checklist") memo.checklistItems.joinToString("\n") {
            "${if (it.isCompleted) "☑" else "☐"} ${it.text}"
        } else memo.content
        val title = if (itemId != null) memo.checklistItems.firstOrNull { it.id == itemId }?.text?.trim().orEmpty()
            else memo.title.trim().ifEmpty { if (memo.mode == "checklist") memo.checklistItems.firstOrNull { it.text.isNotBlank() }?.text.orEmpty() else memo.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.trim()
        if (title.isBlank()) return state
        return state.copy(checkInItems = state.checkInItems + CheckInItem(id = newId, title = title,
            category = category, scheduledStart = scheduledStart, createdAt = now, note = body,
            scheduleKind = "allDay", plannedDurationEnabled = false,
            sourceMemoID = memoId, sourceMemoChecklistItemID = itemId))
    }

    fun copied(source: CheckInItem, id: String, date: String, now: String): CheckInItem = CheckInItem(
        id = id, title = source.title, category = source.category, scheduledStart = date, createdAt = now,
        note = source.note, scheduleKind = source.scheduleKind, detailsConfigured = true,
        plannedMinutes = source.plannedMinutes, plannedDurationEnabled = source.plannedDurationEnabled
            ?: ((source.scheduleKind ?: "exactTime") == "exactTime" && source.detailsConfigured != false),
        reminderMinutesBefore = source.reminderMinutesBefore)

    fun batch(state: PlanSnapshot, ids: Set<String>, zoneId: String, day: LocalDate? = null,
              category: String? = null, archived: Boolean? = null, nextWeekOf: LocalDate? = null,
              blockedId: String? = null): Pair<PlanSnapshot, PlanWorkflowUndo> {
        val zone = TimeZone.of(zoneId)
        val before = mutableListOf<CheckInItem>(); val after = mutableListOf<CheckInItem>()
        val plans = state.checkInItems.map { original ->
            if (original.id !in ids || original.kind != "planned" || original.status == "inProgress" ||
                original.id == state.activeSession?.checkInItemID || original.id == blockedId ||
                ((day != null || nextWeekOf != null) && original.status != "planned")) return@map original
            val oldDate = Instant.parse(original.scheduledStart).toLocalDateTime(zone)
            val targetDay = if (nextWeekOf != null) weekStart(nextWeekOf).plus(7 + oldDate.dayOfWeek.ordinal, DateTimeUnit.DAY) else day
            val changed = original.copy(
                scheduledStart = targetDay?.let { LocalDateTime(it, oldDate.time).toInstant(zone).toString() } ?: original.scheduledStart,
                category = category ?: original.category, isArchived = archived ?: original.isArchived)
            before += original; after += changed; changed
        }
        return state.copy(checkInItems = plans) to PlanWorkflowUndo(before, after)
    }

    fun summary(state: PlanSnapshot, start: String, end: String, category: String? = null): WorkflowWeekSummary {
        val begin = Instant.parse(start); val finish = Instant.parse(end)
        val plans = state.checkInItems.filter { it.kind == "planned" && (category == null || it.category == category) }
        val scheduled = plans.filter { Instant.parse(it.scheduledStart) >= begin && Instant.parse(it.scheduledStart) < finish }
        val completed = plans.filter { it.status == "completed" && Instant.parse(it.completedAt ?: it.actualEndDate ?: it.scheduledStart).let { date -> date >= begin && date < finish } }
        val records = state.records.filter { (it.scheduleKind ?: "exactTime") == "exactTime" && (category == null || it.category == category) }
        val seconds = records.sumOf { maxOf(0L, minOf(finish.epochSeconds, Instant.parse(it.endDate).epochSeconds) - maxOf(begin.epochSeconds, Instant.parse(it.startDate).epochSeconds)) }
        return WorkflowWeekSummary(scheduled, plans.filter { Instant.parse(it.scheduledStart) < begin && it.status == "planned" && it.isArchived != true },
            completed, scheduled.filter { it.plannedDurationEnabled ?: ((it.scheduleKind ?: "exactTime") == "exactTime" && it.detailsConfigured != false) }.sumOf { it.plannedMinutes },
            if (seconds > 0) ((seconds + 59) / 60).toInt() else 0, completed.count { plan -> records.none { it.checkInItemID == plan.id } })
    }
}

data class WorkflowWeekSummary(val scheduled: List<CheckInItem>, val carryover: List<CheckInItem>,
    val completed: List<CheckInItem>, val estimatedMinutes: Int, val actualMinutes: Int, val untrackedCount: Int) {
    val pending get() = scheduled.filter { it.status == "planned" && it.isArchived != true }
}
