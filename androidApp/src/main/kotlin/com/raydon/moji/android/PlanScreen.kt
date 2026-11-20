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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
    var quickDraftId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showsCompleted by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val aiReady = rememberAIReady()
    // What was read out of plans typed a moment ago, by plan id. Never saved.
    val entrySuggestions = remember { mutableStateMapOf<String, com.raydon.moji.android.ai.EntrySuggestion>() }
    val readingEntryIds = remember { mutableStateListOf<String>() }
    var breakdown by remember { mutableStateOf<Pair<CheckInItem, Boolean>?>(null) }
    var showsImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, snapshot.checkInItems.size) {
        if (!didQA && qaRoute.isNotEmpty() && snapshot.checkInItems.isNotEmpty()) {
            didQA = true
            val sample = snapshot.checkInItems.firstOrNull { it.title == "整理 1.6.0 发布说明" }
            when (qaRoute) {
                "library", "library-filters", "templates", "library-batch" -> showLibrary = true
                "plan-new" -> showEditor = true
                "calendar-month", "calendar-week", "calendar-day", "calendar-jump" -> showCalendar = true
                "plan-editor", "plan-editor-advanced" -> { editing = sample; showEditor = true }
                "plan-expanded", "plan-expanded-bottom", "plan-inline" -> expandedId = sample?.id
                "plan-completed" -> showsCompleted = true
                "plan-copy" -> copying = sample
                else -> if (qaRoute.startsWith("plan-quick-")) expandedId = sample?.id
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
        if (expandedId != null) {
            kotlinx.coroutines.delay(200)
            val index = pending.indexOfFirst { it.id == expandedId }
            val activeOffset = if (snapshot.activeSession != null || timer.hasActiveFocus()) 1 else 0
            val archivedIndex = completed.indexOfFirst { it.id == expandedId }
            val target = if (index >= 0) 3 + index + activeOffset else if (archivedIndex >= 0 && showsCompleted) 4 + pending.size.coerceAtLeast(1) + activeOffset + archivedIndex else -1
            if (target >= 0) listState.animateScrollToItem(target, -toolbarPixels)
        }
    }
    /**
     * The plan is already saved as typed. If the sentence carries a time, a length or several
     * things, ask what it says and offer it under the row.
     */
    fun readQuickEntry(id: String, title: String) {
        if (!aiReady || !com.raydon.moji.android.ai.AISettingsStore.shared.settings.value.readsQuickEntry || !com.raydon.moji.android.ai.AIDrafting.isWorthReading(title)) return
        readingEntryIds += id
        scope.launch {
            // A failed reading is not worth a message: the plan is there either way.
            val suggestion = try { com.raydon.moji.android.ai.AIAssistant.shared.readEntry(title, aiCategories(viewModel)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Throwable) { null }
            readingEntryIds.remove(id)
            if (suggestion == null || suggestion.isEmpty) return@launch
            if (viewModel.snapshot.value.checkInItems.none { it.id == id && it.title == title && it.status == "planned" }) return@launch
            entrySuggestions[id] = suggestion
        }
    }
    fun applySuggestion(suggestion: com.raydon.moji.android.ai.EntrySuggestion, id: String) {
        entrySuggestions.remove(id)
        val plan = viewModel.snapshot.value.checkInItems.firstOrNull { it.id == id } ?: return
        val updated = suggestion.applied(plan) ?: return
        val notice = suggestion.day?.let { "已移到${com.raydon.moji.android.ai.AIDrafting.dayText(it, LocalDate.now())}，可在日历查看" } ?: "已按建议调整"
        viewModel.commitDrafts(changed = listOf(updated), notice = notice)
    }
    fun splitEntry(suggestion: com.raydon.moji.android.ai.EntrySuggestion, id: String) {
        entrySuggestions.remove(id)
        if (suggestion.parts.size < 2) return
        val plan = viewModel.snapshot.value.checkInItems.firstOrNull { it.id == id && it.kind == "planned" } ?: return
        val now = Instant.now().toString()
        val rest = suggestion.parts.drop(1).map { part ->
            CheckInItem(id = java.util.UUID.randomUUID().toString(), title = part, category = plan.category, scheduledStart = plan.scheduledStart,
                plannedMinutes = plan.plannedMinutes, createdAt = now, scheduleKind = plan.scheduleKind, detailsConfigured = plan.detailsConfigured,
                plannedDurationEnabled = plan.plannedDurationEnabled)
        }
        viewModel.commitDrafts(added = rest, changed = listOf(plan.copy(title = suggestion.parts[0])), notice = "已拆成 ${suggestion.parts.size} 条")
    }
    fun addQuickPlan() {
        val submitted = quickTitle.trim()
        if (submitted.isEmpty()) return
        val id = quickDraftId
        viewModel.savePlan(existingId = id, title = submitted, note = "", date = today, time = LocalTime.MIDNIGHT,
            scheduleKind = "allDay", category = viewModel.defaultCategory, plannedMinutes = 25,
            repeatRule = "never", weekdays = emptyList(), reminderMinutes = null, calendarSync = false, plannedDurationEnabled = false,
            onSaved = {
                if (quickTitle.trim() == submitted) quickTitle = ""; quickDraftId = java.util.UUID.randomUUID().toString()
                readQuickEntry(id, submitted)
            })
    }
    var didAIQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, snapshot.checkInItems.size) {
        if (!BuildConfig.DEBUG || didAIQA || !qaRoute.startsWith("ai-") || snapshot.checkInItems.isEmpty()) return@LaunchedEffect
        didAIQA = true
        kotlinx.coroutines.delay(500)
        when (qaRoute) {
            "ai-entry-suggestion", "ai-entry-split", "ai-entry-applied" -> {
                quickTitle = if (qaRoute == "ai-entry-split") "买菜、取快递、交电费" else "明天下午三点交保险计划书 45分钟"
                addQuickPlan()
            }
            "ai-breakdown", "ai-breakdown-carry" -> pending.firstOrNull()?.let { breakdown = it to (qaRoute == "ai-breakdown-carry") }
            "ai-import", "ai-import-drafts", "ai-import-ocr" -> showsImport = true
            "ai-library-filter", "ai-library-filter-applied" -> showLibrary = true
        }
    }
    LaunchedEffect(qaRoute, entrySuggestions.size) {
        if (BuildConfig.DEBUG && qaRoute == "ai-entry-applied") entrySuggestions.entries.firstOrNull()?.let { (id, suggestion) ->
            kotlinx.coroutines.delay(1_200); applySuggestion(suggestion, id)
        }
    }
    val openRecord = { addMenu = false; editing = null; editorKind = "completed"; showEditor = true }
    val openPlan = { addMenu = false; editing = null; editorKind = "planned"; showEditor = true }
    val openImport = { addMenu = false; showsImport = true }
    MojiScreen(opaqueTopInset = true) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Box {
                    Column {
                        MojiLargeTitle("计划", MojiIcon.CalendarFill, { showCalendar = true }, listOf(MojiIcon.GridSF to { showLibrary = true }, MojiIcon.Plus to { addMenu = true }), bottomPadding = 2.5.dp, opaqueBackground = true)
                        NavigationBarHairline()
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 40.dp)) {
                        PlanAddMenu(addMenu && listState.firstVisibleItemIndex == 0, { addMenu = false }, openRecord, openPlan, if (aiReady) openImport else null)
                    }
                }
            }
            item {
                InkCard(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 7.dp), padding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.foundation.text.BasicTextField(quickTitle, { quickTitle = it }, Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addQuickPlan() }),
                            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (quickTitle.isEmpty()) Text("写下一件要做的事", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
                        Box(Modifier.size(44.dp).alpha(if (quickTitle.isBlank()) 0.35f else 1f).clickable(enabled = quickTitle.isNotBlank()) {
                            addQuickPlan()
                        }, contentAlignment = Alignment.Center) {
                            InkBrushMedallion(MojiIcon.Plus, MaterialTheme.colorScheme.primary, 36.dp)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp).heightIn(min = 39.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkSeal("今", 25)
                    Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("今日计划", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        // iOS .secondary in the list resolves to primary at 50%: (123,122,118) light, (136,136,135) dark.
                        Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(Modifier.weight(1f))
                    InkDivider(Modifier.width(44.dp), 0.26f, inkSeed("dashboard-today-heading"))
                    Text(if (total == 0) "今日无计划" else "完成 ${completed.count { it.status == "completed" }}/$total", Modifier.padding(start = 12.dp).widthIn(min = 78.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        color = if (total == 0) iosSecondaryLabel else MaterialTheme.colorScheme.primary)
                }
            }
            if (snapshot.activeSession != null || timer.hasActiveFocus()) item {
                InkCard(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { viewModel.openFocus() }, verticalAlignment = Alignment.CenterVertically) {
                        MojiLineIcon(MojiIcon.Timer, 22.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(snapshot.activeSession?.title ?: timer.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (timer.running) "${timer.dashboardStatus()} · ${viewModel.remainingSeconds() / 60}:${(viewModel.remainingSeconds() % 60).toString().padStart(2, '0')}" else timer.dashboardStatus(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                    { editing = item; showEditor = true }, { copying = item },
                    breakDown = if (aiReady) { replaces -> breakdown = item to replaces } else null) {
                    val suggestion = entrySuggestions[item.id]
                    if (suggestion != null) EntrySuggestionStrip(suggestion, { applySuggestion(suggestion, item.id) }, { splitEntry(suggestion, item.id) },
                        { entrySuggestions.remove(item.id) }, Modifier.padding(start = 46.dp, end = 4.dp, bottom = 8.dp))
                    else if (item.id in readingEntryIds) InkDraftWaiting("在看这句话", Modifier.padding(start = 58.dp, bottom = 8.dp))
                }
            }
            if (completed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showsCompleted = !showsCompleted }.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("今日已结束 ${completed.size}", fontSize = 15.sp, color = iosSecondaryLabel, modifier = Modifier.weight(1f))
                        MojiChevronDown(showsCompleted, width = 13f, color = iosSecondaryLabel)
                    }
                }
                if (showsCompleted) items(completed, key = { "completed-" + it.id }) { item ->
                    PlanRow(item, today, expandedId == item.id, true, viewModel, { expandedId = if (expandedId == item.id) null else item.id },
                        { editing = item; showEditor = true }, { copying = item })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (listState.firstVisibleItemIndex > 0) Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 7.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { showCalendar = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.CalendarFill, 28.dp) }
                Text("计划", modifier = Modifier.weight(1f).padding(start = 44.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box(Modifier.size(44.dp).clickable { showLibrary = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.GridSF, 28.dp) }
                Box {
                    Box(Modifier.size(44.dp).clickable { addMenu = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Plus, 23.dp) }
                    PlanAddMenu(addMenu, { addMenu = false }, openRecord, openPlan, if (aiReady) openImport else null)
                }
            }
            NavigationBarHairline()
        }
        }
    }
    if (showEditor) PlanEditorScreenDialog(editing, today, viewModel, initialKind = editorKind) { showEditor = false; editing = null }
    if (showLibrary) PlanLibraryDialog(viewModel) { showLibrary = false }
    if (showsImport) PlanImportDialog(viewModel) { showsImport = false }
    breakdown?.let { (item, replaces) ->
        PlanDraftSheet(viewModel, "拆成步骤", item.title, waitingText = "正在拆步骤", planNote = "拆自：${item.title}", showsDates = false,
            source = item, archivesSourceByDefault = replaces,
            load = { com.raydon.moji.android.ai.AIAssistant.shared.breakDown(item, aiCategories(viewModel)) }) { breakdown = null }
    }
    copying?.let { PlanCopyDialog(it, viewModel) { copying = null } }
}

@Composable
private fun PlanAddMenu(expanded: Boolean, onDismiss: () -> Unit, onRecord: () -> Unit, onPlan: () -> Unit, onImport: (() -> Unit)? = null) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("记录已做") }, onRecord)
        DropdownMenuItem({ Text("详细添加计划") }, onPlan)
        if (onImport != null) DropdownMenuItem({ Text("从文字或截图添加") }, onImport)
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

/** SF "chevron.down": [width] is the glyph width in dp; iOS draws it in the tertiary label colour. */
@Composable
fun MojiChevronDown(expanded: Boolean, width: Float = 9.4f, color: Color = iosTertiaryLabel) {
    Canvas(Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)) {
        val d = density; val half = width / 2f; val stroke = width * 0.2f; val drop = (width - stroke) * 0.5f
        val top = this.size.height / 2f - drop * d / 2f; val cx = this.size.width / 2f
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(cx - (half - stroke / 2) * d, top); lineTo(cx, top + drop * d); lineTo(cx + (half - stroke / 2) * d, top) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The 1px shadow line UIKit draws under a navigation bar whose background is visible. */
@Composable
fun NavigationBarHairline() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.material3.HorizontalDivider(thickness = devicePixel, color = if (dark) Color.White.copy(alpha = 0.17f) else Color.Black.copy(alpha = 0.3f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(plan: CheckInItem, today: LocalDate, expanded: Boolean, archived: Boolean, viewModel: MojiViewModel,
                    toggleExpansion: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit,
                    /** Present only while a model is set up; the argument says whether the steps replace this plan. */
                    breakDown: ((Boolean) -> Unit)? = null,
                    below: @Composable () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    var sealMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var quickField by remember { mutableStateOf<PlanQuickField?>(null) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, expanded) {
        if (BuildConfig.DEBUG && !didQA && expanded && plan.title == "整理 1.6.0 发布说明") {
            val field = if (qaRoute == "plan-inline") PlanQuickField.CONTENT
                else if (qaRoute.startsWith("plan-quick-")) PlanQuickField.entries.firstOrNull { it.name.equals(qaRoute.removePrefix("plan-quick-").substringBefore("-"), true) } else null
            if (field != null) { didQA = true; quickField = field }
        }
    }
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val finished = plan.status in listOf("completed", "skipped")
    val date = Instant.parse(plan.scheduledStart).atZone(ZoneId.systemDefault())
    val schedule = scheduleOptions.firstOrNull { it.first == plan.scheduleKind && it.first != "exactTime" }?.second ?: date.format(DateTimeFormatter.ofPattern("HH:mm"))
    val hasDuration = plan.plannedDurationEnabled ?: (plan.detailsConfigured != false && (plan.scheduleKind ?: "exactTime") == "exactTime")
    val timing = schedule + if (hasDuration) " · 预计 ${editorDuration(plan.plannedMinutes.toLong())}" else ""
    val daysLate = ChronoUnit.DAYS.between(date.toLocalDate(), today).coerceAtLeast(0)
    val repeatText = if (plan.repeatRule == "customWeekdays") plan.repeatWeekdays.orEmpty().sorted().joinToString("、") { mapOf(1 to "周日", 2 to "周一", 3 to "周二", 4 to "周三", 5 to "周四", 6 to "周五", 7 to "周六")[it].orEmpty() }.ifEmpty { "自选星期" }
        else repeatOptions.firstOrNull { it.first == (plan.repeatRule ?: "never") }?.second ?: "不重复"
    val reminderText = when (val m = plan.reminderMinutesBefore) { null -> "不提醒"; 0 -> "开始时"; 60 -> "提前 1 小时"; 1440 -> "提前 1 天"; else -> if (m % 60 == 0) "提前 ${m / 60} 小时" else "提前 $m 分钟" }
    val content: @Composable () -> Unit = {
        Column {
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (expanded) quickField = PlanQuickField.CONTENT else toggleExpansion() }, onLongClick = { menu = true }).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).clickable { viewModel.togglePlan(plan.id) }, contentAlignment = Alignment.Center) {
                    if (finished) {
                        Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (plan.status == "skipped") "−" else "✓", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.surface)
                        }
                        InkAsset("InkIconLoop", Modifier.requiredSize(54.dp).alpha(0.78f))
                        InkAsset("InkCheckMark", Modifier.size(20.dp, 17.dp))
                    } else Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        Text(plan.title, fontSize = 17.sp, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                            // iOS Color.primary: pure black / white (0,0,0 / 255,255,255), not the ink tone.
                            color = if (plan.status == "completed") MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else LocalContentColor.current)
                        if (finished) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                            InkAsset("InkTaskStrike", Modifier.fillMaxWidth().height(11.dp).offset(y = 1.dp).alpha(if (plan.status == "skipped") 0.46f else 0.72f))
                        }
                    }
                    if (!expanded) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val secondary = MaterialTheme.colorScheme.secondary
                            MojiLineIcon(if (plan.scheduleKind == "exactTime") MojiIcon.Clock else MojiIcon.Sun, 10.dp, secondary)
                            Text(schedule, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            if (hasDuration) { Text("·", fontSize = 11.sp, color = secondary); Text(editorDuration(plan.plannedMinutes.toLong()), fontSize = 11.sp, maxLines = 1, color = secondary) }
                            Text("·", fontSize = 11.sp, color = secondary)
                            Text(categoryName(plan.category), fontSize = 11.sp, maxLines = 1, color = secondary)
                        }
                        if (plan.note.isNotEmpty()) Text(plan.note, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                }
                if (daysLate > 0 && !finished) {
                    val seal = if (daysLate > 99) "99+" else daysLate.toString()
                    // A plan that keeps being carried over is usually too big, on the wrong day,
                    // or no longer wanted: the seal offers all three.
                    if (breakDown != null && plan.status == "planned") Box {
                        Box(Modifier.size(22.dp, 44.dp).clickable { sealMenu = true }.semantics { contentDescription = "已顺延 $daysLate 天，处理这个计划" }, contentAlignment = Alignment.Center) { InkSeal(seal, 22) }
                        DropdownMenu(sealMenu, { sealMenu = false }) {
                            DropdownMenuItem({ Text("拆小一点") }, { sealMenu = false; breakDown(true) })
                            DropdownMenuItem({ Text("改期") }, { sealMenu = false; quickField = PlanQuickField.DATE })
                            DropdownMenuItem({ Text("放下") }, { sealMenu = false; viewModel.skipPlan(plan.id) })
                        }
                    } else InkSeal(seal, 22)
                }
                Box(Modifier.size(44.dp).clickable(onClick = toggleExpansion), contentAlignment = Alignment.Center) { MojiChevronDown(expanded) }
            }
            if (expanded) {
                InkDivider()
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { quickField = PlanQuickField.CONTENT }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(plan.note.ifEmpty { "添加备注" }, fontSize = 15.sp, lineHeight = 21.sp, color = if (plan.note.isEmpty()) iosSecondaryLabel else LocalContentColor.current.copy(alpha = 0.86f), modifier = Modifier.weight(1f))
                        Box(Modifier.padding(top = 3.dp)) { MojiLineIcon(MojiIcon.Pencil, 14.dp, iosTertiaryLabel) }
                    }
                    Column(Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(12.dp))) {
                        PlanDetailRow("日期", date.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)), MojiIcon.Calendar) { quickField = PlanQuickField.DATE }
                        HorizontalDivider(Modifier.padding(start = 38.dp), thickness = devicePixel, color = iosSeparator)
                        PlanDetailRow("安排", timing, if (plan.scheduleKind == "exactTime") MojiIcon.Clock else MojiIcon.Sun) { quickField = PlanQuickField.SCHEDULE }
                        HorizontalDivider(Modifier.padding(start = 38.dp), thickness = devicePixel, color = iosSeparator)
                        PlanDetailRow("类型", categoryName(plan.category), when (plan.category) { "work" -> MojiIcon.Work; "study" -> MojiIcon.Book; else -> MojiIcon.Tag }) { quickField = PlanQuickField.CATEGORY }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlanDetailPill(repeatText, MojiIcon.RepeatLoop, Modifier.weight(1f)) { quickField = PlanQuickField.RECURRENCE }
                        PlanDetailPill(reminderText, MojiIcon.BellPlain, Modifier.weight(1f)) { quickField = PlanQuickField.REMINDER }
                    }
                    if (plan.calendarEventIdentifier != null) Text("已写入系统日历", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(11.dp)).clickable { menu = true },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                                MojiLineIcon(MojiIcon.Ellipsis, 22.dp, MaterialTheme.colorScheme.primary)
                                Text("更多操作", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                            PlanRowMenu(menu, { menu = false }, plan, today, viewModel, onEdit, onCopy, { confirmDelete = true }, breakDown)
                        }
                        if (plan.status in listOf("planned", "inProgress")) Row(Modifier.weight(1f).heightIn(min = 44.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp)).clickable {
                            if (!timer.hasActiveFocus()) { viewModel.selectPomodoroPhase("focus"); viewModel.linkPomodoro(plan) }
                            viewModel.openFocus()
                        }.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MojiLineIcon(MojiIcon.TimerSF, 19.dp, MaterialTheme.colorScheme.background)
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(if (timer.hasActiveFocus()) "继续专注" else "去专注", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background)
                                Text(if (timer.hasActiveFocus()) "前往当前会话" else "选择时长后开始", fontSize = 11.sp, color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                            }
                        }
                    }
                }
            } else PlanRowMenu(menu, { menu = false }, plan, today, viewModel, onEdit, onCopy, { confirmDelete = true }, breakDown)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        if (expanded) InkCard(padding = PaddingValues(horizontal = 14.dp)) { content() } else Column(Modifier.padding(horizontal = 4.dp)) { content() }
        if (!archived) below()
        if (!archived && !expanded) InkDivider(Modifier.padding(start = 58.dp), 0.34f, inkSeed(plan.id.uppercase()))
    }
    quickField?.let { PlanQuickEditorDialog(plan, it, viewModel) { quickField = null } }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这项计划？") },
        text = { Text(plan.title) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deletePlan(plan.id) }) { Text("删除", color = mojiVermilion) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun PlanDetailRow(title: String, value: String, icon: MojiIcon, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // iOS centres the symbol on the text; Noto's ink sits about 0.8pt lower in its line than PingFang's.
        // briefcase.fill is 16pt wide here, so it overflows the 18dp slot a little without moving the title.
        Box(Modifier.size(18.dp).offset(y = 0.8.dp), contentAlignment = Alignment.Center) {
            val glyphSize = if (icon == MojiIcon.Work) 19.2.dp else 18.dp
            Box(Modifier.requiredSize(glyphSize)) { MojiLineIcon(icon, glyphSize) }
        }
        Text(title, fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.width(32.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        MojiLineIcon(MojiIcon.Disclosure, 18.dp, iosTertiaryLabel)
    }
}

@Composable
private fun PlanDetailPill(value: String, icon: MojiIcon, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 36.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MojiLineIcon(icon, 15.dp, MaterialTheme.colorScheme.primary)
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f))
        MojiLineIcon(MojiIcon.Disclosure, 15.dp, iosTertiaryLabel)
    }
}

@Composable
private fun PlanRowMenu(expanded: Boolean, onDismiss: () -> Unit, plan: CheckInItem, today: LocalDate,
                        viewModel: MojiViewModel, onEdit: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
                        breakDown: ((Boolean) -> Unit)? = null) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("完整编辑") }, { onDismiss(); onEdit() })
        if (breakDown != null && plan.status in listOf("planned", "inProgress")) DropdownMenuItem({ Text("拆成步骤") }, { onDismiss(); breakDown(false) })
        DropdownMenuItem({ Text("复制到今天") }, { onDismiss(); viewModel.copyPlan(plan.id, today) })
        DropdownMenuItem({ Text("复制到指定日期") }, { onDismiss(); onCopy() })
        DropdownMenuItem({ Text("保存为模板") }, { onDismiss(); viewModel.saveTemplate(plan.id) })
        DropdownMenuItem({ Text("归档") }, { onDismiss(); viewModel.batchPlans(setOf(plan.id), archived = true) })
        if (plan.status == "planned") {
            DropdownMenuItem({ Text("顺延") }, { onDismiss(); viewModel.postponePlan(plan.id) })
            DropdownMenuItem({ Text("跳过") }, { onDismiss(); viewModel.skipPlan(plan.id) })
        }
        DropdownMenuItem({ Text("删除", color = mojiVermilion) }, { onDismiss(); onDelete() })
    }
}

private fun com.raydon.moji.android.data.PomodoroState.hasActiveFocus(): Boolean =
    running || accumulatedSeconds > 0 || pendingNextPhase != null || linkedPlanId.isNotBlank() || remainingSeconds < phaseDurationSeconds
