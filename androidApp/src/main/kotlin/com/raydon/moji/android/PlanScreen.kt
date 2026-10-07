package com.raydon.moji.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.ChecklistPolicy
import com.raydon.moji.ui.InkCard
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun PlanScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val tick by viewModel.tickMillis.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorKind by remember { mutableStateOf("planned") }
    var showCalendar by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf<CheckInItem?>(null) }
    var quickTitle by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showsCompleted by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, snapshot.checkInItems.size) {
        if (!didQA && qaRoute.isNotEmpty() && snapshot.checkInItems.isNotEmpty()) {
            didQA = true
            val sample = snapshot.checkInItems.firstOrNull { it.title == "整理 1.5.1 发布说明" }
            when (qaRoute) {
                "library", "templates", "library-batch" -> showLibrary = true
                "plan-new" -> showEditor = true
                "calendar-month", "calendar-week", "calendar-day", "calendar-jump" -> showCalendar = true
                "plan-editor", "plan-editor-advanced" -> { editing = sample; showEditor = true }
                "plan-expanded", "plan-expanded-bottom", "plan-inline" -> expandedId = sample?.id
                "plan-completed" -> showsCompleted = true
                "plan-copy" -> copying = sample
            }
        }
    }
    if (showCalendar) {
        androidx.activity.compose.BackHandler { showCalendar = false }
        PlanCalendarScreen(viewModel, LocalDate.now()) { showCalendar = false }
        return
    }
    val now = Instant.ofEpochMilli(tick)
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val pending = snapshot.checkInItems.filter {
        it.kind == "planned" && it.isArchived != true && it.status in listOf("planned", "inProgress") &&
            ChecklistPolicy.isVisible(it.scheduledStart, it.status, now.toString(), viewModel.carriesOver, zone.id)
    }.sortedWith(compareBy<CheckInItem> { it.scheduledStart }.thenBy { it.createdAt })
    val completed = snapshot.checkInItems.filter {
        it.kind == "planned" && it.isArchived != true && it.status in listOf("completed", "skipped") &&
            Instant.parse(it.completedAt ?: it.scheduledStart).atZone(zone).toLocalDate() == today
    }.sortedByDescending { it.completedAt ?: it.createdAt }
    val total = pending.size + completed.size
    val toolbarPixels = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }
    LaunchedEffect(qaRoute, expandedId, pending.size) {
        if (BuildConfig.DEBUG && qaRoute == "plan-expanded-bottom" && expandedId != null) {
            kotlinx.coroutines.delay(600)
            val index = pending.indexOfFirst { it.id == expandedId }
            if (index >= 0) listState.scrollToItem(3 + index + if (snapshot.activeSession != null || timer.running || timer.accumulatedSeconds > 0) 1 else 0, -toolbarPixels)
        }
    }
    val openLibrary = { addMenu = false; showLibrary = true }
    val openRecord = { addMenu = false; editing = null; editorKind = "completed"; showEditor = true }
    val openPlan = { addMenu = false; editing = null; editorKind = "planned"; showEditor = true }
    MojiScreen {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Box {
                    MojiLargeTitle("计划", MojiIcon.Calendar, { showCalendar = true }, listOf(MojiIcon.Plus to { addMenu = true }), bottomPadding = 0.dp)
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 40.dp)) {
                        PlanAddMenu(addMenu && listState.firstVisibleItemIndex == 0, { addMenu = false }, openLibrary, openRecord, openPlan)
                    }
                }
            }
            item {
                InkCard(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 7.dp), padding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.text.BasicTextField(quickTitle, { quickTitle = it }, Modifier.weight(1f),
                            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (quickTitle.isEmpty()) Text("写下一件要做的事", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
                        Box(Modifier.size(44.dp).clickable(enabled = quickTitle.isNotBlank()) {
                            viewModel.savePlan(title = quickTitle, note = "", date = today, time = LocalTime.MIDNIGHT,
                                scheduleKind = "allDay", category = viewModel.defaultCategory, plannedMinutes = 25,
                                repeatRule = "never", weekdays = emptyList(), reminderMinutes = null, calendarSync = false, plannedDurationEnabled = false)
                            quickTitle = ""
                        }, contentAlignment = Alignment.Center) {
                            InkAsset("InkIconLoop", Modifier.size(36.dp).alpha(if (quickTitle.isBlank()) 0.18f else 0.65f))
                            MojiLineIcon(MojiIcon.Plus, 18.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (quickTitle.isBlank()) 0.22f else 1f))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp).heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkSeal("今", 25)
                    Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("今日计划", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.weight(1f))
                    InkDivider(Modifier.width(44.dp), 0.26f, inkSeed("dashboard-today-heading"))
                    Text(if (total == 0) "今日无计划" else "完成 ${completed.size}/$total", Modifier.padding(start = 12.dp).widthIn(min = 78.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (total == 0) 0.5f else 1f))
                }
            }
            if (snapshot.activeSession != null || timer.running || timer.accumulatedSeconds > 0) item {
                InkCard(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { viewModel.openFocus() }, verticalAlignment = Alignment.CenterVertically) {
                        MojiLineIcon(MojiIcon.Timer, 22.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(snapshot.activeSession?.title ?: timer.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (timer.running) "正在专注 · ${viewModel.remainingSeconds() / 60}:${(viewModel.remainingSeconds() % 60).toString().padStart(2, '0')}" else "专注已暂停", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        MojiLineIcon(MojiIcon.ChevronRight, 16.dp)
                    }
                }
            }
            if (pending.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 68.dp, bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        InkAsset("InkFocusBloom", Modifier.fillMaxSize().alpha(0.10f))
                        MojiLineIcon(MojiIcon.Checklist, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                    }
                    Text("今天留白", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("写下一件要做的事，让今天有一个落点。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 5.dp))
                }
            }
            items(pending, key = { "pending-" + it.id }) { item ->
                PlanRow(item, today, expandedId == item.id, false, viewModel,
                    { expandedId = if (expandedId == item.id) null else item.id },
                    { editing = item; showEditor = true }, { copying = item })
            }
            if (completed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showsCompleted = !showsCompleted }.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("今日已归档 ${completed.size}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.weight(1f))
                        MojiChevronDown(showsCompleted)
                    }
                }
                if (showsCompleted) items(completed, key = { "completed-" + it.id }) { item ->
                    PlanRow(item, today, expandedId == item.id, true, viewModel, { expandedId = if (expandedId == item.id) null else item.id },
                        { editing = item; showEditor = true }, { copying = item })
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
        if (listState.firstVisibleItemIndex > 0) {
            Row(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { showCalendar = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Calendar, 25.dp) }
                Text("计划", modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box {
                    Box(Modifier.size(44.dp).clickable { addMenu = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Plus, 23.dp) }
                    PlanAddMenu(addMenu, { addMenu = false }, openLibrary, openRecord, openPlan)
                }
            }
        }
        }
    }
    if (showEditor) PlanEditorScreenDialog(editing, today, viewModel, initialKind = editorKind) { showEditor = false; editing = null }
    if (showLibrary) PlanLibraryDialog(viewModel) { showLibrary = false }
    copying?.let { PlanCopyDialog(it, viewModel) { copying = null } }
}

@Composable
private fun PlanAddMenu(expanded: Boolean, onDismiss: () -> Unit, onLibrary: () -> Unit, onRecord: () -> Unit, onPlan: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("计划库与模板") }, onLibrary)
        DropdownMenuItem({ Text("记录已做") }, onRecord)
        DropdownMenuItem({ Text("详细添加计划") }, onPlan)
    }
}

@Composable
fun InkSeal(character: String, size: Int = 25, style: Int = if (character == "今") 1 else if (character == "注") 2 else if (character == "专") 4 else 0) {
    val color = mojiVermilion
    val numeric = character.firstOrNull()?.isDigit() == true
    val context = LocalContext.current
    val typeface = remember(context, numeric) {
        if (numeric) {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            else android.graphics.Typeface.DEFAULT_BOLD
        } else androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jfzskseal) ?: android.graphics.Typeface.DEFAULT
    }
    val glyphPaint = remember(typeface) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = !numeric
        }
    }
    val glyphBounds = remember { android.graphics.Rect() }
    Box(Modifier.size(size.dp).rotate(if (numeric) -4f else if (character == "今") -7f else -3.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = this.size.minDimension; val width = maxOf(0.8.dp.toPx(), u * 0.055f)
            val ink = color.copy(alpha = 0.88f)
            val top = androidx.compose.ui.geometry.Offset(width, width)
            val bounds = androidx.compose.ui.geometry.Size(this.size.width - width*2, this.size.height-width*2)
            when (style) {
                1 -> {
                    drawRoundRect(ink, top, bounds, androidx.compose.ui.geometry.CornerRadius(u*0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(width))
                    drawRoundRect(color.copy(alpha=0.45f), top+androidx.compose.ui.geometry.Offset(u*0.12f,u*0.12f), androidx.compose.ui.geometry.Size(bounds.width-u*0.24f,bounds.height-u*0.24f), androidx.compose.ui.geometry.CornerRadius(u*0.035f), style=androidx.compose.ui.graphics.drawscope.Stroke(maxOf(0.55.dp.toPx(),width*0.58f)))
                }
                2, 4 -> {
                    val dx = if (style == 4) u*0.08f else u*0.015f; val dy=u*0.015f
                    drawOval(ink, top+androidx.compose.ui.geometry.Offset(dx,dy), androidx.compose.ui.geometry.Size(bounds.width-dx*2,bounds.height-dy*2), style=androidx.compose.ui.graphics.drawscope.Stroke(width))
                    val inset=if (style==4) u*0.10f else u*0.095f
                    drawOval(color.copy(alpha=0.28f), top+androidx.compose.ui.geometry.Offset(dx+inset,dy+inset), androidx.compose.ui.geometry.Size(bounds.width-(dx+inset)*2,bounds.height-(dy+inset)*2), style=androidx.compose.ui.graphics.drawscope.Stroke(maxOf(0.5.dp.toPx(),width*0.48f)))
                }
                else -> {
                    val left=width;val right=this.size.width-width;val bottom=this.size.height-width
                    val p=androidx.compose.ui.graphics.Path();p.moveTo(left+u*0.08f,width);p.lineTo(right-u*0.04f,width+u*0.02f);p.lineTo(right,width+u*0.09f);p.lineTo(right-u*0.01f,bottom-u*0.03f);p.lineTo(right-u*0.08f,bottom);p.lineTo(left+u*0.03f,bottom-u*0.01f);p.lineTo(left,bottom-u*0.10f);p.lineTo(left+u*0.01f,width+u*0.04f);p.close()
                    drawPath(p,ink,style=androidx.compose.ui.graphics.drawscope.Stroke(width,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
            if (character.isNotBlank()) {
                glyphPaint.color = color.toArgb()
                // A seal is fixed-size artwork, like iOS's fixedSize font.
                glyphPaint.textSize = u * if (numeric) 0.52f else 0.45f
                glyphPaint.getTextBounds(character, 0, character.length, glyphBounds)
                val available = u * if (style == 1) 0.62f else 0.72f
                val scale = minOf(1f, available / glyphBounds.width().coerceAtLeast(1), available / glyphBounds.height().coerceAtLeast(1))
                if (scale < 1f) {
                    glyphPaint.textSize *= scale
                    glyphPaint.getTextBounds(character, 0, character.length, glyphBounds)
                }
                // Center the painted glyph, not its advance width or line box:
                // the seal font's asymmetric bearings shift a centered Text.
                val x = this.size.width / 2 - glyphBounds.exactCenterX()
                val baseline = this.size.height / 2 - glyphBounds.exactCenterY()
                drawContext.canvas.nativeCanvas.drawText(character, x, baseline, glyphPaint)
            }
        }
    }
}

@Composable
fun MojiChevronDown(expanded: Boolean) {
    Box(Modifier.size(20.dp).rotate(if (expanded) 180f else 0f).rotate(90f), contentAlignment = Alignment.Center) {
        MojiLineIcon(MojiIcon.ChevronRight, 11.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(plan: CheckInItem, today: LocalDate, expanded: Boolean, archived: Boolean, viewModel: MojiViewModel,
                    toggleExpansion: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var inline by remember { mutableStateOf(false) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didInlineQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, expanded) {
        if (BuildConfig.DEBUG && !didInlineQA && expanded && qaRoute == "plan-inline" && plan.title == "整理 1.5.1 发布说明") {
            didInlineQA = true
            inline = true
        }
    }
    var title by remember(plan.title) { mutableStateOf(plan.title) }
    var note by remember(plan.note) { mutableStateOf(plan.note) }
    val inlineFocus = remember { FocusRequester() }
    LaunchedEffect(inline, expanded) { if (inline && expanded) inlineFocus.requestFocus() }
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val finished = plan.status in listOf("completed", "skipped")
    val date = Instant.parse(plan.scheduledStart).atZone(ZoneId.systemDefault())
    val schedule = scheduleOptions.firstOrNull { it.first == plan.scheduleKind && it.first != "exactTime" }?.second ?: date.format(DateTimeFormatter.ofPattern("HH:mm"))
    val timing = schedule + if (plan.plannedDurationEnabled == true) " · 预计 ${plan.plannedMinutes} 分钟" else ""
    val daysLate = ChronoUnit.DAYS.between(date.toLocalDate(), today).coerceAtLeast(0)
    val content: @Composable () -> Unit = {
        Column {
            Box {
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggleExpansion, onLongClick = { menu = true }).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(44.dp).clickable { viewModel.togglePlan(plan.id) }, contentAlignment = Alignment.Center) {
                        if (finished) {
                            Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                                Text(if (plan.status == "skipped") "−" else "✓", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.surface)
                            }
                            InkAsset("InkIconLoop", Modifier.requiredSize(54.dp).alpha(0.78f))
                            InkAsset("InkCheckMark", Modifier.size(20.dp, 17.dp))
                        } else {
                            Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box {
                            Text(plan.title, fontSize = 17.sp, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (plan.status == "completed") 0.5f else 1f))
                            if (finished) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                                InkAsset("InkTaskStrike", Modifier.fillMaxWidth().height(11.dp).offset(y = 1.dp).alpha(if (plan.status == "skipped") 0.46f else 0.72f))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            MojiLineIcon(if (plan.scheduleKind == "exactTime") MojiIcon.Clock else MojiIcon.Sun, 9.dp, MaterialTheme.colorScheme.secondary)
                            Text(schedule, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text((if (plan.plannedDurationEnabled == true) "· ${editorDuration(plan.plannedMinutes.toLong())} · " else "· ") + categoryName(plan.category), fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.secondary)
                        }
                        if (!expanded && plan.note.isNotEmpty()) Text(plan.note, fontSize = 12.sp, maxLines = 2, minLines = 2, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    if (daysLate > 0 && !finished) InkSeal(if (daysLate > 99) "99+" else daysLate.toString(), 26)
                    Box(Modifier.size(44.dp).clickable(onClick = toggleExpansion), contentAlignment = Alignment.Center) { MojiChevronDown(expanded) }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("修改") }, { menu = false; onEdit() })
                    DropdownMenuItem({ Text("复制到今天") }, { menu = false; viewModel.copyPlan(plan.id, today) })
                    DropdownMenuItem({ Text("复制到指定日期") }, { menu = false; onCopy() })
                    DropdownMenuItem({ Text("保存为模板") }, { menu = false; viewModel.saveTemplate(plan.id) })
                    DropdownMenuItem({ Text("归档") }, { menu = false; viewModel.batchPlans(setOf(plan.id), archived = true) })
                    if (plan.status == "planned") {
                        DropdownMenuItem({ Text("顺延") }, { menu = false; viewModel.postponePlan(plan.id) })
                        DropdownMenuItem({ Text("跳过") }, { menu = false; viewModel.skipPlan(plan.id) })
                    }
                    DropdownMenuItem({ Text("删除") }, { menu = false; viewModel.deletePlan(plan.id) })
                }
            }
            if (expanded) {
                InkDivider()
                Column(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    if (inline) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                InkSeal("改", 19, style = 2); Text("快捷编辑", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            androidx.compose.foundation.text.BasicTextField(title, { title = it }, Modifier.fillMaxWidth().heightIn(min = 42.dp).focusRequester(inlineFocus).padding(vertical = 10.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface))
                            androidx.compose.foundation.text.BasicTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 126.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(10.dp)).padding(horizontal = 11.dp, vertical = 8.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { inner -> Box { if (note.isBlank()) Text("添加详细说明（可选）", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)); inner() } })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.weight(1f).height(44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(11.dp)).clickable { inline = false; title = plan.title; note = plan.note }, contentAlignment = Alignment.Center) { Text("取消", fontSize = 17.sp) }
                                Box(Modifier.weight(1f).height(44.dp).alpha(if (title.isNotBlank()) 1f else 0.42f).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(11.dp)).clickable(enabled = title.isNotBlank()) { viewModel.quickEditPlan(plan.id, title, note); inline = false }, contentAlignment = Alignment.Center) { Text("保存", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background) }
                            }
                        }
                    } else if (plan.note.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { InkSeal("注", 19); Text("详细说明", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                            androidx.compose.foundation.text.selection.SelectionContainer { Text(plan.note, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)) }
                        }
                    }
                    val details = buildList {
                        add("日期" to date.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)))
                        add("安排" to timing); add("类型" to categoryName(plan.category))
                        if (plan.repeatRule != null && plan.repeatRule != "never") add("重复" to (repeatOptions.firstOrNull { it.first == plan.repeatRule }?.second ?: "自定义星期"))
                        plan.reminderMinutesBefore?.let { add("提醒" to if (it == 0) "开始时" else "提前 $it 分钟") }
                        if (plan.calendarEventIdentifier != null) add("系统日历" to "已同步")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (label, value) ->
                                    Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val glyph = when (label) {
                                            "日期", "系统日历" -> MojiIcon.Calendar
                                            "重复" -> MojiIcon.Repeat
                                            "类型" -> when (plan.category) { "work" -> MojiIcon.Work; "study" -> MojiIcon.Book; else -> MojiIcon.Tag }
                                            "提醒" -> MojiIcon.Bell
                                            else -> MojiIcon.Clock
                                        }
                                        MojiLineIcon(glyph, 18.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!inline) Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf(Triple("快捷编辑", MojiIcon.PencilLine, { inline = true }), Triple("更多设置", MojiIcon.Sliders, onEdit)).forEach { (label, icon, action) ->
                            Row(Modifier.weight(1f).height(44.dp).clickable(onClick = action), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) { MojiLineIcon(icon, 12.dp, MaterialTheme.colorScheme.secondary); Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                    if (plan.status == "planned") Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp)).clickable {
                        if (!timer.running && timer.accumulatedSeconds == 0) { viewModel.selectPomodoroPhase("focus"); viewModel.linkPomodoro(plan) }
                        viewModel.openFocus()
                    }.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MojiLineIcon(MojiIcon.Timer, 17.dp, MaterialTheme.colorScheme.background)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("开始番茄钟", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background)
                            Text(if (timer.running || timer.accumulatedSeconds > 0) "前往当前会话" else "带入专注", fontSize = 11.sp, color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        if (expanded) InkCard(padding = PaddingValues(horizontal = 14.dp)) { content() } else Column(Modifier.padding(horizontal = 4.dp)) { content() }
        if (!archived && !expanded) InkDivider(Modifier.padding(start = 58.dp), 0.34f, inkSeed(plan.id.uppercase()))
    }
}
