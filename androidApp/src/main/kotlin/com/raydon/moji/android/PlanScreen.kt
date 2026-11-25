package com.raydon.moji.android

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.ChecklistPolicy
import com.raydon.moji.ui.InkCard
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun PlanScreen(viewModel: MojiViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val tick by viewModel.tickMillis.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CheckInItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorKind by remember { mutableStateOf("planned") }
    var showCalendar by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf<CheckInItem?>(null) }
    var quickTitle by remember { mutableStateOf("") }
    var quickDraftId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showsCompleted by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    val aiReady = rememberAIReady()
    // What was read out of plans typed a moment ago, by plan id, and the 存为模板 hint. Never saved;
    // kept in the view model so a tab switch or a rotation does not lose them (A10).
    val entrySuggestions by viewModel.entrySuggestions.collectAsStateWithLifecycle()
    val readingEntryIds by viewModel.readingEntryIds.collectAsStateWithLifecycle()
    val templateHints by viewModel.templateHints.collectAsStateWithLifecycle()
    var breakdown by remember { mutableStateOf<Pair<CheckInItem, Boolean>?>(null) }
    var showsImport by remember { mutableStateOf(false) }
    // 分享: what came in from another app, drafted on the import page (which reads it, then takes it out of the inbox).
    var sharedItem by remember { mutableStateOf<com.raydon.moji.android.ai.ShareInboxItem?>(null) }
    // Entry points show while ready, and while not ready unless hidden; tapping one then opens the intro.
    val showsEntries = rememberAIShowsEntries()
    val aiSettings by com.raydon.moji.android.ai.AISettingsStore.shared.settings.collectAsStateWithLifecycle()
    val proactive = com.raydon.moji.android.ai.AIProactiveStore.shared
    val briefCache by proactive.brief.collectAsStateWithLifecycle()
    val briefDismissedDay by proactive.dismissedDay.collectAsStateWithLifecycle()
    val todayOrder by proactive.todayOrder.collectAsStateWithLifecycle()
    val carryDismissed by proactive.carryDismissed.collectAsStateWithLifecycle()
    val briefLoading by proactive.loading.collectAsStateWithLifecycle()
    // 晚间收尾: the evening's arrangement and the evenings it was closed or carried out; 今天的整理 for its count.
    val wrap = com.raydon.moji.android.ai.AIEveningWrapState.shared
    val wrapSaved by wrap.saved.collectAsStateWithLifecycle()
    val wrapLoading by wrap.loading.collectAsStateWithLifecycle()
    val wrapDismissedDay by wrap.dismissedDay.collectAsStateWithLifecycle()
    val wrapAppliedDay by wrap.appliedDay.collectAsStateWithLifecycle()
    val writeEntries by com.raydon.moji.android.ai.AIWriteLog.shared.entries.collectAsStateWithLifecycle()
    // 逐条改: what was picked in the menus, until 照此处理. Never saved.
    val wrapEdits = remember { mutableStateMapOf<String, com.raydon.moji.android.ai.WrapAction>() }
    var isEditingWrap by remember { mutableStateOf(false) }
    var showsWrites by remember { mutableStateOf(false) }
    // 说一句添加 and 分享 from outside the app (moji://say, moji://share).
    val sayRequested by viewModel.sayRequested.collectAsStateWithLifecycle()
    val shareRequests by viewModel.shareRequests.collectAsStateWithLifecycle()
    val quickFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Chip ids ("kind|text") tapped in the quick-entry field; they take effect only on adding.
    var adoptedChips by remember { mutableStateOf(emptySet<String>()) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, snapshot.checkInItems.size) {
        if (!didQA && qaRoute.isNotEmpty() && snapshot.checkInItems.isNotEmpty()) {
            didQA = true
            val sample = snapshot.checkInItems.firstOrNull { it.title == "整理 1.6.0 发布说明" }
            when (qaRoute) {
                "library", "library-filters", "templates", "library-batch" -> showLibrary = true
                "plan-new" -> showEditor = true
                "calendar-month", "calendar-week", "calendar-day", "calendar-jump" -> showCalendar = true
                "plan-editor", "plan-editor-advanced" -> { editing = sample; showEditor = true }
                "plan-expanded", "plan-expanded-bottom", "plan-inline" -> expandedId = sample?.id
                "plan-completed" -> showsCompleted = true
                "plan-copy" -> copying = sample
                else -> if (qaRoute.startsWith("plan-quick-")) expandedId = sample?.id
            }
        }
    }
    // 说一句添加 comes back to today's list, as on iOS.
    LaunchedEffect(sayRequested) { if (sayRequested) showCalendar = false }
    if (showCalendar) {
        androidx.activity.compose.BackHandler { showCalendar = false }
        PlanCalendarScreen(viewModel, LocalDate.now()) { showCalendar = false }
        return
    }
    val now = Instant.ofEpochMilli(tick)
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val nowZoned = now.atZone(zone)
    val todayKey = com.raydon.moji.android.ai.AIDailyBrief.dayKey(today)
    // Today's open plans in time order: what 今日提要 reads, and the order 恢复原顺序 goes back to.
    val schedulePending = snapshot.checkInItems.filter {
        it.kind == "planned" && it.isArchived != true && it.status in listOf("planned", "inProgress") &&
            ChecklistPolicy.isVisible(it.scheduledStart, it.status, now.toString(), viewModel.carriesOver, zone.id)
    }.sortedWith(compareBy<CheckInItem> { it.scheduledStart }.thenBy { it.createdAt })
    // After 按建议排序 the list keeps that order for the day; plans not in it follow in their own order.
    // Only while the brief itself can show (B6): no card, no order taken from it.
    val suggestedOrder = todayOrder?.takeIf { it.day == todayKey && aiSettings.dailyBrief && aiReady }?.ids
    val pending = if (suggestedOrder != null) com.raydon.moji.android.ai.AIDailyBrief.sorted(schedulePending, suggestedOrder) else schedulePending
    // 晚间收尾 takes the place of 今日提要 from 21:00 on (a photographed run stands the clock at 21:30).
    val wrapNow = com.raydon.moji.android.ai.AIClock.now()
    val wrapDay = wrapNow.toLocalDate()
    val wrapDayKey = com.raydon.moji.android.ai.AIDailyBrief.dayKey(wrapDay)
    val wrapFacts = com.raydon.moji.android.ai.AIEveningWrap.facts(snapshot.checkInItems, snapshot.records, today, zone)
    // 收尾 and 提要 appear only once a model is set up (B6); the entry points still show without one.
    val showsWrap = aiSettings.eveningWrap && aiReady && com.raydon.moji.android.ai.AIEveningWrap.isEvening(wrapNow) &&
        wrapDismissedDay != wrapDayKey && com.raydon.moji.android.ai.AIEveningWrap.shouldShow(wrapFacts, pending.size)
    val showsBrief = !showsWrap && aiSettings.dailyBrief && aiReady && briefDismissedDay != todayKey &&
        com.raydon.moji.android.ai.AIDailyBrief.shouldShow(schedulePending, snapshot.countdowns, nowZoned)
    val quickCategories = aiCategories(viewModel)
    val quickDefaultCategory = viewModel.defaultCategory
    // What the words in the field already say, read on the device; nothing is sent for this.
    fun readQuickTokens(text: String): List<com.raydon.moji.android.ai.QuickEntryToken> =
        if (!aiSettings.readsQuickEntry || !showsEntries || text.isBlank()) emptyList()
        else com.raydon.moji.android.ai.QuickEntryParser.tokens(text, ZonedDateTime.now(), quickCategories, quickDefaultCategory)
    val quickTokens = remember(quickTitle, today, aiSettings.readsQuickEntry, showsEntries, quickCategories, quickDefaultCategory) {
        readQuickTokens(quickTitle)
    }
    // Asked for once a day while the card is on screen: when the page appears, comes back to the
    // foreground, or the card starts to show. The store keeps the rest of the rules.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val briefPlans by rememberUpdatedState(schedulePending)
    val briefEvents by rememberUpdatedState(snapshot.countdowns)
    LaunchedEffect(resumed, showsBrief) {
        if (resumed && showsBrief) proactive.refreshIfNeeded(briefPlans, briefEvents, ZonedDateTime.now())
    }
    val completed = snapshot.checkInItems.filter {
        it.kind == "planned" && it.isArchived != true && it.status in listOf("completed", "skipped") &&
            Instant.parse(it.completedAt ?: it.scheduledStart).atZone(zone).toLocalDate() == today
    }.sortedByDescending { it.completedAt ?: it.createdAt }
    // Asked for once an evening while 收尾 is on screen; the store keeps the rest of the rules.
    // A photographed run waits for its sample data, which lands just after the first frame.
    val waitsForQaSeed = BuildConfig.DEBUG && com.raydon.moji.android.ai.AIQAFixture.scenario.isNotEmpty() && qaRoute.isEmpty()
    val wrapDone by rememberUpdatedState(completed.filter { it.status == "completed" })
    LaunchedEffect(resumed, showsWrap, waitsForQaSeed) {
        if (resumed && showsWrap && !waitsForQaSeed) wrap.refreshIfNeeded(wrapDone, briefPlans, briefEvents, com.raydon.moji.android.ai.AIClock.now())
    }
    // 分享: on coming back and when moji://share says so, the oldest thing in the inbox. Drafted at
    // once when a model is set up (the import page takes it out of the inbox); otherwise the
    // introduction says it is kept, and it stays for later.
    LaunchedEffect(resumed, shareRequests, waitsForQaSeed) {
        if (!resumed || waitsForQaSeed) return@LaunchedEffect
        val inbox = com.raydon.moji.android.ai.ShareInbox.of(context)
        fun busy() = sharedItem != null || showsImport || AIIntro.page.value != null
        // Set up: the oldest. Not set up: the oldest not introduced yet, so a second share still gets its one introduction.
        fun next(): com.raydon.moji.android.ai.ShareInboxItem? {
            val items = inbox.items()
            if (com.raydon.moji.android.ai.AISettingsStore.shared.isReady) return items.firstOrNull()
            val id = proactive.shareToIntroduce(items.map { it.id }) ?: return null
            return items.firstOrNull { it.id == id }
        }
        if (busy() || next() == null) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        if (busy()) return@LaunchedEffect
        val item = next() ?: return@LaunchedEffect
        if (com.raydon.moji.android.ai.AISettingsStore.shared.isReady) { sharedItem = item; showsImport = true }
        // Not set up: the introduction once for each thing shared; it stays in the inbox for later (A4).
        else { proactive.markShareIntroduced(item.id); AIIntro.open(forShare = true) }
    }
    val total = pending.size + completed.size
    val toolbarPixels = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }
    LaunchedEffect(qaRoute, expandedId, pending.size) {
        if (expandedId != null) {
            kotlinx.coroutines.delay(200)
            val index = pending.indexOfFirst { it.id == expandedId }
            val activeOffset = (if (snapshot.activeSession != null || timer.hasActiveFocus()) 1 else 0) + (if (showsBrief || showsWrap) 1 else 0)
            val archivedIndex = completed.indexOfFirst { it.id == expandedId }
            val target = if (index >= 0) 3 + index + activeOffset else if (archivedIndex >= 0 && showsCompleted) 4 + pending.size.coerceAtLeast(1) + activeOffset + archivedIndex else -1
            if (target >= 0) listState.animateScrollToItem(target, -toolbarPixels)
        }
    }
    fun addQuickPlan() {
        val submitted = quickTitle.trim()
        if (submitted.isEmpty()) return
        val id = quickDraftId
        // The chips the user tapped go on in the same save; the rest are ignored. Read from the words
        // as they are now, as iOS does: a caller may set them just before (the QA routes do).
        val tokens = readQuickTokens(quickTitle)
        val adopted = adoptedChips.intersect(tokens.map { it.id }.toSet())
        val chosen = if (adopted.isEmpty()) null else com.raydon.moji.android.ai.QuickEntryParser.suggestion(quickTitle, tokens, adopted)
        // None tapped, but the device read something: that goes under the new row at once (A2).
        val local = if (adopted.isEmpty()) com.raydon.moji.android.ai.QuickEntryParser.localSuggestion(quickTitle, tokens) else null
        viewModel.addQuickPlan(id, submitted, today, chosen, local, aiCategories(viewModel)) {
            if (quickTitle.trim() == submitted) { quickTitle = ""; adoptedChips = emptySet() }
            quickDraftId = java.util.UUID.randomUUID().toString()
        }
    }
    /**
     * 照此处理: one write for every plan left, undoable, kept in 今天的整理. Plans past the six
     * rows are carried to tomorrow as usual; the first 拆小 then opens 拆成步骤 for that plan.
     */
    fun applyWrap(plans: List<CheckInItem>, saved: Map<String, com.raydon.moji.android.ai.AIEveningWrapState.Entry>) {
        val day = com.raydon.moji.android.ai.AIClock.now().toLocalDate()
        val chosen = plans.mapIndexed { index, item ->
            val action = if (index >= com.raydon.moji.android.ai.AIEveningWrap.maximumRows) com.raydon.moji.android.ai.WrapAction.Tomorrow
                else wrapEdits[item.id] ?: saved[item.id]?.action ?: com.raydon.moji.android.ai.WrapAction.Tomorrow
            item to action
        }
        val outcome = com.raydon.moji.android.ai.AIEveningWrap.apply(chosen, day, zone)
        if (outcome.changed.isNotEmpty()) viewModel.commitDrafts(changed = outcome.changed, notice = "已照此安排，可撤销", summary = outcome.summary)
        wrap.markApplied(day)
        isEditingWrap = false
        wrapEdits.clear()
        outcome.firstSplit?.let { id ->
            scope.launch {
                kotlinx.coroutines.delay(450)
                viewModel.snapshot.value.checkInItems.firstOrNull { it.id == id }?.let { item -> aiEntry { breakdown = item to true } }
            }
        }
    }
    // 说一句添加: today's list from the top, the field focused with the keyboard up, then the
    // system speech screen when the phone has one (the emulator has none, so only the keyboard).
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val sayLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == android.app.Activity.RESULT_OK && !spoken.isNullOrBlank()) quickTitle = appendingSpoken(quickTitle, spoken)
    }
    // ai-say: what iOS's stand-in microphone shows, the words in the field while it still listens.
    var sayListeningQA by remember { mutableStateOf(false) }
    LaunchedEffect(sayRequested, resumed) {
        if (!sayRequested || !resumed) return@LaunchedEffect
        expandedId = null
        listState.scrollToItem(0)
        kotlinx.coroutines.delay(350)
        if (BuildConfig.DEBUG && com.raydon.moji.android.ai.AIQAFixture.scenario == "ai-say") {
            // The keyboard stays down, as in ai-entry-chips, so the navigation bar matches the other shots.
            viewModel.sayRequested.value = false
            quickTitle = "明天上午交周报"
            sayListeningQA = true
            focusManager.clearFocus()
            return@LaunchedEffect
        }
        runCatching { quickFocus.requestFocus() }
        keyboard?.show()
        // Cleared before the speech screen pauses the page, so coming back does not start it again.
        viewModel.sayRequested.value = false
        if (speechAvailable(context)) try { sayLauncher.launch(speechIntent()) } catch (_: android.content.ActivityNotFoundException) {}
    }
    var didAIQA by remember { mutableStateOf(false) }
    // The ink rule under a collapsed row and the 2dp below it: ai-origin leaves these behind the tab bar.
    val rowRulePixels = with(androidx.compose.ui.platform.LocalDensity.current) { 9.dp.toPx() }
    LaunchedEffect(qaRoute, snapshot.checkInItems.size) {
        if (!BuildConfig.DEBUG || didAIQA || !qaRoute.startsWith("ai-") || snapshot.checkInItems.isEmpty()) return@LaunchedEffect
        didAIQA = true
        kotlinx.coroutines.delay(500)
        when (qaRoute) {
            "ai-entry-suggestion", "ai-entry-split", "ai-entry-applied" -> {
                quickTitle = if (qaRoute == "ai-entry-split") "买菜、取快递、交电费" else "明天下午三点交保险计划书 45分钟"
                addQuickPlan()
            }
            // Added without tapping a chip while no model is set up: the strip read on the device.
            "ai-entry-local" -> {
                quickTitle = "明早八点背单词半小时，工作日都要"
                addQuickPlan()
                focusManager.clearFocus()
            }
            // The sample data has 「背单词」 twice from earlier days; a third by hand brings the hint.
            "ai-plan-template-hint" -> {
                quickTitle = "背单词"
                addQuickPlan()
                focusManager.clearFocus()
            }
            "ai-breakdown", "ai-breakdown-carry", "ai-breakdown-note" -> pending.firstOrNull()?.let { breakdown = it to (qaRoute == "ai-breakdown-carry") }
            "ai-import", "ai-import-drafts", "ai-import-ocr" -> showsImport = true
            "ai-library-filter", "ai-library-filter-applied" -> showLibrary = true
            "ai-entry-chips" -> {
                // Typed, two chips adopted, not added; the keyboard stays down.
                val text = "周三下午三点交周报 45分钟"
                quickTitle = text
                adoptedChips = com.raydon.moji.android.ai.QuickEntryParser.tokens(text, ZonedDateTime.now(), aiCategories(viewModel), viewModel.defaultCategory)
                    .filter { it.kind == com.raydon.moji.android.ai.QuickEntryToken.Kind.Day || it.kind == com.raydon.moji.android.ai.QuickEntryToken.Kind.Time }
                    .map { it.id }.toSet()
                focusManager.clearFocus()
            }
            "ai-intro" -> AIIntro.open()
            "ai-demo", "ai-demo-bottom" -> AIIntro.open("demo")
            // Not tied to this effect, which starts again when the list changes.
            "ai-wrap-edit" -> scope.launch { kotlinx.coroutines.delay(1_900); isEditingWrap = true }
            "ai-writes" -> scope.launch { kotlinx.coroutines.delay(100); showsWrites = true }
            "ai-say" -> scope.launch { kotlinx.coroutines.delay(100); viewModel.startSaying() }
            "ai-breakdown-button" -> expandedId = snapshot.checkInItems.firstOrNull { it.title == "整理 1.6.0 发布说明" }?.id
            // The first step split off shows whole above the tab bar, 墨记拆自 included, as on iOS;
            // the taller Android bar would otherwise cover its second line. The row is kept there
            // for a few seconds rather than scrolled to once: 今日提要 comes from the stand-in
            // provider about half a second in and pushes the list down, so a single early scroll
            // could land before it (11-23: the light shot had scrolled, the dark one had not).
            "ai-origin" -> scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    androidx.compose.runtime.snapshotFlow {
                        val layout = listState.layoutInfo
                        val id = snapshot.checkInItems.firstOrNull { it.title == "列出汇报的三个要点" }?.id
                        val row = layout.visibleItemsInfo.firstOrNull { it.key == "pending-$id" }
                        if (id == null || row == null) 0f else row.offset + row.size - rowRulePixels - layout.viewportEndOffset
                    }.collect { overflow -> if (overflow > 0.5f) listState.scrollBy(overflow) }
                }
            }
        }
    }
    LaunchedEffect(qaRoute, entrySuggestions.size) {
        if (BuildConfig.DEBUG && qaRoute == "ai-entry-applied") entrySuggestions.keys.firstOrNull()?.let { id ->
            kotlinx.coroutines.delay(1_200); viewModel.applyEntrySuggestion(id)
        }
    }
    val openRecord = { addMenu = false; editing = null; editorKind = "completed"; showEditor = true }
    val openPlan = { addMenu = false; editing = null; editorKind = "planned"; showEditor = true }
    val openImport = { addMenu = false; aiEntry { showsImport = true } }
    // 今天的整理 in the ＋ menu while today has a write not taken back (A9).
    val openWrites = if (com.raydon.moji.android.ai.AIWriteLog.activeCount(writeEntries, today, zone) > 0) { { addMenu = false; showsWrites = true } } else null
    MojiScreen(opaqueTopInset = true) {
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Box {
                    Column {
                        MojiLargeTitle("计划", MojiIcon.CalendarFill, { showCalendar = true }, listOf(MojiIcon.GridSF to { showLibrary = true }, MojiIcon.Plus to { addMenu = true }), bottomPadding = 2.5.dp, opaqueBackground = true)
                        NavigationBarHairline()
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 40.dp)) {
                        PlanAddMenu(addMenu && listState.firstVisibleItemIndex == 0, { addMenu = false }, openRecord, openPlan, if (showsEntries) openImport else null, openWrites)
                    }
                }
            }
            item {
                InkCard(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 7.dp), padding = PaddingValues(0.dp)) {
                  // The chips take a line under the field; the card grows with them.
                  Column(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.tween(220))) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.foundation.text.BasicTextField(quickTitle, { quickTitle = it }, Modifier.weight(1f).focusRequester(quickFocus),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addQuickPlan() }),
                            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner -> if (quickTitle.isEmpty()) Text("写下一件要做的事", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)); inner() })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (showsEntries) QuickEntryMic(quickTitle, listeningForQA = sayListeningQA) { quickTitle = it }
                            Box(Modifier.size(44.dp).alpha(if (quickTitle.isBlank()) 0.35f else 1f).clickable(enabled = quickTitle.isNotBlank()) {
                                addQuickPlan()
                            }, contentAlignment = Alignment.Center) {
                                InkBrushMedallion(MojiIcon.Plus, MaterialTheme.colorScheme.primary, 36.dp)
                            }
                        }
                    }
                    if (quickTokens.isNotEmpty()) QuickEntryChipsRow(quickTokens, adoptedChips, { adoptedChips = it },
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 11.dp))
                  }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp).heightIn(min = 39.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkSeal("今", 25)
                    Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("今日计划", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        // iOS .secondary in the list resolves to primary at 50%: (123,122,118) light, (136,136,135) dark.
                        Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                    Spacer(Modifier.weight(1f))
                    InkDivider(Modifier.width(44.dp), 0.26f, inkSeed("dashboard-today-heading"))
                    Text(if (total == 0) "今日无计划" else "完成 ${completed.count { it.status == "completed" }}/$total", Modifier.padding(start = 12.dp).widthIn(min = 78.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        color = if (total == 0) iosSecondaryLabel else MaterialTheme.colorScheme.primary)
                }
            }
            if (showsWrap) item(key = "evening-wrap") {
                val applied = wrapAppliedDay == wrapDayKey
                // Only a model's answer for this evening counts, and only while one is set up.
                val saved = if (aiReady && wrapSaved?.day == wrapDayKey) wrap.arrangement(wrapDay) else emptyMap()
                val arranged = !applied && pending.any { it.id in saved }
                val rows = if (!arranged) emptyList() else pending.take(com.raydon.moji.android.ai.AIEveningWrap.maximumRows).map { item ->
                    val model = saved[item.id]?.action ?: com.raydon.moji.android.ai.WrapAction.Tomorrow
                    val action = wrapEdits[item.id] ?: model
                    EveningWrapRow(item.id, item.title, action, if (action == model) saved[item.id]?.note else null)
                }
                EveningWrapCard(
                    factsLine = com.raydon.moji.android.ai.AIEveningWrap.factsLine(wrapFacts),
                    secondLine = com.raydon.moji.android.ai.AIEveningWrap.secondLine(applied, pending.size, arranged),
                    isReady = aiReady,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 7.dp),
                    rows = rows,
                    hiddenCount = if (arranged) maxOf(0, pending.size - com.raydon.moji.android.ai.AIEveningWrap.maximumRows) else 0,
                    isLoading = wrapLoading && !applied,
                    isEditing = isEditingWrap,
                    writesCount = com.raydon.moji.android.ai.AIWriteLog.activeCount(writeEntries, wrapDay, zone),
                    choose = { id, action -> wrapEdits[id] = action },
                    apply = { applyWrap(pending, saved) },
                    toggleEditing = { isEditingWrap = !isEditingWrap },
                    openIntro = { AIIntro.open() },
                    openWrites = { showsWrites = true },
                    dismiss = { wrap.dismiss(wrapDay) },
                )
            } else if (showsBrief) item(key = "daily-brief") {
                val cache = briefCache?.takeIf { it.day == todayKey }
                // The longest carried plan, for 拆小顺延那件; only while it is still to do.
                val longest = com.raydon.moji.android.ai.AIDailyBrief.carried(schedulePending, nowZoned).firstOrNull()
                DailyBriefCard(
                    factsLine = com.raydon.moji.android.ai.AIDailyBrief.firstLine(schedulePending, snapshot.countdowns, nowZoned),
                    advice = if (aiReady) com.raydon.moji.android.ai.AIDailyBrief.secondLine(cache, pending, today) else null,
                    isReady = aiReady,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 7.dp),
                    isLoading = briefLoading,
                    isOrdered = todayOrder?.day == todayKey,
                    canBreakDown = longest?.plan?.status == "planned",
                    sort = { cache?.let { proactive.saveTodayOrder(com.raydon.moji.android.ai.AIProactiveStore.TodayOrder(todayKey, it.order)) } },
                    restore = { proactive.saveTodayOrder(null) },
                    breakDown = { longest?.let { carried -> aiEntry { breakdown = carried.plan to true } } },
                    openIntro = { AIIntro.open() },
                    dismiss = { proactive.dismissBrief(todayKey) },
                )
            }
            if (snapshot.activeSession != null || timer.hasActiveFocus()) item {
                InkCard(Modifier.padding(horizontal = 18.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { viewModel.openFocus() }, verticalAlignment = Alignment.CenterVertically) {
                        MojiLineIcon(MojiIcon.Timer, 22.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(snapshot.activeSession?.title ?: timer.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (timer.running) "${timer.dashboardStatus()} · ${viewModel.remainingSeconds() / 60}:${(viewModel.remainingSeconds() % 60).toString().padStart(2, '0')}" else timer.dashboardStatus(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        MojiLineIcon(MojiIcon.ChevronRight, 16.dp)
                    }
                }
            }
            if (pending.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 68.dp, bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        InkAsset("InkFocusBloom", Modifier.fillMaxSize().alpha(0.10f))
                        MojiLineIcon(MojiIcon.Checklist, 38.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                    }
                    Text("今天留白", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("写下一件要做的事，让今天有一个落点。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 5.dp))
                }
            }
            items(pending, key = { "pending-" + it.id }) { item ->
              // Moves into place when 按建议排序 or 恢复原顺序 changes the order.
              Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                PlanRow(item, today, expandedId == item.id, false, viewModel,
                    { expandedId = if (expandedId == item.id) null else item.id },
                    { editing = item; showEditor = true }, { copying = item },
                    breakDown = if (showsEntries) { replaces -> aiEntry { breakdown = item to replaces } } else null,
                    openWrites = { showsWrites = true }) {
                    // A strip for the plan as it was when added; one changed since is not shown (A2).
                    val record = entrySuggestions[item.id]?.takeIf { it.isCurrent(item) }
                    if (record != null) EntrySuggestionStrip(record.suggestion, { viewModel.applyEntrySuggestion(item.id) }, { viewModel.splitEntry(item.id) },
                        { viewModel.dropEntrySuggestion(item.id) }, Modifier.padding(start = 46.dp, end = 4.dp, bottom = 8.dp))
                    else if (item.id in readingEntryIds) InkDraftWaiting("在看这句话", Modifier.padding(start = 58.dp, bottom = 8.dp))
                    // 存为模板: a title written by hand for the third time or more (N3).
                    templateHints[item.id]?.let { count ->
                        TemplateHintStrip(count, { viewModel.saveHintTemplate(item.id) }, { viewModel.dismissTemplateHint(item.id) },
                            Modifier.padding(start = 46.dp, end = 4.dp, bottom = 6.dp))
                    }
                    // 顺延旁批: an open plan carried three days or more, until 不用; only once a model is set up (B6).
                    val late = com.raydon.moji.android.ai.AIDrafting.daysLate(item.scheduledStart, nowZoned) ?: 0
                    val showsNote = aiSettings.carryOverNotes && aiReady && item.status == "planned" && item.id !in carryDismissed &&
                        late >= com.raydon.moji.android.ai.AIDailyBrief.carryNoteDays
                    androidx.compose.animation.AnimatedVisibility(showsNote, enter = fadeIn(tween(200)), exit = fadeOut(tween(200)) + shrinkVertically(tween(200))) {
                        CarryOverNoteLine(late, { aiEntry { breakdown = item to true } }, { proactive.dismissCarryNote(item.id) },
                            Modifier.padding(start = 46.dp, end = 4.dp, bottom = 4.dp))
                    }
                }
              }
            }
            if (completed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showsCompleted = !showsCompleted }.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("今日已结束 ${completed.size}", fontSize = 15.sp, color = iosSecondaryLabel, modifier = Modifier.weight(1f))
                        MojiChevronDown(showsCompleted, width = 13f, color = iosSecondaryLabel)
                    }
                }
                if (showsCompleted) items(completed, key = { "completed-" + it.id }) { item ->
                    PlanRow(item, today, expandedId == item.id, true, viewModel, { expandedId = if (expandedId == item.id) null else item.id },
                        { editing = item; showEditor = true }, { copying = item }, openWrites = { showsWrites = true })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (listState.firstVisibleItemIndex > 0) Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 7.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { showCalendar = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.CalendarFill, 28.dp) }
                Text("计划", modifier = Modifier.weight(1f).padding(start = 44.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box(Modifier.size(44.dp).clickable { showLibrary = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.GridSF, 28.dp) }
                Box {
                    Box(Modifier.size(44.dp).clickable { addMenu = true }, contentAlignment = Alignment.Center) { MojiLineIcon(MojiIcon.Plus, 23.dp) }
                    PlanAddMenu(addMenu, { addMenu = false }, openRecord, openPlan, if (showsEntries) openImport else null, openWrites)
                }
            }
            NavigationBarHairline()
        }
        }
    }
    if (showEditor) PlanEditorScreenDialog(editing, today, viewModel, initialKind = editorKind, offersTemplateHint = true) { showEditor = false; editing = null }
    if (showLibrary) PlanLibraryDialog(viewModel) { showLibrary = false }
    if (showsImport) PlanImportDialog(viewModel, sharedItem) { showsImport = false; sharedItem = null }
    if (showsWrites) AIWritesSheet(viewModel) { showsWrites = false }
    breakdown?.let { (item, replaces) ->
        PlanDraftSheet(viewModel, "拆成步骤", item.title, waitingText = "正在拆步骤", planNote = "拆自：${item.title}", showsDates = false,
            source = item, archivesSourceByDefault = replaces,
            origin = com.raydon.moji.android.ai.AIWriteText.splitOrigin(item.title),
            summary = { count, archived -> com.raydon.moji.android.ai.AIWriteText.breakdown(count, item.title, archived) },
            load = { com.raydon.moji.android.ai.AIAssistant.shared.breakDown(item, aiCategories(viewModel)) }) { breakdown = null }
    }
    copying?.let { PlanCopyDialog(it, viewModel) { copying = null } }
}

@Composable
private fun PlanAddMenu(expanded: Boolean, onDismiss: () -> Unit, onRecord: () -> Unit, onPlan: () -> Unit, onImport: (() -> Unit)? = null,
                        onWrites: (() -> Unit)? = null) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("记录已做") }, onRecord)
        DropdownMenuItem({ Text("详细添加计划") }, onPlan)
        if (onImport != null) DropdownMenuItem({ Text("从文字或截图添加") }, onImport)
        // iOS list.bullet.rectangle, the same drawing as 时间记录 on the review page.
        if (onWrites != null) DropdownMenuItem({ Text("今天的整理") }, onWrites, leadingIcon = { ListBulletRectangleIcon(LocalContentColor.current) })
    }
}

@Composable
fun InkSeal(character: String, size: Int = 25, style: Int = if (character == "今") 1 else if (character == "注") 2 else if (character == "专") 4 else 0) {
    val color = mojiVermilion
    val numeric = character.firstOrNull()?.isDigit() == true
    val context = LocalContext.current
    val typeface = remember(context, numeric) {
        if (numeric) {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            else android.graphics.Typeface.DEFAULT_BOLD
        } else androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jfzskseal) ?: android.graphics.Typeface.DEFAULT
    }
    val glyphPaint = remember(typeface) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = !numeric
        }
    }
    val glyphBounds = remember { android.graphics.Rect() }
    Box(Modifier.size(size.dp).rotate(if (numeric) -4f else if (character == "今") -7f else -3.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = this.size.minDimension; val width = maxOf(0.8.dp.toPx(), u * 0.055f)
            val ink = color.copy(alpha = 0.88f)
            val top = androidx.compose.ui.geometry.Offset(width, width)
            val bounds = androidx.compose.ui.geometry.Size(this.size.width - width*2, this.size.height-width*2)
            when (style) {
                1 -> {
                    drawRoundRect(ink, top, bounds, androidx.compose.ui.geometry.CornerRadius(u*0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(width))
                    drawRoundRect(color.copy(alpha=0.45f), top+androidx.compose.ui.geometry.Offset(u*0.12f,u*0.12f), androidx.compose.ui.geometry.Size(bounds.width-u*0.24f,bounds.height-u*0.24f), androidx.compose.ui.geometry.CornerRadius(u*0.035f), style=androidx.compose.ui.graphics.drawscope.Stroke(maxOf(0.55.dp.toPx(),width*0.58f)))
                }
                2, 4 -> {
                    val dx = if (style == 4) u*0.08f else u*0.015f; val dy=u*0.015f
                    drawOval(ink, top+androidx.compose.ui.geometry.Offset(dx,dy), androidx.compose.ui.geometry.Size(bounds.width-dx*2,bounds.height-dy*2), style=androidx.compose.ui.graphics.drawscope.Stroke(width))
                    val inset=if (style==4) u*0.10f else u*0.095f
                    drawOval(color.copy(alpha=0.28f), top+androidx.compose.ui.geometry.Offset(dx+inset,dy+inset), androidx.compose.ui.geometry.Size(bounds.width-(dx+inset)*2,bounds.height-(dy+inset)*2), style=androidx.compose.ui.graphics.drawscope.Stroke(maxOf(0.5.dp.toPx(),width*0.48f)))
                }
                else -> {
                    val left=width;val right=this.size.width-width;val bottom=this.size.height-width
                    val p=androidx.compose.ui.graphics.Path();p.moveTo(left+u*0.08f,width);p.lineTo(right-u*0.04f,width+u*0.02f);p.lineTo(right,width+u*0.09f);p.lineTo(right-u*0.01f,bottom-u*0.03f);p.lineTo(right-u*0.08f,bottom);p.lineTo(left+u*0.03f,bottom-u*0.01f);p.lineTo(left,bottom-u*0.10f);p.lineTo(left+u*0.01f,width+u*0.04f);p.close()
                    drawPath(p,ink,style=androidx.compose.ui.graphics.drawscope.Stroke(width,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
            if (character.isNotBlank()) {
                glyphPaint.color = color.toArgb()
                // A seal is fixed-size artwork, like iOS's fixedSize font.
                glyphPaint.textSize = u * if (numeric) 0.52f else 0.45f
                glyphPaint.getTextBounds(character, 0, character.length, glyphBounds)
                val available = u * if (style == 1) 0.62f else 0.72f
                val scale = minOf(1f, available / glyphBounds.width().coerceAtLeast(1), available / glyphBounds.height().coerceAtLeast(1))
                if (scale < 1f) {
                    glyphPaint.textSize *= scale
                    glyphPaint.getTextBounds(character, 0, character.length, glyphBounds)
                }
                // Center the painted glyph, not its advance width or line box:
                // the seal font's asymmetric bearings shift a centered Text.
                val x = this.size.width / 2 - glyphBounds.exactCenterX()
                val baseline = this.size.height / 2 - glyphBounds.exactCenterY()
                drawContext.canvas.nativeCanvas.drawText(character, x, baseline, glyphPaint)
            }
        }
    }
}

/** SF "chevron.down": [width] is the glyph width in dp; iOS draws it in the tertiary label colour. */
@Composable
fun MojiChevronDown(expanded: Boolean, width: Float = 9.4f, color: Color = iosTertiaryLabel) {
    Canvas(Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)) {
        val d = density; val half = width / 2f; val stroke = width * 0.2f; val drop = (width - stroke) * 0.5f
        val top = this.size.height / 2f - drop * d / 2f; val cx = this.size.width / 2f
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(cx - (half - stroke / 2) * d, top); lineTo(cx, top + drop * d); lineTo(cx + (half - stroke / 2) * d, top) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * d, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The 1px shadow line UIKit draws under a navigation bar whose background is visible. */
@Composable
fun NavigationBarHairline() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.material3.HorizontalDivider(thickness = devicePixel, color = if (dark) Color.White.copy(alpha = 0.17f) else Color.Black.copy(alpha = 0.3f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(plan: CheckInItem, today: LocalDate, expanded: Boolean, archived: Boolean, viewModel: MojiViewModel,
                    toggleExpansion: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit,
                    /**
                     * Present while AI entry points show (it opens the intro when no model is set up);
                     * the argument says whether the steps replace this plan.
                     */
                    breakDown: ((Boolean) -> Unit)? = null,
                    /** Opens 今天的整理 from the vermilion 墨记… mark (A9). */
                    openWrites: (() -> Unit)? = null,
                    below: @Composable () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    var sealMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var quickField by remember { mutableStateOf<PlanQuickField?>(null) }
    val qaRoute by viewModel.visualQaRoute.collectAsStateWithLifecycle()
    var didQA by remember { mutableStateOf(false) }
    LaunchedEffect(qaRoute, expanded) {
        if (BuildConfig.DEBUG && !didQA && expanded && plan.title == "整理 1.6.0 发布说明") {
            val field = if (qaRoute == "plan-inline") PlanQuickField.CONTENT
                else if (qaRoute.startsWith("plan-quick-")) PlanQuickField.entries.firstOrNull { it.name.equals(qaRoute.removePrefix("plan-quick-").substringBefore("-"), true) } else null
            if (field != null) { didQA = true; quickField = field }
        }
    }
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val finished = plan.status in listOf("completed", "skipped")
    val date = Instant.parse(plan.scheduledStart).atZone(ZoneId.systemDefault())
    val schedule = scheduleOptions.firstOrNull { it.first == plan.scheduleKind && it.first != "exactTime" }?.second ?: date.format(DateTimeFormatter.ofPattern("HH:mm"))
    val hasDuration = plan.plannedDurationEnabled ?: (plan.detailsConfigured != false && (plan.scheduleKind ?: "exactTime") == "exactTime")
    val timing = schedule + if (hasDuration) " · 预计 ${editorDuration(plan.plannedMinutes.toLong())}" else ""
    val daysLate = ChronoUnit.DAYS.between(date.toLocalDate(), today).coerceAtLeast(0)
    val repeatText = if (plan.repeatRule == "customWeekdays") plan.repeatWeekdays.orEmpty().sorted().joinToString("、") { mapOf(1 to "周日", 2 to "周一", 3 to "周二", 4 to "周三", 5 to "周四", 6 to "周五", 7 to "周六")[it].orEmpty() }.ifEmpty { "自选星期" }
        else repeatOptions.firstOrNull { it.first == (plan.repeatRule ?: "never") }?.second ?: "不重复"
    val reminderText = when (val m = plan.reminderMinutesBefore) { null -> "不提醒"; 0 -> "开始时"; 60 -> "提前 1 小时"; 1440 -> "提前 1 天"; else -> if (m % 60 == 0) "提前 ${m / 60} 小时" else "提前 $m 分钟" }
    val content: @Composable () -> Unit = {
        Column {
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (expanded) quickField = PlanQuickField.CONTENT else toggleExpansion() }, onLongClick = { menu = true }).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).clickable { viewModel.togglePlan(plan.id) }, contentAlignment = Alignment.Center) {
                    if (finished) {
                        Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (plan.status == "skipped") "−" else "✓", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.surface)
                        }
                        InkAsset("InkIconLoop", Modifier.requiredSize(54.dp).alpha(0.78f))
                        InkAsset("InkCheckMark", Modifier.size(20.dp, 17.dp))
                    } else Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        Text(plan.title, fontSize = 17.sp, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                            // iOS Color.primary: pure black / white (0,0,0 / 255,255,255), not the ink tone.
                            color = if (plan.status == "completed") MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else LocalContentColor.current)
                        if (finished) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                            InkAsset("InkTaskStrike", Modifier.fillMaxWidth().height(11.dp).offset(y = 1.dp).alpha(if (plan.status == "skipped") 0.46f else 0.72f))
                        }
                    }
                    if (!expanded) {
                        PlanMetaLine(plan.scheduleKind == "exactTime", schedule, if (hasDuration) editorDuration(plan.plannedMinutes.toLong()) else null,
                            categoryName(plan.category), plan.aiOrigin?.takeIf { it.isNotEmpty() }?.let { com.raydon.moji.android.ai.AIWriteText.mark(it) }, openWrites)
                        if (plan.note.isNotEmpty()) Text(plan.note, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.5f))
                    }
                }
                if (daysLate > 0 && !finished) {
                    val seal = if (daysLate > 99) "99+" else daysLate.toString()
                    // A plan that keeps being carried over is usually too big, on the wrong day,
                    // or no longer wanted: the seal offers all three. 改期 and 放下 never need a
                    // model; 拆小一点 shows with the other entry points (A6). 放下 archives, undoably (A7).
                    if (plan.status == "planned") Box {
                        Box(Modifier.size(22.dp, 44.dp).clickable { sealMenu = true }.semantics { contentDescription = "已顺延 $daysLate 天，处理这个计划" }, contentAlignment = Alignment.Center) { InkSeal(seal, 22) }
                        DropdownMenu(sealMenu, { sealMenu = false }) {
                            if (breakDown != null) DropdownMenuItem({ Text("拆小一点") }, { sealMenu = false; breakDown(true) })
                            DropdownMenuItem({ Text("改期") }, { sealMenu = false; quickField = PlanQuickField.DATE })
                            DropdownMenuItem({ Text("放下") }, { sealMenu = false; viewModel.dropPlan(plan.id) })
                        }
                    } else InkSeal(seal, 22)
                }
                Box(Modifier.size(44.dp).clickable(onClick = toggleExpansion), contentAlignment = Alignment.Center) { MojiChevronDown(expanded) }
            }
            if (expanded) {
                InkDivider()
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { quickField = PlanQuickField.CONTENT }, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(plan.note.ifEmpty { "添加备注" }, fontSize = 15.sp, lineHeight = 21.sp, color = if (plan.note.isEmpty()) iosSecondaryLabel else LocalContentColor.current.copy(alpha = 0.86f), modifier = Modifier.weight(1f))
                        Box(Modifier.padding(top = 3.dp)) { MojiLineIcon(MojiIcon.Pencil, 14.dp, iosTertiaryLabel) }
                    }
                    Column(Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(12.dp))) {
                        PlanDetailRow("日期", date.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)), MojiIcon.Calendar) { quickField = PlanQuickField.DATE }
                        HorizontalDivider(Modifier.padding(start = 38.dp), thickness = devicePixel, color = iosSeparator)
                        PlanDetailRow("安排", timing, if (plan.scheduleKind == "exactTime") MojiIcon.Clock else MojiIcon.Sun) { quickField = PlanQuickField.SCHEDULE }
                        HorizontalDivider(Modifier.padding(start = 38.dp), thickness = devicePixel, color = iosSeparator)
                        PlanDetailRow("类型", categoryName(plan.category), when (plan.category) { "work" -> MojiIcon.Work; "study" -> MojiIcon.Book; else -> MojiIcon.Tag }) { quickField = PlanQuickField.CATEGORY }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlanDetailPill(repeatText, MojiIcon.RepeatLoop, Modifier.weight(1f)) { quickField = PlanQuickField.RECURRENCE }
                        PlanDetailPill(reminderText, MojiIcon.BellPlain, Modifier.weight(1f)) { quickField = PlanQuickField.REMINDER }
                    }
                    if (plan.calendarEventIdentifier != null) Text("已写入系统日历", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    val isOpen = plan.status in listOf("planned", "inProgress")
                    // With entry points showing, 拆成步骤 gets its own button and 更多操作 shrinks to its icon.
                    val showsBreakDown = breakDown != null && isOpen
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showsBreakDown) Box {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(11.dp)).clickable { menu = true }
                                .semantics { contentDescription = "更多操作" }, contentAlignment = Alignment.Center) {
                                MojiLineIcon(MojiIcon.Ellipsis, 22.dp, MaterialTheme.colorScheme.primary)
                            }
                            PlanRowMenu(menu, { menu = false }, plan, today, viewModel, onEdit, onCopy, { confirmDelete = true }, null)
                        } else Box(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(11.dp)).clickable { menu = true },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                                MojiLineIcon(MojiIcon.Ellipsis, 22.dp, MaterialTheme.colorScheme.primary)
                                Text("更多操作", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                            PlanRowMenu(menu, { menu = false }, plan, today, viewModel, onEdit, onCopy, { confirmDelete = true }, breakDown)
                        }
                        if (showsBreakDown) Row(Modifier.weight(1f).heightIn(min = 44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.065f), RoundedCornerShape(12.dp))
                            .clickable { breakDown(false) }.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MojiLineIcon(MojiIcon.ListIndent, 19.dp, mojiVermilion)
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("拆成步骤", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.primary)
                                Text("先出草稿再加入", fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
                            }
                        }
                        if (plan.status in listOf("planned", "inProgress")) Row(Modifier.weight(1f).heightIn(min = 44.dp).background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp)).clickable {
                            if (!timer.hasActiveFocus()) { viewModel.selectPomodoroPhase("focus"); viewModel.linkPomodoro(plan) }
                            viewModel.openFocus()
                        }.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MojiLineIcon(MojiIcon.TimerSF, 19.dp, MaterialTheme.colorScheme.background)
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(if (timer.hasActiveFocus()) "继续专注" else "去专注", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background)
                                Text(if (timer.hasActiveFocus()) "前往当前会话" else "选择时长后开始", fontSize = 11.sp, color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
                            }
                        }
                    }
                }
            } else PlanRowMenu(menu, { menu = false }, plan, today, viewModel, onEdit, onCopy, { confirmDelete = true }, breakDown)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        if (expanded) InkCard(padding = PaddingValues(horizontal = 14.dp)) { content() } else Column(Modifier.padding(horizontal = 4.dp)) { content() }
        if (!archived) below()
        if (!archived && !expanded) InkDivider(Modifier.padding(start = 58.dp), 0.34f, inkSeed(plan.id.uppercase()))
    }
    quickField?.let { PlanQuickEditorDialog(plan, it, viewModel) { quickField = null } }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这项计划？") },
        text = { Text(plan.title) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deletePlan(plan.id) }) { Text("删除", color = mojiVermilion) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

/**
 * The line under a plan's title, in caption2 as on iOS, which sets it with .lineLimit(1) and
 * .minimumScaleFactor(0.78): when it does not fit, every word shrinks together (the icon and
 * the 5dp gaps keep their size), and only at 0.78 does 墨记拆自 give way. A line that fits
 * keeps 11sp, so plans without a long line look as before.
 */
@Composable
private fun PlanMetaLine(exactTime: Boolean, schedule: String, duration: String?, category: String, origin: String?, openOrigin: (() -> Unit)? = null) {
    val secondary = MaterialTheme.colorScheme.secondary
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    BoxWithConstraints {
        val words = buildList<Pair<String, FontWeight?>> {
            add(schedule to FontWeight.SemiBold)
            if (duration != null) { add("·" to null); add(duration to null) }
            add("·" to null); add(category to null)
            if (origin != null) { add("·" to null); add(origin to null) }
        }
        val available = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val size = remember(words, available, bounded, style, density) {
            // The icon and one 5dp gap before each word.
            val fixed = with(density) { 10.dp.roundToPx() + words.size * 5.dp.roundToPx() }
            fun lineWidth(points: Float) = fixed + words.sumOf { (text, weight) ->
                measurer.measure(text, style.merge(TextStyle(fontSize = points.sp, fontWeight = weight)), softWrap = false, maxLines = 1, density = density).size.width
            }
            val natural = lineWidth(11f)
            if (!bounded || natural <= available) 11f else {
                val least = 11f * 0.78f
                var fitted = (11f * (available - fixed) / (natural - fixed)).coerceIn(least, 11f)
                // Widths round up to whole pixels, so step down until the line really fits.
                while (fitted > least && lineWidth(fitted) > available) fitted = maxOf(least, fitted - 0.1f)
                fitted
            }
        }.sp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MojiLineIcon(if (exactTime) MojiIcon.Clock else MojiIcon.Sun, 10.dp, secondary)
            Text(schedule, fontSize = size, maxLines = 1, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (duration != null) { Text("·", fontSize = size, color = secondary); Text(duration, fontSize = size, maxLines = 1, color = secondary) }
            Text("·", fontSize = size, color = secondary)
            Text(category, fontSize = size, maxLines = 1, color = secondary)
            // 留痕: where Moji got this plan from; it gives way first when even 0.78 is too wide.
            if (origin != null) {
                Text("·", fontSize = size, color = secondary)
                Text(origin, Modifier.weight(1f, fill = false).then(if (openOrigin != null) Modifier.clickable(onClick = openOrigin) else Modifier),
                    fontSize = size, maxLines = 1, overflow = TextOverflow.Ellipsis, color = mojiVermilion)
            }
        }
    }
}

@Composable
private fun PlanDetailRow(title: String, value: String, icon: MojiIcon, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // iOS centres the symbol on the text; Noto's ink sits about 0.8pt lower in its line than PingFang's.
        // briefcase.fill is 16pt wide here, so it overflows the 18dp slot a little without moving the title.
        Box(Modifier.size(18.dp).offset(y = 0.8.dp), contentAlignment = Alignment.Center) {
            val glyphSize = if (icon == MojiIcon.Work) 19.2.dp else 18.dp
            Box(Modifier.requiredSize(glyphSize)) { MojiLineIcon(icon, glyphSize) }
        }
        Text(title, fontSize = 13.sp, color = iosSecondaryLabel, modifier = Modifier.width(32.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        MojiLineIcon(MojiIcon.Disclosure, 18.dp, iosTertiaryLabel)
    }
}

@Composable
private fun PlanDetailPill(value: String, icon: MojiIcon, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 36.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MojiLineIcon(icon, 15.dp, MaterialTheme.colorScheme.primary)
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f))
        MojiLineIcon(MojiIcon.Disclosure, 15.dp, iosTertiaryLabel)
    }
}

@Composable
private fun PlanRowMenu(expanded: Boolean, onDismiss: () -> Unit, plan: CheckInItem, today: LocalDate,
                        viewModel: MojiViewModel, onEdit: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
                        breakDown: ((Boolean) -> Unit)? = null) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text("完整编辑") }, { onDismiss(); onEdit() })
        if (breakDown != null && plan.status in listOf("planned", "inProgress")) DropdownMenuItem({ Text("拆成步骤") }, { onDismiss(); breakDown(false) })
        DropdownMenuItem({ Text("复制到今天") }, { onDismiss(); viewModel.copyPlan(plan.id, today) })
        DropdownMenuItem({ Text("复制到指定日期") }, { onDismiss(); onCopy() })
        DropdownMenuItem({ Text("保存为模板") }, { onDismiss(); viewModel.saveTemplate(plan.id) })
        DropdownMenuItem({ Text("归档") }, { onDismiss(); viewModel.batchPlans(setOf(plan.id), archived = true) })
        if (plan.status == "planned") {
            DropdownMenuItem({ Text("顺延") }, { onDismiss(); viewModel.postponePlan(plan.id) })
            DropdownMenuItem({ Text("跳过") }, { onDismiss(); viewModel.skipPlan(plan.id) })
        }
        DropdownMenuItem({ Text("删除", color = mojiVermilion) }, { onDismiss(); onDelete() })
    }
}

internal fun com.raydon.moji.android.data.PomodoroState.hasActiveFocus(): Boolean =
    running || accumulatedSeconds > 0 || pendingNextPhase != null || linkedPlanId.isNotBlank() || remainingSeconds < phaseDurationSeconds
