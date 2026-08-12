package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.data.AndroidSettings
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.ChecklistPolicy
import com.raydon.moji.ui.InkCard
import com.raydon.moji.ui.MojiPalette
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.Locale

@Composable
fun PlanScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf("today") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var quickTitle by remember { mutableStateOf("") }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val plans = when (mode) {
        "today" -> snapshot.checkInItems.filter {
            ChecklistPolicy.isVisible(it.scheduledStart, it.status, now.toString(), viewModel.carriesOver, TimeZone.getDefault().id)
        }
        else -> snapshot.checkInItems.filter {
            Instant.parse(it.scheduledStart).atZone(zone).toLocalDate() == selectedDate
        }
    }
    val completed = plans.count { it.status == "completed" }

    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                MojiLargeTitle(
                    title = "计划",
                    leading = MojiIcon.Calendar,
                    leadingAction = { mode = if (mode == "today") "month" else "today" },
                    actions = listOf(MojiIcon.Plus to { editing = null; showEditor = true }),
                )
            }
            if (mode != "today") item {
                CalendarSelector(mode, selectedDate) { selectedDate = it }
                Spacer(Modifier.height(10.dp))
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    shape = RoundedCornerShape(25.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.095f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = quickTitle,
                            onValueChange = { quickTitle = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (quickTitle.isBlank()) Text("写下一件要做的事", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f), fontSize = 17.sp)
                                inner()
                            },
                        )
                        Box(
                            Modifier.size(44.dp).clickable(enabled = quickTitle.isNotBlank()) {
                                viewModel.savePlan(
                                    title = quickTitle, note = "", date = LocalDate.now(), time = LocalTime.of(9, 0),
                                    scheduleKind = "allDay", category = "study", plannedMinutes = 25,
                                    repeatRule = "never", weekdays = emptyList(), reminderMinutes = null, calendarSync = false,
                                )
                                quickTitle = ""
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            InkAsset("InkIconLoop", Modifier.size(36.dp).alpha(if (quickTitle.isBlank()) 0.18f else 0.65f))
                            MojiLineIcon(MojiIcon.Plus, 18.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (quickTitle.isBlank()) 0.22f else 1f))
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(25.dp).border(1.4.dp, MojiPalette.Vermilion.copy(alpha = 0.75f), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("今", color = MojiPalette.Vermilion, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("今日计划", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    InkDivider(Modifier.width(44.dp), opacity = 0.23f)
                    Text(
                        if (plans.isEmpty()) "今日无计划" else "完成 $completed/${plans.size}",
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (plans.isEmpty()) 0.50f else 1f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (plans.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 68.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        InkAsset("InkFocusBloom", Modifier.fillMaxSize().alpha(0.10f))
                        MojiLineIcon(MojiIcon.Checklist, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                    }
                    Text("今天留白", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("写下一件要做的事，让今天有一个落点。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            items(plans, key = { it.id }) { plan ->
                PlanRow(
                    plan = plan,
                    now = now,
                    onToggle = { viewModel.togglePlan(plan.id) },
                    onEdit = { editing = plan; showEditor = true },
                    onSkip = { viewModel.skipPlan(plan.id) },
                    onPostpone = { viewModel.postponePlan(plan.id) },
                    onDelete = { viewModel.deletePlan(plan.id) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    if (showEditor) PlanEditorDialog(editing, selectedDate.takeIf { mode != "today" } ?: LocalDate.now(), viewModel) {
        showEditor = false
        editing = null
    }
}

@Composable
private fun CalendarSelector(mode: String, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    InkCard(Modifier.padding(horizontal = 20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(if (mode == "month") selected.minusMonths(1) else selected.minusDays(if (mode == "week") 7 else 1)) }) { Text("上一${if (mode == "month") "月" else if (mode == "week") "周" else "日"}") }
                Text(selected.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                TextButton(onClick = { onSelect(if (mode == "month") selected.plusMonths(1) else selected.plusDays(if (mode == "week") 7 else 1)) }) { Text("下一${if (mode == "month") "月" else if (mode == "week") "周" else "日"}") }
            }
            if (mode == "month") {
                val first = selected.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        repeat(7) { day ->
                            val date = first.plusDays((week * 7 + day).toLong())
                            Text(
                                date.dayOfMonth.toString(),
                                modifier = Modifier.size(34.dp).background(
                                    if (date == selected) MojiPalette.Vermilion.copy(alpha = 0.16f) else Color.Transparent,
                                    CircleShape,
                                ).clickable { onSelect(date) }.padding(8.dp),
                                color = if (date.month == selected.month) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            } else if (mode == "week") {
                val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(7) { offset ->
                        val date = monday.plusDays(offset.toLong())
                        TextButton(onClick = { onSelect(date) }) { Text("${"一二三四五六日"[offset]}\n${date.dayOfMonth}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: CheckInItem,
    now: Instant,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onSkip: () -> Unit,
    onPostpone: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val completed = plan.status == "completed"
    val date = Instant.parse(plan.scheduledStart).atZone(ZoneId.systemDefault())
    val lateDays = ChronoUnit.DAYS.between(date.toLocalDate(), now.atZone(ZoneId.systemDefault()).toLocalDate()).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onEdit)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).border(1.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), CircleShape)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (completed) Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.title, modifier = Modifier.padding(start = 14.dp), fontSize = 17.sp, textDecoration = if (completed) TextDecoration.LineThrough else null)
                    if (lateDays > 0 && !completed) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (lateDays > 99) "99+" else lateDays.toString(), color = Color.White, modifier = Modifier.background(MojiPalette.Vermilion).padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                val schedule = when (plan.scheduleKind) {
                    "allDay" -> "全天"
                    "morning" -> "上午"
                    "afternoon" -> "下午"
                    "evening" -> "晚上"
                    else -> date.format(DateTimeFormatter.ofPattern("HH:mm"))
                }
                Text("$schedule · ${plan.plannedMinutes} 分钟 · ${categoryName(plan.category)}", modifier = Modifier.padding(start = 14.dp, top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                if (plan.note.isNotBlank()) Text(plan.note, modifier = Modifier.padding(start = 14.dp, top = 5.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(onClick = { menu = true }) { MojiLineIcon(MojiIcon.Ellipsis, 22.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("顺延一天") }, onClick = { menu = false; onPostpone() })
                    DropdownMenuItem(text = { Text("跳过") }, onClick = { menu = false; onSkip() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        InkDivider(Modifier.padding(start = 34.dp), opacity = 0.28f)
    }
}

@Composable
private fun PlanEditorDialog(existing: CheckInItem?, initialDate: LocalDate, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val oldDate = existing?.let { Instant.parse(it.scheduledStart).atZone(ZoneId.systemDefault()) }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var note by remember(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var date by remember(existing?.id) { mutableStateOf(oldDate?.toLocalDate() ?: initialDate) }
    var time by remember(existing?.id) { mutableStateOf(oldDate?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(9, 0)) }
    var schedule by remember(existing?.id) { mutableStateOf(existing?.scheduleKind ?: "allDay") }
    var category by remember(existing?.id) { mutableStateOf(existing?.category ?: "study") }
    var minutes by remember(existing?.id) { mutableStateOf((existing?.plannedMinutes ?: 25).toString()) }
    var repeat by remember(existing?.id) { mutableStateOf(existing?.repeatRule ?: "never") }
    var weekdays by remember(existing?.id) { mutableStateOf((existing?.repeatWeekdays ?: emptyList()).toSet()) }
    var reminder by remember(existing?.id) { mutableStateOf(existing?.reminderMinutesBefore?.toString() ?: "") }
    var calendarSync by remember(existing?.id) { mutableStateOf(existing?.calendarSyncEnabled ?: false) }
    val pickDate = rememberDatePicker { date = it }
    val pickTime = rememberTimePicker { time = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添计划" else "编辑计划") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickDate(date) }) { Text(date.toString()) }
                        Button(onClick = { pickTime(time) }, enabled = schedule == "exactTime") { Text(time.toString()) }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("allDay" to "全天", "morning" to "上午", "afternoon" to "下午", "evening" to "晚上", "exactTime" to "具体时间").forEach { (raw, label) ->
                            FilterChip(schedule == raw, { schedule = raw }, { Text(label) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(category == "study", { category = "study" }, { Text("学习") })
                        FilterChip(category == "work", { category = "work" }, { Text("工作") })
                    }
                }
                item { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("预计分钟") }, singleLine = true) }
                item {
                    Text("重复")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("never" to "不重复", "daily" to "每天", "weekdays" to "工作日", "weekly" to "每周", "customWeekdays" to "自选", "monthly" to "每月").forEach { (raw, label) ->
                            FilterChip(repeat == raw, { repeat = raw }, { Text(label) })
                        }
                    }
                }
                if (repeat == "customWeekdays") item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六", 1 to "日").forEach { (value, label) ->
                            FilterChip(
                                selected = value in weekdays,
                                onClick = { weekdays = if (value in weekdays) weekdays - value else weekdays + value },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                item { OutlinedTextField(reminder, { reminder = it.filter(Char::isDigit) }, label = { Text("提前提醒分钟；留空不提醒") }, singleLine = true) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("写入系统日历", Modifier.weight(1f)); Switch(calendarSync, { calendarSync = it }) } }
                item { OutlinedTextField(note, { note = it }, label = { Text("说明") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    viewModel.savePlan(
                        existing?.id, title, note, date, time, schedule, category,
                        minutes.toIntOrNull() ?: 25, repeat, weekdays.sorted(), reminder.toIntOrNull(), calendarSync,
                    )
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
