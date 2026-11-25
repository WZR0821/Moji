package com.raydon.moji.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.*
import com.raydon.moji.android.ai.AIAssistant
import com.raydon.moji.android.ai.AIDrafting
import com.raydon.moji.android.ai.AIRequestError
import com.raydon.moji.android.ai.ConversionHint
import com.raydon.moji.android.ai.LibraryFilter
import com.raydon.moji.android.ai.ReflectionDraft
import com.raydon.moji.android.ai.draftMessage
import com.raydon.moji.ui.InkCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

fun workflowWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun workflowSummary(snapshot: PlanSnapshot, week: LocalDate, category: String? = null): WorkflowWeekSummary {
    val start = workflowWeek(week); val zone = ZoneId.systemDefault()
    return PlanWorkflow.summary(snapshot, start.atStartOfDay(zone).toInstant().toString(), start.plusDays(7).atStartOfDay(zone).toInstant().toString(), category)
}

private fun categoryChoices(viewModel: MojiViewModel, current: String? = null) =
    (listOf("study" to "学习", "work" to "工作") + viewModel.customCategories.map { "custom:$it" to it } + listOfNotNull(current?.let { it to categoryName(it) })).distinctBy { it.first }

@Composable
private fun WorkflowDialog(title: String, onDismiss: () -> Unit, leading: String = "取消",
                           trailing: String = "完成", enabled: Boolean = true,
                           onSave: () -> Unit = onDismiss, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(title, leading, trailing, onDismiss, onSave, enabled)
                content()
            }
        }
    }
}

@Composable
fun PlanCopyDialog(item: CheckInItem, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val pick = rememberDatePicker { day = it }
    WorkflowDialog("复制计划", onDismiss, trailing = "复制", onSave = { viewModel.copyPlan(item.id, day, onSaved = onDismiss) }) {
        LazyColumn(contentPadding = PaddingValues(top = 34.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection { FormRow(item.title); FormSeparator(); FormDateRow("目标日期", day) { pick(day) } } }
            item { WorkflowInfoSection("复制为独立的新计划，保留类型、说明、时段和预计投入，不复制完成状态、计时记录或重复序列。") }
        }
    }
}

@Composable
fun PlanLibraryDialog(viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val undo by viewModel.workflowUndo.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(if (viewModel.visualQaRoute.value == "templates") "templates" else "plans") }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("all") }
    var status by remember { mutableStateOf("all") }
    var period by remember { mutableStateOf("all") }
    var from by remember { mutableStateOf(LocalDate.now()) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selecting by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    var copying by remember { mutableStateOf<CheckInItem?>(null) }
    var templateDay by remember { mutableStateOf(LocalDate.now()) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var filtersExpanded by remember { mutableStateOf(BuildConfig.DEBUG && viewModel.visualQaRoute.value == "library-filters") }
    val pickFrom = rememberDatePicker { from = it; if (to < it) to = it }
    val pickTo = rememberDatePicker { to = maxOf(from, it) }
    val pickTemplate = rememberDatePicker { templateDay = it }
    val showsAI = rememberAIShowsEntries()
    val scope = rememberCoroutineScope()
    var isReadingFilter by remember { mutableStateOf(false) }
    var filterFailure by remember { mutableStateOf<String?>(null) }
    // The sentence the filters were last set from, and what they were before.
    var appliedFilterSentence by remember { mutableStateOf<String?>(null) }
    var filterBeforeSentence by remember { mutableStateOf<LibraryFilter?>(null) }
    // The search field as the filters were set from it; typing past it lets the sentence go (A5).
    var queryWhenApplied by remember { mutableStateOf<String?>(null) }
    fun setFilter(filter: LibraryFilter) {
        query = filter.keyword; category = filter.category; status = filter.status; period = filter.period
        if (filter.from != null && filter.to != null) { from = filter.from; to = filter.to }
    }
    fun forgetSentence() { appliedFilterSentence = null; filterBeforeSentence = null; queryWhenApplied = null }
    fun applySentenceFilter() {
        if (isReadingFilter) return
        val asked = query
        val sentence = asked.trim()
        val before = LibraryFilter(query, category, status, period, from, to)
        isReadingFilter = true; filterFailure = null
        scope.launch {
            try {
                val filter = AIAssistant.shared.libraryFilter(sentence, aiCategories(viewModel))
                // The field changed while waiting: the answer is for another sentence.
                if (query != asked) return@launch
                filterBeforeSentence = before
                setFilter(filter)
                appliedFilterSentence = sentence
                queryWhenApplied = query
                filtersExpanded = true
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (query == asked) filterFailure = error.draftMessage
            } finally { isReadingFilter = false }
        }
    }
    // 还原: the three filters as before; the search field empty, not the sentence again.
    fun restoreFilterBeforeSentence() {
        filterBeforeSentence?.let { setFilter(it.copy(keyword = "")) }
        forgetSentence()
    }
    LaunchedEffect(query) {
        if (queryWhenApplied != null && query != queryWhenApplied) forgetSentence()
        if (!isReadingFilter) filterFailure = null
    }
    val today = LocalDate.now()
    val plans = snapshot.checkInItems.filter { item ->
        val day = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate()
        val matchesStatus = if (status == "archived") item.isArchived == true else item.isArchived != true && (status == "all" || item.status == status)
        val matchesPeriod = when (period) { "past" -> day < today; "today" -> day == today; "future" -> day > today; "range" -> day in from..to; else -> true }
        item.kind == "planned" && (query.isBlank() || item.title.contains(query, true) || item.note.contains(query, true)) &&
            (category == "all" || item.category == category) && matchesStatus && matchesPeriod
    }.sortedByDescending { it.scheduledStart }
    val selectableIds = plans.filter { it.status != "inProgress" }.map { it.id }.toSet()
    LaunchedEffect(selectableIds) { selected = selected.intersect(selectableIds) }
    val filterSummary = listOfNotNull(category.takeIf { it != "all" }?.let(::categoryName),
        status.takeIf { it != "all" }?.let { if (it == "archived") "已归档" else planStatusName(it) },
        mapOf("past" to "过去", "today" to "今天", "future" to "未来", "range" to "日期范围")[period]).joinToString(" · ").ifEmpty { "全部类型 · 全部状态 · 全部日期" }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "library-batch") {
            selected = plans.take(2).map { it.id }.toSet(); selecting = true; batch = true
        }
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value.startsWith("ai-library-filter")) {
            query = "上周没做完的学习计划"
            if (viewModel.visualQaRoute.value == "ai-library-filter-applied") { delay(400); applySentenceFilter() }
        }
    }
    WorkflowDialog("计划库", onDismiss, leading = "", onSave = onDismiss) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 34.7.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                FormSection {
                    FormSegmentedControl(listOf("plans" to "全部计划", "templates" to "计划模板"), mode) { mode = it; selected = emptySet(); selecting = false }
                    // Return on a sentence does what 按这句话筛选 does (A5).
                    FormSeparator(); FormTextField(query, { query = it }, "搜索标题或说明",
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            if (mode == "plans" && showsAI && appliedFilterSentence == null && AIDrafting.looksLikeFilterSentence(query)) aiEntry { applySentenceFilter() }
                        }))
                    // A whole sentence in the search field can set the three filters at once.
                    // The row only exists while a model is set up and the text reads like one.
                    if (mode == "plans") {
                        val sentence = appliedFilterSentence
                        if (isReadingFilter) { FormSeparator(); Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { InkDraftWaiting("正在读这句话") } }
                        else if (sentence != null) {
                            FormSeparator()
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("已按“$sentence”设好筛选", fontSize = 13.sp, lineHeight = 18.sp, color = iosSecondaryLabel, maxLines = 2, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(4.dp))
                                Text("还原", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { restoreFilterBeforeSentence() }.padding(vertical = 4.dp))
                            }
                        } else if (showsAI && AIDrafting.looksLikeFilterSentence(query)) {
                            FormSeparator()
                            // iOS: a borderless button in .subheadline, medium weight.
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { aiEntry { applySentenceFilter() } }.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                                Text("按这句话筛选", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                            filterFailure?.let { FormSeparator(); Text(it, fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp)) }
                        }
                    }
                }
            }
            if (mode == "plans") {
                item {
                    FormSection(topPadding = 11) {
                        Row(Modifier.fillMaxWidth().clickable { filtersExpanded = !filtersExpanded }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("筛选", fontSize = 17.sp)
                                Text(filterSummary, fontSize = 12.sp, color = iosSecondaryLabel)
                            }
                            Box(Modifier.rotate(if (filtersExpanded) 90f else 0f)) { MojiLineIcon(MojiIcon.Disclosure, 26.dp, MaterialTheme.colorScheme.primary) }
                        }
                        if (filtersExpanded) FormSeparator()
                        if (filtersExpanded) Column(Modifier.padding(start = 20.dp)) {
                        FormChoiceRow("计划类型", category, listOf("all" to "全部类型") + categoryChoices(viewModel)) { category = it }
                        FormSeparator()
                        FormChoiceRow("状态", status, listOf("all" to "全部状态", "planned" to "待完成", "inProgress" to "进行中", "completed" to "已完成", "skipped" to "已跳过", "archived" to "已归档")) { status = it }
                        FormSeparator()
                        FormChoiceRow("日期", period, listOf("all" to "全部日期", "past" to "过去", "today" to "今天", "future" to "未来", "range" to "日期范围")) { period = it }
                        if (period == "range") { FormSeparator(); FormDateRow("开始日期", from) { pickFrom(from) }; FormSeparator(); FormDateRow("结束日期", to) { pickTo(to) } }
                        if (category != "all" || status != "all" || period != "all") { FormSeparator(); FormRow("清除筛选", onClick = { category = "all"; status = "all"; period = "all"; forgetSentence() }) }
                        }
                    }
                }
                item {
                    FormSection(topPadding = 11) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${plans.size} 个计划", fontSize = 15.sp, color = iosSecondaryLabel, modifier = Modifier.weight(1f))
                            Text(if (selecting) "取消选择" else "批量选择", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { selecting = !selecting; selected = emptySet() })
                        }
                        if (selecting) {
                            FormSeparator()
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("全选结果", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { selected = plans.filter { it.status != "inProgress" }.map { it.id }.toSet() })
                                Text("整理 ${selected.size} 项", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = if (selected.isNotEmpty()) 1f else 0.3f), modifier = Modifier.clickable(enabled = selected.isNotEmpty()) { batch = true })
                            }
                        }
                        FormSeparator()
                        if (plans.isEmpty()) FormRow("没有符合条件的计划", titleColor = iosSecondaryLabel)
                        plans.forEach { item ->
                            Box {
                                Row(Modifier.fillMaxWidth().combinedClickable(enabled = !selecting || item.status != "inProgress", onLongClick = { if (!selecting) menuId = item.id }, onClick = {
                                    if (selecting) { if (item.status != "inProgress") selected = if (item.id in selected) selected - item.id else selected + item.id } else editing = item
                                }).padding(start = 20.dp, end = 20.dp, top = 14.5.dp, bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (selecting) { SelectionCircle(item.id in selected); Spacer(Modifier.width(12.dp)) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(item.title, fontSize = 17.sp)
                                        val date = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日"))
                                        Text(withOriginMark("$date · ${categoryName(item.category)} · ${if (item.isArchived == true) "已归档" else planStatusName(item.status)}", item.aiOrigin), fontSize = 12.sp, color = iosSecondaryLabel)
                                    }
                                }
                                DropdownMenu(menuId == item.id, { menuId = null }) {
                                    DropdownMenuItem({ Text("复制到今天") }, { menuId = null; viewModel.copyPlan(item.id, today) })
                                    DropdownMenuItem({ Text("复制到指定日期") }, { menuId = null; copying = item })
                                    DropdownMenuItem({ Text("保存为模板") }, { menuId = null; viewModel.saveTemplate(item.id) })
                                    DropdownMenuItem({ Text(if (item.isArchived == true) "取消归档" else "归档") }, { menuId = null; viewModel.batchPlans(setOf(item.id), archived = item.isArchived != true) })
                                }
                            }
                            FormSeparator()
                        }
                    }
                }
            } else {
                item { FormSection(topPadding = 11) { FormDateRow("添加到", templateDay) { pickTemplate(templateDay) } } }
                item {
                    FormSection("计划模板", titleLineHeight = 17, topPadding = 9) {
                        if (snapshot.planTemplates.isEmpty()) Text("在计划的更多菜单中选择“保存为模板”", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = iosSecondaryLabel)
                        snapshot.planTemplates.filter { query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true) }.forEach { template ->
                            // iOS: title ink 16.3pt below the card top, buttons' ink 16pt above its bottom.
                            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 15.4.dp, bottom = 15.7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(template.title, fontSize = 17.sp)
                                Text("${categoryName(template.category)} · ${scheduleOptions.first { it.first == template.scheduleKind }.second}${if (template.plannedDurationEnabled) " · ${template.plannedMinutes} 分钟" else ""}", fontSize = 12.sp, color = iosSecondaryLabel)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("添加计划", fontSize = 17.sp, modifier = Modifier.heightIn(min = 22.dp).clickable { viewModel.applyTemplate(template.id, templateDay) })
                                    Text("删除", fontSize = 17.sp, color = mojiDestructive, modifier = Modifier.heightIn(min = 22.dp).clickable { viewModel.deleteTemplate(template.id) })
                                }
                            }
                            FormSeparator()
                        }
                    }
                }
            }
            if (undo != null) item { FormSection { FormRow("撤销上次整理", onClick = { viewModel.undoWorkflow() }) } }
        }
    }
    if (batch) PlanBatchDialog(selected, viewModel) { batch = false; selecting = false; selected = emptySet() }
    editing?.let { item -> PlanEditorScreenDialog(item, today, viewModel) { editing = null } }
    copying?.let { PlanCopyDialog(it, viewModel) { copying = null } }
}

fun planStatusName(status: String) = when (status) { "completed" -> "已完成"; "skipped" -> "已跳过"; "inProgress" -> "进行中"; else -> "待完成" }

@Composable
fun SelectionCircle(selected: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val cutout = MaterialTheme.colorScheme.surface
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        drawCircle(ink, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = if (selected) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.2.dp.toPx()))
        if (selected) {
            val stroke = 1.4.dp.toPx()
            drawLine(cutout, Offset(size.width * 0.25f, size.height * 0.5f), Offset(size.width * 0.43f, size.height * 0.67f), stroke, StrokeCap.Round)
            drawLine(cutout, Offset(size.width * 0.43f, size.height * 0.67f), Offset(size.width * 0.76f, size.height * 0.32f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun WorkflowCaption(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = iosSecondaryLabel, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 3.8.dp, bottom = 4.9.dp))
}

@Composable
private fun WorkflowInfoSection(text: String) {
    Column(Modifier.padding(top = 10.7.dp)) { FormSection { WorkflowCaption(text) } }
}

@Composable
private fun PlanBatchDialog(ids: Set<String>, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf("date") }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var category by remember { mutableStateOf("study") }
    var confirm by remember { mutableStateOf(false) }
    val pick = rememberDatePicker { day = it }
    WorkflowDialog("批量整理", onDismiss, trailing = "应用", onSave = { confirm = true }) {
        LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection("已选择 ${ids.size} 个计划") {
                FormChoiceRow("整理方式", action, listOf("date" to "修改日期", "category" to "修改类型", "archive" to "归档", "unarchive" to "取消归档")) { action = it }
                if (action == "date") { FormSeparator(); FormDateRow("目标日期", day) { pick(day) } }
                if (action == "category") { FormSeparator(); FormChoiceRow("计划类型", category, categoryChoices(viewModel, category)) { category = it } }
            } }
            item { WorkflowInfoSection("正在专注的计划不会被修改。修改日期只处理待完成计划；归档保留历史记录。整理后可在计划库撤销。") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("整理 ${ids.size} 个计划？") }, text = { Text("将应用所选整理方式，历史记录不会删除。") }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }, confirmButton = { TextButton(onClick = {
        viewModel.batchPlans(ids, day.takeIf { action == "date" }, category.takeIf { action == "category" }, if (action == "archive") true else if (action == "unarchive") false else null); onDismiss()
    }) { Text("应用") } })
}

@Composable
fun MemoConversionDialog(memo: MemoItem, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var day by remember { mutableStateOf(LocalDate.now()) }
    var category by remember { mutableStateOf("study") }
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    val pick = rememberDatePicker { day = it }
    val showsAI = rememberAIShowsEntries()
    val scope = rememberCoroutineScope()
    // A date and a type read out of each row, keyed by checklist item (null for the whole memo).
    var hints by remember { mutableStateOf<Map<String?, ConversionHint>>(emptyMap()) }
    var isReadingHints by remember { mutableStateOf(false) }
    var hintFailure by remember { mutableStateOf<String?>(null) }
    // The row whose own date and type are being changed in the small sheet.
    var editingHint by remember { mutableStateOf<Pair<String?, String>?>(null) }
    val checklistRows = if (memo.mode == "checklist") memo.checklistItems.filter { it.text.isNotBlank() } else emptyList()
    fun readHints() {
        val rows = checklistRows
        val texts = listOf(listOf(memo.title, if (memo.mode == "note") memo.content else "").filter { it.isNotEmpty() }.joinToString("：")) + rows.map { it.text }
        isReadingHints = true; hintFailure = null
        scope.launch {
            try {
                val found = AIAssistant.shared.conversionHints(memo.title, texts, aiCategories(viewModel))
                val result = found.entries.mapNotNull { (index, hint) ->
                    if (index == 0) (null as String?) to hint else rows.getOrNull(index - 1)?.let { it.id to hint }
                }.toMap()
                if (result.isEmpty()) hintFailure = "这几行里没有读到日期或类型，沿用上面选的。"
                hints = result
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                hintFailure = error.draftMessage
            } finally { isReadingHints = false }
        }
    }
    // Prefilled as the page opens once a model is set up; otherwise the row below opens the intro (B5).
    LaunchedEffect(Unit) {
        if (com.raydon.moji.android.ai.AISettingsStore.shared.isReady && showsAI && hints.isEmpty() && !isReadingHints) readHints()
    }
    // ai-memo-convert-edit: the small sheet of the first prefilled row.
    LaunchedEffect(hints.isNotEmpty()) {
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "ai-memo-convert-edit" && hints.isNotEmpty() && editingHint == null) {
            delay(600)
            // The first prefilled row in the page's order: the whole memo, then its items.
            val order = listOf<Pair<String?, String>>(null to "整则备忘") + checklistRows.map { it.id to it.text }
            editingHint = order.firstOrNull { it.first in hints } ?: return@LaunchedEffect
        }
    }
    WorkflowDialog("备忘转计划", onDismiss, leading = "", onSave = onDismiss) {
        // A prefilled row is written as a draft: its undo bar shows on this page, above the rows.
        Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(top = 34.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection {
                FormDateRow("计划日期", day) { pick(day) }; FormSeparator(); FormChoiceRow("计划类型", category, categoryChoices(viewModel, category)) { category = it }
                if (showsAI) {
                    FormSeparator()
                    if (isReadingHints) Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { InkDraftWaiting("正在看每一行") }
                    else if (hints.isEmpty()) {
                        // A reading that failed (or found nothing) says why, then offers 再试一次 in place of the first button.
                        val failure = hintFailure
                        if (failure != null) {
                            Text(failure, fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp))
                            FormSeparator()
                            FormRow("再试一次", onClick = { aiEntry { readHints() } }, titleColor = MaterialTheme.colorScheme.primary)
                        } else FormRow("按内容预填每一行", onClick = { aiEntry { readHints() } }, titleColor = MaterialTheme.colorScheme.primary)
                    } else Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("带浅色标注的行会用它自己的日期和类型", fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        Text("不用", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { hints = emptyMap() }.padding(vertical = 4.dp))
                    }
                }
            } }
            item { FormSection("转为计划", topPadding = 9) {
                val rows = listOf(null to "整则备忘") + checklistRows.map { it.id to it.text }
                rows.forEach { (itemId, title) ->
                    val plan = snapshot.checkInItems.firstOrNull { it.sourceMemoID == memo.id && it.sourceMemoChecklistItemID == itemId }
                    val hint = if (plan == null) hints[itemId] else null
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                        when {
                            plan != null -> editing = plan
                            // A prefilled row is one undoable write, marked 按备忘预填.
                            hint != null -> viewModel.convertMemoWithHint(memo.id, itemId, hint.day ?: day, hint.category ?: category)
                            else -> viewModel.convertMemo(memo.id, itemId, day, category)
                        }
                    }.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = if (hint != null) 10.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, fontSize = 17.sp)
                            // The small line opens this row's own date and type.
                            if (hint != null) Row(Modifier.heightIn(min = 28.dp).clickable { editingHint = itemId to title },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                DraftSeal(15)
                                Text(hint.text(), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Text(if (plan == null) "转换" else "查看计划", fontSize = 15.sp, color = iosSecondaryLabel, modifier = Modifier.padding(start = 12.dp))
                    }
                    FormSeparator()
                }
            } }
            item { WorkflowInfoSection("保留原备忘，标题和内容带入新计划。同一备忘或清单项不会重复转换；已转换的条目可直接查看计划。") }
        }
        DraftUndoToast(viewModel, bottomPadding = 18.dp)
        }
    }
    editing?.let { PlanEditorScreenDialog(it, day, viewModel) { editing = null } }
    editingHint?.let { (itemId, title) ->
        val hint = hints[itemId]
        if (hint != null) ConversionHintSheet(title, hint.day ?: day, hint.category ?: category, categoryChoices(viewModel, hint.category ?: category),
            // Only what was changed here is written into the row; untouched fields keep following the shared ones above.
            done = { changedDay, changedCategory ->
                hints = hints + (itemId to ConversionHint(changedDay ?: hint.day, changedCategory ?: hint.category)); editingHint = null
            },
            drop = { hints = hints - itemId; editingHint = null },
            onDismiss = { editingHint = null })
    }
}

/** 这一行转成计划: one row's own date and type, or back to the ones above (B5). */
@Composable
private fun ConversionHintSheet(title: String, initialDay: LocalDate, initialCategory: String, choices: List<Pair<String, String>>,
                                done: (changedDay: LocalDate?, changedCategory: String?) -> Unit, drop: () -> Unit, onDismiss: () -> Unit) {
    // Shown as the row will be converted: the shared day above stays as it is unless changed here.
    var day by remember { mutableStateOf(initialDay) }
    var category by remember { mutableStateOf(initialCategory) }
    val pick = rememberDatePicker { day = it }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen(sheetFraction = 0.532f) {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar("这一行转成计划", "", "完成", onDismiss, { done(day.takeIf { it != initialDay }, category.takeIf { it != initialCategory }) })
                LazyColumn(contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // As on iOS: the row's words first, in secondary text, then its day and type.
                    item { FormSection {
                        Text(title, Modifier.fillMaxWidth().heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 20.dp, vertical = 11.dp),
                            fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FormSeparator()
                        FormDateRow("日期", day) { pick(day) }; FormSeparator()
                        FormChoiceRow("类型", category, choices) { category = it }
                    } }
                    item { FormSection {
                        Text("不用这行的预填", Modifier.fillMaxWidth().heightIn(min = 44.dp).wrapContentHeight().clickable(onClick = drop).padding(horizontal = 20.dp),
                            fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
                    } }
                }
            }
        }
    }
}

@Composable
fun WorkflowWeekSection(viewModel: MojiViewModel, week: LocalDate) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val key = workflowWeek(week).toString()
    val summary = workflowSummary(snapshot, week)
    val reflection = snapshot.weeklyReflections.firstOrNull { it.weekStart == key }
    var goalEditor by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<WeeklyGoal?>(null) }
    var reflectionEditor by remember { mutableStateOf(false) }
    var carryEditor by remember { mutableStateOf(false) }
    val showsAI = rememberAIShowsEntries()
    var nextWeekDrafts by remember { mutableStateOf(false) }
    val proactive = com.raydon.moji.android.ai.AIProactiveStore.shared
    val handledWeeks by proactive.nextWeekHandled.collectAsStateWithLifecycle()
    var nextWeekNotice by remember(key) { mutableStateOf<String?>(null) }
    // 下周草稿 is offered once the 周复盘 is saved, for this week and last week only, until × or used (B3).
    val offersNextWeek = AIDrafting.showsNextWeekOffer(reflection, week, LocalDate.now(), showsAI, key in handledWeeks)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("计划 / 实际", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row { WorkflowMetric("待完成", "${summary.pending.size} 项", Modifier.weight(1f)); WorkflowMetric("结转待办", "${summary.carryover.size} 项", Modifier.weight(1f)); WorkflowMetric("未记录", "${summary.untrackedCount} 项", Modifier.weight(1f)) }
                Row { WorkflowMetric("预计投入", "${summary.estimatedMinutes} 分钟", Modifier.weight(1f)); WorkflowMetric("实际专注", "${summary.actualMinutes} 分钟", Modifier.weight(1f)) }
                Text("勾选完成不等于已计时；未计时的完成计划显示“未记录”，不计入专注时长。", fontSize = 12.sp, color = iosSecondaryLabel)
                summary.scheduled.forEach { item ->
                    val timed = snapshot.records.filter { it.checkInItemID == item.id && (it.scheduleKind ?: "exactTime") == "exactTime" }
                    val duration = workflowSummary(snapshot.copy(records = timed), week).actualMinutes
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, fontSize = 15.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (item.plannedDurationEnabled ?: ((item.scheduleKind ?: "exactTime") == "exactTime" && item.detailsConfigured != false)) "预计 ${item.plannedMinutes} 分钟" else "未估算", fontSize = 12.sp, color = iosSecondaryLabel)
                            Text(if (timed.isEmpty()) "未记录" else "实际 $duration 分钟", fontSize = 12.sp, color = iosSecondaryLabel)
                        }
                    }
                }
            }
        }
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkflowCardHeading("周目标", "设置目标") { editingGoal = null; goalEditor = true }
                val goals = snapshot.weeklyGoals.filter { it.weekStart == key }
                if (goals.isEmpty()) Text("可选：给这一周一个轻量目标", fontSize = 15.sp, color = iosSecondaryLabel)
                goals.forEach { goal ->
                    val progress = workflowSummary(snapshot, week, goal.category)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(goal.category?.let(::categoryName) ?: "全部类型", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { editingGoal = goal; goalEditor = true })
                        goal.targetCount?.let { GoalProgress("完成计划", progress.completed.size, it, "项") }
                        goal.targetMinutes?.let { GoalProgress("实际专注", progress.actualMinutes, it, "分钟") }
                    }
                }
            }
        }
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkflowCardHeading("周复盘", "编辑") { reflectionEditor = true }
                Text(reflection?.note?.takeIf { it.isNotEmpty() } ?: "记录这一周做得好的事、遇到的问题。", fontSize = 15.sp, color = iosSecondaryLabel)
                Text("下周关注", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(reflection?.nextFocus?.takeIf { it.isNotEmpty() } ?: "给下周留下一句提醒。", fontSize = 15.sp, color = iosSecondaryLabel)
                Text("选择待办移至下周", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { carryEditor = true }.padding(vertical = 5.dp))
                if (offersNextWeek) {
                    NextWeekOfferStrip(
                        accept = {
                            aiEntry {
                                // A week without plans has nothing to draft from: say so here, send nothing.
                                if (AIDrafting.weekRecord(viewModel.snapshot.value, workflowWeek(week), java.time.ZonedDateTime.now()).plans.isEmpty()) {
                                    nextWeekNotice = AIRequestError.emptyWeek.message
                                } else {
                                    nextWeekNotice = null
                                    nextWeekDrafts = true
                                }
                            }
                        },
                        dismiss = { proactive.markNextWeekOfferHandled(key) },
                    )
                    nextWeekNotice?.let { Text(it, fontSize = 13.sp, color = iosSecondaryLabel) }
                }
            }
        }
    }
    if (goalEditor) WeeklyGoalDialog(viewModel, week, editingGoal) { goalEditor = false }
    if (reflectionEditor) WeeklyReflectionDialog(viewModel, week, reflection) { reflectionEditor = false }
    if (carryEditor) NextWeekPlansDialog(viewModel, week) { carryEditor = false }
    if (nextWeekDrafts) NextWeekDraftSheet(viewModel, workflowWeek(week)) { nextWeekDrafts = false }
}

/** 下周草稿 for the week after [week]; putting plans in marks that week's offer as used. */
@Composable
fun NextWeekDraftSheet(viewModel: MojiViewModel, week: LocalDate, onDismiss: () -> Unit) {
    PlanDraftSheet(viewModel, "下周草稿", "按这一周的记录和“下周关注”拟的", waitingText = "正在拟下周",
        origin = com.raydon.moji.android.ai.AIWriteText.nextWeekOrigin, summary = { count, _ -> com.raydon.moji.android.ai.AIWriteText.nextWeek(count) },
        onAccepted = { com.raydon.moji.android.ai.AIProactiveStore.shared.markNextWeekOfferHandled(workflowWeek(week).toString()) },
        load = { AIAssistant.shared.draftNextWeek(viewModel.snapshot.value, workflowWeek(week), aiCategories(viewModel)) }, onDismiss = onDismiss)
}

@Composable
private fun WorkflowCardHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(action, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
    }
}

@Composable
private fun WorkflowMetric(title: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(title, fontSize = 12.sp, color = iosSecondaryLabel) }
}

@Composable
private fun GoalProgress(title: String, value: Int, target: Int, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 12.sp); Text("$value/$target $unit", fontSize = 12.sp) }
        LinearProgressIndicator(progress = { (value.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), drawStopIndicator = {})
    }
}

@Composable
fun WeeklyGoalDialog(viewModel: MojiViewModel, week: LocalDate, goal: WeeklyGoal?, onDismiss: () -> Unit) {
    var category by remember { mutableStateOf(goal?.category ?: "all") }
    var count by remember { mutableStateOf(goal?.targetCount?.toString() ?: "") }
    var minutes by remember { mutableStateOf(goal?.targetMinutes?.toString() ?: "") }
    val valid = (count.isBlank() || count.toIntOrNull() in 1..100000) && (minutes.isBlank() || minutes.toIntOrNull() in 1..100000) && (count.isNotBlank() || minutes.isNotBlank())
    val draftId = remember { goal?.id ?: UUID.randomUUID().toString() }
    val requestDismiss = rememberEditorDismiss(category != (goal?.category ?: "all") || count != (goal?.targetCount?.toString() ?: "") || minutes != (goal?.targetMinutes?.toString() ?: ""), onDismiss)
    WorkflowDialog("周目标", requestDismiss, trailing = "保存", enabled = valid, onSave = {
        viewModel.saveWeeklyGoal(WeeklyGoal(draftId, workflowWeek(week).toString(), category.takeIf { it != "all" }, count.toIntOrNull(), minutes.toIntOrNull(), Instant.now().toString()), onSaved = onDismiss)
    }) {
        LazyColumn(contentPadding = PaddingValues(top = 15.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection("${workflowWeek(week)} 起的一周") {
                FormChoiceRow("目标范围", category, listOf("all" to "全部类型") + categoryChoices(viewModel, goal?.category)) { category = it }
                FormSeparator(); FormLabelledNumberField("完成计划（项）", count) { count = it }
                FormSeparator(); FormLabelledNumberField("实际专注（分钟）", minutes) { minutes = it }
            } }
            item { WorkflowInfoSection("至少填写一项正整数目标（1–100000）。专注目标仅统计真实计时，不把勾选完成换算成分钟。") }
            if (goal != null) item { FormSection(topPadding = 11) { Text("删除目标", color = mojiDestructive, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { viewModel.deleteWeeklyGoal(goal.id); onDismiss() }.padding(horizontal = 20.dp, vertical = 11.8.dp)) } }
        }
    }
}

@Composable
fun WeeklyReflectionDialog(viewModel: MojiViewModel, week: LocalDate, reflection: WeeklyReflection?, initialDraft: ReflectionDraft? = null, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(reflection?.note ?: "") }
    var nextFocus by remember { mutableStateOf(reflection?.nextFocus ?: "") }
    val draftId = remember { reflection?.id ?: UUID.randomUUID().toString() }
    val requestDismiss = rememberEditorDismiss(note != (reflection?.note ?: "") || nextFocus != (reflection?.nextFocus ?: ""), onDismiss)
    val showsAI = rememberAIShowsEntries()
    val scope = rememberCoroutineScope()
    // 本周来信's 放进复盘 opens with the letter already in the draft area; 放进两栏 still puts it in.
    var draft by remember { mutableStateOf(initialDraft) }
    var isDrafting by remember { mutableStateOf(false) }
    var draftFailure by remember { mutableStateOf<String?>(null) }
    // What the two fields held before a draft was put into them.
    var beforeDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Put into two empty fields by itself (B2): the card then offers 清空 instead of 还原.
    var placedByItself by remember { mutableStateOf(false) }
    val monday = workflowWeek(week)
    // The week as the page opened: whether there is anything to draft from, and the counts for a letter's draft.
    val record = remember(monday) { AIDrafting.weekRecord(viewModel.snapshot.value, monday, ZonedDateTime.now()) }
    fun applyDraft(result: ReflectionDraft) {
        beforeDraft = note to nextFocus
        placedByItself = false
        note = result.note
        if (result.nextFocus.isNotEmpty()) nextFocus = result.nextFocus
    }
    /** [automatic]: opened with both fields empty; the draft goes in only if they still are. */
    fun runDraft(automatic: Boolean = false) {
        isDrafting = true; draftFailure = null
        scope.launch {
            try {
                val result = AIAssistant.shared.draftReflection(viewModel.snapshot.value, monday)
                draft = result
                if (!automatic) applyDraft(result)
                else if (note.isBlank() && nextFocus.isBlank()) {
                    // Into the two empty fields; 清空 takes it out again.
                    note = result.note
                    if (result.nextFocus.isNotEmpty()) nextFocus = result.nextFocus
                    beforeDraft = "" to ""
                    placedByItself = true
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                draftFailure = if (error === AIRequestError.emptyText) "这一周还没有计划记录，没有可以起草的内容。" else error.draftMessage
            } finally { isDrafting = false }
        }
    }
    LaunchedEffect(Unit) {
        // ai-reflection-applied: two fields already written, so 起草复盘 by hand rather than by itself.
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "ai-reflection-applied" && initialDraft == null && note.isBlank() && nextFocus.isBlank()) {
            note = "周一到周三按计划复习，周四开始有点松。"
            nextFocus = "先把顺延的事做掉。"
        }
        // Started by itself when a model is set up, the two fields are empty and the week has plans.
        // Without a model nothing starts and no introduction opens; 起草复盘 stays as it was.
        if (initialDraft == null && com.raydon.moji.android.ai.AISettingsStore.shared.isReady && note.isBlank() && nextFocus.isBlank() && record.plans.isNotEmpty()) runDraft(automatic = true)
        else if (BuildConfig.DEBUG && viewModel.visualQaRoute.value.startsWith("ai-reflection") && draft == null && !isDrafting) runDraft()
    }
    WorkflowDialog("周复盘", requestDismiss, trailing = "保存", onSave = { viewModel.saveWeeklyReflection(WeeklyReflection(draftId, monday.toString(), note, nextFocus, Instant.now().toString()), onSaved = onDismiss) }) {
        LazyColumn(contentPadding = PaddingValues(top = 15.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(30.6.dp)) {
            // The draft sits above the two fields, dashed and stamped. Saving is still 保存.
            if (showsAI) item {
                val current = draft
                if (current != null && !isDrafting) Spacer(Modifier.height(20.dp))
                if (isDrafting) FormSection { Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { InkDraftWaiting("正在起草") } }
                else if (current != null) Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().inkDraftSurface().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val placed = beforeDraft != null
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        DraftSeal(20)
                        Text(when {
                            placedByItself -> "墨记先放了一份草稿，可以直接改或清空"
                            placed -> "已放进下面两栏，保存前可以改"
                            else -> "草稿，还没有放进两栏"
                        }, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (!placed) {
                        Text(current.note, fontSize = 15.sp, lineHeight = 20.sp)
                        if (current.nextFocus.isNotEmpty()) Text("下周关注：${current.nextFocus}", fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    // Counted on the device, not by the model (A8), above what the draft rests on: the same
                    // counts the request sent, kept with the draft (a letter's draft takes the week as the page opened).
                    Text((current.stats ?: record.stats).line, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.secondary)
                    if (current.evidence.isNotEmpty()) {
                        InkDivider(opacity = 0.4f * 0.30f, seed = 17)
                        // The plans behind a point as they are now; a letter's points carry no ids and do not open.
                        ReflectionEvidenceList(current.evidence, plansOf = { point -> point.planIds.mapNotNull { id -> viewModel.snapshot.value.checkInItems.firstOrNull { it.id == id } } },
                            initiallyOpen = if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "ai-reflection-auto") current.evidence.firstOrNull()?.id else null)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        when {
                            !placed -> {
                                Text("放进两栏", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { applyDraft(current) })
                                Text("不用", fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.clickable { draft = null })
                            }
                            placedByItself -> Text("清空", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                                note = ""; nextFocus = ""
                                beforeDraft = null; draft = null; placedByItself = false
                            })
                            else -> Text("还原", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                                beforeDraft?.let { note = it.first; nextFocus = it.second }
                                beforeDraft = null; draft = null
                            })
                        }
                    }
                }
                else FormSection(footer = "按这一周的计划记录起草，并注明依据的是哪几条计划。") {
                    FormRow("起草复盘", onClick = { aiEntry { runDraft() } }, titleColor = MaterialTheme.colorScheme.primary)
                    draftFailure?.let { FormSeparator(); Text(it, fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp)) }
                }
            }
            item { FormSection("本周复盘") { FormTextField(note, { note = it }, "做得好的事、遇到的问题", singleLine = false, minHeight = 131) } }
            item { FormSection("下周关注") { FormTextField(nextFocus, { nextFocus = it }, "给下周留下一句提醒", singleLine = false, minHeight = 87) } }
        }
    }
}

@Composable
fun NextWeekPlansDialog(viewModel: MojiViewModel, week: LocalDate, onDismiss: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val summary = workflowSummary(snapshot, week)
    val candidates = (summary.carryover + summary.pending).sortedBy { it.scheduledStart }
    var selected by remember { mutableStateOf(setOf<String>()) }
    WorkflowDialog("移至下周", onDismiss, trailing = "移动 ${selected.size} 项", enabled = selected.isNotEmpty(), onSave = { viewModel.batchPlans(selected, nextWeekOf = week); onDismiss() }) {
        LazyColumn(contentPadding = PaddingValues(top = 34.7.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(32.4.dp)) {
            item { FormSection { WorkflowCaption("移动原计划到下周对应星期，保留时间和说明，不复制、不改变重复序列。专注中的计划不会移动。") } }
            item { FormSection("待办 ${candidates.size} 项") {
                FormRow("全选", onClick = { selected = candidates.map { it.id }.toSet() })
                if (candidates.isEmpty()) FormRow("没有可结转的待办")
                candidates.forEachIndexed { index, item ->
                    // Each separator belongs to the row above it: 全选 is inset to 20, a selectable row to its text.
                    FormSeparator(if (index == 0) 20.dp else 47.7.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { selected = if (item.id in selected) selected - item.id else selected + item.id }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionCircle(item.id in selected); Spacer(Modifier.width(12.dp)); Text(item.title, fontSize = 17.sp, modifier = Modifier.weight(1f)); Text(Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 12.sp, color = iosSecondaryLabel)
                    }
                }
            } }
        }
    }
}
