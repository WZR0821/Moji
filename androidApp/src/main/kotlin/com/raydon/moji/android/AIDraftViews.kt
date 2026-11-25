package com.raydon.moji.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.ai.AIDrafting
import com.raydon.moji.android.ai.AIQAFixture
import com.raydon.moji.android.ai.EntrySuggestion
import com.raydon.moji.android.ai.PlanDraft
import com.raydon.moji.android.ai.ReflectionDraft
import com.raydon.moji.android.ai.draftMessage
import com.raydon.moji.core.CheckInItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// The iOS AIDraftViews / AIFeatureViews, drawn with the Android form and ink components.

/** A draft is lighter than what is saved: thinner paper, a dashed edge. */
fun Modifier.inkDraftSurface(cornerRadius: Dp = 14.dp): Modifier = composed {
    val paper = MaterialTheme.colorScheme.surface.copy(alpha = 0.46f)
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    drawBehind {
        val radius = CornerRadius(cornerRadius.toPx())
        drawRoundRect(paper, cornerRadius = radius)
        val width = 0.9.dp.toPx()
        drawRoundRect(
            edge, topLeft = Offset(width / 2, width / 2), size = Size(size.width - width, size.height - width),
            cornerRadius = radius,
            style = Stroke(width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
        )
    }
}

/** The paper the rest of the app writes on, for parts of a draft page that are not drafts. */
@Composable
internal fun Modifier.inkPaperSurface(cornerRadius: Dp = 14.dp): Modifier {
    val paper = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.095f)
    return this.background(paper, RoundedCornerShape(cornerRadius)).drawBehind {
        drawRoundRect(edge, cornerRadius = CornerRadius(cornerRadius.toPx()), style = Stroke(0.75.dp.toPx()))
    }
}

/** The small 朱印 that marks something as not yet saved. */
@Composable
fun DraftSeal(size: Int = 20) {
    Box(Modifier.semantics { contentDescription = "草稿" }) { InkSeal("稿", size, 0) }
}

@Composable
internal fun inkMotionMoves(): Boolean {
    val app = LocalContext.current.applicationContext as? MojiApplication
    return app?.settings?.inkMotionLevel != "off"
}

/** Three blots soaking into the paper while a draft is being written. */
@Composable
fun InkDraftWaiting(text: String = "正在起草", modifier: Modifier = Modifier) {
    val moves = inkMotionMoves()
    val transition = rememberInfiniteTransition(label = "draft-waiting")
    Row(modifier.semantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { index ->
                val soak by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(850, easing = EaseInOut), RepeatMode.Reverse, StartOffset(index * 240)), label = "blot-$index")
                val seed = (31 + index * 7).toLong()
                var hash = (seed * 2654435761L).toULong(); hash = hash xor (hash shr 13); hash *= 0x9E3779B97F4A7C15uL; hash = hash xor (hash shr 7)
                val angle = ((hash and 255u).toFloat() / 255f * 2f - 1f) * 26f * 2.2f
                val scale = 1f + (((hash shr 8) and 255u).toFloat() / 255f * 2f - 1f) * 0.11f
                val opacity = if (moves) 0.20f + 0.75f * soak else 0.62f
                val grow = if (moves) 0.70f + 0.30f * soak else 1f
                InkAsset("InkStatusBlot", Modifier.size(10.dp).graphicsLayer {
                    rotationZ = angle; scaleX = scale * grow * (if (((hash shr 24) and 1u) == 1uL) -1f else 1f); scaleY = scale * grow; alpha = opacity
                }, MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
            }
        }
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

/** What went wrong, and a way to try again. Never a blocking alert. */
@Composable
fun DraftFailureView(message: String, retry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().inkDraftSurface(12.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary)
        if (retry != null) Text("再试一次", Modifier.clickable(onClick = retry), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

/** A small capsule button used on draft strips. */
@Composable
fun DraftCapsuleButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 30.dp).alpha(if (enabled) 1f else 0.4f)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
}

@Composable
internal fun DraftDismissButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp, 34.dp).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MojiLineIcon(MojiIcon.Xmark, 17.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
    }
}

/** Runs [load] once per attempt and keeps its result, its failure, or that it is still running. */
private class DraftLoad<T> {
    var value by mutableStateOf<T?>(null)
    var failure by mutableStateOf<String?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(true)
    var attempt by mutableIntStateOf(0)
}

@Composable
private fun <T> rememberDraftLoad(load: suspend () -> T, onLoaded: (T) -> Unit = {}): DraftLoad<T> {
    val state = remember { DraftLoad<T>() }
    LaunchedEffect(state.attempt) {
        state.loading = true; state.failure = null; state.error = null
        try {
            val result = load()
            state.value = result; onLoaded(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { state.failure = error.draftMessage; state.error = error }
        state.loading = false
    }
    return state
}

/** The plan types a draft may use, as raw values: study, work and "custom:<name>". */
fun aiCategories(viewModel: MojiViewModel): List<String> = listOf("study", "work") + viewModel.customCategories.map { "custom:$it" }

/** Whether a model is set up: the only state in which anything is sent. */
@Composable
fun rememberAIReady(): Boolean {
    val ready by com.raydon.moji.android.ai.AISettingsStore.shared.readyFlow.collectAsStateWithLifecycle()
    return ready
}

/**
 * Whether entry points show: always when ready, and while not ready unless
 * the user hid them in the intro or in 「AI 模型」.
 */
@Composable
fun rememberAIShowsEntries(): Boolean {
    val shows by com.raydon.moji.android.ai.AISettingsStore.shared.showsEntriesFlow.collectAsStateWithLifecycle()
    return shows
}

/** Runs [action] when a model is ready; otherwise opens the intro sheet first. */
fun aiEntry(action: () -> Unit) {
    if (com.raydon.moji.android.ai.AISettingsStore.shared.isReady) action() else AIIntro.open()
}

// MARK: - Plan drafts

/**
 * Proposed plans, shown before any of them exists, as a sheet of its own. Opens at half height
 * and grows to full height when the drafts do not fit, like the iOS medium/large detents.
 */
@Composable
fun PlanDraftSheet(
    viewModel: MojiViewModel,
    title: String,
    subject: String,
    waitingText: String = "正在起草",
    planNote: String = "",
    showsDates: Boolean = true,
    source: CheckInItem? = null,
    archivesSourceByDefault: Boolean = false,
    origin: String? = null,
    summary: ((count: Int, archivedSource: Boolean) -> String)? = null,
    /** Shown in place of a draft when there was nothing to send (下周草稿 for a week with no plans). */
    /** After plans were put in, before the sheet closes. */
    onAccepted: (() -> Unit)? = null,
    load: suspend () -> List<PlanDraft>,
    onDismiss: () -> Unit,
) {
    var large by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen(sheetFraction = if (large) null else 0.532f) {
            PlanDraftPage(viewModel, title, subject, waitingText, planNote, showsDates, source, archivesSourceByDefault, origin, summary, load,
                leading = "取消", onLeading = onDismiss, needsRoom = { large = true }, close = onDismiss,
                onAccepted = onAccepted)
        }
    }
}

/** The draft list itself, for a sheet of its own or as the second page of one. */
@Composable
fun PlanDraftPage(
    viewModel: MojiViewModel,
    title: String,
    subject: String,
    waitingText: String = "正在起草",
    planNote: String = "",
    showsDates: Boolean = true,
    source: CheckInItem? = null,
    archivesSourceByDefault: Boolean = false,
    /** Written into every accepted plan as 墨记's mark, e.g. 拆自「准备季度汇报」. */
    origin: String? = null,
    /** The line kept in 今天的整理: plans added, and whether the source was archived. */
    summary: ((count: Int, archivedSource: Boolean) -> String)? = null,
    load: suspend () -> List<PlanDraft>,
    leading: String,
    onLeading: () -> Unit,
    needsRoom: (() -> Unit)? = null,
    close: () -> Unit,
    onAccepted: (() -> Unit)? = null,
) {
    val drafts = remember { mutableStateListOf<PlanDraft>() }
    var archivesSource by remember { mutableStateOf(archivesSourceByDefault) }
    // 拆成步骤 (a draft with a source) can also go into the plan's note or a checklist memo (B1).
    var destination by remember {
        mutableStateOf(if (BuildConfig.DEBUG && source != null && viewModel.visualQaRoute.value == "ai-breakdown-note")
            com.raydon.moji.android.ai.BreakdownDestination.Note else com.raydon.moji.android.ai.BreakdownDestination.Plans)
    }
    // Once tapped, the confirm button stays off until the page closes (A12).
    var committed by remember { mutableStateOf(false) }
    // A plan being timed is not rewritten: 写进说明 cannot be picked for it.
    val timer by viewModel.pomodoro.collectAsStateWithLifecycle()
    val stored by viewModel.snapshot.collectAsStateWithLifecycle()
    val sourceFocused = remember(timer, stored.activeSession, source?.id) { source != null && viewModel.isBeingFocused(source.id) }
    LaunchedEffect(sourceFocused) {
        if (sourceFocused && destination == com.raydon.moji.android.ai.BreakdownDestination.Note) destination = com.raydon.moji.android.ai.BreakdownDestination.Plans
    }
    val state = rememberDraftLoad(load) { drafts.clear(); drafts.addAll(it) }
    val selected = drafts.filter { it.isSelected && it.title.isNotBlank() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.loading, state.failure, scroll.maxValue) {
        if (!state.loading && state.failure == null && scroll.maxValue > 4) needsRoom?.invoke()
    }
    fun accept() {
        if (committed) return
        committed = true
        val original = source?.let { s -> viewModel.snapshot.value.checkInItems.firstOrNull { it.id == s.id } }
        val steps = selected.map { it.title.trim() }
        when (if (original == null) com.raydon.moji.android.ai.BreakdownDestination.Plans else destination) {
            com.raydon.moji.android.ai.BreakdownDestination.Note -> {
                val plan = original!!
                viewModel.commitDrafts(changed = listOf(plan.copy(note = com.raydon.moji.android.ai.BreakdownText.noteWithSteps(plan.note, steps))),
                    notice = "已写进说明，可撤销", summary = com.raydon.moji.android.ai.AIWriteText.stepsIntoNote(steps.size, plan.title))
            }
            com.raydon.moji.android.ai.BreakdownDestination.Checklist -> {
                val plan = original!!
                viewModel.commitDrafts(addedMemos = listOf(com.raydon.moji.android.ai.BreakdownText.checklistMemo(plan.title, steps)),
                    notice = "已存成清单备忘，可撤销", summary = com.raydon.moji.android.ai.AIWriteText.stepsIntoChecklist(plan.title, steps.size))
            }
            com.raydon.moji.android.ai.BreakdownDestination.Plans -> {
                val mark = origin?.let { AIDrafting.prefix(it, 40) }
                val shaped = selected.map { draft -> draft to draft.copy(title = draft.title.trim()).plan(planNote).copy(aiOrigin = mark) }
                // The usual length of each type (N1), worked out once per type rather than once per step.
                val usual = shaped.filter { it.first.minutes == null }.map { it.second.category }.distinct()
                    .associateWith { viewModel.historyMinutes(it) }
                val plans = shaped.map { (draft, plan) ->
                    // A step the model gave no length takes the usual one for its type; it stays off until switched on.
                    if (draft.minutes == null) usual[plan.category]?.let { plan.copy(plannedMinutes = it) } ?: plan else plan
                }
                val changed = if (archivesSource && original != null) listOf(original.copy(isArchived = true)) else emptyList()
                // A plan being timed is not archived; the notice says only what happened (A12).
                viewModel.commitDrafts(plans, changed, "已加入 ${plans.size} 条计划", summary?.invoke(plans.size, false),
                    describe = { archived ->
                        (if (archived > 0) "已加入 ${plans.size} 条计划，原计划已归档" else "已加入 ${plans.size} 条计划") to summary?.invoke(plans.size, archived > 0)
                    })
            }
        }
        onAccepted?.invoke()
        close()
    }
    val chosen = if (source == null) com.raydon.moji.android.ai.BreakdownDestination.Plans else destination
    Column(Modifier.fillMaxSize()) {
        MojiSheetBar(title, leading, chosen.confirmTitle(selected.size), onLeading, ::accept,
            trailingEnabled = !state.loading && selected.isNotEmpty() && !committed)
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(subject, Modifier.padding(horizontal = 2.dp), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
            when {
                state.loading -> InkDraftWaiting(waitingText, Modifier.fillMaxWidth().inkDraftSurface().padding(14.dp))
                // A week without plans: nothing to try again (A3).
                state.error === com.raydon.moji.android.ai.AIRequestError.emptyWeek -> DraftFailureView(com.raydon.moji.android.ai.AIRequestError.emptyWeek.message, null)
                state.failure != null -> DraftFailureView(state.failure!!) { state.attempt++ }
                else -> {
                    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        DraftSeal(20)
                        // Follows the three-way choice on 拆成步骤; every other draft page adds plans.
                        Text(chosen.draftHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    drafts.forEachIndexed { index, draft ->
                        PlanDraftRow(draft, showsDates) { drafts[index] = it }
                    }
                    if (source != null) {
                        FormSegmentedControl(com.raydon.moji.android.ai.BreakdownDestination.entries.map { it.name to it.label }, destination.name,
                            horizontalPadding = 0,
                            disabled = if (sourceFocused) setOf(com.raydon.moji.android.ai.BreakdownDestination.Note.name) else emptySet()) { name ->
                            destination = com.raydon.moji.android.ai.BreakdownDestination.valueOf(name)
                        }
                        if (sourceFocused) Text("这条计划正在专注，结束后再写进说明。", Modifier.padding(horizontal = 2.dp), fontSize = 13.sp,
                            lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary)
                        if (destination == com.raydon.moji.android.ai.BreakdownDestination.Plans) {
                            Row(Modifier.fillMaxWidth().inkPaperSurface(14.dp).padding(horizontal = 14.dp, vertical = 10.5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("加入后把原计划归档", Modifier.weight(1f), fontSize = 15.sp)
                                FormSwitch(archivesSource, { archivesSource = it })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanDraftRow(draft: PlanDraft, showsDate: Boolean, onChange: (PlanDraft) -> Unit) {
    val pickDay = rememberDatePicker { onChange(draft.copy(day = it)) }
    Row(Modifier.fillMaxWidth().alpha(if (draft.isSelected) 1f else 0.55f).inkDraftSurface().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clickable { onChange(draft.copy(isSelected = !draft.isSelected)) }
            .semantics { contentDescription = if (draft.isSelected) "不加入这一条" else "加入这一条" }, contentAlignment = Alignment.Center) {
            MojiLineIcon(if (draft.isSelected) MojiIcon.CheckCircleFill else MojiIcon.Circle, 24.dp,
                if (draft.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                cutoutColor = MaterialTheme.colorScheme.background)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BasicTextField(draft.title, { onChange(draft.copy(title = it)) }, Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner -> if (draft.title.isEmpty()) Text("计划内容", fontSize = 17.sp, color = iosTertiaryLabel); inner() })
            // iOS shows a compact DatePicker here: 15pt in a 28pt capsule, its frame leaving ~30pt before the detail.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (showsDate) 28.dp else 8.dp)) {
                if (showsDate) Box(Modifier.background(iosFill, RoundedCornerShape(7.dp)).clickable { pickDay(draft.day) }.padding(horizontal = 11.dp, vertical = 4.5.dp)) {
                    Text(draft.day.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)), fontSize = 15.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text(planDraftDetail(draft, showsDate), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
            }
            if (draft.reason.isNotEmpty()) Text(draft.reason, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f))
        }
    }
}

/** Everything but the day when the date button already shows it. */
private fun planDraftDetail(draft: PlanDraft, showsDate: Boolean): String {
    val parts = mutableListOf<String>()
    if (!showsDate) parts += AIDrafting.dayText(draft.day, LocalDate.now())
    if (draft.scheduleKind != "allDay") parts += draft.scheduleText
    draft.minutes?.let { parts += editorDuration(it.toLong()) }
    parts += categoryName(draft.category)
    return parts.joinToString(" · ")
}

// MARK: - Undo

/**
 * Says what an accepted draft changed and offers to take it back. Sits above the tab bar so it
 * is reachable from every page a draft can be accepted on.
 */
@Composable
fun BoxScope.DraftUndoToast(viewModel: MojiViewModel, bottomPadding: Dp = 58.dp) {
    val notice by viewModel.draftNotice.collectAsStateWithLifecycle()
    val undo by viewModel.draftUndo.collectAsStateWithLifecycle()
    // Each notice has its own number: the same words twice in a row still get their full time (A2).
    val serial by viewModel.draftNoticeSerial.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<String?>(null) }
    if (notice != null) shown = notice
    LaunchedEffect(serial, undo != null) {
        if (notice == null) return@LaunchedEffect
        val lifetime = if (BuildConfig.DEBUG && AIQAFixture.isActive) 60_000L else if (undo == null) 3_000L else 8_000L
        delay(lifetime)
        if (viewModel.draftNoticeSerial.value == serial) viewModel.clearDraftNotice()
    }
    AnimatedVisibility(notice != null, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(240)),
        exit = slideOutVertically(tween(240)) { it / 2 } + fadeOut(tween(240))) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = bottomPadding).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
            .toastEdge()
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(shown.orEmpty(), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            if (undo != null) Box(Modifier.widthIn(min = 44.dp).heightIn(min = 32.dp).clickable { viewModel.undoDrafts() }, contentAlignment = Alignment.Center) {
                Text("撤销", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion)
            }
        }
    }
}

@Composable
private fun Modifier.toastEdge(): Modifier {
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    return drawBehind { drawRoundRect(edge, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(0.75.dp.toPx())) }
}

// MARK: - 首页录入建议

/** What was read out of a plan the user just typed, shown under that row. */
@Composable
fun EntrySuggestionStrip(suggestion: EntrySuggestion, apply: () -> Unit, split: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    val chips = suggestion.chips()
    Row(modifier.fillMaxWidth().inkDraftSurface(12.dp).padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DraftSeal(18)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            suggestion.title?.let { Text(it, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) }
            // Up to two lines before it is cut, so a day, a time, a length and a repeat all fit; the buttons stay centred beside it.
            if (chips.isNotEmpty()) Text(chips.joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
            else if (suggestion.parts.size >= 2) Text("这句话里像是 ${suggestion.parts.size} 件事", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.secondary)
        }
        if (suggestion.parts.size >= 2) DraftCapsuleButton("拆成 ${suggestion.parts.size} 条", onClick = split)
        if (suggestion.hasDetails) DraftCapsuleButton("应用", onClick = apply)
        DraftDismissButton("不用这条建议", dismiss)
    }
}

/** 存为模板 under a plan whose title keeps being written by hand (N3). Plain, not a draft. */
@Composable
fun TemplateHintStrip(count: Int, save: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Plain secondary text on a faint wash, not a draft: nothing here was written by a model.
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
        .padding(start = 10.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(com.raydon.moji.android.ai.TemplateHint.text(count), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
        Box(Modifier.heightIn(min = 32.dp).clickable(onClick = save), contentAlignment = Alignment.Center) {
            Text("存为模板", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        DraftDismissButton("不再提示", dismiss)
    }
}

/** Under a saved 周复盘 of this week or last: the 下周 offer (B3). */
@Composable
fun NextWeekOfferStrip(accept: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().inkDraftSurface(12.dp).padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DraftSeal(18)
        Text("复盘存好了，按「下周关注」拟一份下周计划？", Modifier.weight(1f).padding(vertical = 4.dp), fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.primary)
        DraftCapsuleButton("拟下周草稿", onClick = accept)
        DraftDismissButton("不用", dismiss)
    }
}

// MARK: - 倒数日的准备计划

/** Shown once under the mode cards after a day is added. It only asks. */
@Composable
fun PreparationOfferStrip(title: String, daysLeft: Long, accept: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().inkDraftSurface(12.dp).padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DraftSeal(18)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("“$title”还有 $daysLeft 天", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
            Text("要不要把准备的事排进这几天", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
        }
        DraftCapsuleButton("拟准备计划", onClick = accept)
        DraftDismissButton("不用", dismiss)
    }
}

// MARK: - 整理成清单

private data class ChecklistDraftRow(val text: String, val isSelected: Boolean = true)

/** Checklist items drafted from a memo's text. The memo only changes when accepted. */
@Composable
fun ChecklistDraftSheet(subject: String, load: suspend () -> List<String>, accept: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val rows = remember { mutableStateListOf<ChecklistDraftRow>() }
    val state = rememberDraftLoad(load) { items -> rows.clear(); rows.addAll(items.map { ChecklistDraftRow(it) }) }
    val selected = rows.filter { it.isSelected }.map { it.text.trim() }.filter { it.isNotEmpty() }
    var large by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(state.loading, scroll.maxValue) { if (!state.loading && scroll.maxValue > 4) large = true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen(sheetFraction = if (large) null else 0.532f) {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar("整理成清单", "取消", "用这份清单", onDismiss, { if (!committed) { committed = true; accept(selected); onDismiss() } },
                    trailingEnabled = !state.loading && selected.isNotEmpty() && !committed)
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(subject, Modifier.padding(horizontal = 2.dp), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
                    when {
                        state.loading -> InkDraftWaiting("正在整理", Modifier.fillMaxWidth().inkDraftSurface().padding(14.dp))
                        state.failure != null -> DraftFailureView(state.failure!!) { state.attempt++ }
                        else -> {
                            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                DraftSeal(20)
                                Text("这是清单草稿。用了它，正文会换成清单；保存前可以还原。", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            Column(Modifier.fillMaxWidth().inkDraftSurface().padding(horizontal = 10.dp, vertical = 6.dp)) {
                                rows.forEachIndexed { index, row ->
                                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(32.dp).clickable { rows[index] = row.copy(isSelected = !row.isSelected) }
                                            .semantics { contentDescription = if (row.isSelected) "不要这一项" else "要这一项" }, contentAlignment = Alignment.Center) {
                                            MojiLineIcon(if (row.isSelected) MojiIcon.CheckCircleFill else MojiIcon.Circle, 23.dp,
                                                if (row.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                                                cutoutColor = MaterialTheme.colorScheme.background)
                                        }
                                        BasicTextField(row.text, { rows[index] = row.copy(text = it) }, Modifier.weight(1f).padding(top = 6.dp).alpha(if (row.isSelected) 1f else 0.5f), maxLines = 3,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                            decorationBox = { inner -> if (row.text.isEmpty()) Text("清单项", fontSize = 17.sp, color = iosTertiaryLabel); inner() })
                                    }
                                    if (index != rows.lastIndex) Box(Modifier.padding(start = 40.dp).fillMaxWidth().height(devicePixel).alpha(0.5f).background(iosSeparator))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 周复盘草稿

/**
 * The points a drafted reflection rests on, each naming the plans behind it. With
 * [plansOf], a point opens to one line per plan: 标题 · 日期 · 状态 (B2).
 */
@Composable
fun ReflectionEvidenceList(
    evidence: List<ReflectionDraft.Evidence>,
    plansOf: ((ReflectionDraft.Evidence) -> List<CheckInItem>)? = null,
    initiallyOpen: String? = null,
) {
    // Any number of points can be open at once; a point with no plans to show does not open.
    var open by remember(evidence) { mutableStateOf(setOfNotNull(initiallyOpen)) }
    val now = java.time.ZonedDateTime.now()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        evidence.forEach { item ->
            val plans = plansOf?.invoke(item).orEmpty()
            val isOpen = item.id in open && plans.isNotEmpty()
            Column(
                Modifier.fillMaxWidth().then(if (plans.isNotEmpty()) Modifier.clickable { open = if (isOpen) open - item.id else open + item.id } else Modifier)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.point, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("依据：" + item.planTitles.joinToString("、"), Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.secondary)
                    if (plans.isNotEmpty()) WrapMenuChevron(MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        Modifier.graphicsLayer { rotationZ = if (isOpen) 180f else 0f })
                }
                AnimatedVisibility(isOpen) {
                    Column(Modifier.padding(start = 10.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        plans.forEach { plan ->
                            Text(com.raydon.moji.android.ai.ReflectionEvidenceText.line(plan, now), fontSize = 12.sp, lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}
