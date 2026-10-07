package com.raydon.moji.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raydon.moji.ui.MojiPalette

@Composable
fun SettingsPageContent(
    page: String,
    viewModel: MojiViewModel,
    onOpen: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRestoreBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    when {
        page == "defaults" -> PlanDefaultsPage(viewModel)
        page == "widgets" -> PresetsPage(viewModel, onOpen)
        page.startsWith("widgets:") -> PresetEditorPage(page.substringAfter(':').toIntOrNull() ?: 0, viewModel) { onOpen("widgets") }
        page == "pomodoro" -> PomodoroSettingsPage(viewModel)
        page == "notifications" -> NotificationSettingsPage(viewModel, onRequestPermissions)
        page == "appearance" -> AppearanceSettingsPage(viewModel)
        page == "permissions" -> PermissionsPage(onRequestPermissions)
        page == "backup" -> BackupPage(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRestoreBackupFolder)
        else -> AboutPage()
    }
}

@Composable
private fun SettingsList(top: Int = 12, spacing: Int = 18, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content,
    )
}

@Composable
private fun SettingsSection(title: String? = null, footer: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        if (title != null) Spacer(Modifier.height(18.dp))
        FormSection(title, footer, titleLineHeight = 17, content = content)
    }
}

@Composable
private fun PlanDefaultsPage(viewModel: MojiViewModel) {
    var category by remember { mutableStateOf(viewModel.defaultCategory) }
    var schedule by remember { mutableStateOf(viewModel.defaultScheduleKind) }
    var carry by remember { mutableStateOf(viewModel.carriesOver) }
    var durationEnabled by remember { mutableStateOf(viewModel.defaultPlannedDurationEnabled) }
    var minutes by remember { mutableIntStateOf(viewModel.defaultPlannedMinutes) }
    var customName by remember { mutableStateOf("") }
    var customCategories by remember { mutableStateOf(viewModel.customCategories) }
    var deletingCategory by remember { mutableStateOf<String?>(null) }
    SettingsList(spacing = 12) {
        item {
            SettingsSection("详细新建", "首页快速输入仍保持“今天、无时长”，避免添加一句计划时被迫配置细节。") {
                FormChoiceRow("默认类型", category, listOf("study" to "学习", "work" to "工作") + customCategories.map { "custom:$it" to it }) { category = it; viewModel.setDefaultCategory(it) }
                FormSeparator()
                FormChoiceRow("默认安排", schedule, scheduleOptions) { schedule = it; viewModel.setDefaultSchedule(it) }
            }
        }
        item {
            SettingsSection("今日清单", "开启时，之前没做完的计划会继续留在今日清单里；关闭后今日清单只显示安排在今天的计划，旧计划仍在日历和总结里，不会丢。已完成的计划只属于完成当天，任何时候都不会跟到第二天。") {
                FormToggle("未完成的计划顺延到今天", carry, { carry = it; viewModel.setCarryOver(it) })
            }
        }
        item {
            SettingsSection("自定义类型", "删除只会从今后的选择中移除；已有计划和实际记录仍会保留原来的类型名称。") {
                customCategories.forEach { name ->
                    Row(Modifier.fillMaxWidth().height(44.dp).combinedClickable(onClick = {}, onLongClick = { deletingCategory = name }).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MojiLineIcon(MojiIcon.Tag, 20.dp); Text(name, fontSize = 17.sp)
                    }
                    FormSeparator()
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(customName, { customName = it }, Modifier.weight(1f).padding(vertical = 10.dp), singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (customName.isBlank()) Text("例如：运动、阅读、生活", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() } })
                    Spacer(Modifier.width(10.dp))
                    Text("添加", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (customName.isBlank()) 0.24f else 1f), modifier = Modifier.clickable(enabled = customName.isNotBlank()) {
                        viewModel.addCategory(customName); customCategories = viewModel.customCategories; category = viewModel.defaultCategory; customName = ""
                    })
                }
            }
        }
        item {
            SettingsSection("投入估算", "这个值只用于详细新建；全天、时段和预计投入仍然相互独立。") {
                FormToggle("默认添加预计投入", durationEnabled, { durationEnabled = it; viewModel.setDefaultDurationEnabled(it) })
                if (durationEnabled) {
                    FormSeparator()
                    FormStepper("默认投入", "$minutes 分钟", { minutes = (minutes - 5).coerceAtLeast(5); viewModel.setDefaultMinutes(minutes) }, { minutes = (minutes + 5).coerceAtMost(480); viewModel.setDefaultMinutes(minutes) })
                }
            }
        }
    }
    deletingCategory?.let { name ->
        AlertDialog(onDismissRequest = { deletingCategory = null }, title = { Text("删除类型“$name”？") }, text = { Text("已有计划和实际记录仍会保留原类型。") },
            confirmButton = { TextButton(onClick = { viewModel.removeCategory(name); customCategories = viewModel.customCategories; category = viewModel.defaultCategory; deletingCategory = null }) { Text("删除", color = mojiDestructive) } },
            dismissButton = { TextButton(onClick = { deletingCategory = null }) { Text("取消") } })
    }
}

@Composable
private fun PresetsPage(viewModel: MojiViewModel, onOpen: (String) -> Unit) {
    SettingsList {
        item {
            SettingsSection("三个快捷按钮", "中号小组件显示全部三个预设，小号小组件使用第一个。修改后会自动刷新桌面。") {
                repeat(3) { index ->
                    Row(Modifier.fillMaxWidth().height(64.dp).clickable { onOpen("widgets:$index") }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        val category = viewModel.presetCategory(index)
                        val tint = if (category == "work") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        InkBrushMedallion(when (category) { "" -> MojiIcon.Plus; "study" -> MojiIcon.Book; "work" -> MojiIcon.Work; else -> MojiIcon.Tag }, tint, 31.dp, inkSeed(listOf("general", "study", "work")[index]))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(viewModel.presetButton(index), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text("${viewModel.presetTitle(index)} · ${if (viewModel.presetCategory(index).isBlank()) "跟随默认类型" else categoryName(viewModel.presetCategory(index))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1)
                        }
                        MojiLineIcon(MojiIcon.ChevronRight, 17.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                    }
                    if (index < 2) FormSeparator()
                }
            }
        }
        item {
            SettingsSection { Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = viewModel::resetPresets).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("恢复默认预设", color = mojiVermilion, fontSize = 17.sp) } }
        }
    }
}

@Composable
private fun PresetEditorPage(index: Int, viewModel: MojiViewModel, onBack: () -> Unit) {
    var button by remember(index) { mutableStateOf(viewModel.presetButton(index)) }
    var title by remember(index) { mutableStateOf(viewModel.presetTitle(index)) }
    var category by remember(index) { mutableStateOf(viewModel.presetCategory(index)) }
    Column(Modifier.fillMaxSize()) {
    MojiSheetBar("编辑预设", "快速添加预设", "保存", onBack, {
        viewModel.savePreset(index, button.trim().take(8), title.trim().take(24), category); onBack()
    }, button.isNotBlank() && title.isNotBlank(), leadingIsBack = true)
    SettingsList(top = 18) {
        item {
            SettingsSection(footer = "按钮文字最多 8 个字，计划标题最多 24 个字。重复添加时会自动加上序号。") {
                PresetField("按钮文字", button, { button = it }, "例如：背单词")
                FormSeparator()
                PresetField("计划标题", title, { title = it }, "例如：今日背单词")
                FormSeparator()
                FormChoiceRow("计划类型", category, (listOf("" to "跟随默认类型", "study" to "学习", "work" to "工作") + viewModel.customCategories.map { "custom:$it" to it } + listOf(category to if (category.isBlank()) "跟随默认类型" else categoryName(category))).distinctBy { it.first }) { category = it }
            }
        }
        item {
            SettingsSection {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("小组件预览", modifier = Modifier.weight(1f), fontSize = 17.sp)
                    val color = when (category) { "work" -> MaterialTheme.colorScheme.secondary; "", "study" -> MaterialTheme.colorScheme.onSurface; else -> if (MaterialTheme.colorScheme.background.luminance() < 0.5f) androidx.compose.ui.graphics.Color(0xFF948C7D) else androidx.compose.ui.graphics.Color(0xFF8A8073) }
                    Surface(color = color.copy(alpha = 0.09f), shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 8.dp, bottomEnd = 11.dp, topEnd = 7.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            MojiLineIcon(when (category) { "" -> MojiIcon.Plus; "study" -> MojiIcon.Book; "work" -> MojiIcon.Work; else -> MojiIcon.Tag }, 13.dp, color)
                            Text(button.trim().take(8).ifBlank { "预设" }, color = color, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PresetField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 17.sp)
        BasicTextField(value, onChange, Modifier.weight(1f).padding(vertical = 10.dp), singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (value.isBlank()) Text(placeholder, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() } })
    }
}

@Composable
private fun PomodoroSettingsPage(viewModel: MojiViewModel) {
    var focus by remember { mutableIntStateOf(viewModel.defaultFocusMinutes) }
    var shortBreak by remember { mutableIntStateOf(viewModel.shortBreakMinutes) }
    var longBreak by remember { mutableIntStateOf(viewModel.longBreakMinutes) }
    var longEnabled by remember { mutableStateOf(viewModel.longBreakEnabled) }
    var interval by remember { mutableIntStateOf(viewModel.longBreakInterval) }
    var autoBreak by remember { mutableStateOf(viewModel.autoStartBreaks) }
    var autoFocus by remember { mutableStateOf(viewModel.autoStartFocus) }
    var awake by remember { mutableStateOf(viewModel.keepScreenAwake) }
    SettingsList(spacing = 12) {
        item {
            SettingsSection("时长", "新的时长会在重置当前阶段或进入下一阶段时生效。") {
                FormStepper("专注时长", "$focus 分钟", { focus = (focus - 1).coerceAtLeast(1); viewModel.setPhaseMinutes("focus", focus, defaultsOnly = true) }, { focus = (focus + 1).coerceAtMost(120); viewModel.setPhaseMinutes("focus", focus, defaultsOnly = true) })
                FormSeparator()
                FormStepper("短休息", "$shortBreak 分钟", { shortBreak = (shortBreak - 1).coerceAtLeast(1); viewModel.setPhaseMinutes("shortBreak", shortBreak, defaultsOnly = true) }, { shortBreak = (shortBreak + 1).coerceAtMost(30); viewModel.setPhaseMinutes("shortBreak", shortBreak, defaultsOnly = true) })
                FormSeparator()
                FormToggle("使用长休息", longEnabled, { longEnabled = it; viewModel.setLongBreakEnabled(it) })
                if (longEnabled) {
                    FormSeparator()
                    FormStepper("长休息", "$longBreak 分钟", { longBreak = (longBreak - 1).coerceAtLeast(1); viewModel.setPhaseMinutes("longBreak", longBreak, defaultsOnly = true) }, { longBreak = (longBreak + 1).coerceAtMost(60); viewModel.setPhaseMinutes("longBreak", longBreak, defaultsOnly = true) })
                    FormSeparator()
                    FormStepper("长休息间隔", "$interval 个番茄", { interval = (interval - 1).coerceAtLeast(1); viewModel.setLongBreakInterval(interval) }, { interval = (interval + 1).coerceAtMost(8); viewModel.setLongBreakInterval(interval) })
                }
            }
        }
        item {
            SettingsSection("自动衔接", "手动跳过阶段不会触发自动开始。") {
                FormToggle("专注后自动开始休息", autoBreak, { autoBreak = it; viewModel.setAutoStartBreaks(it) })
                FormSeparator()
                FormToggle("休息后自动开始专注", autoFocus, { autoFocus = it; viewModel.setAutoStartFocus(it) })
            }
        }
        item { SettingsSection("显示") { FormToggle("专注时保持屏幕常亮", awake, { awake = it; viewModel.setKeepScreenAwake(it) }) } }
    }
}

@Composable
private fun NotificationSettingsPage(viewModel: MojiViewModel, onRequestPermissions: () -> Unit) {
    var enabled by remember { mutableStateOf(viewModel.notificationsEnabled) }
    var sound by remember { mutableStateOf(viewModel.soundEnabled) }
    var haptics by remember { mutableStateOf(viewModel.hapticsEnabled) }
    var hour by remember { mutableIntStateOf(viewModel.allDayReminderHour) }
    SettingsList {
        item {
            SettingsSection("提醒", "这是 Moji 内部总开关；Android 系统通知权限仍可在“权限”中检查。具体计划仍需单独选择提醒。") {
                FormToggle("允许 App 安排提醒", enabled, { enabled = it; viewModel.setNotifications(it) })
                FormSeparator()
                FormToggle("通知声音", sound, { sound = it; viewModel.setSound(it) }, enabled)
                FormSeparator()
                FormStepper("全天计划提醒", "%02d:00".format(hour), { hour = (hour - 1).coerceAtLeast(0); viewModel.setAllDayReminderHour(hour) }, { hour = (hour + 1).coerceAtMost(23); viewModel.setAllDayReminderHour(hour) }, enabled = enabled)
            }
        }
        item { SettingsSection(footer = "控制完成打卡和底部切换时的触感反馈。") { FormToggle("操作触感", haptics, { haptics = it; viewModel.setHaptics(it) }) } }
    }
}

@Composable
private fun AppearanceSettingsPage(viewModel: MojiViewModel) {
    var appearance by remember { mutableStateOf(viewModel.appearanceMode.value) }
    var texture by remember { mutableStateOf(viewModel.paperTextureEnabled) }
    var motion by remember { mutableStateOf(viewModel.inkMotionLevel) }
    SettingsList(spacing = 4) {
        item {
            SettingsSection("纸墨") {
                FormChoiceRow("显示模式", appearance, listOf("system" to "跟随系统", "light" to "浅色宣纸", "dark" to "深色墨夜")) { appearance = it; viewModel.setAppearance(it) }
                FormSeparator()
                FormToggle("宣纸纹理", texture, { texture = it; viewModel.setPaperTexture(it) })
            }
        }
        item {
            SettingsSection("动效", "“轻量”降低绘制刷新率；“关闭”保留计时进度，但不显示移动笔锋。系统“移除动画”仍有更高优先级。") {
                FormSegmentedControl(listOf("full" to "完整", "reduced" to "轻量", "off" to "关闭"), motion) { motion = it; viewModel.setInkMotion(it) }
            }
        }
    }
}

@Composable
private fun PermissionsPage(onRequestPermissions: () -> Unit) {
    SettingsList {
        item {
            SettingsSection("通知", "番茄钟结束和计划到时提醒均使用 Android 本地通知。") {
                FormRow("计划提醒", if (androidx.core.app.NotificationManagerCompat.from(androidx.compose.ui.platform.LocalContext.current).areNotificationsEnabled()) "已允许" else "未允许", icon = MojiIcon.Bell)
                FormSeparator()
                FormRow("请求通知权限", trailingChevron = true, onClick = onRequestPermissions)
            }
        }
        item {
            SettingsSection("系统日历", "App 只在你明确开启同步时写入事件，不会上传日历内容。") {
                FormRow("计划与纪念日均可单独选择", icon = MojiIcon.Calendar)
            }
        }
    }
}

@Composable
private fun BackupPage(viewModel: MojiViewModel, onExport: () -> Unit, onImport: () -> Unit, onFolder: () -> Unit, onRestoreFolder: () -> Unit) {
    val revision by viewModel.settingsRevision.collectAsStateWithLifecycle()
    val folder = remember(revision) { viewModel.backupFolderName }
    val retentionOptions = listOf("30" to "1 个月", "90" to "3 个月", "180" to "半年", "365" to "1 年", "0" to "一直保留")
    SettingsList {
        item {
            SettingsSection("文件夹自动备份", "备份文件可放在 Google Drive、OneDrive 或其他系统文件提供方中。数据变化后会自动写入，并每天另存历史备份。") {
                if (folder == null) FormRow("选择自动备份文件夹", icon = MojiIcon.Backup, onClick = onFolder)
                else {
                    FormRow("备份文件夹", folder, icon = MojiIcon.Backup)
                    FormSeparator()
                    val date = if (viewModel.lastBackupAt == 0L) "尚未备份" else java.time.Instant.ofEpochMilli(viewModel.lastBackupAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
                    FormRow("上次备份", date)
                    FormSeparator()
                    val days = viewModel.backupRetentionDays.toString()
                    FormChoiceRow("历史备份保留", days, if (retentionOptions.any { it.first == days }) retentionOptions else retentionOptions + (days to "$days 天")) { viewModel.setBackupRetention(it.toInt()) }
                    FormSeparator()
                    FormRow("立即备份一次", onClick = viewModel::backupNow)
                    FormSeparator()
                    Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = viewModel::stopAutomaticBackup).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("停止自动备份", color = mojiDestructive, fontSize = 17.sp) }
                }
            }
        }
        item {
            SettingsSection("手动备份", "备份包含计划、记录、倒数日、备忘与非敏感设置。") {
                FormRow("导出完整备份", icon = MojiIcon.Backup, onClick = onExport)
                FormSeparator()
                FormRow("从备份文件夹恢复", icon = MojiIcon.Backup, onClick = onRestoreFolder)
                FormSeparator()
                FormRow("从单个备份文件导入", icon = MojiIcon.Backup, onClick = onImport)
            }
        }
        item {
            Text("恢复前会校验文件并显示条目数量，由你确认后替换当前数据。建议换机或重新签名安装前先导出一次。", modifier = Modifier.padding(horizontal = 22.dp), color = mojiVermilion.copy(alpha = 0.78f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AboutPage() {
    SettingsList(top = 18) {
        item {
            SettingsSection {
                FormRow("应用", "Moji")
                FormSeparator()
                FormRow("版本", "V${BuildConfig.VERSION_NAME} 正式版")
                FormSeparator()
                FormRow("开发者", "Jerry Wong")
                FormSeparator()
                FormRow("数据格式", "v${com.raydon.moji.core.PlanSnapshot.CURRENT_SCHEMA_VERSION}")
                FormSeparator()
                FormRow("最低系统", "Android 8.0")
            }
        }
    }
}

private fun scheduleLabel(raw: String): String = when (raw) {
    "morning" -> "上午"
    "afternoon" -> "下午"
    "evening" -> "晚上"
    "exactTime" -> "具体时间"
    else -> "全天"
}
