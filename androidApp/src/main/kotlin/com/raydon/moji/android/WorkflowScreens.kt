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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.*
import com.raydon.moji.ui.InkCard
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
    WorkflowDialog("复制计划", onDismiss, trailing = "复制", onSave = { viewModel.copyPlan(item.id, day); onDismiss() }) {
        LazyColumn(contentPadding = PaddingValues(top = 45.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
    val pickFrom = rememberDatePicker { from = it; if (to < it) to = it }
    val pickTo = rememberDatePicker { to = maxOf(from, it) }
    val pickTemplate = rememberDatePicker { templateDay = it }
    val today = LocalDate.now()
    val plans = snapshot.checkInItems.filter { item ->
        val day = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).toLocalDate()
        val matchesStatus = if (status == "archived") item.isArchived == true else item.isArchived != true && (status == "all" || item.status == status)
        val matchesPeriod = when (period) { "past" -> day < today; "today" -> day == today; "future" -> day > today; "range" -> day in from..to; else -> true }
        item.kind == "planned" && (query.isBlank() || item.title.contains(query, true) || item.note.contains(query, true)) &&
            (category == "all" || item.category == category) && matchesStatus && matchesPeriod
    }.sortedByDescending { it.scheduledStart }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "library-batch") {
            selected = plans.take(2).map { it.id }.toSet(); selecting = true; batch = true
        }
    }
    WorkflowDialog("计划库", onDismiss, leading = "", onSave = onDismiss) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 45.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                FormSection {
                    FormSegmentedControl(listOf("plans" to "全部计划", "templates" to "计划模板"), mode) { mode = it; selected = emptySet(); selecting = false }
                    FormSeparator(); FormTextField(query, { query = it }, "搜索标题或说明")
                }
            }
            if (mode == "plans") {
                item {
                    FormSection("筛选") {
                        FormChoiceRow("计划类型", category, listOf("all" to "全部类型") + categoryChoices(viewModel)) { category = it }
                        FormSeparator()
                        FormChoiceRow("状态", status, listOf("all" to "全部状态", "planned" to "待完成", "inProgress" to "进行中", "completed" to "已完成", "skipped" to "已跳过", "archived" to "已归档")) { status = it }
                        FormSeparator()
                        FormChoiceRow("日期", period, listOf("all" to "全部日期", "past" to "过去", "today" to "今天", "future" to "未来", "range" to "日期范围")) { period = it }
                        if (period == "range") { FormSeparator(); FormDateRow("开始日期", from) { pickFrom(from) }; FormSeparator(); FormDateRow("结束日期", to) { pickTo(to) } }
                    }
                }
                item {
                    FormSection(topPadding = 10) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${plans.size} 个计划", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.weight(1f))
                            TextButton(onClick = { selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "取消选择" else "批量选择") }
                        }
                        if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { selected = plans.filter { it.status != "inProgress" }.map { it.id }.toSet() }) { Text("全选结果") }
                            TextButton(enabled = selected.isNotEmpty(), onClick = { batch = true }) { Text("整理 ${selected.size} 项") }
                        }
                        if (plans.isEmpty()) FormRow("没有符合条件的计划")
                        plans.forEach { item ->
                            Box {
                                Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { if (!selecting) menuId = item.id }, onClick = {
                                    if (selecting) selected = if (item.id in selected) selected - item.id else selected + item.id else editing = item
                                }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (selecting) { SelectionCircle(item.id in selected); Spacer(Modifier.width(12.dp)) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(item.title, fontSize = 17.sp)
                                        val date = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日"))
                                        Text("$date · ${categoryName(item.category)} · ${if (item.isArchived == true) "已归档" else planStatusName(item.status)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                item { FormSection(topPadding = 10) { FormDateRow("添加到", templateDay) { pickTemplate(templateDay) } } }
                item {
                    FormSection("计划模板") {
                        if (snapshot.planTemplates.isEmpty()) Text("在计划的更多菜单中选择“保存为模板”", fontSize = 15.sp, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        snapshot.planTemplates.filter { query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true) }.forEach { template ->
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(template.title, fontSize = 17.sp)
                                Text("${categoryName(template.category)} · ${scheduleOptions.first { it.first == template.scheduleKind }.second}${if (template.plannedDurationEnabled) " · ${template.plannedMinutes} 分钟" else ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
    Text(text, fontSize = 13.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp))
}

@Composable
private fun WorkflowInfoSection(text: String) {
    Column(Modifier.padding(top = 12.dp)) { FormSection { WorkflowCaption(text) } }
}

@Composable
private fun PlanBatchDialog(ids: Set<String>, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf("date") }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var category by remember { mutableStateOf("study") }
    var confirm by remember { mutableStateOf(false) }
    val pick = rememberDatePicker { day = it }
    WorkflowDialog("批量整理", onDismiss, trailing = "应用", onSave = { confirm = true }) {
        LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
    WorkflowDialog("备忘转计划", onDismiss, leading = "", onSave = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(top = 48.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection { FormDateRow("计划日期", day) { pick(day) }; FormSeparator(); FormChoiceRow("计划类型", category, categoryChoices(viewModel, category)) { category = it } } }
            item { FormSection("转为计划") {
                val rows = listOf(null to "整则备忘") + if (memo.mode == "checklist") memo.checklistItems.filter { it.text.isNotBlank() }.map { it.id to it.text } else emptyList()
                rows.forEach { (itemId, title) ->
                    val plan = snapshot.checkInItems.firstOrNull { it.sourceMemoID == memo.id && it.sourceMemoChecklistItemID == itemId }
                    FormRow(title, if (plan == null) "转换" else "查看计划", onClick = {
                        if (plan != null) editing = plan else viewModel.convertMemo(memo.id, itemId, day, category)
                    }); FormSeparator()
                }
            } }
            item { WorkflowInfoSection("保留原备忘，标题和内容带入新计划。同一备忘或清单项不会重复转换；已转换的条目可直接查看计划。") }
        }
    }
    editing?.let { PlanEditorScreenDialog(it, day, viewModel) { editing = null } }
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("计划 / 实际", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row { WorkflowMetric("待完成", "${summary.pending.size} 项", Modifier.weight(1f)); WorkflowMetric("结转待办", "${summary.carryover.size} 项", Modifier.weight(1f)); WorkflowMetric("未记录", "${summary.untrackedCount} 项", Modifier.weight(1f)) }
                Row { WorkflowMetric("预计投入", "${summary.estimatedMinutes} 分钟", Modifier.weight(1f)); WorkflowMetric("实际专注", "${summary.actualMinutes} 分钟", Modifier.weight(1f)) }
                Text("勾选完成不等于已计时；未计时的完成计划显示“未记录”，不计入专注时长。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                summary.scheduled.forEach { item ->
                    val timed = snapshot.records.filter { it.checkInItemID == item.id && (it.scheduleKind ?: "exactTime") == "exactTime" }
                    val duration = workflowSummary(snapshot.copy(records = timed), week).actualMinutes
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, fontSize = 15.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (item.plannedDurationEnabled ?: ((item.scheduleKind ?: "exactTime") == "exactTime" && item.detailsConfigured != false)) "预计 ${item.plannedMinutes} 分钟" else "未估算", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text(if (timed.isEmpty()) "未记录" else "实际 $duration 分钟", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
        InkCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkflowCardHeading("周目标", "设置目标") { editingGoal = null; goalEditor = true }
                val goals = snapshot.weeklyGoals.filter { it.weekStart == key }
                if (goals.isEmpty()) Text("可选：给这一周一个轻量目标", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                Text(reflection?.note?.takeIf { it.isNotEmpty() } ?: "记录这一周做得好的事、遇到的问题。", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("下周关注", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(reflection?.nextFocus?.takeIf { it.isNotEmpty() } ?: "给下周留下一句提醒。", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Text("选择待办移至下周", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { carryEditor = true }.padding(vertical = 5.dp))
            }
        }
    }
    if (goalEditor) WeeklyGoalDialog(viewModel, week, editingGoal) { goalEditor = false }
    if (reflectionEditor) WeeklyReflectionDialog(viewModel, week, reflection) { reflectionEditor = false }
    if (carryEditor) NextWeekPlansDialog(viewModel, week) { carryEditor = false }
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
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
    WorkflowDialog("周目标", onDismiss, trailing = "保存", enabled = valid, onSave = {
        viewModel.saveWeeklyGoal(WeeklyGoal(goal?.id ?: UUID.randomUUID().toString(), workflowWeek(week).toString(), category.takeIf { it != "all" }, count.toIntOrNull(), minutes.toIntOrNull(), Instant.now().toString())); onDismiss()
    }) {
        LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection("${workflowWeek(week)} 起的一周") {
                FormChoiceRow("目标范围", category, listOf("all" to "全部类型") + categoryChoices(viewModel, goal?.category)) { category = it }
                FormSeparator(); FormLabelledNumberField("完成计划（项）", count) { count = it }
                FormSeparator(); FormLabelledNumberField("实际专注（分钟）", minutes) { minutes = it }
            } }
            item { WorkflowInfoSection("至少填写一项正整数目标（1–100000）。专注目标仅统计真实计时，不把勾选完成换算成分钟。") }
            if (goal != null) item { FormSection { TextButton(onClick = { viewModel.deleteWeeklyGoal(goal.id); onDismiss() }) { Text("删除目标", color = mojiDestructive) } } }
        }
    }
}

@Composable
fun WeeklyReflectionDialog(viewModel: MojiViewModel, week: LocalDate, reflection: WeeklyReflection?, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(reflection?.note ?: "") }
    var nextFocus by remember { mutableStateOf(reflection?.nextFocus ?: "") }
    WorkflowDialog("周复盘", onDismiss, trailing = "保存", onSave = { viewModel.saveWeeklyReflection(WeeklyReflection(reflection?.id ?: UUID.randomUUID().toString(), workflowWeek(week).toString(), note, nextFocus, Instant.now().toString())); onDismiss() }) {
        LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection("本周复盘") { FormTextField(note, { note = it }, "做得好的事、遇到的问题", singleLine = false, minHeight = 132) } }
            item { FormSection("下周关注") { FormTextField(nextFocus, { nextFocus = it }, "给下周留下一句提醒", singleLine = false, minHeight = 88) } }
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
        LazyColumn(contentPadding = PaddingValues(top = 48.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { FormSection { WorkflowCaption("移动原计划到下周对应星期，保留时间和说明，不复制、不改变重复序列。专注中的计划不会移动。") } }
            item { FormSection("待办 ${candidates.size} 项") {
                FormRow("全选", onClick = { selected = candidates.map { it.id }.toSet() })
                if (candidates.isEmpty()) FormRow("没有可结转的待办")
                candidates.forEach { item ->
                    FormSeparator()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { selected = if (item.id in selected) selected - item.id else selected + item.id }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionCircle(item.id in selected); Spacer(Modifier.width(12.dp)); Text(item.title, fontSize = 17.sp, modifier = Modifier.weight(1f)); Text(Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            } }
        }
    }
}
