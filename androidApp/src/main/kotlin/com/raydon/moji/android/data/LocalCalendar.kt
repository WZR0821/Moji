package com.raydon.moji.android.data

import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.TimeRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LocalCalendarEntry(
    val id: String,
    val title: String,
    val category: String,
    val date: Instant,
    val schedule: String,
    val status: String,
    val plan: CheckInItem? = null,
    val record: TimeRecord? = null,
    val countdown: CountdownEvent? = null,
)

/** Same completion/record pairing as iOS PlanCalendarData, without a second row
 * for the linked completion. Untimed records still belong to their start day. */
class LocalCalendar(snapshot: PlanSnapshot) {
    private val consumed = mutableSetOf<String>()
    private val linked = snapshot.records.groupBy { it.checkInItemID }
    private val entries = buildList {
        snapshot.checkInItems.filter { it.kind == "planned" }.forEach { plan ->
            val candidates = linked[plan.id].orEmpty()
            val record = if (plan.status == "completed") {
                candidates.firstOrNull { plan.actualStartDate != null && plan.actualEndDate != null &&
                    kotlin.math.abs(Instant.parse(it.startDate).toEpochMilli() - Instant.parse(plan.actualStartDate).toEpochMilli()) < 500 &&
                    kotlin.math.abs(Instant.parse(it.endDate).toEpochMilli() - Instant.parse(plan.actualEndDate).toEpochMilli()) < 500 }
                    ?: candidates.maxByOrNull { Instant.parse(it.startDate) }
            } else null
            record?.let { consumed.add(it.id) }
            add(LocalCalendarEntry("plan-${plan.id}", plan.title, plan.category,
                Instant.parse(record?.startDate ?: plan.scheduledStart), record?.scheduleKind ?: plan.scheduleKind ?: "exactTime",
                plan.status, plan = plan, record = record))
        }
        snapshot.records.filter { it.id !in consumed }.forEach { record ->
            add(LocalCalendarEntry("record-${record.id}", record.title, record.category,
                Instant.parse(record.startDate), record.scheduleKind ?: "exactTime", "completed", record = record))
        }
    }
    private val countdowns = snapshot.countdowns
    private val entriesByZone = mutableMapOf<ZoneId, Map<LocalDate, List<LocalCalendarEntry>>>()

    fun entriesOn(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<LocalCalendarEntry> {
        val events = countdowns.filter { LocalRules.occursOn(it, day, zone) }.map { event ->
            LocalCalendarEntry("countdown-${event.id}-$day", event.title, "_customSummary",
                day.atStartOfDay(zone).toInstant(), "allDay", "planned", countdown = event)
        }
        val buckets = entriesByZone.getOrPut(zone) { entries.groupBy { it.date.atZone(zone).toLocalDate() } }
        return (buckets[day].orEmpty() + events)
            .sortedWith(compareBy<LocalCalendarEntry> { it.date }.thenBy { if (it.countdown != null) 0 else 1 }
                .thenBy { listOf("allDay", "morning", "afternoon", "evening", "exactTime").indexOf(it.schedule) }.thenBy { it.title })
    }
}
