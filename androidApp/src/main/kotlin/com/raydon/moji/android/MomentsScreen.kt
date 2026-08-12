package com.raydon.moji.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.CountdownPolicy
import com.raydon.moji.ui.InkCard
import com.raydon.moji.ui.MojiPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        if (scope == "upcoming") origin >= today || event.repeatRule != null && event.repeatRule != "never" else origin < today
    }
    MojiScreen {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            item {
                MojiLargeTitle(
                    title = "时刻",
                    actions = if (scope == "focus") emptyList() else listOf(
                        MojiIcon.Ellipsis to {},
                        MojiIcon.Plus to { editing = null; showEditor = true },
                    ),
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    listOf("upcoming" to "倒数日", "past" to "纪念日", "focus" to "专注").forEach { (raw, label) ->
                        Column(
                            Modifier.weight(1f).height(46.dp).clickable { scope = raw },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(label, fontSize = 15.sp, fontWeight = if (scope == raw) FontWeight.SemiBold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (scope == raw) 1f else 0.50f))
                            Box(Modifier.width(42.dp).height(7.dp).padding(top = 2.dp)) {
                                if (scope == raw) InkDivider(opacity = 0.62f)
                            }
                        }
                    }
                }
            }
            if (scope == "focus") {
                item { PomodoroPanel(viewModel) }
            } else {
                if (events.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                            InkAsset("InkFocusBloom", Modifier.fillMaxSize().alpha(0.10f))
                            MojiLineIcon(MojiIcon.Timer, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                        }
                        Text(if (scope == "upcoming") "还没有要倒数的日期" else "还没有纪念的日期", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (scope == "upcoming") "未来的日期会出现在这里，不足三天时以朱红标记。" else "日期过去之后会自动移到这里，改为正数计日。", modifier = Modifier.padding(horizontal = 34.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
                items(events.size, key = { events[it].id }) { index ->
                    val event = events[index]
                    CountdownRow(event, scope == "upcoming", { editing = event; showEditor = true }, { viewModel.toggleCountdownPin(event.id) }, { viewModel.deleteCountdown(event.id) })
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showEditor) CountdownEditorDialog(editing, viewModel) { showEditor = false; editing = null }
}

@Composable
private fun CountdownRow(
    event: CountdownEvent,
    upcomingScope: Boolean,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
    val today = LocalDate.now()
    val effective = CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", today.toKotlinDate())
    val effectiveJava = LocalDate.of(effective.year, effective.month.ordinal + 1, effective.day)
    val days = if (upcomingScope) java.time.temporal.ChronoUnit.DAYS.between(today, effectiveJava).toInt() + if (event.includesToday == true) 1 else 0
    else java.time.temporal.ChronoUnit.DAYS.between(origin, today).toInt() + if (event.includesToday == true) 1 else 0
    InkCard(Modifier.padding(horizontal = 18.dp, vertical = 7.dp).clickable(onClick = onEdit)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isPinned) MojiLineIcon(MojiIcon.Pin, 16.dp, MojiPalette.Vermilion)
                    Text(event.title, modifier = Modifier.padding(start = if (event.isPinned) 8.dp else 0.dp), style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (days == 0 && upcomingScope) "今天" else days.toString(), fontSize = 34.sp, fontWeight = FontWeight.Light, color = if (upcomingScope && days <= 3) MojiPalette.Vermilion else MaterialTheme.colorScheme.onSurface)
                    if (days != 0 || !upcomingScope) Text(if (upcomingScope) " 天后" else " 天前", modifier = Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), fontSize = 14.sp)
                }
                Text("${origin.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))} · ${repeatName(event.repeatRule)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f))
            }
            Box {
                TextButton(onClick = { menu = true }) { MojiLineIcon(MojiIcon.Ellipsis, 22.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)) }
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
    val total = if (state.phase == "focus") snapshot.checkInItems.firstOrNull { it.id == state.linkedPlanId }?.plannedMinutes?.times(60) ?: viewModel.durationForPhase(state.phase) else viewModel.durationForPhase(state.phase)
    var planMenu by remember { mutableStateOf(false) }
    var endDialog by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(252.dp), contentAlignment = Alignment.Center) {
            InkRing(Modifier.fillMaxSize().alpha(if (state.running) 0.88f else 0.14f), active = state.running)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(phaseName(state.phase), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 14.sp)
                Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", fontSize = 50.sp, fontWeight = FontWeight.Light)
                Box {
                    TextButton(onClick = { planMenu = true }, enabled = !state.running && state.phase == "focus") {
                        Text(state.title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 14.sp)
                    }
                    DropdownMenu(planMenu, { planMenu = false }) {
                        DropdownMenuItem(text = { Text("不关联计划") }, onClick = { planMenu = false; viewModel.linkPomodoro(null) })
                        snapshot.checkInItems.filter { it.status == "planned" || it.status == "inProgress" }.take(20).forEach { plan ->
                            DropdownMenuItem(text = { Text(plan.title) }, onClick = { planMenu = false; viewModel.linkPomodoro(plan) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("focus" to "专注", "shortBreak" to "短休息", "longBreak" to "长休息").forEach { (raw, label) ->
                val selected = state.phase == raw
                Surface(
                    modifier = Modifier.weight(1f).height(44.dp).clickable(enabled = !state.running) { viewModel.selectPomodoroPhase(raw) },
                    shape = RoundedCornerShape(23.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0f else 0.14f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.running) {
            Text("进行中 · 结束后可调整时长", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 13.sp)
        } else {
            Row(Modifier.fillMaxWidth()) {
                DurationCell("专注", viewModel.durationForPhase("focus") / 60, Modifier.weight(1f))
                Box(Modifier.width(0.6.dp).height(42.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
                DurationCell("短休", viewModel.durationForPhase("shortBreak") / 60, Modifier.weight(1f))
                Box(Modifier.width(0.6.dp).height(42.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
                DurationCell("长休", viewModel.durationForPhase("longBreak") / 60, Modifier.weight(1f))
            }
            Text("点击时长调整", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerControl(MojiIcon.Stop, "结束", MojiPalette.Vermilion, state.running || state.accumulatedSeconds > 0) { endDialog = true }
            TimerControl(if (state.running) MojiIcon.Pause else MojiIcon.Play, if (state.running) "暂停" else "开始", MaterialTheme.colorScheme.onSurface, true) {
                if (state.running) viewModel.pausePomodoro() else viewModel.startPomodoro()
            }
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
private fun DurationCell(label: String, minutes: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp)
        Text("$minutes 分⌄", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun TimerControl(icon: MojiIcon, label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).alpha(if (enabled) 1f else 0.28f).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 56.dp else 44.dp), contentAlignment = Alignment.Center) {
            InkAsset("InkIconLoop", Modifier.fillMaxSize().alpha(0.72f), tint = color)
            MojiLineIcon(icon, if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 24.dp else 18.dp, color)
        }
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
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
        title = { Text(if (existing == null) "添加这个日子" else "编辑这个日子") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { picker(date) }) { Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("never" to "不重复", "yearly" to "每年", "monthly" to "每月", "weekly" to "每周").forEach { (raw, label) -> FilterChip(repeat == raw, { repeat = raw }, { Text(label) }) }
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
