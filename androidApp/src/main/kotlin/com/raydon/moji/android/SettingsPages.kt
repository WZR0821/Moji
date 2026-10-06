package com.raydon.moji.android

import androidx.compose.foundation.clickable
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
import com.raydon.moji.ui.MojiPalette

@Composable
fun SettingsPageContent(
    page: String,
    viewModel: MojiViewModel,
    onOpen: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    when {
        page == "defaults" -> PlanDefaultsPage(viewModel)
        page == "widgets" -> PresetsPage(viewModel, onOpen)
        page.startsWith("widgets:") -> PresetEditorPage(page.substringAfter(':').toIntOrNull() ?: 0, viewModel)
        page == "pomodoro" -> PomodoroSettingsPage(viewModel)
        page == "notifications" -> NotificationSettingsPage(viewModel, onRequestPermissions)
        page == "appearance" -> AppearanceSettingsPage(viewModel)
        page == "permissions" -> PermissionsPage(onRequestPermissions)
        page == "backup" -> BackupPage(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder)
        else -> AboutPage()
    }
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
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
    SettingsList {
        item {
            FormSection("详细新建", "首页快速输入仍保持“今天、无时长”，避免添加一句计划时被迫配置细节。") {
                FormChoiceRow("默认类型", category, listOf("study" to "学习", "work" to "工作") + customCategories.map { "custom:$it" to it }) { category = it; viewModel.setDefaultCategory(it) }
                FormSeparator()
                FormChoiceRow("默认安排", schedule, scheduleOptions) { schedule = it; viewModel.setDefaultSchedule(it) }
            }
        }
        item {
            FormSection("今日清单", "开启时，之前没做完的计划会继续留在今日清单里；关闭后旧计划仍保留在日历和总结里。") {
                FormToggle("未完成的计划顺延到今天", carry, { carry = it; viewModel.setCarryOver(it) })
            }
        }
        item {
            FormSection("自定义类型", "删除只影响今后的选择，已有计划和实际记录会保留原类型。") {
                customCategories.forEachIndexed { index, name ->
                    FormRow(name, "删除", onClick = { viewModel.removeCategory(name); customCategories = viewModel.customCategories })
                    FormSeparator()
                }
                FormTextField(customName, { customName = it }, "例如：运动、阅读、生活")
                if (customName.isNotBlank()) {
                    FormSeparator()
                    FormRow("添加“${customName.trim().take(10)}”", onClick = { viewModel.addCategory(customName); customCategories = viewModel.customCategories; customName = "" })
                }
            }
        }
        item {
            FormSection("投入估算", "这个值只用于详细新建；全天、时段和预计投入相互独立。") {
                FormToggle("默认添加预计投入", durationEnabled, { durationEnabled = it; viewModel.setDefaultDurationEnabled(it) })
                if (durationEnabled) {
                    FormSeparator()
                    FormStepper("默认投入", "$minutes 分钟", { minutes = (minutes - 5).coerceAtLeast(5); viewModel.setDefaultMinutes(minutes) }, { minutes = (minutes + 5).coerceAtMost(480); viewModel.setDefaultMinutes(minutes) })
                }
            }
        }
    }
}

@Composable
private fun PresetsPage(viewModel: MojiViewModel, onOpen: (String) -> Unit) {
    SettingsList {
        item {
            FormSection("三个快捷按钮", "中号小组件显示全部三个预设，小号小组件使用第一个。修改后会自动刷新桌面。") {
                repeat(3) { index ->
                    FormRow(viewModel.presetButton(index), "${viewModel.presetTitle(index)} · ${categoryName(viewModel.presetCategory(index))}", trailingChevron = true, onClick = { onOpen("widgets:$index") })
                    if (index < 2) FormSeparator()
                }
            }
        }
        item {
            FormSection { FormRow("恢复默认预设", onClick = viewModel::resetPresets) }
        }
    }
}

@Composable
private fun PresetEditorPage(index: Int, viewModel: MojiViewModel) {
    var button by remember(index) { mutableStateOf(viewModel.presetButton(index)) }
    var title by remember(index) { mutableStateOf(viewModel.presetTitle(index)) }
    var category by remember(index) { mutableStateOf(viewModel.presetCategory(index)) }
    SettingsList {
        item {
            FormSection(footer = "按钮文字最多 8 个字，计划标题最多 24 个字。重复添加时会自动加上序号。") {
                FormTextField(button, { button = it.take(8) }, "按钮文字")
                FormSeparator()
                FormTextField(title, { title = it.take(24) }, "计划标题")
                FormSeparator()
                FormChoiceRow("计划类型", category, (listOf("study" to "学习", "work" to "工作") + viewModel.customCategories.map { "custom:$it" to it } + listOf(category to categoryName(category))).distinctBy { it.first }) { category = it }
            }
        }
        item {
            FormSection("小组件预览") {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("桌面按钮", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Surface(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                        Text(button.ifBlank { "预设" }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            FormSection { FormRow("保存预设", onClick = { if (button.isNotBlank() && title.isNotBlank()) viewModel.savePreset(index, button, title, category) }) }
        }
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
    SettingsList {
        item {
            FormSection("时长", "新的时长会在重置当前阶段或进入下一阶段时生效。") {
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
            FormSection("自动衔接", "手动跳过阶段不会触发自动开始。") {
                FormToggle("专注后自动开始休息", autoBreak, { autoBreak = it; viewModel.setAutoStartBreaks(it) })
                FormSeparator()
                FormToggle("休息后自动开始专注", autoFocus, { autoFocus = it; viewModel.setAutoStartFocus(it) })
            }
        }
        item { FormSection("显示") { FormToggle("专注时保持屏幕常亮", awake, { awake = it; viewModel.setKeepScreenAwake(it) }) } }
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
            FormSection("提醒", "这是 Moji 内部总开关；Android 系统通知权限仍可在“权限”中检查。") {
                FormToggle("允许 App 安排提醒", enabled, { enabled = it; viewModel.setNotifications(it) })
                FormSeparator()
                FormToggle("通知声音", sound, { sound = it; viewModel.setSound(it) }, enabled)
                FormSeparator()
                FormStepper("全天计划提醒", "%02d:00".format(hour), { hour = (hour + 23) % 24; viewModel.setAllDayReminderHour(hour) }, { hour = (hour + 1) % 24; viewModel.setAllDayReminderHour(hour) })
            }
        }
        item { FormSection("反馈", "控制完成打卡和底部切换时的触感反馈。") { FormToggle("操作触感", haptics, { haptics = it; viewModel.setHaptics(it) }) } }
        item { FormSection { FormRow("管理系统权限", trailingChevron = true, onClick = onRequestPermissions) } }
    }
}

@Composable
private fun AppearanceSettingsPage(viewModel: MojiViewModel) {
    var appearance by remember { mutableStateOf(viewModel.appearanceMode.value) }
    var texture by remember { mutableStateOf(viewModel.paperTextureEnabled) }
    var motion by remember { mutableStateOf(viewModel.inkMotionLevel) }
    SettingsList {
        item {
            FormSection("纸墨") {
                FormSegmentedControl(listOf("system" to "跟随系统", "light" to "浅色宣纸", "dark" to "深色墨夜"), appearance) { appearance = it; viewModel.setAppearance(it) }
                FormSeparator()
                FormToggle("宣纸纹理", texture, { texture = it; viewModel.setPaperTexture(it) })
            }
        }
        item {
            FormSection("动效", "“轻量”减弱运笔幅度；“关闭”保留计时进度，但不显示移动笔锋。") {
                FormSegmentedControl(listOf("full" to "完整", "reduced" to "轻量", "off" to "关闭"), motion) { motion = it; viewModel.setInkMotion(it) }
            }
        }
    }
}

@Composable
private fun PermissionsPage(onRequestPermissions: () -> Unit) {
    SettingsList {
        item {
            FormSection("通知", "番茄钟结束和计划到时提醒均使用 Android 本地通知。") {
                FormRow("计划提醒", "由系统管理")
                FormSeparator()
                FormRow("请求通知权限", trailingChevron = true, onClick = onRequestPermissions)
            }
        }
        item {
            FormSection("系统日历") {
                FormRow("计划与纪念日均可单独选择")
                FormSeparator()
                Text("App 只在你明确开启同步时写入事件，不会上传日历内容。", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun BackupPage(viewModel: MojiViewModel, onExport: () -> Unit, onImport: () -> Unit, onFolder: () -> Unit) {
    SettingsList {
        item {
            FormSection("文件夹自动备份", "备份文件可放在 Google Drive、OneDrive 或其他系统文件提供方中。") {
                FormRow("选择备份文件夹", trailingChevron = true, onClick = onFolder)
                FormSeparator()
                FormRow("历史备份保留", "${viewModel.backupRetentionDays} 天")
            }
        }
        item {
            FormSection("手动备份", "备份包含计划、记录、倒数日、备忘与非敏感设置。") {
                FormRow("导出 Moji 备份", trailingChevron = true, onClick = onExport)
                FormSeparator()
                FormRow("从备份恢复", trailingChevron = true, onClick = onImport)
            }
        }
        item {
            Text("恢复前会校验文件并显示条目数量，由你确认后替换当前数据。建议换机或重新签名安装前先导出一次。", modifier = Modifier.padding(horizontal = 22.dp), color = mojiVermilion.copy(alpha = 0.78f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AboutPage() {
    SettingsList {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp)) {
                    Text("墨", modifier = Modifier.padding(horizontal = 23.dp, vertical = 18.dp), color = MaterialTheme.colorScheme.background, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                Text("Moji", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 13.dp))
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f), fontSize = 13.sp)
            }
        }
        item {
            FormSection {
                FormRow("数据格式", "v12")
                FormSeparator()
                FormRow("隐私", "本地优先")
                FormSeparator()
                FormRow("业务服务器", "无")
            }
        }
        item { Text("由 WANG ZIRUI 设计与开发", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f), fontSize = 12.sp) }
    }
}

private fun scheduleLabel(raw: String): String = when (raw) {
    "morning" -> "上午"
    "afternoon" -> "下午"
    "evening" -> "晚上"
    "exactTime" -> "具体时间"
    else -> "全天"
}
