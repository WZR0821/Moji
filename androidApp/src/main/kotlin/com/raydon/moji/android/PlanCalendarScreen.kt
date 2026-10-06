package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.ui.MojiPalette
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun PlanCalendarDialog(
    viewModel: MojiViewModel,
    initialDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(initialDate) }
    var mode by remember { mutableStateOf("month") }
    var editorVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    var editingRecord by remember { mutableStateOf<com.raydon.moji.core.TimeRecord?>(null) }
    var editingCountdown by remember { mutableStateOf<com.raydon.moji.core.CountdownEvent?>(null) }
    val zone = ZoneId.systemDefault()
    val dayPlans = snapshot.checkInItems.filter { Instant.parse(it.scheduledStart).atZone(zone).toLocalDate() == selected }
    val dayCountdowns = snapshot.countdowns.filter { com.raydon.moji.android.data.LocalRules.occursOn(it, selected) }
    val dayRecords = snapshot.records.filter { Instant.parse(it.startDate) < selected.plusDays(1).atStartOfDay(zone).toInstant() && Instant.parse(it.endDate) > selected.atStartOfDay(zone).toInstant() }
    val firstVisible = selected.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val marked = (0..41).map { firstVisible.plusDays(it.toLong()) }.filter { day ->
        snapshot.checkInItems.any { Instant.parse(it.scheduledStart).atZone(zone).toLocalDate() == day } ||
            snapshot.records.any { Instant.parse(it.startDate) < day.plusDays(1).atStartOfDay(zone).toInstant() && Instant.parse(it.endDate) > day.atStartOfDay(zone).toInstant() } ||
            snapshot.countdowns.any { com.raydon.moji.android.data.LocalRules.occursOn(it, day) }
    }.toSet()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar("日历", "返回", "+", onDismiss, { editing = null; editorVisible = true })
                androidx.compose.material3.TextButton(onClick = { selected = LocalDate.now() }) { Text("今天") }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CalendarArrow("‹") { selected = if (mode == "month") selected.minusMonths(1) else if (mode == "week") selected.minusWeeks(1) else selected.minusDays(1) }
                    Text(
                        if (mode == "month") selected.format(DateTimeFormatter.ofPattern("yyyy年 M月")) else selected.format(DateTimeFormatter.ofPattern("M月d日")),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CalendarArrow("›") { selected = if (mode == "month") selected.plusMonths(1) else if (mode == "week") selected.plusWeeks(1) else selected.plusDays(1) }
                }
                FormSegmentedControl(listOf("month" to "月", "week" to "周", "day" to "日"), mode) { mode = it }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                            shape = RoundedCornerShape(15.dp),
                        ) {
                            when (mode) {
                                "month" -> MonthCalendar(selected, marked) { selected = it }
                                "week" -> WeekCalendar(selected) { selected = it }
                                else -> WeekCalendar(selected) { selected = it }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(selected.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${dayPlans.size + dayCountdowns.size + dayRecords.size} 项", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 13.sp)
                        }
                    }
                    items(dayPlans.filter { mode == "month" || it.scheduleKind != "exactTime" }, key = { "plan-${it.id}" }) { plan ->
                        CalendarEventRow(
                            title = plan.title,
                            detail = "${if (plan.status == "completed") "已完成" else scheduleName(plan.scheduleKind)} · ${categoryName(plan.category)}",
                            marker = mojiVermilion,
                        ) { editing = plan; editorVisible = true }
                    }
                    items(dayCountdowns, key = { "count-${it.id}" }) { event ->
                        CalendarEventRow(event.title, "倒数日 · ${if (event.includesToday == true) "含今天" else "不含今天"}", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) { editingCountdown = event }
                    }
                    items(dayRecords.filter { mode == "month" || (it.scheduleKind != null && it.scheduleKind != "exactTime") }, key = { "record-${it.id}" }) { record ->
                        val start = Instant.parse(record.startDate).atZone(zone)
                        val end = Instant.parse(record.endDate).atZone(zone)
                        CalendarEventRow(record.title, "${start.format(DateTimeFormatter.ofPattern("HH:mm"))}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))} · 实际记录", Color(0xFF597B68)) { editingRecord = record }
                    }
                    if (mode == "month" && dayPlans.isEmpty() && dayCountdowns.isEmpty() && dayRecords.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MojiLineIcon(MojiIcon.Calendar, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                            Text("这一天没有安排", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), modifier = Modifier.padding(top = 10.dp))
                            Text("轻点右上角添加计划", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (mode != "month") item {
                        CalendarTimeline(selected, if (mode == "week") 7 else 1, snapshot, { editing = it; editorVisible = true }, { editingRecord = it })
                    }
                }
            }
        }
    }
    if (editorVisible) PlanEditorScreenDialog(editing, selected, viewModel) {
        editorVisible = false
        editing = null
        onSelect(selected)
    }
    editingRecord?.let { RecordEditorScreenDialog(it, viewModel) { editingRecord = null } }
    editingCountdown?.let { CountdownEditorScreenDialog(it, viewModel) { editingCountdown = null } }
}

@Composable
private fun CalendarArrow(text: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(text, fontSize = 30.sp, fontWeight = FontWeight.Light) }
}

@Composable
private fun MonthCalendar(selected: LocalDate, marked: Set<LocalDate>, onSelect: (LocalDate) -> Unit) {
    val first = selected.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth()) {
            "一二三四五六日".forEach { label -> Text(label.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)) }
        }
        Spacer(Modifier.height(6.dp))
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val date = first.plusDays((week * 7L) + weekday)
                    val active = date == selected
                    Column(
                        Modifier.weight(1f).height(45.dp).clickable { onSelect(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(31.dp).background(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString(), color = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface.copy(alpha = if (date.month == selected.month) 1f else 0.30f), fontSize = 14.sp)
                        }
                        if (date in marked) Box(Modifier.size(4.dp).background(if (active) MaterialTheme.colorScheme.background else mojiVermilion, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekCalendar(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp)) {
        repeat(7) { offset ->
            val date = monday.plusDays(offset.toLong())
            val active = date == selected
            Column(Modifier.weight(1f).clickable { onSelect(date) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("一二三四五六日"[offset].toString(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.43f), fontSize = 12.sp)
                Box(Modifier.padding(top = 8.dp).size(34.dp).background(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(date.dayOfMonth.toString(), color = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun DayCalendar(selected: LocalDate) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(selected.dayOfMonth.toString(), fontSize = 48.sp, fontWeight = FontWeight.Light)
        Text(selected.format(DateTimeFormatter.ofPattern("yyyy年M月 EEEE")), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 13.sp)
    }
}

@Composable
private fun CalendarEventRow(title: String, detail: String, marker: Color, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(38.dp).background(marker, RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (onClick != null) MojiLineIcon(MojiIcon.ChevronRight, 17.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
    }
}

private fun scheduleName(raw: String?): String = when (raw) {
    "allDay" -> "全天"
    "morning" -> "上午"
    "afternoon" -> "下午"
    "evening" -> "晚上"
    else -> "具体时间"
}
