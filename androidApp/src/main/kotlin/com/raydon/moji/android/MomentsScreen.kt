package com.raydon.moji.android

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.CountdownPolicy
import com.raydon.moji.ui.InkCard
import com.raydon.moji.ui.MojiPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

@Composable
fun MomentsScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var scope by remember { mutableStateOf("upcoming") }
    var editing by remember { mutableStateOf<CountdownEvent?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val events = snapshot.countdowns.filter { event ->
        val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
        if (scope == "upcoming") origin >= today else origin < today
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScreenHeader("观时知往", "倒数将来，纪念已经走过的日子") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(scope == "upcoming", { scope = "upcoming" }, { Text("倒数日") })
                    FilterChip(scope == "past", { scope = "past" }, { Text("纪念日") })
                    FilterChip(scope == "focus", { scope = "focus" }, { Text("番茄专注") })
                }
            }
            if (scope == "focus") {
                item { PomodoroPanel(viewModel) }
            } else {
                if (events.isEmpty()) item { Text("这里还没有时刻。", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
                items(events.size, key = { events[it].id }) { index ->
                    val event = events[index]
                    CountdownRow(
                        event,
                        scope == "upcoming",
                        onEdit = { editing = event; showEditor = true },
                        onPin = { viewModel.toggleCountdownPin(event.id) },
                        onDelete = { viewModel.deleteCountdown(event.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
        if (scope != "focus") FloatingActionButton(
            onClick = { editing = null; showEditor = true },
            containerColor = MojiPalette.Vermilion,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Text("＋", color = Color.White) }
    }
    if (showEditor) CountdownEditorDialog(editing, viewModel) { showEditor = false; editing = null }
}

@Composable
private fun CountdownRow(event: CountdownEvent, upcomingScope: Boolean, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
    val today = LocalDate.now()
    val effective = CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", today.toKotlinDate())
    val effectiveJava = LocalDate.of(effective.year, effective.monthNumber, effective.dayOfMonth)
    val days = if (upcomingScope) java.time.temporal.ChronoUnit.DAYS.between(today, effectiveJava).toInt() + if (event.includesToday == true) 1 else 0
    else java.time.temporal.ChronoUnit.DAYS.between(origin, today).toInt() + if (event.includesToday == true) 1 else 0
    InkCard(Modifier.padding(horizontal = 20.dp).clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isPinned) Text("印", color = MojiPalette.Vermilion, modifier = Modifier.padding(end = 8.dp))
                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (upcomingScope) {
                        if (days == 0) "就是今天" else "还有 $days 天"
                    } else "已经 $days 天",
                    color = MojiPalette.Vermilion,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text("${origin.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))} · ${repeatName(event.repeatRule)}", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("•••") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (event.isPinned) "取消重点" else "设为重点") }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun PomodoroPanel(viewModel: MojiViewModel) {
    val state by viewModel.pomodoro.collectAsStateWithLifecycle()
    val tick by viewModel.tickMillis.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val remaining = viewModel.remainingSeconds(state)
    val total = if (state.phase == "focus") {
        snapshot.checkInItems.firstOrNull { it.id == state.linkedPlanId }?.plannedMinutes?.times(60)
            ?: viewModel.durationForPhase(state.phase)
    } else viewModel.durationForPhase(state.phase)
    var planMenu by remember { mutableStateOf(false) }
    var endDialog by remember { mutableStateOf(false) }
    InkCard(Modifier.padding(horizontal = 20.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("focus" to "专注", "shortBreak" to "短休息", "longBreak" to "长休息").forEach { (raw, label) ->
                    FilterChip(state.phase == raw, { viewModel.selectPomodoroPhase(raw) }, { Text(label) }, enabled = !state.running)
                }
            }
            Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (remaining.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = MojiPalette.Vermilion,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    strokeWidth = 9.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phaseName(state.phase), color = MojiPalette.Vermilion)
                    Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.displayMedium)
                    Text(state.title, maxLines = 1)
                }
            }
            Box {
                TextButton(onClick = { planMenu = true }, enabled = !state.running && state.phase == "focus") {
                    Text(if (state.linkedPlanId.isBlank()) "选择计划" else "更换计划")
                }
                DropdownMenu(planMenu, { planMenu = false }) {
                    DropdownMenuItem(text = { Text("不关联计划") }, onClick = { planMenu = false; viewModel.linkPomodoro(null) })
                    snapshot.checkInItems.filter { it.status == "planned" || it.status == "inProgress" }.take(20).forEach { plan ->
                        DropdownMenuItem(text = { Text(plan.title) }, onClick = { planMenu = false; viewModel.linkPomodoro(plan) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (state.running) viewModel.pausePomodoro() else viewModel.startPomodoro() }) {
                    Text(if (state.running) "暂停" else "开始")
                }
                TextButton(onClick = { viewModel.resetPomodoro() }) { Text("重置") }
                TextButton(onClick = { endDialog = true }) { Text("结束") }
            }
            Text("已完成 ${state.completedFocusCount} 个专注", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (endDialog) AlertDialog(
        onDismissRequest = { endDialog = false },
        title = { Text("结束当前计时？") },
        text = { Text("可以保留已经专注的分钟，也可以放弃本次记录。") },
        confirmButton = { TextButton(onClick = { endDialog = false; viewModel.finishPomodoro(true) }) { Text("保留记录") } },
        dismissButton = { Row { TextButton(onClick = { endDialog = false; viewModel.finishPomodoro(false) }) { Text("放弃") }; TextButton(onClick = { endDialog = false }) { Text("继续") } } },
    )
}

@Composable
private fun CountdownEditorDialog(existing: CountdownEvent?, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val oldDate = existing?.let { Instant.parse(it.targetDate).atZone(ZoneId.systemDefault()).toLocalDate() }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var date by remember(existing?.id) { mutableStateOf(oldDate ?: LocalDate.now().plusDays(1)) }
    var repeat by remember(existing?.id) { mutableStateOf(existing?.repeatRule ?: "never") }
    var includesToday by remember(existing?.id) { mutableStateOf(existing?.includesToday ?: false) }
    var pinned by remember(existing?.id) { mutableStateOf(existing?.isPinned ?: false) }
    var calendarSync by remember(existing?.id) { mutableStateOf(existing?.calendarSyncEnabled ?: false) }
    val picker = rememberDatePicker { date = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添时刻" else "编辑时刻") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { picker(date) }) { Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("never" to "不重复", "yearly" to "每年", "monthly" to "每月", "weekly" to "每周").forEach { (raw, label) ->
                        FilterChip(repeat == raw, { repeat = raw }, { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("包含当天", Modifier.weight(1f)); Switch(includesToday, { includesToday = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("重点标记", Modifier.weight(1f)); Switch(pinned, { pinned = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("写入系统日历", Modifier.weight(1f)); Switch(calendarSync, { calendarSync = it }) }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { viewModel.saveCountdown(existing?.id, title, date, repeat, includesToday, pinned, calendarSync); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun repeatName(raw: String?): String = when (raw) {
    "yearly" -> "每年"
    "monthly" -> "每月"
    "weekly" -> "每周"
    else -> "不重复"
}

private fun LocalDate.toKotlinDate() = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)
