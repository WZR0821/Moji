package com.raydon.moji.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.ui.InkCard
import com.raydon.moji.ui.MojiPalette
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
    var carryOver by remember { mutableStateOf(viewModel.carriesOver) }
    var notifications by remember { mutableStateOf(viewModel.notificationsEnabled) }
    var appearance by remember { mutableStateOf(viewModel.appearance) }
    var focusMinutes by remember { mutableStateOf(viewModel.defaultFocusMinutes.toString()) }
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
    val minutes = weekRecords.sumOf {
        Duration.between(Instant.parse(it.startDate), Instant.parse(it.endDate)).toMinutes().coerceAtLeast(0)
    }
    val streak = calculateStreak(snapshot.checkInItems.mapNotNull {
        if (it.status == "completed") it.completedAt?.let(Instant::parse)?.atZone(zone)?.toLocalDate() else null
    }.toSet())

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("回望成章", "统计只来自本机数据") }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("本周摘要", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("完成率", if (weekPlans.isEmpty()) "—" else "${completed * 100 / weekPlans.size}%")
                        Metric("专注", "$minutes 分")
                        Metric("连续", "$streak 天")
                    }
                }
            }
        }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本月印迹", style = MaterialTheme.typography.titleLarge)
                    MonthHeatmap(snapshot.checkInItems)
                    val study = weekRecords.filter { it.category == "study" }.sumOf { Duration.between(Instant.parse(it.startDate), Instant.parse(it.endDate)).toMinutes() }
                    val work = weekRecords.filter { it.category == "work" }.sumOf { Duration.between(Instant.parse(it.startDate), Instant.parse(it.endDate)).toMinutes() }
                    Text("学习 $study 分钟 · 工作 $work 分钟")
                }
            }
        }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最近记录", style = MaterialTheme.typography.titleLarge)
                    if (snapshot.records.isEmpty()) Text("尚无专注记录", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    snapshot.records.take(8).forEach { record ->
                        val start = Instant.parse(record.startDate).atZone(zone)
                        val duration = Duration.between(Instant.parse(record.startDate), Instant.parse(record.endDate)).toMinutes()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.title)
                                Text("${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} · ${categoryName(record.category)} · $duration 分", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.deleteRecord(record.id) }) { Text("删除") }
                        }
                    }
                }
            }
        }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("计划与提醒", style = MaterialTheme.typography.titleLarge)
                    SettingSwitch("旧计划顺延到今天", carryOver) { carryOver = it; viewModel.setCarryOver(it) }
                    SettingSwitch("计划与专注通知", notifications) { notifications = it; viewModel.setNotifications(it) }
                    OutlinedTextField(
                        focusMinutes,
                        { focusMinutes = it.filter(Char::isDigit) },
                        label = { Text("默认专注分钟") },
                        trailingIcon = { TextButton(onClick = { focusMinutes.toIntOrNull()?.let(viewModel::setFocusMinutes) }) { Text("保存") } },
                        singleLine = true,
                    )
                    Button(onClick = onRequestPermissions) { Text("管理通知与日历权限") }
                }
            }
        }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (raw, label) ->
                            FilterChip(appearance == raw, { appearance = raw; viewModel.setAppearance(raw) }, { Text(label) })
                        }
                    }
                }
            }
        }
        item {
            InkCard(Modifier.padding(horizontal = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("数据与备份", style = MaterialTheme.typography.titleLarge)
                    Text("备份包含计划、记录、倒数日、备忘与非敏感设置；不包含系统权限和日历事件标识。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("导出") }
                        Button(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Text("恢复") }
                    }
                    Button(onClick = onChooseBackupFolder, modifier = Modifier.fillMaxWidth()) { Text("选择自动备份文件夹") }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Moji ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MojiPalette.Vermilion)
                Text("本地优先 · 无账号 · 无业务服务器", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MojiPalette.Vermilion, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun MonthHeatmap(plans: List<com.raydon.moji.core.CheckInItem>) {
    val zone = ZoneId.systemDefault()
    val month = LocalDate.now().withDayOfMonth(1)
    val completedDays = plans.filter { it.status == "completed" }.mapNotNull {
        it.completedAt?.let(Instant::parse)?.atZone(zone)?.toLocalDate()
    }.groupingBy { it }.eachCount()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var day = month.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        repeat(6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(7) {
                    val current = day
                    val count = completedDays[current] ?: 0
                    Box(
                        Modifier.size(30.dp).background(
                            if (current.month != month.month) Color.Transparent
                            else MojiPalette.Vermilion.copy(alpha = when { count >= 3 -> 0.9f; count == 2 -> 0.62f; count == 1 -> 0.34f; else -> 0.08f }),
                            RoundedCornerShape(3.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) { if (current.month == month.month) Text(current.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall) }
                    day = day.plusDays(1)
                }
            }
        }
    }
}

private fun calculateStreak(days: Set<LocalDate>): Int {
    var cursor = LocalDate.now()
    if (cursor !in days) cursor = cursor.minusDays(1)
    var count = 0
    while (cursor in days) { count += 1; cursor = cursor.minusDays(1) }
    return count
}
