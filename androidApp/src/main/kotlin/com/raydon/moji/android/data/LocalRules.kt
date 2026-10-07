package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Local interaction rules. Backup serialization is deliberately unchanged. */
object LocalRules {
    // Zero has the same meaning as iOS BackupRetention.forever.
    fun backupRetentionDays(value: Int): Int = if (value == 0) 0 else value.coerceIn(7, 3650)

    fun occursOn(event: com.raydon.moji.core.CountdownEvent, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
        val next = com.raydon.moji.core.CountdownPolicy.nextOccurrenceDate(
            kotlinx.datetime.LocalDate.parse(origin.toString()), event.repeatRule ?: "never", kotlinx.datetime.LocalDate.parse(date.toString()))
        return next.toString() == date.toString()
    }

    fun streak(plans: List<CheckInItem>, from: LocalDate, until: LocalDate, current: Boolean): Int {
        val days = plans.filter { it.status == "completed" }.map { Instant.parse(it.completedAt ?: it.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
        if (current) {
            var day = LocalDate.now()
            if (day !in days) day = day.minusDays(1)
            var count = 0
            while (day in days) { count++; day = day.minusDays(1) }
            return count
        }
        var run = 0; var best = 0; var day = from
        while (day < until) { run = if (day in days) run + 1 else 0; best = maxOf(best, run); day = day.plusDays(1) }
        return best
    }
    fun successor(item: CheckInItem, now: Instant, zone: ZoneId = ZoneId.systemDefault()): CheckInItem? {
        val rule = item.repeatRule ?: "never"
        if (rule == "never" || item.kind != "planned") return null
        val source = Instant.parse(item.scheduledStart).atZone(zone)
        val after = maxOf(source.toLocalDate(), now.atZone(zone).toLocalDate())
        val selected = item.repeatWeekdays.orEmpty().filter { it in 1..7 }.map { if (it == 1) 7 else it - 1 }.toSet()
        if (rule == "customWeekdays" && selected.isEmpty()) return null
        var candidate = source
        var step = 0L
        do {
            step++
            candidate = when (rule) {
                "daily", "weekdays", "customWeekdays" -> source.plusDays(step)
                "weekly" -> source.plusWeeks(step)
                "monthly" -> source.plusMonths(step)
                else -> return null
            }
        } while (candidate.toLocalDate() <= after ||
            (rule == "weekdays" && candidate.dayOfWeek.value > 5) ||
            (rule == "customWeekdays" && candidate.dayOfWeek.value !in selected))
        return item.copy(id = UUID.randomUUID().toString(), scheduledStart = candidate.toInstant().toString(),
            status = "planned", completedAt = null, actualStartDate = null, actualEndDate = null,
            createdAt = now.toString(), seriesID = item.seriesID ?: item.id,
            generatedFromOccurrenceID = item.id, calendarEventIdentifier = null)
    }

    fun appendSuccessor(state: PlanSnapshot, item: CheckInItem, now: Instant): PlanSnapshot {
        if (state.checkInItems.any { it.generatedFromOccurrenceID == item.id }) return state
        val next = successor(item, now) ?: return state
        return state.copy(checkInItems = state.checkInItems + next)
    }

    fun minutes(records: List<TimeRecord>, from: LocalDate, until: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (until > from.plusDays(1)) {
            var day = from; var total = 0L
            while (day < until) { total += minutes(records, day, day.plusDays(1), zone); day = day.plusDays(1) }
            return total
        }
        val lower = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val upper = until.atStartOfDay(zone).toInstant().toEpochMilli()
        val overlapMillis = records.sumOf { record ->
            if (record.scheduleKind != null && record.scheduleKind != "exactTime") 0L
            else runCatching {
                val overlap = (minOf(Instant.parse(record.endDate).toEpochMilli(), upper) -
                    maxOf(Instant.parse(record.startDate).toEpochMilli(), lower)).coerceAtLeast(0)
                overlap
            }.getOrDefault(0L)
        }
        return (overlapMillis + 59_999) / 60_000
    }
}
