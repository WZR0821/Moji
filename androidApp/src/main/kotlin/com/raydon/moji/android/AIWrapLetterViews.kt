package com.raydon.moji.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.ai.AIClock
import com.raydon.moji.android.ai.AIDailyBrief
import com.raydon.moji.android.ai.AIWeeklyLetter
import com.raydon.moji.android.ai.AIWeeklyLetterState
import com.raydon.moji.android.ai.AIWriteEntry
import com.raydon.moji.android.ai.AIWriteLog
import com.raydon.moji.android.ai.AIWriteText
import com.raydon.moji.android.ai.WeeklyLetter
import com.raydon.moji.android.ai.WrapAction
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The iOS AIWrapLetterViews: 晚间收尾, 今天的整理 and 本周来信. Like 今日提要
// they are drafts on dashed paper with the 「稿」 seal: the first sentence is
// worked out on the device every time it is drawn, and nothing is written
// until the user taps.

// MARK: - 留痕

/**
 * A plan's small line with 墨记's mark after it in vermilion:
 * 「11月23日 · 工作 · 墨记拆自「准备季度汇报」」. Without a mark the line is as it was.
 */
@Composable
internal fun withOriginMark(line: String, origin: String?): AnnotatedString {
    val vermilion = mojiVermilion
    return buildAnnotatedString {
        append(line)
        if (!origin.isNullOrEmpty()) {
            if (line.isNotEmpty()) append(" · ")
            withStyle(SpanStyle(color = vermilion)) { append(AIWriteText.mark(origin)) }
        }
    }
}

// MARK: - 晚间收尾

/** One plan left this evening, as the card shows it. [note] is the model's reason, shown only while its own arrangement stands. */
data class EveningWrapRow(val id: String, val title: String, val action: WrapAction, val note: String?)

/**
 * 晚间收尾: what today came to, worked out on the device, and below it an
 * arrangement for each plan left. Nothing moves until 照此处理; 逐条改 turns
 * each arrangement into a menu. While not [isReady] the card ends with a link
 * to the introduction.
 */
@Composable
fun EveningWrapCard(
    factsLine: String,
    secondLine: String,
    isReady: Boolean,
    modifier: Modifier = Modifier,
    /** The arrangement, in the order of the list; empty unless there is one. */
    rows: List<EveningWrapRow> = emptyList(),
    /** Plans past the six rows: 还有 K 件照常顺延. */
    hiddenCount: Int = 0,
    isLoading: Boolean = false,
    isEditing: Boolean = false,
    /** 今天墨记替你整理了 N 处 ›, left out at 0. */
    writesCount: Int = 0,
    showsClose: Boolean = true,
    choose: ((String, WrapAction) -> Unit)? = null,
    apply: (() -> Unit)? = null,
    toggleEditing: (() -> Unit)? = null,
    openIntro: (() -> Unit)? = null,
    openWrites: (() -> Unit)? = null,
    dismiss: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(
        modifier.fillMaxWidth().inkDraftSurface(14.dp).animateContentSize(tween(250, easing = EaseOut))
            .padding(start = 12.dp, end = if (showsClose) 2.dp else 12.dp, top = if (showsClose) 4.dp else 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (showsClose) 30.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DraftSeal(18)
            Text("收尾", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion)
            Spacer(Modifier.weight(1f))
            if (showsClose) Box(
                Modifier.size(32.dp, 30.dp)
                    .then(if (dismiss != null) Modifier.clickable(role = Role.Button, onClick = dismiss) else Modifier)
                    .semantics { contentDescription = "今天不再显示收尾" },
                contentAlignment = Alignment.Center,
            ) { MojiLineIcon(MojiIcon.Xmark, 17.dp, secondary.copy(alpha = 0.7f)) }
        }
        // The card keeps only 2 dp on the right for the close button; the text keeps 12 like the left.
        val textEnd = Modifier.padding(end = if (showsClose) 10.dp else 0.dp)
        Text(factsLine, textEnd, fontSize = 13.sp, lineHeight = 18.sp, color = primary)
        Text(secondLine, textEnd, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (rows.isEmpty()) FontWeight.Normal else FontWeight.Medium, color = primary)
        if (isReady && isLoading && rows.isEmpty()) InkDraftWaiting("在想剩下的怎么安排")
        if (rows.isNotEmpty()) {
            Column(textEnd.padding(start = 2.dp, top = 1.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                for (row in rows) key(row.id) { EveningWrapRowView(row, isEditing, choose) }
                if (hiddenCount > 0) Text("还有 $hiddenCount 件照常顺延", fontSize = 12.sp, color = secondary)
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BriefCapsule("照此处理", apply)
                BriefCapsule(if (isEditing) "改好了" else "逐条改", toggleEditing)
            }
        }
        if (!isReady) Box(
            Modifier.heightIn(min = 28.dp).then(if (openIntro != null) Modifier.clickable(role = Role.Button, onClick = openIntro) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) { Text("开启后，墨记会替你安排剩下的 ›", fontSize = 13.sp, color = secondary) }
        if (writesCount > 0) Box(
            Modifier.heightIn(min = 26.dp).then(if (openWrites != null) Modifier.clickable(role = Role.Button, onClick = openWrites) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) { Text("今天墨记替你整理了 $writesCount 处 ›", fontSize = 12.sp, color = mojiVermilion) }
    }
}

@Composable
private fun EveningWrapRowView(row: EveningWrapRow, isEditing: Boolean, choose: ((String, WrapAction) -> Unit)?) {
    val secondary = MaterialTheme.colorScheme.secondary
    val vermilion = mojiVermilion
    Column(if (isEditing) Modifier else Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(AIDailyBrief.shortTitle(row.title), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isEditing) {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        Modifier.heightIn(min = 26.dp).clickable(role = Role.Button) { open = true }
                            .semantics { contentDescription = "「${row.title}」：${row.action.title}，点一下换" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(row.action.title, Modifier.clearAndSetSemantics {}, fontSize = 12.sp, color = vermilion, maxLines = 1)
                        WrapMenuChevron(vermilion)
                    }
                    DropdownMenu(open, { open = false }) {
                        for (action in WrapAction.entries) DropdownMenuItem(
                            text = { Text(if (action == row.action) "✓  ${action.title}" else action.title) },
                            onClick = { open = false; choose?.invoke(row.id, action) },
                        )
                    }
                }
                row.note?.let { Text(" · $it", fontSize = 12.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = vermilion)) { append(row.action.title) }
                    row.note?.let { note -> withStyle(SpanStyle(color = secondary)) { append(" · $note") } }
                },
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The small chevron.down after an arrangement that can be changed (and after a 周复盘 point that opens). */
@Composable
internal fun WrapMenuChevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp, 5.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.14f)
            lineTo(size.width * 0.5f, size.height * 0.86f)
            lineTo(size.width * 0.92f, size.height * 0.14f)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// MARK: - 今天的整理

private val writeTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** Every write 墨记 made today, newest first, each one able to be taken back. */
@Composable
fun AIWritesContent(viewModel: MojiViewModel, modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState()) {
    val all by AIWriteLog.shared.entries.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val entries = AIWriteLog.entriesOn(all, AIClock.now().toLocalDate(), zone)
    var notice by remember { mutableStateOf<String?>(null) }
    // Each notice gets its own number, so the same words twice in a row still stay their full time.
    var noticeSerial by remember { mutableStateOf(0) }
    LaunchedEffect(noticeSerial) {
        if (notice != null) {
            delay(3_000)
            notice = null
        }
    }
    val secondary = MaterialTheme.colorScheme.secondary
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("墨记今天写入的，都能在这里撤销；之后改过的计划保持不变。", fontSize = 13.sp, lineHeight = 18.sp, color = secondary)
            if (entries.isEmpty()) {
                Text("今天墨记还没有写入。", Modifier.fillMaxWidth().padding(vertical = 28.dp), fontSize = 15.sp, color = secondary, textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().inkPaperSurface(14.dp).padding(horizontal = 14.dp)) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) InkDivider(Modifier.padding(start = 60.dp), 0.22f, (40 + index).toLong())
                        AIWriteRow(entry, zone) { viewModel.undoWrite(entry.id) { text -> notice = text; noticeSerial += 1 } }
                    }
                }
            }
        }
        var shown by remember { mutableStateOf("") }
        val current = notice
        if (current != null) shown = current
        AnimatedVisibility(current != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            WritesNotice(shown)
        }
    }
}

@Composable
private fun WritesNotice(text: String) {
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Text(
        text,
        Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .drawBehind { drawRoundRect(edge, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(0.75.dp.toPx())) }
            .padding(12.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AIWriteRow(entry: AIWriteEntry, zone: ZoneId, undo: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.secondary
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            entry.instant.atZone(zone).format(writeTimeFormat), Modifier.alignByBaseline().widthIn(min = 40.dp),
            fontSize = 13.sp, maxLines = 1, color = secondary,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
        Text(
            entry.summary, Modifier.alignByBaseline().weight(1f), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (entry.undone) secondary else MaterialTheme.colorScheme.primary,
        )
        if (entry.undone) {
            Box(Modifier.alignByBaseline().widthIn(min = 44.dp).heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
                Text("已撤销", fontSize = 13.sp, color = secondary)
            }
        } else {
            Box(
                Modifier.alignByBaseline().widthIn(min = 44.dp).heightIn(min = 32.dp).clickable(role = Role.Button, onClick = undo)
                    .semantics { contentDescription = "撤销：${entry.summary}" },
                contentAlignment = Alignment.Center,
            ) { Text("撤销", Modifier.clearAndSetSemantics {}, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion) }
        }
    }
}

/**
 * 今天的整理 as a sheet of its own: half height, and full height once the
 * list does not fit, like the iOS medium/large detents.
 */
@Composable
fun AIWritesSheet(viewModel: MojiViewModel, onDismiss: () -> Unit) {
    var large by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { if (scroll.maxValue > 4) large = true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen(sheetFraction = if (large) null else 0.532f) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth()) {
                    MojiSheetBar("今天的整理", "", "", {}, {})
                    Box(
                        Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(44.dp)
                            .clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = "关闭" },
                        contentAlignment = Alignment.CenterEnd,
                    ) { MojiLineIcon(MojiIcon.Xmark, 28.dp, MaterialTheme.colorScheme.secondary) }
                }
                AIWritesContent(viewModel, Modifier.weight(1f), scroll)
            }
        }
    }
}

/** 今天的整理 pushed from 「AI 模型」 in the settings. */
@Composable
fun AIWritesPage(viewModel: MojiViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MojiSheetBar("今天的整理", "AI 模型", "", onBack, {}, leadingIsBack = true)
        AIWritesContent(viewModel, Modifier.weight(1f))
    }
}

// MARK: - 本周来信

/** What the letter card holds: the letter with the sentence of facts worked out now, the wait for it, or (not set up) the teaser. */
sealed interface WeeklyLetterContent {
    data class Letter(val letter: WeeklyLetter, val facts: String) : WeeklyLetterContent
    data object Loading : WeeklyLetterContent
    data object Teaser : WeeklyLetterContent
}

/** The head every form of the letter card shares. */
@Composable
private fun WeeklyLetterHeader(range: String, showsClose: Boolean, dismiss: (() -> Unit)?) {
    val secondary = MaterialTheme.colorScheme.secondary
    Row(Modifier.fillMaxWidth().heightIn(min = if (showsClose) 30.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DraftSeal(18)
        Text("本周来信", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion)
        Spacer(Modifier.weight(1f))
        Text(range, fontSize = 12.sp, color = secondary, maxLines = 1)
        if (showsClose) Box(
            Modifier.size(32.dp, 30.dp)
                .then(if (dismiss != null) Modifier.clickable(role = Role.Button, onClick = dismiss) else Modifier)
                .semantics { contentDescription = "收起这封来信" },
            contentAlignment = Alignment.Center,
        ) { MojiLineIcon(MojiIcon.Xmark, 17.dp, secondary.copy(alpha = 0.7f)) }
        else Spacer(Modifier.width(10.dp))
    }
}

/**
 * 本周来信 on the 回顾 page. The facts are this week's, worked out now; the
 * title, the body and the evidence are the model's, kept on this device.
 */
@Composable
fun WeeklyLetterCard(
    range: String,
    content: WeeklyLetterContent,
    modifier: Modifier = Modifier,
    showsReminder: Boolean = false,
    showsClose: Boolean = true,
    putIntoReflection: (() -> Unit)? = null,
    draftNextWeek: (() -> Unit)? = null,
    enableReminder: (() -> Unit)? = null,
    openIntro: (() -> Unit)? = null,
    dismiss: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(
        modifier.fillMaxWidth().inkDraftSurface(16.dp).animateContentSize(tween(250, easing = EaseOut))
            .padding(start = 14.dp, end = if (showsClose) 2.dp else 14.dp, top = if (showsClose) 5.dp else 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeeklyLetterHeader(range, showsClose, dismiss)
        when (content) {
            is WeeklyLetterContent.Letter -> Column(Modifier.padding(end = if (showsClose) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(content.letter.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(content.facts + content.letter.body, fontSize = 14.sp, lineHeight = 20.sp, color = primary)
                AIWeeklyLetter.evidenceLine(content.letter.evidence)?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = secondary) }
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BriefCapsule("放进复盘", putIntoReflection)
                    // Gone once the week after the letter's has passed (A3); the demo shows it as a picture.
                    if (draftNextWeek != null || !showsClose) BriefCapsule("拟下周计划", draftNextWeek)
                }
                if (showsReminder) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每周日 20:00 提醒来信", Modifier.weight(1f), fontSize = 12.sp, color = secondary)
                    Box(
                        Modifier.heightIn(min = 30.dp)
                            .then(if (enableReminder != null) Modifier.clickable(role = Role.Button, onClick = enableReminder) else Modifier)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("开启提醒", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion) }
                }
            }
            WeeklyLetterContent.Loading -> InkDraftWaiting("正在写这一周的来信", Modifier.padding(bottom = 2.dp))
            WeeklyLetterContent.Teaser -> Box(
                Modifier.padding(end = 10.dp).heightIn(min = 28.dp)
                    .then(if (openIntro != null) Modifier.clickable(role = Role.Button, onClick = openIntro) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) { Text("开启后，每周日晚上墨记会写一封这一周的来信 ›", fontSize = 13.sp, lineHeight = 18.sp, color = secondary) }
        }
    }
}

/** 11月16日 – 22日 for a kept letter, or its key when that cannot be read. */
internal fun letterRangeText(letter: WeeklyLetter): String =
    AIWeeklyLetter.monday(letter.week)?.let(AIWeeklyLetter::rangeText) ?: letter.week

/** 往期来信: every letter kept on this device, newest first, as a pushed page. */
@Composable
fun WeeklyLetterArchivePage(backTitle: String, onBack: () -> Unit, onOpen: (WeeklyLetter) -> Unit) {
    val letters by AIWeeklyLetterState.shared.letters.collectAsStateWithLifecycle()
    val newest = letters.sortedByDescending { it.week }
    val scheme = MaterialTheme.colorScheme
    // iOS lists these on Color.planCard, the ink card's paper, rather than the grey Form surface.
    val planCard = scheme.surface.copy(alpha = if (scheme.background == com.raydon.moji.ui.MojiPalette.PaperDark) 0.94f else 0.88f)
    MojiScreen {
        Column(Modifier.fillMaxSize()) {
            MojiSheetBar("往期来信", backTitle, "", onBack, {}, leadingIsBack = true)
            // An inset-grouped List starts its first section 34.7pt under the bar.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 34.7.dp, bottom = 34.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(13.dp)).background(planCard)) {
                    newest.forEachIndexed { index, letter ->
                        if (index > 0) FormSeparator()
                        // The List's own 11pt row margin plus the view's 3pt padding, above and below; the
                        // caption and the body line sit 3 apart in system-font line boxes (14.32 and 20.29pt).
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onOpen(letter) }
                                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(letterRangeText(letter), fontSize = 12.sp, lineHeight = 14.32.sp, color = scheme.secondary)
                                Text(letter.title, fontSize = 17.sp, lineHeight = 20.29.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            FormChevron()
                        }
                    }
                }
            }
        }
    }
}

/** One kept letter, as it was written. Read only. */
@Composable
fun WeeklyLetterDetailPage(letter: WeeklyLetter, backTitle: String, onBack: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val evidence = AIWeeklyLetter.evidenceLine(letter.evidence)
    MojiScreen {
        Column(Modifier.fillMaxSize()) {
            MojiSheetBar("来信", backTitle, "", onBack, {}, leadingIsBack = true)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                // Line boxes as iOS draws them with the system font: title3 23.87, body 20.29 + lineSpacing 4,
                // footnote 15.51, subheadline 17.9. Android's body lines carry the 4pt on their first and
                // last line too, so the gaps around the body are 12 rather than 14.
                Column(Modifier.fillMaxWidth().inkPaperSurface(16.dp).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        DraftSeal(18)
                        Text(letterRangeText(letter), fontSize = 13.sp, color = secondary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(letter.title, fontSize = 20.sp, lineHeight = 23.87.sp, fontWeight = FontWeight.SemiBold, color = primary)
                    Spacer(Modifier.height(12.dp))
                    // iOS sets the body about 0.15pt tighter per character than Noto does, which is
                    // what lets a 19-character line such as 「…周日补完两件。」 stay on one line.
                    Text(letter.facts + letter.body, fontSize = 17.sp, lineHeight = 24.29.sp, letterSpacing = (-0.15).sp, color = primary)
                    if (evidence != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(evidence, fontSize = 13.sp, lineHeight = 15.51.sp, color = secondary)
                    }
                    if (letter.nextFocus.isNotEmpty()) {
                        Spacer(Modifier.height(if (evidence == null) 12.dp else 14.dp))
                        InkDivider(opacity = 0.3f, seed = 61)
                        Spacer(Modifier.height(14.dp))
                        Text("下周关注：${letter.nextFocus}", fontSize = 15.sp, lineHeight = 17.9.sp, color = primary)
                    }
                }
            }
        }
    }
}
