package com.raydon.moji.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val scheduleOptions = listOf("allDay" to "全天", "morning" to "上午", "afternoon" to "下午", "evening" to "晚上", "exactTime" to "具体时间")
val repeatOptions = listOf("never" to "不重复", "daily" to "每天", "weekdays" to "工作日", "weekly" to "每周", "monthly" to "每月", "customWeekdays" to "自定义星期")

@Composable
fun FormChoiceRow(title: String, value: String, options: List<Pair<String, String>>, showsValueIcon: Boolean = false, minHeight: Int = 44, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clickable { open = true }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
            val glyph = when {
                showsValueIcon && value != "all" -> when (value) { "work" -> MojiIcon.Work; "study" -> MojiIcon.Book; else -> MojiIcon.Tag }
                title == "安排方式" || title == "记录时段" -> if (value == "exactTime") MojiIcon.Clock else MojiIcon.Sun
                else -> null
            }
            if (glyph != null) { MojiLineIcon(glyph, 23.dp); Spacer(Modifier.width(4.dp)) }
            Text(options.firstOrNull { it.first == value }?.second ?: categoryName(value), fontSize = 17.sp, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp))
            val chevronColor = MaterialTheme.colorScheme.onSurface
            androidx.compose.foundation.Canvas(Modifier.padding(start = 8.dp).size(10.dp, 16.dp)) {
                val d = density
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(1*d, 5*d); lineTo(5*d, 1*d); lineTo(9*d, 5*d)
                    moveTo(1*d, 11*d); lineTo(5*d, 15*d); lineTo(9*d, 11*d)
                }
                drawPath(path, chevronColor.copy(alpha = 0.65f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.7f*d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (raw, label) ->
                DropdownMenuItem(text = { Text(if (raw == value) "✓  $label" else label) }, onClick = { open = false; onSelect(raw) })
            }
        }
    }
}

@Composable
fun CategoryFields(value: String, viewModel: MojiViewModel, onSelect: (String) -> Unit) {
    val revision by viewModel.settingsRevision.collectAsStateWithLifecycle()
    val choices = remember(revision, value) {
        (listOf("study" to "学习", "work" to "工作") + viewModel.customCategories.map { "custom:$it" to it } +
            listOf(value to categoryName(value))).distinctBy { it.first }
    }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    FormChoiceRow("计划类型", value, choices, showsValueIcon = true, onSelect = onSelect)
    FormSeparator()
    FormRow("＋  新建自定义类型", onClick = { name = ""; adding = true })
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("新建自定义类型") },
        text = { FormTextField(name, { name = it.take(20) }, "例如：阅读、运动") },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            viewModel.addCategory(name); onSelect("custom:${name.trim()}"); adding = false
        }) { Text("添加") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}
