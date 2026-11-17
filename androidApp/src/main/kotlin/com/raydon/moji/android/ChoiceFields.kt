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
val repeatOptions = listOf("never" to "不重复", "daily" to "每天", "weekdays" to "每个工作日", "weekly" to "每周", "customWeekdays" to "自选星期", "monthly" to "每月")

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
            // iOS menu Picker: value, glyph and up/down chevron all take the app tint (planPrimary).
            val tint = MaterialTheme.colorScheme.primary
            if (glyph != null) {
                when (glyph) {
                    MojiIcon.Book -> StudyBookGlyph(tint)
                    MojiIcon.Sun -> MojiLineIcon(glyph, 25.dp, tint)
                    // briefcase.fill / tag.fill at the picker's 17pt: 24.4pt wide, centred on the value text.
                    MojiIcon.Work, MojiIcon.Tag -> Box(Modifier.offset(y = 0.8.dp)) { MojiLineIcon(glyph, 29.dp, tint) }
                    else -> MojiLineIcon(glyph, 23.dp, tint)
                }
                Spacer(Modifier.width(if (glyph == MojiIcon.Work || glyph == MojiIcon.Tag) 0.5.dp else 1.dp))
            }
            Text(options.firstOrNull { it.first == value }?.second ?: categoryName(value), fontSize = 17.sp, maxLines = 1, color = tint, modifier = Modifier.widthIn(max = 180.dp))
            // chevron.up.chevron.down: 8.7 x 13 pt ink, right edge 2pt inside the row inset.
            androidx.compose.foundation.Canvas(Modifier.padding(start = 7.dp, end = 2.dp).size(8.67.dp, 13.dp)) {
                val d = density
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.7f*d, 4.3f*d); lineTo(4.33f*d, 0.7f*d); lineTo(7.97f*d, 4.3f*d)
                    moveTo(0.7f*d, 8.7f*d); lineTo(4.33f*d, 12.3f*d); lineTo(7.97f*d, 8.7f*d)
                }
                drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4f*d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
    // SwiftUI Label("新建自定义类型", systemImage: "plus") as a Form button: tinted glyph in a 28pt slot.
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { name = ""; adding = true }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Plus, 21.6.dp, MaterialTheme.colorScheme.primary, strokeWidth = 1.67f) }
        Spacer(Modifier.width(16.dp))
        Text("新建自定义类型", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("新建自定义类型") },
        text = { FormTextField(name, { name = it.take(20) }, "例如：阅读、运动") },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            viewModel.addCategory(name); onSelect("custom:${name.trim()}"); adding = false
        }) { Text("添加") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}

/** SF Symbol book.closed.fill as drawn by iOS beside "学习": 17.7 x 23 pt ink, cover with spine slot and page block. */
@Composable
private fun StudyBookGlyph(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp, 23.dp)) {
        val d = density
        fun x(v: Float) = v * d
        val path = androidx.compose.ui.graphics.Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            // Outer silhouette: rounded cover, chamfered into the page block, flared foot.
            moveTo(x(0f), x(2.8f))
            quadraticTo(x(0f), x(0f), x(2.8f), x(0f))
            lineTo(x(14.87f), x(0f))
            quadraticTo(x(17.67f), x(0f), x(17.67f), x(2.8f))
            lineTo(x(17.67f), x(16.9f))
            quadraticTo(x(17.67f), x(17.2f), x(17.4f), x(17.45f))
            lineTo(x(16.5f), x(18.3f))
            quadraticTo(x(16.2f), x(18.55f), x(16.2f), x(18.9f))
            lineTo(x(16.2f), x(20.7f))
            quadraticTo(x(16.25f), x(21.5f), x(17.67f), x(21.6f))
            lineTo(x(17.67f), x(22.7f))
            quadraticTo(x(17.67f), x(23f), x(17.37f), x(23f))
            lineTo(x(3f), x(23f))
            cubicTo(x(1.34f), x(23f), x(0f), x(21.66f), x(0f), x(20f))
            close()
            // Spine slot.
            addRoundRect(androidx.compose.ui.geometry.RoundRect(x(2.35f), x(1.65f), x(3.35f), x(16.7f), androidx.compose.ui.geometry.CornerRadius(x(0.5f))))
            // Page block opening.
            moveTo(x(14.8f), x(18.33f))
            lineTo(x(2.5f), x(18.33f))
            quadraticTo(x(1.6f), x(18.4f), x(1.6f), x(19.3f))
            lineTo(x(1.6f), x(20.2f))
            cubicTo(x(1.6f), x(21.05f), x(2.25f), x(21.67f), x(3.1f), x(21.67f))
            lineTo(x(14.9f), x(21.67f))
            close()
        }
        drawPath(path, color)
    }
}
