package com.raydon.moji.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.ui.InkCard
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun ReviewScreen(
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEnd = weekStart.plusDays(7)
    val weekPlans = snapshot.checkInItems.filter {
        val date = Instant.parse(it.scheduledStart).atZone(zone).toLocalDate()
        date >= weekStart && date < weekEnd
    }
    val completed = weekPlans.count { it.status == "completed" }
    val weekRecords = snapshot.records.filter {
        val date = Instant.parse(it.startDate).atZone(zone).toLocalDate()
        date >= weekStart && date < weekEnd
    }
    val minutes = weekRecords.sumOf { Duration.between(Instant.parse(it.startDate), Instant.parse(it.endDate)).toMinutes().coerceAtLeast(0) }
    val streak = calculateStreak(snapshot.checkInItems.mapNotNull { if (it.status == "completed") it.completedAt?.let(Instant::parse)?.atZone(zone)?.toLocalDate() else null }.toSet())
    val pending = weekPlans.count { it.status == "planned" || it.status == "inProgress" }
    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            item { MojiLargeTitle("回顾", actions = listOf(MojiIcon.Gear to { showSettings = true })) }
            item {
                InkCard(Modifier.padding(horizontal = 18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("本周回顾", style = MaterialTheme.typography.titleLarge)
                                Text("${weekStart.format(DateTimeFormatter.ofPattern("M月d日"))} – ${weekEnd.minusDays(1).format(DateTimeFormatter.ofPattern("M月d日"))}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            Text("完成率 ${if (weekPlans.isEmpty()) 0 else completed * 100 / weekPlans.size}%", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ReviewMetric(if (weekPlans.isEmpty()) "—" else "$completed/${weekPlans.size}", "完成计划", Modifier.weight(1f))
                            ReviewMetric(durationText(minutes), "实际专注", Modifier.weight(1f))
                            ReviewMetric("${streak}天", "连续完成", Modifier.weight(1f))
                        }
                        WeeklyBars(weekRecords)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), thickness = 0.6.dp)
                        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                            MojiLineIcon(if (pending > 0) MojiIcon.Checklist else MojiIcon.Timer, 24.dp)
                            Text(if (pending > 0) "下一步：完成剩余 $pending 项计划" else "下一步：开始一个 25 分钟专注", modifier = Modifier.weight(1f).padding(start = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            MojiLineIcon(MojiIcon.ChevronRight, 19.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    ReviewActionRow(MojiIcon.Chart, "周总结", "查看本周完成、专注和每日趋势")
                    InkDivider(opacity = 0.30f)
                    ReviewActionRow(MojiIcon.Calendar, "月总结", "查看月度热力图和计划/实际对比")
                    InkDivider(opacity = 0.30f)
                    ReviewActionRow(MojiIcon.Note, "时间记录", "查看、补记或修改全部记录")
                }
            }
            item {
                Text("数据本机优先，可自动备份到所选文件夹", modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f), fontSize = 12.sp)
            }
        }
    }
    if (showSettings) SettingsDialog(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRequestPermissions) { showSettings = false }
}

@Composable
private fun ReviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp)
    }
}

@Composable
private fun WeeklyBars(records: List<com.raydon.moji.core.TimeRecord>) {
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val values = (0..6).map { offset ->
        val date = start.plusDays(offset.toLong())
        records.filter { Instant.parse(it.startDate).atZone(zone).toLocalDate() == date }
            .sumOf { Duration.between(Instant.parse(it.startDate), Instant.parse(it.endDate)).toMinutes().coerceAtLeast(0) }
    }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    if (values.all { it == 0L }) {
        Column(Modifier.fillMaxWidth().height(44.dp), verticalArrangement = Arrangement.Bottom) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f), RoundedCornerShape(1.dp)))
            Text("本周暂无专注记录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { index, value ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.width(12.dp).height((4 + 44 * value.toFloat() / max).dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)))
                    Text("一二三四五六日"[index].toString(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ReviewActionRow(icon: MojiIcon, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().height(82.dp), verticalAlignment = Alignment.CenterVertically) {
        MojiLineIcon(icon, 27.dp)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }
        MojiLineIcon(MojiIcon.ChevronRight, 19.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f))
    }
}

@Composable
private fun SettingsDialog(
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar(
                    title = page?.let(::settingsPageTitle) ?: "设置",
                    leading = if (page == null) "" else "返回",
                    trailing = "完成",
                    onLeading = { page = null },
                    onTrailing = onDismiss,
                )
                if (page == null) SettingsIndex { page = it }
                else SettingsDetail(page!!, viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRequestPermissions)
            }
        }
    }
}

@Composable
private fun SettingsIndex(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        item { SectionCaption("偏好", Modifier.padding(horizontal = 4.dp)) }
        item {
            SettingsGroup {
                SettingsRow(MojiIcon.Checklist, "计划默认值", "类型、安排方式与投入估算") { onOpen("defaults") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Grid, "小组件快速添加", "自定义三个桌面预设") { onOpen("widgets") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Timer, "番茄钟", "专注与休息时长") { onOpen("pomodoro") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Timer, "提醒与反馈", "全天提醒、声音与触感") { onOpen("notifications") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Brush, "外观", "明暗、宣纸纹理与运笔动效") { onOpen("appearance") }
            }
            Spacer(Modifier.height(28.dp))
        }
        item { SectionCaption("系统与数据", Modifier.padding(horizontal = 4.dp)) }
        item {
            SettingsGroup {
                SettingsRow(MojiIcon.Hand, "权限", "通知与系统日历") { onOpen("permissions") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Backup, "数据备份", "导出、导入与换签保护") { onOpen("backup") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Info, "关于 Moji", "版本与数据格式") { onOpen("about") }
            }
            Spacer(Modifier.height(28.dp))
        }
        item {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开发者", fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    Text("WANG ZIRUI", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f), fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) { Column { content() } }
}

@Composable
private fun SettingsRow(icon: MojiIcon, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        MojiLineIcon(icon, 25.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        MojiLineIcon(MojiIcon.ChevronRight, 18.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.26f))
    }
}

@Composable
private fun SettingsSeparator() { HorizontalDivider(Modifier.padding(start = 57.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), thickness = 0.6.dp) }

@Composable
private fun SettingsDetail(
    page: String,
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    var carryOver by remember { mutableStateOf(viewModel.carriesOver) }
    var notifications by remember { mutableStateOf(viewModel.notificationsEnabled) }
    var appearance by remember { mutableStateOf(viewModel.appearance) }
    var focusMinutes by remember { mutableStateOf(viewModel.defaultFocusMinutes.toString()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SettingsGroup {
                when (page) {
                    "defaults" -> SettingSwitch("旧计划顺延到今天", carryOver) { carryOver = it; viewModel.setCarryOver(it) }
                    "widgets" -> Text("三个桌面快速添加预设会随本机备份保存。", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f))
                    "pomodoro" -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(focusMinutes, { focusMinutes = it.filter(Char::isDigit) }, label = { Text("默认专注分钟") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { focusMinutes.toIntOrNull()?.let(viewModel::setFocusMinutes) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                    }
                    "notifications" -> Column {
                        SettingSwitch("计划与专注通知", notifications) { notifications = it; viewModel.setNotifications(it) }
                        SettingsSeparator()
                        TextButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) { Text("管理系统权限") }
                    }
                    "appearance" -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (raw, label) -> FilterChip(appearance == raw, { appearance = raw; viewModel.setAppearance(raw) }, { Text(label) }) }
                    }
                    "permissions" -> TextButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("管理通知与系统日历权限") }
                    "backup" -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("备份包含计划、记录、倒数日、备忘与非敏感设置。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("导出") }
                            Button(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Text("恢复") }
                        }
                        Button(onClick = onChooseBackupFolder, modifier = Modifier.fillMaxWidth()) { Text("选择自动备份文件夹") }
                    }
                    "about" -> Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Moji ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold)
                        Text("本地优先 · 无账号 · 无业务服务器", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private fun settingsPageTitle(page: String): String = when (page) {
    "defaults" -> "计划默认值"
    "widgets" -> "小组件快速添加"
    "pomodoro" -> "番茄钟"
    "notifications" -> "提醒与反馈"
    "appearance" -> "外观"
    "permissions" -> "权限"
    "backup" -> "数据备份"
    else -> "关于 Moji"
}

private fun durationText(minutes: Long): String = when {
    minutes >= 60 -> "${minutes / 60}时 ${minutes % 60}分"
    minutes > 0 -> "$minutes 分"
    else -> "0 分"
}

private fun calculateStreak(days: Set<LocalDate>): Int {
    var cursor = LocalDate.now()
    if (cursor !in days) cursor = cursor.minusDays(1)
    var count = 0
    while (cursor in days) { count += 1; cursor = cursor.minusDays(1) }
    return count
}
