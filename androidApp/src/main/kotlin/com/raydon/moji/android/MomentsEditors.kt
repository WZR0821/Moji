package com.raydon.moji.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raydon.moji.core.CountdownEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CountdownEditorScreenDialog(existing: CountdownEvent?, viewModel: MojiViewModel, context: String = "upcoming", onDismiss: () -> Unit) {
    val oldDate = existing?.let { Instant.parse(it.targetDate).atZone(ZoneId.systemDefault()).toLocalDate() }
    val draftId = remember(existing?.id) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var date by remember(existing?.id) { mutableStateOf(oldDate ?: if (context == "past") LocalDate.now().minusDays(1) else LocalDate.now().plusDays(1)) }
    var repeat by remember(existing?.id) { mutableStateOf(existing?.repeatRule ?: "never") }
    var includesToday by remember(existing?.id) { mutableStateOf(existing?.includesToday ?: false) }
    var pinned by remember(existing?.id) { mutableStateOf(existing?.isPinned ?: false) }
    var calendarSync by remember(existing?.id) { mutableStateOf(existing?.calendarSyncEnabled ?: false) }
    var advanced by remember { mutableStateOf(false) }
    val picker = rememberDatePicker { date = it }
    val summary = buildList { add(countdownRepeatLabel(repeat)); if (includesToday) add("含当天"); if (pinned) add("重点"); if (calendarSync || existing?.calendarEventIdentifier != null) add("日历") }.joinToString(" · ")
    val occurrence = com.raydon.moji.core.CountdownPolicy.nextOccurrenceDate(date.toKotlinDate(), repeat, LocalDate.now().toKotlinDate())
    val preview = com.raydon.moji.core.CountdownPolicy.dayCount(LocalDate.parse(occurrence.toString()).atStartOfDay(ZoneId.systemDefault()).toInstant().toString(), Instant.now().toString(), includesToday, ZoneId.systemDefault().id)
    Dialog(onDismissRequest = { if (advanced) advanced = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(if (advanced) "更多设置" else if (existing != null) "编辑这个日子" else if (context == "past") "添加纪念日" else "添加倒数日",
                    if (advanced) "返回" else "取消", if (advanced) "" else "保存", { if (advanced) advanced = false else onDismiss() }, {
                        viewModel.saveCountdown(draftId, title, date, repeat, includesToday, pinned, calendarSync,
                            existing?.symbolName ?: if (context == "past") "seal" else "hourglass", existing?.colorName ?: "vermilion", onSaved = onDismiss)
                    }, title.isNotBlank())
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (!advanced) {
                        item { FormSection {
                            FormTextField(title, { title = it }, "这个日子叫什么？"); FormSeparator()
                            FormDateRow("日期", date) { picker(date) }
                        } }
                        item { FormSection {
                            Row(Modifier.fillMaxWidth().clickable { advanced = true }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("更多设置", fontSize = 17.sp); Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                                MojiLineIcon(MojiIcon.ChevronRight, 17.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                            }
                        } }
                    } else {
                        item { FormSection(footer = "例如生日可设为每年重复；包含当天适合“第几天”的纪念方式。") {
                            FormChoiceRow("重复", repeat, listOf("never" to "不重复", "weekly" to "每周", "monthly" to "每月", "yearly" to "每年")) { repeat = it }
                            FormSeparator(); FormToggle("包含当天（+1）", includesToday, { includesToday = it })
                            FormSeparator(); FormToggle("重点标记", pinned, { pinned = it })
                        } }
                        item { FormSection("计日预览", "未来日期自动倒数，过去日期自动正数；重复纪念日按下一次发生日期倒数。") {
                            FormRow("方式", if (preview == 0) "今天" else if (preview < 0) "正数日" else "倒数日"); FormSeparator()
                            FormRow("显示", if (preview == 0) "今天" else if (preview < 0) "已过 ${kotlin.math.abs(preview)} 天" else "还有 $preview 天")
                        } }
                        item { FormSection(footer = if (existing?.calendarEventIdentifier == null) "保存时写入为全天事件，并沿用上方重复规则。" else "日历为单向添加；此处后续修改不会覆盖日历中的事件。") {
                            if (existing?.calendarEventIdentifier == null) FormToggle("写入系统日历", calendarSync, { calendarSync = it }) else FormRow("已写入系统日历")
                        } }
                    }
                }
            }
        }
    }
}

@Composable
fun PomodoroDurationDialog(phase: String, currentMinutes: Int, viewModel: MojiViewModel, onDismiss: () -> Unit) {
    val maximum = when (phase) { "shortBreak" -> 30; "longBreak" -> 60; else -> 120 }
    var minutes by remember(phase) { mutableIntStateOf(currentMinutes) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ConfigureDialogWindow()
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.padding(top = 6.dp, bottom = 20.dp)) {
                MojiSheetBar("${phaseName(phase)}时长", "取消", "保存", onDismiss, { viewModel.setPhaseMinutes(phase, minutes); onDismiss() })
                Text("选择一个常用时长，或使用下方步进器微调。", modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 13.sp)
                DurationPills(phasePresets(phase)) { minutes = it }
                FormSection {
                    FormStepper("时长", "$minutes 分钟", { minutes = (minutes - 1).coerceAtLeast(1) }, { minutes = (minutes + 1).coerceAtMost(maximum) })
                }
                Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前选择", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), modifier = Modifier.weight(1f))
                    Text("$minutes 分钟", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                }
            }
        }
    }
}

private fun countdownRepeatLabel(raw: String): String = when (raw) {
    "yearly" -> "每年"
    "monthly" -> "每月"
    "weekly" -> "每周"
    else -> "不重复"
}
