package com.raydon.moji.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.ai.AIClock
import com.raydon.moji.android.ai.AIQAFixture
import com.raydon.moji.android.ai.AIWeeklyLetter
import com.raydon.moji.android.ai.AIWeeklyLetterState
import com.raydon.moji.android.ai.WeeklyLetter
import com.raydon.moji.android.platform.AIWeeklyLetterNotifier
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.ui.InkCard
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import com.raydon.moji.android.data.LocalRules

@Composable
fun ReviewScreen(
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRestoreBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var secondaryRoute by remember { mutableStateOf<String?>(null) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    var qaEditor by remember { mutableStateOf("") }
    // 本周来信: the switches, the letters kept on this device, the letter tapped for
    // 放进复盘 or 拟下周计划, and the pages pushed from the card (往期来信, one letter).
    val aiReady = rememberAIReady()
    val showsEntries = rememberAIShowsEntries()
    val aiSettings by com.raydon.moji.android.ai.AISettingsStore.shared.settings.collectAsStateWithLifecycle()
    val letterState = AIWeeklyLetterState.shared
    val letters by letterState.letters.collectAsStateWithLifecycle()
    val letterLoadingWeek by letterState.loadingWeek.collectAsStateWithLifecycle()
    val letterDismissedWeek by letterState.dismissedWeek.collectAsStateWithLifecycle()
    val notifyAsked by letterState.notifyAsked.collectAsStateWithLifecycle()
    var letterReflection by remember { mutableStateOf<WeeklyLetter?>(null) }
    var letterNextWeek by remember { mutableStateOf<WeeklyLetter?>(null) }
    var showsLetterArchive by remember { mutableStateOf(false) }
    var shownLetter by remember { mutableStateOf<WeeklyLetter?>(null) }
    var letterQA by remember { mutableStateOf("") }
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    // 开启提醒: asked once from the card; the row stays away afterwards, granted or not.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        letterState.markNotifyAsked()
        AIWeeklyLetterNotifier.reschedule(context)
    }
    LaunchedEffect(qaRoute, snapshot.records.size) { if (!didQA && qaRoute.isNotEmpty() && snapshot.records.isNotEmpty()) {
        didQA = true
        if (qaRoute.startsWith("settings") || qaRoute == "ai-settings-help") showSettings = true
        else when (qaRoute) { "review-week", "ai-nextweek-offer" -> secondaryRoute = "weekly"; "review-month" -> secondaryRoute = "monthly"; "review-records" -> secondaryRoute = "records"; "goal", "reflection", "next-week", "record-editor", "record-untimed", "ai-reflection", "ai-reflection-applied", "ai-reflection-auto", "ai-next-week" -> qaEditor = qaRoute
            "ai-letter-archive", "ai-letter-detail" -> letterQA = qaRoute }
    } }
    // ai-letter-archive / ai-letter-detail: three earlier letters, then their list or one of them.
    LaunchedEffect(letterQA) {
        if (!BuildConfig.DEBUG || letterQA.isEmpty()) return@LaunchedEffect
        letterState.seedArchiveForQA(AIClock.now())
        kotlinx.coroutines.delay(500)
        if (letterQA == "ai-letter-archive") showsLetterArchive = true
        else shownLetter = letterState.newestFirst.firstOrNull { it.title == "顺延集中在周四" }
    }
    if (qaEditor == "goal") WeeklyGoalDialog(viewModel, workflowWeek(LocalDate.now()), snapshot.weeklyGoals.firstOrNull()) { qaEditor = "" }
    if (qaEditor == "reflection") WeeklyReflectionDialog(viewModel, workflowWeek(LocalDate.now()), snapshot.weeklyReflections.firstOrNull()) { qaEditor = "" }
    if (qaEditor == "next-week") NextWeekPlansDialog(viewModel, workflowWeek(LocalDate.now())) { qaEditor = "" }
    if (qaEditor.startsWith("ai-reflection")) WeeklyReflectionDialog(viewModel, workflowWeek(LocalDate.now()), null) { qaEditor = "" }
    if (qaEditor == "ai-next-week") NextWeekDraftSheet(viewModel, workflowWeek(LocalDate.now())) { qaEditor = "" }
    if (qaEditor == "record-editor") RecordEditorScreenDialog(snapshot.records.firstOrNull { it.scheduleKind == "exactTime" }, viewModel) { qaEditor = "" }
    if (qaEditor == "record-untimed") RecordEditorScreenDialog(snapshot.records.firstOrNull { it.scheduleKind == "allDay" }, viewModel) { qaEditor = "" }
    // The letter pages are pushed in place of the page, as on iOS.
    shownLetter?.let { letter ->
        BackHandler { shownLetter = null }
        WeeklyLetterDetailPage(letter, if (showsLetterArchive) "往期来信" else "回顾") { shownLetter = null }
        return
    }
    if (showsLetterArchive) {
        BackHandler { showsLetterArchive = false }
        WeeklyLetterArchivePage("回顾", { showsLetterArchive = false }) { shownLetter = it }
        return
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEnd = weekStart.plusDays(7)
    val weekPlans = snapshot.checkInItems.filter {
        val date = Instant.parse(it.scheduledStart).atZone(zone).toLocalDate()
        it.kind == "planned" && date >= weekStart && date < weekEnd
    }
    val completed = weekPlans.count { it.status == "completed" }
    val weekRecords = snapshot.records.filter {
        val date = Instant.parse(it.startDate).atZone(zone).toLocalDate()
        date >= weekStart && date < weekEnd
    }
    val minutes = LocalRules.minutes(snapshot.records, weekStart, weekEnd)
    val streak = calculateStreak(snapshot.checkInItems.mapNotNull { if (it.status == "completed") it.completedAt?.let(Instant::parse)?.atZone(zone)?.toLocalDate() else null }.toSet())
    val pendingPlans = snapshot.checkInItems.filter { it.kind == "planned" && it.isArchived != true && it.status in listOf("planned", "inProgress") && com.raydon.moji.core.ChecklistPolicy.isVisible(it.scheduledStart, it.status, Instant.now().toString(), viewModel.carriesOver, zone.id) }
        .sortedWith(compareBy<CheckInItem> { it.scheduledStart }.thenBy { it.createdAt })
    val pending = pendingPlans.size
    // 下一步: the plan carried longest goes first, straight into the pomodoro (N2).
    val carried = com.raydon.moji.android.ai.ReviewNextStep.carried(pendingPlans, java.time.ZonedDateTime.now(zone))
    // 本周来信 above 本周回顾: the letter for the week that has just ended, the wait for it, or
    // the teaser; then the letters kept so far. The facts are worked out now, as on the card.
    val letterNow = AIClock.now()
    val letterMonday = AIWeeklyLetter.letterWeek(letterNow)
    val letterKey = AIWeeklyLetter.weekKey(letterMonday)
    // The card (and its teaser) only once a model is set up (B6); 往期来信 stays with the entry points.
    val letterOn = aiSettings.weeklyLetter && aiReady
    val keepsLetters = aiSettings.weeklyLetter && showsEntries
    val letterFacts = AIWeeklyLetter.facts(snapshot.checkInItems, snapshot.records, letterMonday, letterNow.zone)
    val letterEnough = letterFacts.planned >= AIWeeklyLetter.minimumPlans
    val currentLetter = letters.firstOrNull { it.week == letterKey }
    val letterContent: WeeklyLetterContent? = when {
        !letterOn || letterDismissedWeek == letterKey -> null
        currentLetter != null -> WeeklyLetterContent.Letter(currentLetter, AIWeeklyLetter.factsLine(letterFacts))
        aiReady && letterEnough && letterLoadingWeek == letterKey -> WeeklyLetterContent.Loading
        !aiReady && letterEnough && AIWeeklyLetter.isTeaserTime(letterNow) -> WeeklyLetterContent.Teaser
        else -> null
    }
    val showsLetterArchiveRow = keepsLetters && letters.isNotEmpty()
    // Like iOS's “not determined”: notifications not allowed yet and 开启提醒 not tapped before.
    val showsReminder = android.os.Build.VERSION.SDK_INT >= 33 && !notifyAsked &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    // Asked for when the page shows or comes back; the store keeps the rest of the rules.
    // A photographed run waits for its sample data, which lands just after the first frame.
    val letterSnapshot by rememberUpdatedState(snapshot)
    val waitsForQaSeed = BuildConfig.DEBUG && AIQAFixture.scenario.isNotEmpty() && qaRoute.isEmpty()
    LaunchedEffect(resumed, letterOn, letterEnough, waitsForQaSeed) {
        if (resumed && letterOn && !waitsForQaSeed) AIClock.now().let { now -> letterState.refreshIfNeeded(AIWeeklyLetter.letterWeek(now), letterSnapshot, now) }
    }
    MojiScreen {
        LazyColumn(Modifier.fillMaxSize()) {
            item { MojiLargeTitle("回顾", actions = listOf(MojiIcon.Gear to { showSettings = true }), bottomPadding = 18.5.dp) }
            if (letterContent != null || showsLetterArchiveRow) item(key = "weekly-letter") {
                Column(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp).animateContentSize(tween(250, easing = EaseOut)),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (letterContent != null) WeeklyLetterCard(
                        range = AIWeeklyLetter.rangeText(letterMonday),
                        content = letterContent,
                        showsReminder = showsReminder,
                        putIntoReflection = { currentLetter?.let { letterReflection = it } },
                        // Only while the week after the letter's is still ahead (A3).
                        draftNextWeek = currentLetter?.takeIf { letter -> AIWeeklyLetter.monday(letter.week)?.let { com.raydon.moji.android.ai.AIDrafting.offersNextWeek(it, today) } == true }
                            ?.let { letter -> { aiEntry { letterNextWeek = letter } } },
                        enableReminder = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else { letterState.markNotifyAsked(); AIWeeklyLetterNotifier.reschedule(context) }
                        },
                        openIntro = { AIIntro.open() },
                        dismiss = { letterState.dismiss(letterMonday) },
                    )
                    if (showsLetterArchiveRow) LetterArchiveRow(letters.size) { showsLetterArchive = true }
                }
            }
            item {
                InkCard(Modifier.padding(horizontal = 18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).alignByBaseline()) {
                                Text("本周回顾", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("${weekStart.format(DateTimeFormatter.ofPattern("M月d日"))} – ${weekEnd.minusDays(1).format(DateTimeFormatter.ofPattern("M月d日"))}", color = iosSecondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            val rate = if (weekPlans.isEmpty()) 0 else completed * 100 / weekPlans.size
                            Text("完成率 $rate%", Modifier.alignByBaseline(), color = if (completed > 0) MaterialTheme.colorScheme.primary else iosSecondaryLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            ReviewMetric(if (weekPlans.isEmpty()) "—" else "$completed/${weekPlans.size}", "完成计划", Modifier.weight(1f))
                            ReviewMetric(durationText(minutes), "实际专注", Modifier.weight(1f))
                            ReviewMetric("${streak}天", "连续完成", Modifier.weight(1f))
                        }
                        WeeklyBars(snapshot.records)
                        if (pending > 0 || minutes == 0L) {
                        HorizontalDivider(color = iosSeparator.copy(alpha = iosSeparator.alpha * 0.22f), thickness = devicePixel)
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                            if (carried != null) viewModel.focusOn(carried.first) else if (pending > 0) viewModel.openPlans() else viewModel.openFocus()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                if (pending > 0 && carried == null) MojiLineIcon(MojiIcon.TabChecklist, 19.dp, MaterialTheme.colorScheme.primary)
                                else MojiLineIcon(MojiIcon.Timer, 21.dp, MaterialTheme.colorScheme.primary)
                            }
                            Text(when {
                                carried != null -> com.raydon.moji.android.ai.ReviewNextStep.text(carried.first.title, carried.second)
                                pending > 0 -> "下一步：完成剩余 $pending 项计划"
                                else -> "下一步：开始一个 25 分钟专注"
                            }, modifier = Modifier.weight(1f).padding(start = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Box(Modifier.offset(x = 6.dp)) { MojiLineIcon(MojiIcon.ArrowUpRight, 24.dp, MaterialTheme.colorScheme.secondary) }
                        }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    ReviewActionRow({ MojiLineIcon(MojiIcon.TabChart, 20.5.dp, MaterialTheme.colorScheme.primary) }, "周总结", "查看本周完成、专注和每日趋势") { secondaryRoute = "weekly" }
                    InkDivider(opacity = 0.32f)
                    ReviewActionRow({ MojiLineIcon(MojiIcon.CalendarFill, 21.8.dp, MaterialTheme.colorScheme.primary) }, "月总结", "查看月度热力图和计划/实际对比") { secondaryRoute = "monthly" }
                    InkDivider(opacity = 0.32f)
                    ReviewActionRow({ ListBulletRectangleIcon(MaterialTheme.colorScheme.primary) }, "时间记录", "查看、补记或修改全部记录") { secondaryRoute = "records" }
                }
            }
            item {
                Text("数据本机优先，可自动备份到所选文件夹", modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 17.3.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f), fontSize = 12.sp)
            }
        }
    }
    if (showSettings) SettingsDialog(viewModel, onExportBackup, onImportBackup, onChooseBackupFolder, onRestoreBackupFolder, onRequestPermissions) { showSettings = false }
    secondaryRoute?.let { route -> ReviewSecondaryDialog(route, viewModel) { secondaryRoute = null } }
    // 放进复盘: that week's 周复盘 with the letter already in the draft area; nothing is saved until 放进两栏 and 保存.
    letterReflection?.let { letter ->
        WeeklyReflectionDialog(viewModel, AIWeeklyLetter.monday(letter.week) ?: letterMonday,
            snapshot.weeklyReflections.firstOrNull { it.weekStart == letter.week }, AIWeeklyLetter.reflectionDraft(letter)) { letterReflection = null }
    }
    // 拟下周计划: the existing 下周草稿, for the week after the letter's.
    letterNextWeek?.let { letter ->
        NextWeekDraftSheet(viewModel, AIWeeklyLetter.monday(letter.week) ?: letterMonday) { letterNextWeek = null }
    }
}

/** 往期来信 under the letter card (or alone once it is put away): the count kept, then the list. */
@Composable
private fun LetterArchiveRow(count: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("往期来信", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Text("已存 $count 封 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ReviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = iosSecondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun WeeklyBars(records: List<com.raydon.moji.core.TimeRecord>) {
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val values = (0..6).map { offset ->
        val date = start.plusDays(offset.toLong())
        LocalRules.minutes(records, date, date.plusDays(1), zone)
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
                Column(Modifier.weight(1f).clickable { selectedDay = index }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.width(12.dp).height(maxOf(4f, 48 * value.toFloat() / max).dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (value > 0) 1f else 0.11f), RoundedCornerShape(8.dp)))
                    Text("一二三四五六日"[index].toString(), color = iosSecondaryLabel, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        selectedDay?.let { Text("星期${"一二三四五六日"[it]} · 实际专注 ${durationText(values[it])}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun ReviewActionRow(icon: @Composable () -> Unit, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = iosSecondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        // SF chevron.right (caption, bold) is 6.3 × 10.7 pt and sits flush with the row's trailing edge.
        Box(Modifier.offset(x = 6.8.dp)) { MojiLineIcon(MojiIcon.Disclosure, 22.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f)) }
    }
}

/** SF Symbol list.bullet.rectangle, 19.33 × 15.33 pt: rounded frame, four bullets, long/short lines alternating. */
@Composable
internal fun ListBulletRectangleIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 19.33.dp, height = 15.33.dp)) {
        val u = 1.dp.toPx()
        val frame = 1.43f * u
        drawRoundRect(
            color,
            androidx.compose.ui.geometry.Offset(frame / 2, frame / 2),
            androidx.compose.ui.geometry.Size(size.width - frame, size.height - frame),
            androidx.compose.ui.geometry.CornerRadius(1.8f * u),
            style = androidx.compose.ui.graphics.drawscope.Stroke(frame),
        )
        listOf(3.7f to 15.5f, 6.25f to 11.4f, 8.8f to 15.5f, 11.4f to 11.4f).forEach { (y, end) ->
            drawCircle(color, 0.8f * u, androidx.compose.ui.geometry.Offset(3.7f * u, y * u))
            drawLine(color, androidx.compose.ui.geometry.Offset(6.4f * u, y * u), androidx.compose.ui.geometry.Offset(end * u, y * u), 1.05f * u, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun SettingsDialog(
    viewModel: MojiViewModel,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onRestoreBackupFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    // ai-settings-help opens 「用在哪里」 straight away, like settings-ai with the help page.
    var page by remember { mutableStateOf(if (!BuildConfig.DEBUG) null else if (viewModel.visualQaRoute.value == "ai-settings-help") "ai:help"
        else viewModel.visualQaRoute.value.removePrefix("settings-").takeIf { viewModel.visualQaRoute.value.startsWith("settings-") }
            ?.let { if (it == "ai") com.raydon.moji.android.ai.aiQaInitialPage() ?: "ai" else it }) }
    // Sub-pages are named "<page>:<sub>"; going back drops the last part.
    // The AI pages carry IDs in their routes, so they ask for their own parent.
    val goBack: () -> Unit = {
        val current = page
        if (current == null) onDismiss()
        else page = if (current == "ai" || current.startsWith("ai:")) com.raydon.moji.android.ai.aiSettingsParent(current)
            else current.takeIf { it.contains(':') }?.substringBeforeLast(':')
    }
    Dialog(onDismissRequest = goBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                // The AI pages draw their own bars, like the widget preset editor.
                if (page?.startsWith("widgets:") != true && page?.let { it == "ai" || it.startsWith("ai:") } != true) MojiSheetBar(
                    title = page?.let(::settingsPageTitle) ?: "设置",
                    leading = if (page == null) "" else "设置",
                    trailing = if (page == null) "完成" else "",
                    onLeading = goBack,
                    onTrailing = onDismiss,
                    leadingIsBack = page != null,
                )
                if (page == null) SettingsIndex(viewModel) { page = it }
                else if (page == "ai" || page!!.startsWith("ai:")) com.raydon.moji.android.ai.AISettingsScreen(page!!, viewModel = viewModel) { page = it }
                else SettingsPageContent(page!!, viewModel, { page = it }, onExportBackup, onImportBackup, onChooseBackupFolder, onRestoreBackupFolder, onRequestPermissions)
            }
        }
    }
}

@Composable
private fun SettingsIndex(viewModel: MojiViewModel, onOpen: (String) -> Unit) {
    val schedule = scheduleOptions.firstOrNull { it.first == viewModel.defaultScheduleKind }?.second ?: "全天"
    val estimate = if (viewModel.defaultPlannedDurationEnabled) "预计 ${editorDuration(viewModel.defaultPlannedMinutes.toLong())}" else "不估算时长"
    val carry = if (viewModel.carriesOver) "未完成顺延" else "只看当天"
    val appearance = when (viewModel.appearance) { "light" -> "浅色宣纸"; "dark" -> "深色墨夜"; else -> "跟随系统" }
    val texture = if (viewModel.paperTextureEnabled) "纹理开" else "纹理关"
    val motion = when (viewModel.inkMotionLevel) { "reduced" -> "轻量"; "off" -> "关闭"; else -> "完整" }
    val reminder = if (viewModel.notificationsEnabled) "提醒开" else "提醒关"
    val sound = if (viewModel.soundEnabled) "声音开" else "静音"
    // iPhone reference: "偏好" glyph top at 144pt, card-bottom→next header glyph 36.7pt, card→card 35.4pt.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 15.7.dp, bottom = 24.dp)) {
        item { SectionCaption("偏好", Modifier.padding(horizontal = 20.dp)) }
        item {
            SettingsGroup {
                // SF "checklist", "square.grid.2x2" and "timer" at body size use the SF look-alike glyphs.
                SettingsRow(MojiIcon.TabChecklist, "计划默认值", "${categoryName(viewModel.defaultCategory)} · $schedule · $estimate · $carry", 21.dp) { onOpen("defaults") }
                SettingsSeparator()
                SettingsRow(MojiIcon.GridSF, "小组件快速添加", "3 个桌面预设", 22.dp) { onOpen("widgets") }
                SettingsSeparator()
                SettingsRow(MojiIcon.TimerSF, "番茄钟", "专注 ${viewModel.defaultFocusMinutes} 分钟 · 短休 ${viewModel.shortBreakMinutes} 分钟", 22.dp) { onOpen("pomodoro") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Bell, "提醒与反馈", "全天 ${viewModel.allDayReminderHour.toString().padStart(2, '0')}:00 · $reminder · $sound") { onOpen("notifications") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Brush, "外观", "$appearance · $texture · 运笔$motion") { onOpen("appearance") }
                SettingsSeparator()
                val aiSettings by com.raydon.moji.android.ai.AISettingsStore.shared.settings.collectAsStateWithLifecycle()
                val aiKeyed by com.raydon.moji.android.ai.AISettingsStore.shared.keyedIDs.collectAsStateWithLifecycle()
                SettingsRow(MojiIcon.Cpu, "AI 模型", com.raydon.moji.android.ai.aiSettingsSummary(aiSettings, aiKeyed)) { onOpen("ai") }
            }
            Spacer(Modifier.height(34.4.dp))
        }
        item { SectionCaption("系统与数据", Modifier.padding(horizontal = 20.dp)) }
        item {
            SettingsGroup {
                SettingsRow(MojiIcon.Hand, "权限", "通知与系统日历") { onOpen("permissions") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Backup, "数据备份", "导出、导入与换签保护") { onOpen("backup") }
                SettingsSeparator()
                SettingsRow(MojiIcon.Info, "关于 Moji", "版本与数据格式", 21.dp) { onOpen("about") }
            }
            Spacer(Modifier.height(35.4.dp))
        }
        item {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开发者", fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    Text("Jerry Wong", color = iosSecondaryLabel, fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val settingsGroupColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background == com.raydon.moji.ui.MojiPalette.PaperDark) Color(0xFF2C2C2E) else Color.White

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background == com.raydon.moji.ui.MojiPalette.PaperDark
    // List rows use UIKit's label colour: pure black / white, not the ink tone.
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = settingsGroupColor, contentColor = if (dark) Color.White else Color.Black) { Column { content() } }
}

@Composable
private fun SettingsRow(icon: MojiIcon, title: String, detail: String, iconSize: androidx.compose.ui.unit.Dp = 24.dp, onClick: () -> Unit) {
    // iOS row: 197px (65.67pt) tall; the symbol sits centred in a 24pt frame at its natural body size.
    Row(Modifier.fillMaxWidth().height(65.67.dp).clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            MojiLineIcon(icon, iconSize, MaterialTheme.colorScheme.primary, strokeWidth = 1.5f, cutoutColor = settingsGroupColor)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        FormChevron()
    }
}

@Composable
private fun SettingsSeparator() {
    // Dp.Hairline strokes on the pixel boundary and smears into two grey rows; draw exactly one device pixel like UITableView.
    val onePixel = with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toDp() }
    HorizontalDivider(Modifier.overlayHairline().padding(start = 56.dp), color = iosSeparator, thickness = onePixel)
}

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
    "widgets" -> "快速添加预设"
    in listOf("widgets:0", "widgets:1", "widgets:2") -> "编辑预设"
    "pomodoro" -> "番茄钟"
    "notifications" -> "提醒与反馈"
    "appearance" -> "外观"
    "permissions" -> "权限"
    "backup" -> "数据备份"
    else -> "关于 Moji"
}

fun durationText(minutes: Long): String = when {
    minutes >= 60 && minutes % 60 == 0L -> "${minutes / 60}时"
    minutes >= 60 -> "${minutes / 60}时${minutes % 60}分"
    minutes > 0 -> "${minutes}分"
    else -> "0分"
}

private fun calculateStreak(days: Set<LocalDate>): Int {
    var cursor = LocalDate.now()
    if (cursor !in days) cursor = cursor.minusDays(1)
    var count = 0
    while (cursor in days) { count += 1; cursor = cursor.minusDays(1) }
    return count
}
