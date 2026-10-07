package com.raydon.moji.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raydon.moji.core.CheckInItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

@Composable
fun PlanEditorScreenDialog(
    existing: CheckInItem?,
    initialDate: LocalDate,
    viewModel: MojiViewModel,
    initialKind: String = "planned",
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
    val draftId = remember(existing?.id) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    val oldDate = existing?.let { Instant.parse(it.scheduledStart).atZone(zone) }
    var kind by remember(existing?.id) { mutableStateOf(if (existing?.kind == "completedLog") "completed" else existing?.kind ?: initialKind) }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var note by remember(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var category by remember(existing?.id) { mutableStateOf(existing?.category ?: viewModel.defaultCategory) }
    var date by remember(existing?.id) { mutableStateOf(oldDate?.toLocalDate() ?: initialDate) }
    var time by remember(existing?.id) { mutableStateOf(oldDate?.toLocalTime()?.withSecond(0)?.withNano(0) ?: now.toLocalTime()) }
    var schedule by remember(existing?.id) { mutableStateOf(existing?.scheduleKind ?: viewModel.defaultScheduleKind) }
    var plannedDurationEnabled by remember(existing?.id) { mutableStateOf(existing?.plannedDurationEnabled ?: viewModel.defaultPlannedDurationEnabled) }
    var plannedMinutes by remember(existing?.id) { mutableStateOf(existing?.plannedMinutes ?: viewModel.defaultPlannedMinutes) }
    var actualStart by remember(existing?.id) {
        mutableStateOf(existing?.actualStartDate?.let { Instant.parse(it).atZone(zone).toLocalTime() } ?: now.minusMinutes(plannedMinutes.toLong()).toLocalTime())
    }
    var actualEnd by remember(existing?.id) {
        mutableStateOf(existing?.actualEndDate?.let { Instant.parse(it).atZone(zone).toLocalTime() } ?: now.toLocalTime())
    }
    var endDate by remember(existing?.id) { mutableStateOf(existing?.actualEndDate?.let { Instant.parse(it).atZone(zone).toLocalDate() } ?: date) }
    var weekdays by remember(existing?.id) { mutableStateOf(existing?.repeatWeekdays?.takeIf { it.isNotEmpty() } ?: listOf(date.dayOfWeek.value % 7 + 1)) }
    val pickEndDate = rememberDatePicker { endDate = it }
    var repeatRule by remember(existing?.id) { mutableStateOf(existing?.repeatRule ?: "never") }
    var reminderEnabled by remember(existing?.id) { mutableStateOf(existing?.reminderMinutesBefore != null) }
    var reminderMinutes by remember(existing?.id) { mutableStateOf(existing?.reminderMinutesBefore ?: 10) }
    var calendarSync by remember(existing?.id) { mutableStateOf(existing?.calendarSyncEnabled ?: false) }
    var advanced by remember { mutableStateOf(plannedDurationEnabled || repeatRule != "never" || reminderEnabled || calendarSync) }
    val pickDate = rememberDatePicker { if (endDate == date) endDate = it; date = it }
    val pickTime = rememberTimePicker { time = it }
    val pickActualStart = rememberTimePicker { actualStart = it }
    val pickActualEnd = rememberTimePicker { actualEnd = it }
    val listState = rememberLazyListState()
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val qaScrollInset = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(
                    title = if (kind == "completed") "记录已做" else if (existing == null) "计划要做" else "编辑计划",
                    leading = "取消",
                    trailing = "保存",
                    onLeading = onDismiss,
                    trailingEnabled = title.isNotBlank() && (kind != "completed" || schedule != "exactTime" || endDate.atTime(actualEnd).isAfter(date.atTime(actualStart))) && (kind != "planned" || repeatRule != "customWeekdays" || weekdays.isNotEmpty()),
                    onTrailing = {
                        viewModel.savePlan(
                            existingId = draftId,
                            title = title,
                            note = note,
                            date = date,
                            time = if (kind == "completed") actualStart else time,
                            scheduleKind = schedule,
                            category = category,
                            plannedMinutes = plannedMinutes,
                            repeatRule = if (kind == "completed") "never" else repeatRule,
                            weekdays = weekdays,
                            reminderMinutes = if (kind == "planned" && reminderEnabled) reminderMinutes else null,
                            calendarSync = calendarSync,
                            kind = kind,
                            actualStart = if (kind == "completed") date.atTime(if (schedule == "exactTime") actualStart else LocalTime.of(when (schedule) { "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0 }, 0)) else null,
                            actualEnd = if (kind == "completed") if (schedule == "exactTime") endDate.atTime(actualEnd) else date.atTime(LocalTime.of(when (schedule) { "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0 }, 0)) else null,
                            plannedDurationEnabled = plannedDurationEnabled,
                            onSaved = onDismiss,
                        )
                    },
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = 27.dp, bottom = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(19.dp),
                ) {
                    item {
                        FormSection("记录方式") {
                            FormSegmentedControl(
                                listOf("planned" to "计划要做", "completed" to "记录已做"),
                                kind,
                            ) {
                                if (it != kind && it == "completed") { date = now.toLocalDate(); endDate = date; actualEnd = now.toLocalTime(); actualStart = now.minusMinutes(plannedMinutes.toLong()).toLocalTime() }
                                kind = it
                            }
                        }
                    }
                    item {
                        FormSection("标题") { FormTextField(title, { title = it }, if (kind == "planned") "例如：完成报告第一章" else "例如：整理会议纪要", focusRequester = titleFocus) }
                    }
                    item {
                        FormSection("详细说明（可选）", "保存后，展开计划即可直接阅读完整内容。") {
                            FormTextField(note, { note = it }, "补充背景、步骤或注意事项", singleLine = false, minHeight = 140)
                        }
                    }
                    item {
                        FormSection("类型") { CategoryFields(category, viewModel) { category = it } }
                    }
                    if (kind == "planned") {
                        item {
                            FormSection("安排到", when (schedule) { "allDay" -> "全天只决定计划在哪一天出现，不要求时长，也不会占用一段日历时间。"; "exactTime" -> "具体时间表示开始点；预计投入仍然是可选项。"; else -> "时段用于清单排序与提醒；是否估算投入时间由下方单独决定。" }) {
                                FormChoiceRow("安排方式", schedule, scheduleOptions) { schedule = it }
                                FormSeparator()
                                if (schedule == "exactTime") {
                                    FormDateTimeRow("开始", date, time, { pickDate(date) }, { pickTime(time) })
                                } else FormDateRow("日期", date) { pickDate(date) }
                            }
                        }
                    } else {
                        item {
                            FormSection("实际发生在", if (schedule == "exactTime") "需要统计实际投入时，可精确记录到分钟。" else "全天与时段记录会计入完成项，但不会被换算成虚假的分钟数。") {
                                FormChoiceRow("记录时段", schedule, scheduleOptions) {
                                    schedule = it
                                    if (it == "exactTime" && !endDate.atTime(actualEnd).isAfter(date.atTime(actualStart))) { val end = date.atTime(actualStart).plusMinutes(plannedMinutes.toLong()); endDate = end.toLocalDate(); actualEnd = end.toLocalTime() }
                                }
                                FormSeparator()
                                if (schedule == "exactTime") {
                                FormDateTimeRow("开始", date, actualStart, { pickDate(date) }, { pickActualStart(actualStart) })
                                FormSeparator()
                                FormDateTimeRow("结束", endDate, actualEnd, { pickEndDate(endDate) }, { pickActualEnd(actualEnd) })
                                FormSeparator()
                                DurationPills { minutes -> val end = date.atTime(actualStart).plusMinutes(minutes.toLong()); endDate = end.toLocalDate(); actualEnd = end.toLocalTime() }
                                FormRow("实际用时", editorDuration(java.time.Duration.between(date.atTime(actualStart), endDate.atTime(actualEnd)).toMinutes().coerceAtLeast(0)))
                                } else {
                                    FormDateRow("日期", date) { pickDate(date) }
                                    EditorCaption("${scheduleOptions.first { it.first == schedule }.second}记录不虚构开始时间或时长")
                                }
                            }
                        }
                    }
                    item {
                        val reminderOptions = listOf("-1" to "不提醒", "0" to "开始时", "5" to "提前 5 分钟", "15" to "提前 15 分钟", "30" to "提前 30 分钟", "60" to "提前 1 小时", "1440" to "提前 1 天")
                        val advancedSummary = buildList {
                            if (kind == "planned" && plannedDurationEnabled) add("预计 ${editorDuration(plannedMinutes.toLong())}")
                            if (kind == "planned" && repeatRule != "never") add(repeatOptions.first { it.first == repeatRule }.second)
                            if (kind == "planned" && reminderEnabled) add(if (reminderMinutes == 0) "开始时提醒" else "${reminderOptions.firstOrNull { it.first == reminderMinutes.toString() }?.second ?: "提前 $reminderMinutes 分钟"}提醒")
                            if (calendarSync) add("已同步日历")
                        }.joinToString(" · ").ifEmpty { "未设置" }
                        FormSection {
                            Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("高级设置", fontSize = 17.sp, fontWeight = FontWeight.Medium); Text(advancedSummary, fontSize = 12.sp, color = if (advancedSummary == "未设置") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) }
                                Box(Modifier.rotate(if (advanced) 90f else 0f)) { MojiLineIcon(MojiIcon.ChevronRight, 14.dp) }
                            }
                            if (advanced) {
                                FormSeparator()
                                Column(Modifier.padding(start = 20.dp, top = 15.dp, bottom = 15.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (kind == "planned") {
                                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    DenseEditorCaption("投入估算（可选）", true)
                                    FormToggle("添加预计投入", plannedDurationEnabled, { plannedDurationEnabled = it }, minHeight = 32)
                                    if (plannedDurationEnabled) {
                                        FormStepper("预计投入", editorDuration(plannedMinutes.toLong()), { plannedMinutes = (plannedMinutes - 1).coerceAtLeast(1) }, { plannedMinutes = (plannedMinutes + 1).coerceAtMost(1440) }, valueStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold), minHeight = 32)
                                        DurationPills(verticalPadding = 0) { plannedMinutes = it }
                                    }
                                    DenseEditorCaption("预计投入用于番茄钟和周总结，与全天或具体时间相互独立。")
                                    }
                                    androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.6.dp)
                                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    DenseEditorCaption("重复与提醒", true)
                                    FormChoiceRow("重复", repeatRule, repeatOptions, minHeight = 32) { repeatRule = it }
                                    if (repeatRule == "customWeekdays") {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(2, 3, 4, 5, 6, 7, 1).forEachIndexed { index, day ->
                                                androidx.compose.material3.Surface(Modifier.weight(1f).height(32.dp).clickable { weekdays = if (day in weekdays) if (weekdays.size > 1) weekdays - day else weekdays else weekdays + day }, shape = androidx.compose.foundation.shape.CircleShape, color = if (day in weekdays) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                                                    Box(contentAlignment = Alignment.Center) { Text("一二三四五六日"[index].toString(), fontSize = 12.sp, color = if (day in weekdays) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.secondary) }
                                                }
                                            }
                                        }
                                    }
                                    FormChoiceRow("提醒", if (reminderEnabled) reminderMinutes.toString() else "-1", reminderOptions + if (reminderOptions.none { it.first == reminderMinutes.toString() }) listOf(reminderMinutes.toString() to "提前 $reminderMinutes 分钟") else emptyList(), minHeight = 32) { reminderEnabled = it != "-1"; if (reminderEnabled) reminderMinutes = it.toInt() }
                                    DenseEditorCaption("重复计划会在完成或跳过后生成下一次；提醒只保存在本机。")
                                    }
                                    androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), thickness = 0.6.dp)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                FormToggle("添加到系统日历", calendarSync, { calendarSync = it }, enabled = existing?.calendarEventIdentifier == null, minHeight = 32)
                                if (existing?.calendarEventIdentifier != null) DenseEditorCaption("已添加到系统日历")
                                DenseEditorCaption("默认不上传。开启后仅单向添加一次；之后如需修改或删除日历事件，请在系统日历中操作。")
                                }
                                }
                            }
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(existing?.id) {
                    // The Dialog has its own composition. Wait until its list is
                    // laid out before requesting focus or capturing a deep row.
                    androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= 6 }
                    if (BuildConfig.DEBUG && viewModel.visualQaRoute.value == "plan-editor-advanced") {
                        listState.scrollToItem(5, -qaScrollInset)
                    }
                    if (existing == null) titleFocus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun DenseEditorCaption(text: String, heading: Boolean = false) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
fun EditorCaption(text: String, heading: Boolean = false) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp))
}

fun editorDuration(minutes: Long) = when { minutes < 60 -> "$minutes 分钟"; minutes % 60 == 0L -> "${minutes / 60} 小时"; else -> "${minutes / 60} 小时 ${minutes % 60} 分钟" }
