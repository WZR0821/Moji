package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.raydon.moji.core.TimeRecord
import com.raydon.moji.ui.MojiPalette
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun ReviewSecondaryDialog(route: String, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var recordEditor by remember { mutableStateOf<TimeRecord?>(null) }
    var showRecordEditor by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(
                    when (route) { "weekly" -> "周总结"; "monthly" -> "月总结"; else -> "时间记录" },
                    "返回",
                    if (route == "records") "+" else "",
                    onDismiss,
                    { if (route == "records") { recordEditor = null; showRecordEditor = true } },
                )
                when (route) {
                    "weekly" -> WeeklySummaryContent(snapshot.checkInItems, snapshot.records, viewModel)
                    "monthly" -> MonthlySummaryContent(snapshot.checkInItems, snapshot.records)
                    else -> RecordsContent(snapshot.records, { recordEditor = it; showRecordEditor = true }, viewModel::deleteRecord)
                }
            }
        }
    }
    if (showRecordEditor) RecordEditorScreenDialog(recordEditor, viewModel) { showRecordEditor = false; recordEditor = null }
}

private fun fullDuration(minutes: Long): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0L -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}
private fun estimated(item: com.raydon.moji.core.CheckInItem) = if (item.plannedDurationEnabled ?: ((item.scheduleKind ?: "exactTime") == "exactTime" && item.detailsConfigured != false)) item.plannedMinutes else 0

@Composable
private fun WeeklySummaryContent(plans: List<com.raydon.moji.core.CheckInItem>, records: List<TimeRecord>, viewModel: MojiViewModel) {
    val current = workflowWeek(LocalDate.now())
    var week by remember { mutableStateOf(current) }
    val weekPlans = plans.filter { it.kind == "planned" && Instant.parse(it.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate() in week..week.plusDays(6) }
    val minutes = com.raydon.moji.android.data.LocalRules.minutes(records, week, week.plusDays(7))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            com.raydon.moji.ui.InkCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SummaryNavigation("周总结", "${week.monthValue}月${week.dayOfMonth}日 – ${week.plusDays(6).monthValue}月${week.plusDays(6).dayOfMonth}日", week < current, { week = week.minusWeeks(1) }, { week = week.plusWeeks(1) })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text(fullDuration(minutes), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (week < current) Text("回到本周", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { week = current })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeeklyMetric("${weekPlans.count { it.status == "completed" }}/${weekPlans.size}", "完成计划", MojiIcon.Checklist, Modifier.weight(1f))
                        WeeklyMetric(durationText(weekPlans.sumOf(::estimated).toLong()), "计划时间", MojiIcon.Calendar, Modifier.weight(1f))
                    }
                    SummaryChart(records, week, 7)
                    SummaryLegend(records, week, week.plusDays(7))
                }
            }
        }
        item { WorkflowWeekSection(viewModel, week) }
    }
}

@Composable
private fun MonthlySummaryContent(plans: List<com.raydon.moji.core.CheckInItem>, records: List<TimeRecord>) {
    val current = LocalDate.now().withDayOfMonth(1)
    var month by remember { mutableStateOf(current) }
    val monthPlans = plans.filter { it.kind == "planned" && Instant.parse(it.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate() in month..month.plusMonths(1).minusDays(1) }
    val minutes = com.raydon.moji.android.data.LocalRules.minutes(records, month, month.plusMonths(1))
    val planned = monthPlans.sumOf(::estimated).toLong()
    val completed = monthPlans.count { it.status == "completed" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        item {
            com.raydon.moji.ui.InkCard {
                Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    SummaryNavigation("月总结", month.format(DateTimeFormatter.ofPattern("yyyy年M月")), month < current, { month = month.minusMonths(1) }, { month = month.plusMonths(1) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MonthMetric("$completed/${monthPlans.size}", "完成计划", Modifier.weight(1f))
                        MonthMetric(if (monthPlans.isEmpty()) "0%" else "${completed * 100 / monthPlans.size}%", "完成率", Modifier.weight(1f))
                        MonthMetric("${com.raydon.moji.android.data.LocalRules.streak(plans, month, month.plusMonths(1), month == current)} 天", if (month == current) "当前连续" else "月内最长", Modifier.weight(1f))
                    }
                    MonthHeatmap(month, monthPlans, records)
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        ComparisonRow("计划", planned, maxOf(planned, minutes), 0.5f)
                        ComparisonRow("实际", minutes, maxOf(planned, minutes), 0.88f)
                    }
                    SummaryChart(records, month, month.lengthOfMonth())
                    SummaryLegend(records, month, month.plusMonths(1), compact = true)
                }
            }
        }
    }
}

@Composable
private fun SummaryNavigation(title: String, subtitle: String, canNext: Boolean, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = previous), contentAlignment = Alignment.Center) { Box(Modifier.rotate(180f)) { MojiLineIcon(MojiIcon.ChevronRight, 17.dp) } }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        Box(Modifier.size(44.dp).alpha(if (canNext) 1f else 0.3f).clickable(enabled = canNext, onClick = next), contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.ChevronRight, 17.dp) }
    }
}

@Composable
private fun SummaryChart(records: List<TimeRecord>, start: LocalDate, days: Int) {
    val groups = listOf(records.filter { it.category == "study" }, records.filter { it.category == "work" }, records.filter { it.category !in listOf("study", "work") })
    val values = (0 until days).map { offset -> groups.map { com.raydon.moji.android.data.LocalRules.minutes(it, start.plusDays(offset.toLong()), start.plusDays(offset + 1L)) } }
    val maximum = values.maxOf { it.sum() }.coerceAtLeast(1)
    val scale = if (maximum <= 1) 1L else kotlin.math.ceil(maximum / 25.0).toLong() * 25
    val colors = chartColors()
    Column(Modifier.fillMaxWidth().height(if (days == 7) 180.dp else 118.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (days == 7) Column(Modifier.width(42.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                (4 downTo 0).forEach { Text(durationText(scale * it / 4), fontSize = 10.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.secondary) }
            }
            Box(Modifier.fillMaxHeight().weight(1f)) {
                if (days == 7) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(5) { HorizontalDivider(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), thickness = 0.5.dp) }
                }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val slot = size.width / days
                    values.forEachIndexed { index, segments ->
                        var bottom = size.height
                        segments.forEachIndexed { category, value ->
                            val height = size.height * value / scale
                            if (height > 0f) drawRoundRect(colors[category], androidx.compose.ui.geometry.Offset(slot * (index + 0.2f), bottom - height), androidx.compose.ui.geometry.Size(slot * 0.6f, height), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                            bottom -= height
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = if (days == 7) 42.dp else 0.dp, top = 5.dp)) {
            (0 until days).forEach { index -> Text(if (days == 7) "一二三四五六日"[index].toString() else if (index % 7 == 0) "${index + 1}日" else "", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible, color = MaterialTheme.colorScheme.secondary) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("学习", "工作", "其他").forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).background(colors[index], CircleShape))
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun chartColors(): List<Color> = listOf(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.secondary,
    if (MaterialTheme.colorScheme.background == com.raydon.moji.ui.MojiPalette.PaperDark) Color(0xFF948C7D) else Color(0xFF8A8073))

@Composable
private fun SummaryLegend(records: List<TimeRecord>, from: LocalDate, until: LocalDate, compact: Boolean = false) {
    val colors = chartColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("study" to "学习", "work" to "工作", "custom" to "其他").forEachIndexed { index, (category, label) ->
            val matching = records.filter { if (category == "custom") it.category !in listOf("study", "work") else it.category == category }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Box(Modifier.size(8.dp).background(colors[index], CircleShape)); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                val minutes = com.raydon.moji.android.data.LocalRules.minutes(matching, from, until)
                Text(if (compact) durationText(minutes) else fullDuration(minutes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WeeklyMetric(value: String, label: String, icon: MojiIcon, modifier: Modifier) {
    Row(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MojiLineIcon(icon, 17.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun MonthMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) }
}

@Composable
private fun ComparisonRow(title: String, minutes: Long, maximum: Long, opacity: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(30.dp))
        Box(Modifier.weight(1f).height(9.dp)) {
            InkAsset("InkThinDivider", Modifier.fillMaxSize().alpha(0.10f))
            InkAsset("InkProgressBar", Modifier.fillMaxWidth(if (maximum > 0) minutes.toFloat() / maximum else 0f).fillMaxHeight().alpha(opacity))
        }
        Text(durationText(minutes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun MonthHeatmap(month: LocalDate, plans: List<com.raydon.moji.core.CheckInItem>, records: List<TimeRecord>) {
    val zone = ZoneId.systemDefault()
    val blanks = month.dayOfWeek.value - 1
    val cells = (0 until blanks).map { null } + (0 until month.lengthOfMonth()).map { month.plusDays(it.toLong()) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary) } }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { index ->
                        val day = row.getOrNull(index)
                        if (day == null) Spacer(Modifier.weight(1f).aspectRatio(1f))
                        else {
                            val dayPlans = plans.filter { Instant.parse(it.scheduledStart).atZone(zone).toLocalDate() == day }
                            val completed = dayPlans.count { it.status == "completed" }
                            val minutes = com.raydon.moji.android.data.LocalRules.minutes(records, day, day.plusDays(1))
                            val opacity = if (dayPlans.isEmpty()) if (minutes > 0) 0.18f else 0.035f else if (completed == 0) 0.08f else 0.30f + minOf(0.60f, completed.toFloat() / dayPlans.size * 0.60f)
                            Box(Modifier.weight(1f).aspectRatio(1f).background(MaterialTheme.colorScheme.onSurface.copy(alpha = opacity), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                Text(day.dayOfMonth.toString(), fontSize = 11.sp, fontWeight = if (completed > 0) FontWeight.Bold else FontWeight.Normal, color = if (completed > 0) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsContent(records: List<TimeRecord>, onEdit: (TimeRecord) -> Unit, onDelete: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val grouped = records.groupBy { Instant.parse(it.startDate).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (records.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MojiLineIcon(MojiIcon.Note, 40.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                Text("暂无时间记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("轻点右上角补记一段时间", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        grouped.forEach { (date, dayRecords) ->
            item(key = "records-$date") {
                FormSection(when (date) { LocalDate.now() -> "今天"; LocalDate.now().minusDays(1) -> "昨天"; else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)) }) {
                    dayRecords.forEachIndexed { index, record ->
                        if (index > 0) FormSeparator()
                        RecordListRow(record, onEdit, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordListRow(record: TimeRecord, onEdit: (TimeRecord) -> Unit, onDelete: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.parse(record.startDate).atZone(zone)
    val end = Instant.parse(record.endDate).atZone(zone)
    var menu by remember(record.id) { mutableStateOf(false) }
    Box {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onEdit(record) }, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { MojiLineIcon(when (record.category) { "study" -> MojiIcon.Book; "work" -> MojiIcon.Work; else -> MojiIcon.Tag }, 18.dp, MaterialTheme.colorScheme.secondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val timing = if ((record.scheduleKind ?: "exactTime") == "exactTime") "${start.format(DateTimeFormatter.ofPattern("HH:mm"))} – ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}" else scheduleOptions.firstOrNull { it.first == record.scheduleKind }?.second ?: "全天"
            Text(timing, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        }
        val seconds = Duration.between(start, end).seconds.coerceAtLeast(0)
        Text(if ((record.scheduleKind ?: "exactTime") == "exactTime") fullDuration((seconds + 59) / 60) else "已记录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.End)
    }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit(record) })
                DropdownMenuItem(text = { Text("删除", color = mojiVermilion) }, onClick = { menu = false; onDelete(record.id) })
            }
    }
}

@Composable
fun RecordEditorScreenDialog(existing: TimeRecord?, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val oldStart = existing?.let { Instant.parse(it.startDate).atZone(zone) }
    val oldEnd = existing?.let { Instant.parse(it.endDate).atZone(zone) }
    val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
    val draftId = remember(existing?.id) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var note by remember(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var category by remember(existing?.id) { mutableStateOf(existing?.category ?: "study") }
    var schedule by remember(existing?.id) { mutableStateOf(existing?.scheduleKind ?: "exactTime") }
    var deleting by remember { mutableStateOf(false) }
    var date by remember(existing?.id) { mutableStateOf(oldStart?.toLocalDate() ?: now.minusHours(1).toLocalDate()) }
    var endDate by remember(existing?.id) { mutableStateOf(oldEnd?.toLocalDate() ?: date) }
    var start by remember(existing?.id) { mutableStateOf(oldStart?.toLocalTime()?.withSecond(0)?.withNano(0) ?: now.minusHours(1).toLocalTime()) }
    var end by remember(existing?.id) { mutableStateOf(oldEnd?.toLocalTime()?.withSecond(0)?.withNano(0) ?: now.toLocalTime()) }
    val pickDate = rememberDatePicker { if (endDate == date) endDate = it; date = it }
    val pickEndDate = rememberDatePicker { endDate = it }
    val pickStart = rememberTimePicker { start = it }
    val pickEnd = rememberTimePicker { end = it }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(if (existing == null) "添加记录" else "编辑记录", "取消", "保存", onDismiss, {
                    viewModel.saveRecord(draftId, title, category, date, start, end, note, endDate, schedule, onSaved = onDismiss)
                }, title.isNotBlank() && (schedule != "exactTime" || endDate.atTime(end).isAfter(date.atTime(start))))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 27.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
                    item {
                        FormSection("标题") { FormTextField(title, { title = it }, "例如：复习高等数学") }
                    }
                    item { FormSection("详细说明（可选）") { FormTextField(note, { note = it }, "补充这段记录的细节", singleLine = false, minHeight = 140) } }
                    item { FormSection("类型") { CategoryFields(category, viewModel) { category = it } }
                    }
                    item {
                        FormSection("实际发生在") {
                            FormChoiceRow("记录时段", schedule, scheduleOptions) { schedule = it }
                            FormSeparator()
                            if (schedule == "exactTime") {
                                FormDateTimeRow("开始", date, start, { pickDate(date) }, { pickStart(start) })
                                FormSeparator()
                                FormDateTimeRow("结束", endDate, end, { pickEndDate(endDate) }, { pickEnd(end) })
                                DurationPills(listOf(25, 45, 60, 90)) { val result = date.atTime(start).plusMinutes(it.toLong()); end = result.toLocalTime(); endDate = result.toLocalDate() }
                                FormRow("本次合计", editorDuration(Duration.between(date.atTime(start), endDate.atTime(end)).toMinutes().coerceAtLeast(0)))
                            } else {
                                FormDateRow("日期", date) { pickDate(date) }
                                EditorCaption("只记录“${scheduleOptions.first { it.first == schedule }.second}”这一事实，不自动估算分钟数。")
                            }
                        }
                    }
                    if (existing != null) item { Column(Modifier.offset(y = (-7).dp)) { FormSection(footer = if (existing.checkInItemID != null) "若记录关联计划，删除后对应计划会恢复为未完成。" else null) {
                        Text("删除这条记录", color = mojiDestructive, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { deleting = true }.padding(horizontal = 20.dp, vertical = 11.dp))
                    } } }
                }
            }
        }
    }
    if (deleting && existing != null) androidx.compose.material3.AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除记录？") },
        text = { Text(if (existing.checkInItemID == null) "记录会从日历和总结中删除，此操作无法撤销。" else "记录会被删除，对应计划会恢复为未完成，此操作无法撤销。") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.deleteRecord(existing.id); onDismiss() }) { Text("删除", color = mojiVermilion) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text("取消") } })
}
