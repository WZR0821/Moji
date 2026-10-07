package com.raydon.moji.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
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
    var scope by remember { mutableStateOf(if (viewModel.showFocus.value) "focus" else "upcoming") }
    val requestedFocus by viewModel.showFocus.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(requestedFocus) { if (requestedFocus) { scope = "focus"; viewModel.showFocus.value = false } }
    var more by remember { mutableStateOf(false) }
    val revision by viewModel.settingsRevision.collectAsStateWithLifecycle()
    val sort = remember(scope, revision) { viewModel.countdownSort(scope) }
    var reordering by remember(scope) { mutableStateOf(false) }
    var reorderIds by remember(scope) { mutableStateOf(emptyList<String>()) }
    var editing by remember { mutableStateOf<CountdownEvent?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(qaRoute, snapshot.countdowns.size) { if (!didQA && qaRoute.isNotEmpty() && snapshot.countdowns.isNotEmpty()) {
        didQA = true
        when (qaRoute) { "moment-editor", "moment-advanced" -> { editing = snapshot.countdowns.first(); showEditor = true }; "moment-past" -> scope = "past" }
    } }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val events = snapshot.countdowns.filter { event ->
        val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
        val occurrence = CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", today.toKotlinDate())
        if (scope == "upcoming") occurrence >= today.toKotlinDate() else occurrence < today.toKotlinDate()
    }.sortedWith(if (reordering) compareBy<CountdownEvent> { reorderIds.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        else if (sort == "manual") compareBy<CountdownEvent> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.createdAt }
        else compareBy<CountdownEvent> { val origin = Instant.parse(it.targetDate).atZone(zone).toLocalDate(); val next = LocalDate.parse(CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), it.repeatRule ?: "never", today.toKotlinDate()).toString()); kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, next)) }.thenBy { it.createdAt })
    MojiScreen {
        BoxWithConstraints {
        val focusHeight = (maxHeight - 150.dp).coerceAtLeast(480.dp)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            item {
                MojiLargeTitle(
                    title = "时刻",
                    actions = if (scope == "focus") emptyList() else listOf(
                        MojiIcon.Ellipsis to { more = true },
                        MojiIcon.Plus to { editing = null; showEditor = true },
                    ),
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    listOf("upcoming" to "倒数日", "past" to "纪念日", "focus" to "专注").forEach { (raw, label) ->
                        Column(
                            Modifier.weight(1f).height(38.dp).clickable { scope = raw },
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
                item { PomodoroPanel(viewModel, focusHeight) }
            } else {
                if (sort == "date" && events.isNotEmpty()) item { Text("已按时间排序 · 离今天最近的在前", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center) }
                if (reordering) item { Text("调整顺序", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { reordering = false }.padding(vertical = 6.dp), textAlign = TextAlign.Center) }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CountdownRow(event, { editing = event; showEditor = true }, { viewModel.toggleCountdownPin(event.id) }, { viewModel.deleteCountdown(event.id) }) }
                        if (reordering) {
                            var drag by remember(event.id) { mutableStateOf(0f) }
                            val density = androidx.compose.ui.platform.LocalDensity.current.density
                            Box(Modifier.size(40.dp).pointerInput(event.id) {
                                detectDragGestures(onDragStart = { drag = 0f }, onDragEnd = { viewModel.reorderCountdowns(reorderIds); drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                                    change.consume(); drag += amount.y
                                    val direction = if (drag > 48 * density) 1 else if (drag < -48 * density) -1 else 0
                                    if (direction != 0) { val position = reorderIds.indexOf(event.id); val target = position + direction; if (position >= 0 && target in reorderIds.indices) { val ids = reorderIds.toMutableList(); java.util.Collections.swap(ids, position, target); reorderIds = ids; drag = 0f } }
                                }
                            }, contentAlignment = Alignment.Center) { Text("≡", fontSize = 24.sp, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
        }
    }
    if (more) AlertDialog(onDismissRequest = { more = false }, title = { Text("排列方式") },
        text = { Column {
            FormRow("按时间排序", if (sort == "date") "✓" else "", onClick = { viewModel.setCountdownSort(scope, "date"); reordering = false; more = false })
            FormRow("手动排序", if (sort == "manual") "✓" else "", onClick = { viewModel.setCountdownSort(scope, "manual"); more = false })
            if (sort == "manual") FormRow("调整顺序", onClick = { reorderIds = events.map { it.id }; reordering = true; more = false })
        } },
        confirmButton = { TextButton(onClick = { more = false }) { Text("完成") } })
    if (showEditor) CountdownEditorScreenDialog(editing, viewModel, context = scope) { showEditor = false; editing = null }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CountdownRow(event: CountdownEvent, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
    val today = LocalDate.now()
    val occurrence = LocalDate.parse(CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", today.toKotlinDate()).toString())
    val rawDays = java.time.temporal.ChronoUnit.DAYS.between(today, occurrence).toInt()
    val days = CountdownPolicy.dayCount(occurrence.atStartOfDay(zone).toInstant().toString(), Instant.now().toString(), event.includesToday == true, zone.id)
    val urgent = rawDays in 0..2
    val color = if (urgent) mojiVermilion else MaterialTheme.colorScheme.onSurface
    val dateText = when (event.repeatRule) {
        "yearly" -> origin.format(DateTimeFormatter.ofPattern("M月d日")) + " · 每年"
        "monthly" -> "${origin.dayOfMonth} 日 · 每月"
        "weekly" -> origin.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.SIMPLIFIED_CHINESE)) + " · 每周"
        else -> origin.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
    val elapsed = if (origin < today) {
        val p = java.time.Period.between(origin, today); val total = java.time.temporal.ChronoUnit.DAYS.between(origin, today)
        if (p.years >= 1) "已经 ${p.years} 年" + (if (p.months > 0) " ${p.months} 个月" else "") + (if (p.years < 3 && p.days > 0) " ${p.days} 天" else "") + " · 共 $total 天"
        else if (p.months > 0) "已经 ${p.months} 个月" + (if (p.days > 0) " ${p.days} 天" else "") + " · 共 $total 天" else "已经 $total 天"
    } else null
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)) {
        Column(Modifier.fillMaxWidth()
            .background(if (urgent) MaterialTheme.colorScheme.surface.copy(alpha = 0.88f) else Color.Transparent, RoundedCornerShape(12.dp))
            .then(if (urgent) Modifier.border(BorderStroke(0.75.dp, mojiVermilion.copy(alpha = 0.22f)), RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onEdit, onLongClick = { menu = true })) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(event.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (event.isPinned) MojiLineIcon(MojiIcon.Pin, 9.dp, mojiVermilion)
                        if (event.calendarEventIdentifier != null) MojiLineIcon(MojiIcon.Calendar, 11.dp, MaterialTheme.colorScheme.secondary)
                    }
                    Text(dateText, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    if (elapsed != null && days >= 0) Text(elapsed, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
                if (urgent) InkSeal("急", 25)
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(if (days == 0) "今天" else if (days < 0) "正数" else "倒数", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (urgent) color else MaterialTheme.colorScheme.secondary)
                    Box {
                        InkAsset("InkHeaderWash", Modifier.align(Alignment.BottomCenter).width(70.dp).height(9.dp).alpha(if (urgent) 0.18f else 0.065f), tint = color)
                        if (days == 0) Text("今天", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)
                        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(kotlin.math.abs(days).toString(), fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = color)
                            Text("天", fontSize = 12.sp, color = color, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
            if (!urgent) InkDivider(opacity = 0.22f)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (event.isPinned) "取消重点" else "设为重点") }, { menu = false; onPin() })
            DropdownMenuItem({ Text("修改") }, { menu = false; onEdit() })
            DropdownMenuItem({ Text("删除") }, { menu = false; onDelete() })
        }
    }
}

@Composable
private fun PomodoroPanel(viewModel: MojiViewModel, availableHeight: androidx.compose.ui.unit.Dp) {
    val state by viewModel.pomodoro.collectAsStateWithLifecycle()
    val tick by viewModel.tickMillis.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val remaining = remember(state, tick) { viewModel.remainingSeconds(state) }
    val total = state.phaseDurationSeconds.coerceAtLeast(1)
    val progress = (1f - remaining.toFloat() / total).coerceIn(0f, 1f)
    val active = state.running || state.accumulatedSeconds > 0 || state.pendingNextPhase != null || state.linkedPlanId.isNotEmpty()
    val canChoose = !state.running && state.accumulatedSeconds == 0 && state.phase == "focus" && state.pendingNextPhase == null
    val canKeep = state.phase == "focus" && (state.accumulatedSeconds + if (state.running) (state.remainingSeconds - remaining).coerceAtLeast(0) else 0) > 0
    var planMenu by remember { mutableStateOf(false) }
    var endDialog by remember { mutableStateOf(false) }
    var durationPhase by remember { mutableStateOf(if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "focus-duration") "focus" else null) }
    var switchPhase by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().height(availableHeight).padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(252.dp), contentAlignment = Alignment.Center) {
            PomodoroInkRing(progress, active, state.running && viewModel.inkMotionLevel != "off", Modifier.fillMaxSize())
            if (state.completedPhase == "focus") Box(Modifier.align(Alignment.Center).padding(start = 168.dp, top = 168.dp)) { InkSeal("专", 32) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.completedPhase?.takeIf { state.pendingNextPhase != null }?.let { "${phaseName(it)}完成" } ?: phaseName(state.phase), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                Text(if (state.pendingNextPhase != null) "00:00" else "${(remaining / 60).toString().padStart(2, '0')}:${(remaining % 60).toString().padStart(2, '0')}", fontSize = 50.sp, fontWeight = FontWeight.Light)
                Box {
                    Row(Modifier.clickable(enabled = canChoose) { planMenu = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = if (state.pendingNextPhase != null) if (state.completedPhase == "focus") state.title else "休息结束" else if (state.phase == "focus") state.title else if (state.running) "休息中" else "轻触开始休息"
                        Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, maxLines = 1)
                        if (canChoose) Text("⌃⌄", fontSize = 8.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    DropdownMenu(planMenu, { planMenu = false }) {
                        DropdownMenuItem(text = { Text("不关联计划") }, onClick = { planMenu = false; viewModel.linkPomodoro(null) })
                        snapshot.checkInItems.filter { it.kind == "planned" && it.status == "planned" && it.isArchived != true && Instant.parse(it.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate() <= LocalDate.now() }.sortedBy { it.scheduledStart }.forEach { plan ->
                            DropdownMenuItem(text = { Text(plan.title) }, onClick = { planMenu = false; viewModel.linkPomodoro(plan) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("focus" to "专注", "shortBreak" to "短休息", "longBreak" to "长休息").filter { it.first != "longBreak" || viewModel.longBreakEnabled }.forEach { (raw, label) ->
                val selected = state.phase == raw && state.pendingNextPhase == null
                Surface(
                    modifier = Modifier.weight(1f).height(44.dp).clickable { if (!selected) { if (active) switchPhase = raw else viewModel.selectPomodoroPhase(raw) } },
                    shape = RoundedCornerShape(23.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0f else 0.14f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            MojiLineIcon(when (raw) { "shortBreak" -> MojiIcon.Cup; "longBreak" -> MojiIcon.Leaf; else -> MojiIcon.Timer }, 14.dp, if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                            Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
        if (state.pendingNextPhase != null) {
            Text("轻触中间开始${phaseName(state.pendingNextPhase!!)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        } else if (state.running) {
            Text("进行中 · 预计 ${Instant.ofEpochMilli(state.targetEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} 结束", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                DurationCell("专注", "focus", viewModel.durationForPhase("focus") / 60, viewModel, Modifier.weight(1f)) { durationPhase = "focus" }
                Box(Modifier.width(0.6.dp).height(28.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
                DurationCell("短休", "shortBreak", viewModel.durationForPhase("shortBreak") / 60, viewModel, Modifier.weight(1f)) { durationPhase = "shortBreak" }
                if (viewModel.longBreakEnabled) {
                    Box(Modifier.width(0.6.dp).height(28.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)))
                    DurationCell("长休", "longBreak", viewModel.durationForPhase("longBreak") / 60, viewModel, Modifier.weight(1f)) { durationPhase = "longBreak" }
                }
            }
            Text("点击时长调整", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerControl(MojiIcon.Stop, "结束", mojiVermilion, active) { endDialog = true }
            TimerControl(if (state.running) MojiIcon.Pause else MojiIcon.Play, state.pendingNextPhase?.let { "开始${phaseName(it)}" } ?: if (state.running) "暂停" else "开始", MaterialTheme.colorScheme.onSurface, true) {
                if (state.running) viewModel.pausePomodoro() else viewModel.startPomodoro()
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
    }
    if (endDialog) AlertDialog(
        onDismissRequest = { endDialog = false },
        title = { Text("结束当前${phaseName(state.phase)}？") },
        text = { Text(if (canKeep) "你可以把已经专注的时间写入记录，也可以直接丢弃。关联计划不会被标记为完成。" else "结束后会停止计时并清除锁屏显示。") },
        confirmButton = { TextButton(onClick = { endDialog = false; viewModel.finishPomodoro(canKeep) }) { Text(if (canKeep) "结束并保留记录" else "结束本轮") } },
        dismissButton = { Column { if (canKeep) TextButton(onClick = { endDialog = false; viewModel.finishPomodoro(false) }) { Text("结束且不保留") }; TextButton(onClick = { endDialog = false }) { Text("取消") } } },
    )
    switchPhase?.let { target -> AlertDialog(onDismissRequest = { switchPhase = null }, title = { Text("切换到${phaseName(target)}？") },
        text = { Text(if (canKeep) "当前专注会结束，可选择是否保留本轮记录。" else "当前阶段会结束，随后切换到所选阶段。") },
        confirmButton = { TextButton(onClick = { switchPhase = null; viewModel.switchPomodoroPhase(target, canKeep) }) { Text(if (canKeep) "保留本轮记录并切换" else "结束当前阶段并切换") } },
        dismissButton = { Column { if (canKeep) TextButton(onClick = { switchPhase = null; viewModel.switchPomodoroPhase(target, false) }) { Text("不保留记录并切换") }; TextButton(onClick = { switchPhase = null }) { Text("取消") } } }) }
    durationPhase?.let { phase ->
        PomodoroDurationDialog(phase, viewModel.durationForPhase(phase) / 60, viewModel) { durationPhase = null }
    }
}

@Composable
private fun DurationCell(label: String, phase: String, minutes: Int, viewModel: MojiViewModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
    Column(Modifier.fillMaxWidth().clickable { menu = true }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
        Text("$minutes 分⌄", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
    DropdownMenu(menu, { menu = false }) {
        phasePresets(phase).forEach { preset -> DropdownMenuItem({ Text("${if (preset == minutes) "✓ " else ""}$preset 分钟") }, { menu = false; viewModel.setPhaseMinutes(phase, preset) }) }
        HorizontalDivider()
        DropdownMenuItem({ Text("自定义…") }, { menu = false; onClick() })
    }
    }
}

fun phasePresets(phase: String): List<Int> = when (phase) { "shortBreak" -> listOf(5, 10, 15); "longBreak" -> listOf(15, 20, 30); else -> listOf(25, 30, 45, 60) }

@Composable
private fun TimerControl(icon: MojiIcon, label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 88.dp else 64.dp).alpha(if (enabled) 1f else 0.34f).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 56.dp else 44.dp), contentAlignment = Alignment.Center) {
            InkAsset("InkIconLoop", Modifier.fillMaxSize().alpha(0.72f), tint = color)
            MojiLineIcon(icon, if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 24.dp else 18.dp, color)
        }
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}


private fun repeatName(raw: String?): String = when (raw) {
    "yearly" -> "每年"
    "monthly" -> "每月"
    "weekly" -> "每周"
    else -> "不重复"
}

fun LocalDate.toKotlinDate() = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)
