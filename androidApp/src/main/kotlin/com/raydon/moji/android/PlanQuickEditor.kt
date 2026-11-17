package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.data.PlanQuickChange
import com.raydon.moji.core.CheckInItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class PlanQuickField(val label: String, val height: Int) {
    CONTENT("编辑内容", 500), DATE("修改日期", 300), SCHEDULE("修改安排", 540),
    CATEGORY("修改类型", 380), RECURRENCE("重复计划", 430), REMINDER("计划提醒", 430)
}

@Composable
fun PlanQuickEditorDialog(item: CheckInItem, field: PlanQuickField, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val original = remember(item.id, field) { item }
    val initialDate = remember(original) { Instant.parse(original.scheduledStart).atZone(ZoneId.systemDefault()) }
    var title by remember { mutableStateOf(original.title) }
    var note by remember { mutableStateOf(original.note) }
    var day by remember { mutableStateOf(initialDate.toLocalDate()) }
    var time by remember { mutableStateOf(initialDate.toLocalTime().withSecond(0).withNano(0)) }
    var schedule by remember { mutableStateOf(original.scheduleKind ?: "exactTime") }
    var category by remember { mutableStateOf(original.category) }
    var durationEnabled by remember { mutableStateOf(original.plannedDurationEnabled ?: (original.detailsConfigured != false && schedule == "exactTime")) }
    var minutes by remember { mutableIntStateOf(original.plannedMinutes.coerceIn(1, 1440)) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    val customWeekdayQA = BuildConfig.DEBUG && qaRoute == "plan-quick-recurrence-custom"
    val newCategoryQA = BuildConfig.DEBUG && qaRoute == "plan-quick-category-new"
    var recurrence by remember { mutableStateOf(if (customWeekdayQA) "customWeekdays" else original.repeatRule ?: "never") }
    var weekdays by remember { mutableStateOf(if (customWeekdayQA) setOf(2, 4, 6) else original.repeatWeekdays.orEmpty().takeIf { it.isNotEmpty() }?.toSet() ?: setOf(initialDate.dayOfWeek.value % 7 + 1)) }
    var reminder by remember { mutableIntStateOf(original.reminderMinutesBefore ?: -1) }
    var adding by remember { mutableStateOf(newCategoryQA) }
    var newCategoryName by remember { mutableStateOf(if (newCategoryQA) "阅读与运动" else "") }
    val revision by viewModel.settingsRevision.collectAsStateWithLifecycle()
    val categories = remember(revision, category) { (listOf("study" to "学习", "work" to "工作") + viewModel.customCategories.map { "custom:$it" to it } + listOf(category to categoryName(category))).distinctBy { it.first } }
    val pickDate = rememberDatePicker { day = it }
    val pickTime = rememberTimePicker { time = it }
    val change: PlanQuickChange = when (field) {
        PlanQuickField.CONTENT -> PlanQuickChange.Content(title, note)
        PlanQuickField.DATE -> PlanQuickChange.Date(day)
        PlanQuickField.SCHEDULE -> PlanQuickChange.Schedule(schedule, time, if (durationEnabled) minutes else null)
        PlanQuickField.CATEGORY -> PlanQuickChange.Category(category)
        PlanQuickField.RECURRENCE -> PlanQuickChange.Recurrence(recurrence, weekdays)
        PlanQuickField.REMINDER -> PlanQuickChange.Reminder(reminder.takeIf { it >= 0 })
    }
    val initialChange = remember { change }
    val dismiss = rememberEditorDismiss(change != initialChange || newCategoryName.isNotEmpty(), onDismiss)
    val canSave = change.applyTo(original) != null
    var titleFocused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen(sheetHeight = (field.height + 34).dp) {
            // The Dialog window has its own focus owner.
            val focusManager = LocalFocusManager.current
            Column(Modifier.fillMaxSize()) {
                // Capsule().fill(Color.primary.opacity(0.23)): the label colour, not ink.
                Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(5.dp).background(quickLabel.copy(alpha = 0.23f), CircleShape))
                }
                // iOS draws its own 44pt header here (ZStack .frame(height: 44)), not a 56pt sheet navigation bar.
                // iOS: .disabled(!canSave && !isTitleFocused); tapping commits the IME, then save() guards canSave.
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    MojiSheetBar(field.label, "取消", "保存", dismiss, {
                        focusManager.clearFocus()
                        if (canSave) viewModel.quickUpdatePlan(item.id, change, onDismiss)
                    }, canSave || (field == PlanQuickField.CONTENT && titleFocused), transparentBackground = true)
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (field != PlanQuickField.CONTENT) Text(original.title, fontSize = 15.sp, color = quickSecondary)
                    when (field) {
                        PlanQuickField.CONTENT -> {
                            QuickCaption("标题")
                            Box(Modifier.onFocusChanged { titleFocused = it.hasFocus }) { QuickTextInput(title, { title = it }, "计划标题", false) }
                            QuickCaption("备注")
                            QuickTextInput(note, { note = it }, "添加背景、步骤或注意事项", true)
                        }
                        PlanQuickField.DATE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("今天" to 0, "明天" to 1, "一周后" to 7).forEach { (label, offset) ->
                                    Box(Modifier.weight(1f).heightIn(min = 44.dp).quickSurface().clickable { day = LocalDate.now().plusDays(offset.toLong()) }, contentAlignment = Alignment.Center) { Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                            Box(Modifier.quickSurface()) { FormDateRow("日期", day, minHeight = 58, horizontalPadding = 12) { pickDate(day) } }
                            QuickCaption("修改日期会保留原有时段、预计投入和重复规则。")
                        }
                        PlanQuickField.SCHEDULE -> {
                            QuickChoices(scheduleOptions, schedule) { schedule = it }
                            if (schedule == "exactTime") Row(Modifier.fillMaxWidth().quickSurface().heightIn(min = 44.dp).clickable { pickTime(time) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("开始时间", fontSize = 17.sp, modifier = Modifier.weight(1f))
                                // Compact DatePicker capsule: tertiarySystemFill, 70 x 35.7pt, radius 8.
                                Text(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), fontSize = 17.sp,
                                    modifier = Modifier.background(iosFill, RoundedCornerShape(8.dp)).padding(horizontal = 13.65.dp, vertical = 8.dp))
                            }
                            Column(Modifier.quickSurface()) {
                                // Toggle and Stepper labels/glyphs are label-coloured here; the amount buttons take the tint.
                                QuickLabelTheme {
                                    FormToggle("预计投入", durationEnabled, { durationEnabled = it }, horizontalPadding = 12)
                                    if (durationEnabled) {
                                        FormStepper(editorDuration(minutes.toLong()), "", { minutes = (minutes - 1).coerceAtLeast(1) }, { minutes = (minutes + 1).coerceAtMost(1440) }, horizontalPadding = 12)
                                        Row(Modifier.padding(horizontal = 12.dp)) {
                                            listOf(15, 25, 45, 60).forEach { amount ->
                                                Box(Modifier.weight(1f).heightIn(min = 36.dp).clickable { minutes = amount }, contentAlignment = Alignment.Center) { Text("$amount 分钟", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        PlanQuickField.CATEGORY -> {
                            QuickChoices(categories, category) { category = it }
                            // HStack { TextField; Button }.padding(12): 12 + 22pt field + 12.
                            if (adding) Row(Modifier.quickSurface().heightIn(min = 46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(newCategoryName, { newCategoryName = it.take(20) }, Modifier.weight(1f), singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = quickLabel),
                                    decorationBox = { inner -> if (newCategoryName.isEmpty()) Text("例如：阅读、运动", color = MaterialTheme.colorScheme.secondary); inner() })
                                Text("添加", Modifier.clickable(enabled = newCategoryName.isNotBlank()) {
                                    viewModel.addCategory(newCategoryName); category = "custom:${newCategoryName.trim()}"; newCategoryName = ""; adding = false
                                }, color = MaterialTheme.colorScheme.primary.copy(alpha = if (newCategoryName.isNotBlank()) 1f else 0.35f))
                            } else Row(Modifier.heightIn(min = 44.dp).clickable { adding = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                // Label("新建类型", systemImage: "plus"), tinted: 13.7pt plus in a 17pt slot.
                                Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Plus, 16.4.dp, MaterialTheme.colorScheme.primary) }
                                Text("新建类型", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        PlanQuickField.RECURRENCE -> {
                            QuickChoices(repeatOptions, recurrence) { recurrence = it }
                            if (recurrence == "customWeekdays") Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf(2, 3, 4, 5, 6, 7, 1).forEachIndexed { index, weekday ->
                                    Box(Modifier.weight(1f).height(36.dp).background(if (weekday in weekdays) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape).clickable {
                                        weekdays = if (weekday in weekdays) if (weekdays.size > 1) weekdays - weekday else weekdays else weekdays + weekday
                                    }, contentAlignment = Alignment.Center) { Text("一二三四五六日"[index].toString(), fontSize = 14.sp, color = if (weekday in weekdays) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                        }
                        PlanQuickField.REMINDER -> {
                            val options = listOf(-1 to "不提醒", 0 to "开始时", 5 to "提前 5 分钟", 15 to "提前 15 分钟", 30 to "提前 30 分钟", 60 to "提前 1 小时", 1440 to "提前 1 天")
                            val values = options + if (options.any { it.first == reminder }) emptyList() else listOf(reminder to "提前 $reminder 分钟")
                            QuickChoices(values.map { it.first.toString() to it.second }, reminder.toString()) { reminder = it.toInt() }
                        }
                    }
                    if (original.calendarEventIdentifier != null && field in listOf(PlanQuickField.DATE, PlanQuickField.SCHEDULE)) QuickCaption("这里修改计划，已写入系统日历的事件需在日历中另行修改。")
                }
            }
        }
    }
}

@Composable
private fun Modifier.quickSurface() = background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(12.dp))

/** SwiftUI .primary / .secondary in this sheet: black or white, the latter at 50%. */
private val quickLabel: Color @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color.Black
private val quickSecondary: Color @Composable get() = quickLabel.copy(alpha = 0.5f)

@Composable
private fun QuickLabelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = quickLabel), content = content)
}

@Composable
private fun QuickCaption(value: String) { Text(value, fontSize = 12.sp, color = quickSecondary) }

@Composable
private fun QuickTextInput(value: String, onChange: (String) -> Unit, placeholder: String, multiline: Boolean) {
    BasicTextField(value, onChange, Modifier.fillMaxWidth().quickSurface().heightIn(min = if (multiline) 204.dp else 44.dp).padding(12.dp), singleLine = !multiline,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = if (multiline) 16.sp else 17.sp, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, fontSize = if (multiline) 16.sp else 17.sp, color = MaterialTheme.colorScheme.secondary); inner() } })
}

@Composable
private fun QuickChoices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().quickSurface()) {
        options.forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { this.selected = value == selected }.clickable(role = Role.RadioButton) { onSelect(value) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                QuickCheckmark(value == selected)
            }
        }
    }
}

/** SF Symbol "checkmark" at 15pt semibold: 13.3 x 13pt ink, 2pt stroke, ink ending 2pt inside the row inset. */
@Composable
private fun QuickCheckmark(visible: Boolean) {
    val color = quickLabel
    androidx.compose.foundation.Canvas(Modifier.size(15.3.dp, 14.dp)) {
        if (!visible) return@Canvas
        val d = density
        val path = Path().apply { moveTo(1f * d, 7.4f * d); lineTo(4.9f * d, 12.5f * d); lineTo(12.3f * d, 1.5f * d) }
        drawPath(path, color, style = Stroke(2f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
