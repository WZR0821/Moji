package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raydon.moji.core.*
import java.time.*
import java.time.temporal.TemporalAdjusters

private data class TimelineEntry(val title: String, val start: Int, val end: Int, val record: Boolean, val open: () -> Unit)

@Composable
fun CalendarTimeline(selected: LocalDate, days: Int, snapshot: PlanSnapshot, editPlan: (CheckInItem) -> Unit, editRecord: (TimeRecord) -> Unit) {
    val first = if (days == 7) selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else selected
    val zone = ZoneId.systemDefault()
    val ink = MaterialTheme.colorScheme.onSurface
    val height = 48.dp
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
        Column(Modifier.width(40.dp)) {
            Spacer(Modifier.height(24.dp))
            repeat(24) { hour -> Text("${hour.toString().padStart(2, '0')}:00", Modifier.height(height), fontSize = 10.sp, color = ink.copy(alpha = 0.5f)) }
        }
        repeat(days) { offset ->
            val day = first.plusDays(offset.toLong())
            val lower = day.atStartOfDay(zone).toInstant()
            val upper = day.plusDays(1).atStartOfDay(zone).toInstant()
            val entries = snapshot.checkInItems.filter { it.scheduleKind == "exactTime" && Instant.parse(it.scheduledStart) >= lower && Instant.parse(it.scheduledStart) < upper }.map { plan ->
                val start = Instant.parse(plan.scheduledStart).atZone(zone).toLocalTime().toSecondOfDay() / 60
                TimelineEntry(plan.title, start, (start + if (plan.plannedDurationEnabled == true) plan.plannedMinutes else 25).coerceAtMost(1440), false) { editPlan(plan) }
            } + snapshot.records.filter { (it.scheduleKind == null || it.scheduleKind == "exactTime") && Instant.parse(it.startDate) < upper && Instant.parse(it.endDate) > lower }.map { record ->
                val begin = maxOf(Instant.parse(record.startDate), lower)
                val finish = minOf(Instant.parse(record.endDate), upper)
                TimelineEntry(record.title, Duration.between(lower, begin).toMinutes().toInt(), Duration.between(lower, finish).toMinutes().toInt(), true) { editRecord(record) }
            }
            val laneEnds = mutableListOf<Int>()
            val placed = entries.sortedBy { it.start }.map { entry ->
                var lane = laneEnds.indexOfFirst { it <= entry.start }
                val visualEnd = maxOf(entry.end, entry.start + 28)
                if (lane < 0) { lane = laneEnds.size; laneEnds.add(visualEnd) } else laneEnds[lane] = visualEnd
                entry to lane
            }
            Column(Modifier.weight(1f)) {
                Text("${day.dayOfMonth}日", Modifier.height(24.dp), fontSize = 11.sp)
                BoxWithConstraints(Modifier.fillMaxWidth().height(height * 24)) {
                    Column {
                        repeat(24) { Box(Modifier.fillMaxWidth().height(height).padding(end = 1.dp).background(ink.copy(alpha = 0.015f))) {
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = 0.12f)))
                        } }
                    }
                    val laneWidth = maxWidth / laneEnds.size.coerceAtLeast(1)
                    placed.forEach { (entry, lane) ->
                        Text(entry.title, modifier = Modifier.offset(x = laneWidth * lane, y = height * (entry.start / 60f))
                            .width(laneWidth).height((height * ((entry.end - entry.start) / 60f)).coerceAtLeast(22.dp))
                            .padding(end = 2.dp).background(if (entry.record) Color(0xFF597B68).copy(alpha = 0.24f) else ink.copy(alpha = 0.13f), RoundedCornerShape(4.dp))
                            .clickable(onClick = entry.open).padding(3.dp), fontSize = if (days == 7) 10.sp else 13.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}
