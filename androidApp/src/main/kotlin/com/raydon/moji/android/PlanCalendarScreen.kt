package com.raydon.moji.android

import android.widget.NumberPicker
import android.view.ContextThemeWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.data.LocalCalendar
import com.raydon.moji.android.data.LocalCalendarEntry
import com.raydon.moji.core.*
import com.raydon.moji.ui.InkCard
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** In-stack calendar, matching iOS month/weekly agenda/daily schedule groups. */
@Composable
fun PlanCalendarScreen(viewModel: MojiViewModel, initialDate: LocalDate, onDismiss: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val data = remember(snapshot) { LocalCalendar(snapshot) }
    val route by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(initialDate) }
    var mode by remember { mutableStateOf("month") }
    var didQA by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("planned") }
    var plan by remember { mutableStateOf<CheckInItem?>(null) }
    var record by remember { mutableStateOf<TimeRecord?>(null) }
    var countdown by remember { mutableStateOf<CountdownEvent?>(null) }
    var deletion by remember { mutableStateOf<LocalCalendarEntry?>(null) }
    var deleteRecordOnly by remember { mutableStateOf(false) }
    LaunchedEffect(route, snapshot.checkInItems.size) {
        if (BuildConfig.DEBUG && !didQA && route.startsWith("calendar-") && snapshot.checkInItems.isNotEmpty()) {
            didQA = true
            mode = route.removePrefix("calendar-").takeIf { it in listOf("month", "week", "day") } ?: "month"
            jump = route == "calendar-jump"
        }
    }
    val open: (LocalCalendarEntry) -> Unit = { entry ->
        when {
            entry.countdown != null -> countdown = entry.countdown
            entry.plan != null -> { plan = entry.plan; kind = "planned"; editor = true }
            else -> record = entry.record
        }
    }
    val requestDelete: (LocalCalendarEntry, Boolean) -> Unit = { entry, onlyRecord -> deletion = entry; deleteRecordOnly = onlyRecord }
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))) {
    MojiScreen {
        Column(Modifier.fillMaxSize()) {
            Box {
                MojiSheetBar("日历", "计划", "+", onDismiss, { addMenu = true }, leadingIsBack = true)
                Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 40.dp)) {
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem({ Text("添加计划") }, { addMenu = false; plan = null; kind = "planned"; editor = true })
                        DropdownMenuItem({ Text("记录已做") }, { addMenu = false; plan = null; kind = "completed"; editor = true })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CalendarArrow(false) { selected = shiftCalendar(selected, mode, -1) }
                Row(Modifier.weight(1f).clickable { jump = true }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(calendarPeriod(selected, mode), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp)); MojiChevronDown(false)
                }
                Text("今天", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion, modifier = Modifier.clickable { selected = LocalDate.now() })
                CalendarArrow(true) { selected = shiftCalendar(selected, mode, 1) }
            }
            Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                CalendarModeControl(mode) { mode = it }
            }
            val swipe = Modifier.pointerInput(mode, selected) {
                var distance = 0f
                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                    if (kotlin.math.abs(distance) > 80.dp.toPx()) selected = shiftCalendar(selected, mode, if (distance > 0) -1 else 1)
                }) { change, delta -> change.consume(); distance += delta }
            }
            LazyColumn(Modifier.fillMaxSize().then(swipe), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(if (mode == "week") 10.dp else if (mode == "day") 16.dp else 14.dp)) {
                when (mode) {
                    "month" -> {
                        item { InkCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
                            MonthCalendar(selected, data) { selected = it }
                        } }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("当日安排", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dayTitle(selected), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("${data.entriesOn(selected).size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                                CalendarEntries(data.entriesOn(selected), "这一天尚未落墨", open, { plan = it; kind = "planned"; editor = true }, { record = it }, requestDelete, { countdown = it }, viewModel)
                            }
                        }
                    }
                    "week" -> {
                        val monday = workflowWeek(selected)
                        repeat(7) { offset ->
                            val day = monday.plusDays(offset.toLong())
                            item {
                                val entries = data.entriesOn(day)
                                InkCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                                    Column {
                                    Row(Modifier.fillMaxWidth().clickable { selected = day; mode = "day" }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(day.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                                            Text(day.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 12.sp, lineHeight = 14.sp, color = if (day == LocalDate.now()) mojiVermilion else MaterialTheme.colorScheme.secondary)
                                        }
                                        Text(if (entries.isEmpty()) "留白" else "${entries.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.width(8.dp)); MojiLineIcon(MojiIcon.ChevronRight, 12.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                                    }
                                    if (entries.isNotEmpty()) {
                                        InkDivider(opacity = 0.13f, seed = inkSeed(day.toString()))
                                        entries.forEach { entry -> CalendarEntryRow(entry, open, { plan = it; kind = "planned"; editor = true }, { record = it }, requestDelete, { countdown = it }, viewModel) }
                                    }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val entries = data.entriesOn(selected)
                        if (entries.isEmpty()) item { Box(Modifier.padding(top = 42.dp)) { CalendarEmpty("今日留白") } }
                        scheduleOptions.forEach { (schedule, label) ->
                            val group = entries.filter { it.schedule == schedule }
                            if (group.isNotEmpty()) item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MojiLineIcon(if (schedule == "exactTime") MojiIcon.Clock else MojiIcon.Sun, 17.dp, MaterialTheme.colorScheme.secondary)
                                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    CalendarEntries(group, "今日留白", open, { plan = it; kind = "planned"; editor = true }, { record = it }, requestDelete, { countdown = it }, viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
    if (editor) PlanEditorScreenDialog(plan, selected, viewModel, kind) { editor = false; plan = null }
    record?.let { RecordEditorScreenDialog(it, viewModel) { record = null } }
    countdown?.let { CountdownEditorScreenDialog(it, viewModel) { countdown = null } }
    if (jump) CalendarJumpDialog(selected, { selected = it; jump = false }) { jump = false }
    deletion?.let { entry ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text(if (deleteRecordOnly) "删除记录？" else if (entry.countdown != null) "删除倒数日？" else if (entry.plan != null) "删除计划？" else "删除记录？") },
            text = { Text(if (entry.record?.checkInItemID != null && (deleteRecordOnly || entry.plan == null)) "对应计划会恢复为未完成。此操作无法撤销。" else "内容将从日历中删除，此操作无法撤销。") },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                when {
                    deleteRecordOnly -> entry.record?.let { viewModel.deleteRecord(it.id) }
                    entry.countdown != null -> viewModel.deleteCountdown(entry.countdown.id)
                    entry.plan != null -> viewModel.deletePlan(entry.plan.id)
                    entry.record != null -> viewModel.deleteRecord(entry.record.id)
                }
                deletion = null
            }) { Text("删除", color = mojiDestructive) } })
    }
}

private fun shiftCalendar(day: LocalDate, mode: String, direction: Int) = when (mode) {
    "month" -> day.plusMonths(direction.toLong()); "week" -> day.plusWeeks(direction.toLong()); else -> day.plusDays(direction.toLong())
}
private fun calendarPeriod(day: LocalDate, mode: String) = when (mode) {
    "month" -> day.format(DateTimeFormatter.ofPattern("yyyy年M月"))
    "week" -> "${workflowWeek(day).format(DateTimeFormatter.ofPattern("M月d日"))}—${workflowWeek(day).plusDays(6).format(DateTimeFormatter.ofPattern("M月d日"))}"
    else -> dayTitle(day)
}
private fun dayTitle(day: LocalDate) = day.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))

@Composable
private fun CalendarArrow(next: Boolean, action: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = action), contentAlignment = Alignment.Center) {
        Box(Modifier.rotate(if (next) 0f else 180f)) { MojiLineIcon(MojiIcon.ChevronRight, 17.dp) }
    }
}

@Composable
private fun CalendarModeControl(mode: String, onSelect: (String) -> Unit) {
    // The navigation picker has no extra grouped-form insets.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(2.dp)) {
            listOf("month" to "月", "week" to "周", "day" to "日").forEach { (raw, title) ->
                Surface(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(raw) }, color = if (raw == mode) if (dark) Color(0xFF727278) else Color.White else Color.Transparent, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), shadowElevation = if (raw == mode) 1.dp else 0.dp) {
                    Box(contentAlignment = Alignment.Center) { Text(title, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(selected: LocalDate, data: LocalCalendar, onSelect: (LocalDate) -> Unit) {
    val first = workflowWeek(selected.withDayOfMonth(1))
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f), fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(6) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(7) { weekday ->
                        val date = first.plusDays(week * 7L + weekday)
                        val entries = data.entriesOn(date)
                        Column(Modifier.weight(1f).height(49.dp).clickable { onSelect(date) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
                            val selectedInk = MaterialTheme.colorScheme.onSurface
                            Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                                if (date == selected) Canvas(Modifier.fillMaxSize()) { drawCircle(selectedInk.copy(alpha = 0.10f)); drawCircle(selectedInk.copy(alpha = 0.42f), style = Stroke(0.8.dp.toPx())) }
                                Text(date.dayOfMonth.toString(), fontSize = 15.sp, fontWeight = if (date == selected || date == LocalDate.now()) FontWeight.SemiBold else FontWeight.Normal, color = if (date == LocalDate.now()) mojiVermilion else MaterialTheme.colorScheme.onSurface.copy(alpha = if (date.month == selected.month) 1f else 0.30f))
                            }
                            Row(Modifier.height(7.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
                                entries.take(2).forEach { CalendarStatusMark(it.status, it.countdown != null, calendarEntryColor(it)) }
                                if (entries.size > 2) Text("+${entries.size - 2}", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("planned" to "待完成", "inProgress" to "进行中", "completed" to "已完成", "skipped" to "已跳过").forEach { (status, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CalendarStatusMark(status, false, if (status == "planned") mojiVermilion else if (status == "completed") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary)
                        Text(title, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { CalendarStatusMark("planned", true, MaterialTheme.colorScheme.secondary); Text("倒数日", fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

@Composable
private fun CalendarStatusMark(status: String, countdown: Boolean, tint: Color, size: Int = 9) {
    Canvas(Modifier.size(size.dp)) {
        val radius = this.size.minDimension / 2 - 0.6.dp.toPx()
        if (countdown) drawRoundRect(tint.copy(alpha = 0.72f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
        else when (status) {
            "completed" -> drawCircle(tint, radius)
            "inProgress" -> { drawCircle(tint.copy(alpha = 0.36f), radius); drawCircle(tint, radius, style = Stroke(1.dp.toPx())) }
            "skipped" -> { drawCircle(tint, radius, style = Stroke(1.dp.toPx())); drawLine(tint, Offset(this.size.width * 0.25f, this.size.height * 0.75f), Offset(this.size.width * 0.75f, this.size.height * 0.25f), strokeWidth = 1.dp.toPx()) }
            else -> drawCircle(tint, radius, style = Stroke(1.15.dp.toPx()))
        }
    }
}

@Composable
private fun calendarEntryColor(entry: LocalCalendarEntry): Color = when {
    entry.countdown != null || entry.status in listOf("planned", "inProgress") -> mojiVermilion
    entry.status == "completed" -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun CalendarEntries(entries: List<LocalCalendarEntry>, empty: String, open: (LocalCalendarEntry) -> Unit, editPlan: (CheckInItem) -> Unit, editRecord: (TimeRecord) -> Unit, delete: (LocalCalendarEntry, Boolean) -> Unit, editCountdown: (CountdownEvent) -> Unit, viewModel: MojiViewModel) {
    if (entries.isEmpty()) CalendarEmpty(empty)
    else InkCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)) {
        Column {
        entries.forEachIndexed { index, entry ->
            CalendarEntryRow(entry, open, editPlan, editRecord, delete, editCountdown, viewModel)
            if (index != entries.lastIndex) InkDivider(opacity = 0.13f, seed = inkSeed(entry.id))
        }
        }
    }
}

@Composable
private fun CalendarEmpty(title: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InkBrushMedallion(MojiIcon.Calendar, MaterialTheme.colorScheme.onSurface, 38.dp)
        Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun CalendarEntryRow(entry: LocalCalendarEntry, open: (LocalCalendarEntry) -> Unit, editPlan: (CheckInItem) -> Unit, editRecord: (TimeRecord) -> Unit, delete: (LocalCalendarEntry, Boolean) -> Unit, editCountdown: (CountdownEvent) -> Unit, viewModel: MojiViewModel) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val tint = calendarEntryColor(entry)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.weight(1f).clickable { open(entry) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (entry.countdown != null) MojiLineIcon(MojiIcon.Timer, 17.dp, tint)
                else if (entry.status == "completed") Box(Modifier.size(15.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) { Text("✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.surface) }
                else CalendarStatusMark(entry.status, false, tint, 15)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, color = if (entry.status == "completed") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface, textDecoration = if (entry.status == "skipped") TextDecoration.LineThrough else null)
                Text("${calendarTiming(entry)} · ${if (entry.countdown != null) "倒数日" else categoryName(entry.category)}", fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.secondary)
            }
            Text(if (entry.countdown != null) "倒数日" else planStatusName(entry.status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
        Box {
            Box(Modifier.size(44.dp).clickable { menu = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Ellipsis, 17.dp, MaterialTheme.colorScheme.secondary) }
            DropdownMenu(menu, { menu = false }) {
                if (entry.plan != null) {
                    DropdownMenuItem({ Text("修改计划") }, { menu = false; editPlan(entry.plan) })
                    entry.record?.let { actual ->
                        DropdownMenuItem({ Text(if (actual.note.contains("番茄钟")) "修改番茄钟记录" else "修改实际记录") }, { menu = false; editRecord(actual) })
                        DropdownMenuItem({ Text("删除实际记录", color = mojiDestructive) }, { menu = false; delete(entry, true) })
                    }
                    if (entry.status in listOf("completed", "skipped")) DropdownMenuItem({ Text("恢复为未完成") }, { menu = false; viewModel.togglePlan(entry.plan.id) })
                    DropdownMenuItem({ Text("删除计划", color = mojiDestructive) }, { menu = false; delete(entry, false) })
                } else if (entry.countdown != null) {
                    DropdownMenuItem({ Text("修改倒数日") }, { menu = false; editCountdown(entry.countdown) })
                    DropdownMenuItem({ Text("删除倒数日", color = mojiDestructive) }, { menu = false; delete(entry, false) })
                } else entry.record?.let { actual ->
                    DropdownMenuItem({ Text(if (actual.note.contains("番茄钟")) "修改番茄钟记录" else "修改记录") }, { menu = false; editRecord(actual) })
                    DropdownMenuItem({ Text("删除记录", color = mojiDestructive) }, { menu = false; delete(entry, false) })
                }
            }
        }
    }
}

private fun calendarTiming(entry: LocalCalendarEntry): String {
    val zone = ZoneId.systemDefault()
    if (entry.countdown != null) return entry.date.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    if (entry.schedule != "exactTime") return scheduleOptions.firstOrNull { it.first == entry.schedule }?.second ?: "全天"
    val time = entry.date.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    entry.record?.let { return "$time – ${Instant.parse(it.endDate).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}" }
    return time + if (entry.plan?.plannedDurationEnabled == true) " · 预计 ${entry.plan.plannedMinutes} 分钟" else ""
}

@Composable
private fun CalendarJumpDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableIntStateOf(initial.year) }
    var month by remember { mutableIntStateOf(initial.monthValue) }
    val now = LocalDate.now()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // SwiftUI's 320pt detent excludes the 34pt bottom safe area.
        MojiScreen(sheetHeight = 354.dp) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.width(36.dp).height(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.23f), CircleShape)) }
                MojiSheetBar("跳转到", "取消", "跳转", onDismiss, { onPick(LocalDate.of(year, month, minOf(initial.dayOfMonth, YearMonth.of(year, month).lengthOfMonth()))) })
                Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp)) {
                    CalendarWheel(minOf(now.year - 8, initial.year), maxOf(now.year + 8, initial.year), year, "年", Modifier.weight(1f)) { year = it }
                    CalendarWheel(1, 12, month, "月", Modifier.weight(1f)) { month = it }
                }
                Text("回到本月", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { year = now.year; month = now.monthValue }.padding(vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun CalendarWheel(min: Int, max: Int, value: Int, suffix: String, modifier: Modifier, onPick: (Int) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    AndroidView(factory = { context -> NumberPicker(ContextThemeWrapper(context, if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)).apply {
        minValue = min; maxValue = max; displayedValues = (min..max).map { "$it $suffix" }.toTypedArray(); wrapSelectorWheel = false
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
    } }, update = { picker ->
        picker.value = value
        picker.setOnValueChangedListener { _, _, picked -> onPick(picked) }
    }, modifier = modifier.fillMaxHeight())
}
