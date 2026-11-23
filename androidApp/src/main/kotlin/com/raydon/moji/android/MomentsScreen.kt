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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.CountdownPolicy
import kotlinx.coroutines.launch
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
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    fun daysUntil(event: CountdownEvent): Long {
        val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
        val next = LocalDate.parse(CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", LocalDate.now().toKotlinDate()).toString())
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), next)
    }
    val aiReady = rememberAIReady()
    val offerScope = androidx.compose.runtime.rememberCoroutineScope()
    // A day that was just added and is far enough off to prepare for.
    var preparationOffer by remember { mutableStateOf<CountdownEvent?>(null) }
    var preparing by remember { mutableStateOf<CountdownEvent?>(null) }
    // Offered once, right after the day is added, and only when there is time to prepare.
    // Nothing is drafted until the strip is tapped.
    fun offerPreparation(event: CountdownEvent) {
        if (!com.raydon.moji.android.ai.AISettingsStore.shared.isReady || daysUntil(event) < 2) return
        offerScope.launch { kotlinx.coroutines.delay(450); preparationOffer = event }
    }
    androidx.compose.runtime.LaunchedEffect(qaRoute, snapshot.countdowns.size) { if (!didQA && qaRoute.isNotEmpty() && snapshot.countdowns.isNotEmpty()) {
        didQA = true
        when (qaRoute) { "moment-editor", "moment-advanced" -> { editing = snapshot.countdowns.first(); showEditor = true }; "moment-past" -> scope = "past" }
        if (BuildConfig.DEBUG && qaRoute.startsWith("ai-preparation")) {
            snapshot.countdowns.filter { daysUntil(it) >= 2 }.minByOrNull { daysUntil(it) }?.let { upcoming ->
                kotlinx.coroutines.delay(350)
                if (qaRoute == "ai-preparation-drafts") preparing = upcoming else preparationOffer = upcoming
            }
        }
    } }
    val events = snapshot.countdowns.filter { event ->
        val origin = Instant.parse(event.targetDate).atZone(zone).toLocalDate()
        val occurrence = CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), event.repeatRule ?: "never", today.toKotlinDate())
        if (scope == "upcoming") occurrence >= today.toKotlinDate() else occurrence < today.toKotlinDate()
    }.sortedWith(if (reordering) compareBy<CountdownEvent> { reorderIds.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        else if (sort == "manual") compareBy<CountdownEvent> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.createdAt }
        else compareBy<CountdownEvent> { val origin = Instant.parse(it.targetDate).atZone(zone).toLocalDate(); val next = LocalDate.parse(CountdownPolicy.nextOccurrenceDate(origin.toKotlinDate(), it.repeatRule ?: "never", today.toKotlinDate()).toString()); kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, next)) }.thenBy { it.createdAt })
    MojiScreen {
        if (scope == "focus") {
            // iOS keeps the mode cards fixed; PomodoroView's GeometryReader gets only the space below them.
            Column(Modifier.fillMaxSize()) {
                MomentsTitle(scope, { more = true }, { editing = null; showEditor = true })
                MomentsModeRow(scope) { scope = it }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val available = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PomodoroPanel(viewModel, available) }
                }
            }
        } else {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            item { MomentsTitle(scope, { more = true }, { editing = null; showEditor = true }) }
            item { MomentsModeRow(scope) { scope = it } }
            val offer = preparationOffer
            if (scope == "upcoming" && offer != null && aiReady) item(key = "preparation-offer") {
                PreparationOfferStrip(offer.title, daysUntil(offer), accept = { preparing = offer; preparationOffer = null }, dismiss = { preparationOffer = null },
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp).animateItem())
            }
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
    if (showEditor) CountdownEditorScreenDialog(editing, viewModel, context = scope, onCreated = { offerPreparation(it) }) { showEditor = false; editing = null }
    preparing?.let { event ->
        PlanDraftSheet(viewModel, "准备计划", "为“${event.title}”做准备，还有 ${daysUntil(event)} 天", waitingText = "正在拟准备计划",
            planNote = "为“${event.title}”做准备",
            origin = com.raydon.moji.android.ai.AIWriteText.preparationOrigin(event.title),
            summary = { count, _ -> com.raydon.moji.android.ai.AIWriteText.preparation(count, event.title) },
            load = { com.raydon.moji.android.ai.AIAssistant.shared.draftPreparation(event, aiCategories(viewModel)) }) { preparing = null }
    }
}

@Composable
private fun MomentsTitle(scope: String, onMore: () -> Unit, onAdd: () -> Unit) {
    MojiLargeTitle(
        title = "时刻",
        actions = if (scope == "focus") emptyList() else listOf(
            MojiIcon.Ellipsis to onMore,
            MojiIcon.Plus to onAdd,
        ),
        // iOS nav bar ends at 150pt and the mode cards add .padding(.top, 5): row 155–193pt.
        bottomPadding = 3.5.dp,
    )
}

/** iOS modeCards: VStack(spacing 3) { subheadline; ZStack(height 5) + InkBrushDivider(height 7) width 42 }, frame height 38. */
@Composable
private fun MomentsModeRow(scope: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 5.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        listOf("upcoming" to "倒数日", "past" to "纪念日", "focus" to "专注").forEach { (raw, label) ->
            Column(
                Modifier.weight(1f).height(38.dp).clickable { onSelect(raw) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, fontSize = 15.sp, fontWeight = if (scope == raw) FontWeight.SemiBold else FontWeight.Normal, color = if (scope == raw) MaterialTheme.colorScheme.onSurface else iosSecondaryLabel)
                Box(Modifier.padding(top = 3.dp).width(42.dp).height(7.dp)) {
                    if (scope == raw) InkDivider(opacity = 1f)
                }
            }
        }
    }
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
                        if (event.isPinned) MojiLineIcon(MojiIcon.Pin, 12.dp, mojiVermilion)
                        if (event.calendarEventIdentifier != null) MojiLineIcon(MojiIcon.Calendar, 11.dp, iosSecondaryLabel)
                    }
                    // iOS .secondary inside the plain list button resolves to primary at 50%: (123,122,118) on paper.
                    Text(dateText, fontSize = 12.sp, color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f))
                    if (elapsed != null && days >= 0) Text(elapsed, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
                if (urgent) InkSeal("急", 25)
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(if (days == 0) "今天" else if (days < 0) "正数" else "倒数", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (urgent) color else iosSecondaryLabel)
                    Box(contentAlignment = Alignment.BottomEnd) {
                        // SwiftUI .background(alignment: .bottom): the wash takes the number's width, 9pt high, 5pt low.
                        InkAsset("InkHeaderWash", Modifier.matchParentSize().wrapContentHeight(Alignment.Bottom).height(9.dp).offset(y = 5.dp).alpha(if (urgent) 0.18f else 0.065f), tint = color)
                        if (days == 0) Text("今天", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)
                        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(kotlin.math.abs(days).toString(), fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = color)
                            Text("天", fontSize = 12.sp, color = color, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
        if (!urgent) InkDivider(Modifier.align(Alignment.BottomStart), opacity = 0.22f, seed = inkSeed(event.id.uppercase()))
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
    // SwiftUI VStack(spacing 20) { Spacer(min 20); ring 252; phases 44; 60; controls 76; Spacer(min 30) } with minHeight = available.
    val rigid = 252.dp + 44.dp + 60.dp + 76.dp + 20.dp * 5
    val extra = availableHeight - rigid
    val topSpace = if (extra >= 60.dp) extra / 2 else if (extra >= 50.dp) extra - 30.dp else 20.dp
    val bottomSpace = if (extra >= 60.dp) extra / 2 else 30.dp
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(topSpace + 20.dp))
        Box(Modifier.size(252.dp), contentAlignment = Alignment.Center) {
            PomodoroInkRing(progress, active, state.running && viewModel.inkMotionLevel != "off", Modifier.fillMaxSize())
            if (state.completedPhase == "focus") Box(Modifier.align(Alignment.Center).padding(start = 168.dp, top = 168.dp)) { InkSeal("专", 32) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.completedPhase?.takeIf { state.pendingNextPhase != null }?.let { "${phaseName(it)}完成" } ?: phaseName(state.phase), color = iosSecondaryLabel, fontSize = 12.sp)
                Text(if (state.pendingNextPhase != null) "00:00" else "${(remaining / 60).toString().padStart(2, '0')}:${(remaining % 60).toString().padStart(2, '0')}", fontSize = 50.sp, fontWeight = FontWeight.Light)
                Box {
                    Row(Modifier.clickable(enabled = canChoose) { planMenu = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = if (state.pendingNextPhase != null) if (state.completedPhase == "focus") state.title else "休息结束" else if (state.phase == "focus") state.title else if (state.running) "休息中" else "轻触开始休息"
                        // The Menu label is tinted: .secondary resolves to planPrimary at 50% (141,139,134).
                        val menuInk = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        Text(label, color = menuInk, fontSize = 12.sp, maxLines = 1)
                        if (canChoose) UpDownChevron(menuInk)
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
                            MojiLineIcon(when (raw) { "shortBreak" -> MojiIcon.Cup; "longBreak" -> MojiIcon.Leaf; else -> MojiIcon.TimerSF }, if (raw == "focus") 16.dp else 14.dp, if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
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
            Text("进行中 · 预计 ${Instant.ofEpochMilli(state.targetEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} 结束", color = iosSecondaryLabel, fontSize = 13.sp)
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
            Text("点击时长调整", color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.92f), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
        }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.heightIn(min = 76.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerControl(MojiIcon.Stop, "结束", mojiVermilion, active) { endDialog = true }
            TimerControl(if (state.running) MojiIcon.Pause else MojiIcon.Play, state.pendingNextPhase?.let { "开始${phaseName(it)}" } ?: if (state.running) "暂停" else "开始", MaterialTheme.colorScheme.onSurface, true) {
                if (state.running) viewModel.pausePomodoro() else viewModel.startPomodoro()
            }
        }
        Spacer(Modifier.height(20.dp + bottomSpace))
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
        val current by viewModel.pomodoro.collectAsStateWithLifecycle()
        Text(label, color = if (current.phase == phase) MaterialTheme.colorScheme.primary else iosSecondaryLabel, fontSize = 11.sp)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$minutes 分", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.size(width = 7.dp, height = 16.dp), contentAlignment = Alignment.Center) { Box(Modifier.requiredSize(16.dp).rotate(90f)) { MojiLineIcon(MojiIcon.Disclosure, 16.dp, MaterialTheme.colorScheme.primary) } }
        }
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
    Column(Modifier.width(if (icon == MojiIcon.Play || icon == MojiIcon.Pause) 88.dp else 64.dp).alpha(if (enabled) 1f else 0.18f).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        // iOS seeds the stop medallion 2 and the start medallion 0 (artwork, angle, scale, opacity).
        val primaryControl = icon == MojiIcon.Play || icon == MojiIcon.Pause
        InkBrushMedallion(icon, color, if (primaryControl) 56.dp else 44.dp, seed = if (primaryControl) 0 else 2, iconSize = if (primaryControl) 28.dp else 22.dp)
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

/** SF chevron.up.chevron.down at 8pt bold. */
@Composable
private fun UpDownChevron(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 6.dp, height = 10.dp)) {
        val u = size.width / 6f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.4f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0.9f*u, 3.8f*u); lineTo(3f*u, 1.4f*u); lineTo(5.1f*u, 3.8f*u) }, color, style = stroke)
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0.9f*u, 6.2f*u); lineTo(3f*u, 8.6f*u); lineTo(5.1f*u, 6.2f*u) }, color, style = stroke)
    }
}
