package com.raydon.moji.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.ai.AIDailyBrief
import com.raydon.moji.android.ai.AIDrafting
import com.raydon.moji.android.ai.AISettingsScreen
import com.raydon.moji.android.ai.AISettingsStore
import com.raydon.moji.android.ai.QuickEntryToken
import com.raydon.moji.android.ai.draftMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The iOS AIProactiveViews: chips under the quick-entry field, the microphone,
// 今日提要, 顺延旁批, and the introduction shown in place of any entry point
// while AI 整理 is not set up. Vermilion and the 「稿」 seal mark what Moji
// wrote; nothing here writes a plan by itself.

/** Moves only when the ink motion setting and the system's animator scale both allow it. */
@Composable
internal fun aiMotionMoves(): Boolean {
    val context = LocalContext.current
    val scale = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    return inkMotionMoves() && scale != 0f
}

// MARK: - 录入标签

/** One chip: dashed and light until adopted, solid ink once adopted. */
@Composable
fun QuickEntryChip(text: String, isOn: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.background
    val fill by animateColorAsState(if (isOn) primary else primary.copy(alpha = 0.06f), tween(160, easing = EaseOut), label = "chip-fill")
    val ink by animateColorAsState(if (isOn) paper else primary, tween(160, easing = EaseOut), label = "chip-ink")
    val edge = primary.copy(alpha = 0.28f)
    Row(
        modifier.heightIn(min = 30.dp).background(fill, RoundedCornerShape(50))
            .drawBehind {
                if (!isOn) {
                    val width = 0.9.dp.toPx()
                    drawRoundRect(
                        edge, topLeft = Offset(width / 2, width / 2), size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius((size.height - width) / 2),
                        style = Stroke(width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(isOn, enter = fadeIn(tween(160)) + expandHorizontally(tween(160)), exit = fadeOut(tween(160)) + shrinkHorizontally(tween(160))) {
            Canvas(Modifier.padding(end = 3.dp).size(9.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.10f, size.height * 0.54f)
                    lineTo(size.width * 0.38f, size.height * 0.82f)
                    lineTo(size.width * 0.92f, size.height * 0.20f)
                }
                drawPath(path, ink, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

/**
 * What the words in the quick-entry field already say, read on the device.
 * A chip changes nothing until it is tapped, and only takes effect on adding.
 */
@Composable
fun QuickEntryChipsRow(tokens: List<QuickEntryToken>, adopted: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (token in tokens) {
            val isOn = token.id in adopted
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .semantics { contentDescription = if (isOn) "已采用「${token.text}」，再点取消" else "采用「${token.text}」" }
                    .clickable(role = Role.Button) { onChange(if (isOn) adopted - token.id else adopted + token.id) },
            ) { QuickEntryChip(token.text, isOn, Modifier.clearAndSetSemantics {}) }
        }
    }
}

// MARK: - 话筒

/** The system recognizer, in Chinese, free-form. No recording permission: the recognizer app records. */
internal fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
}

/** False when nothing on the phone handles the system speech screen: no microphone then. */
internal fun speechAvailable(context: Context): Boolean = runCatching {
    speechIntent().resolveActivity(context.packageManager) != null
}.getOrDefault(false)

/** The spoken words after what is already typed, as SpeechCapture does on iOS: trimmed, without a closing 。 or period. */
internal fun appendingSpoken(base: String, spoken: String): String = base + spoken.trim().trimEnd('。', '.')

/**
 * The microphone inside the quick-entry field. The system recognizer shows its
 * own listening screen; the words come back into the field after what is typed.
 * [listeningForQA] only draws the listening state, for the photographed ai-say.
 */
@Composable
fun QuickEntryMic(text: String, listeningForQA: Boolean = false, onText: (String) -> Unit) {
    val context = LocalContext.current
    // The QA emulator has no recognizer; the debug fixture still draws the microphone, as iOS shows it.
    // A tap there fails quietly below. Release builds keep the microphone only where speech works.
    val available = remember(context) {
        speechAvailable(context) || (BuildConfig.DEBUG && com.raydon.moji.android.ai.AIQAFixture.isActive)
    }
    if (!available) return
    var listening by remember { mutableStateOf(false) }
    val currentText by rememberUpdatedState(text)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) onText(appendingSpoken(currentText, spoken))
    }
    QuickEntryMicButton(listening || listeningForQA) {
        if (listening) {
            // The recognizer's own screen ends listening; this only resets the button if it was left on.
            listening = false
        } else {
            listening = true
            try { launcher.launch(speechIntent()) } catch (missing: ActivityNotFoundException) { listening = false }
        }
    }
}

@Composable
fun QuickEntryMicButton(listening: Boolean, onClick: () -> Unit) {
    val moves = aiMotionMoves()
    val vermilion = mojiVermilion
    Box(
        Modifier.size(36.dp, 44.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (listening) "正在听，点一下结束" else "说一句话" },
        contentAlignment = Alignment.Center,
    ) {
        if (listening) {
            val transition = rememberInfiniteTransition(label = "mic-breath")
            val breath = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse), label = "breath")
            Canvas(Modifier.size(30.dp)) {
                val value = if (moves) breath.value else 0f
                scale(0.86f + 0.24f * value) { drawCircle(vermilion.copy(alpha = 0.09f + 0.08f * value)) }
            }
        }
        MojiLineIcon(MojiIcon.Mic, 22.dp, if (listening) vermilion else MaterialTheme.colorScheme.secondary)
    }
}

// MARK: - 今日提要

/** The capsule of the suggestion strip; without an action it is only a picture (the demo). */
@Composable
internal fun BriefCapsule(title: String, onClick: (() -> Unit)?) {
    Box(
        Modifier.heightIn(min = 30.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
}

/**
 * 今日提要: a line worked out on the device from today's plans, and below it
 * the order the model suggests, kept for the day. [advice] is 宜先做… or null
 * while there is no answer for today; while not [isReady] the second line is a
 * link to the introduction.
 */
@Composable
fun DailyBriefCard(
    factsLine: String,
    advice: String?,
    isReady: Boolean,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isOrdered: Boolean = false,
    canBreakDown: Boolean = false,
    showsClose: Boolean = true,
    sort: (() -> Unit)? = null,
    restore: (() -> Unit)? = null,
    breakDown: (() -> Unit)? = null,
    openIntro: (() -> Unit)? = null,
    dismiss: (() -> Unit)? = null,
) {
    val showsSort = isOrdered || (isReady && advice != null)
    val secondary = MaterialTheme.colorScheme.secondary
    Column(
        modifier.fillMaxWidth().inkDraftSurface(14.dp).animateContentSize(tween(250, easing = EaseOut))
            .padding(start = 12.dp, end = if (showsClose) 2.dp else 12.dp, top = if (showsClose) 4.dp else 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (showsClose) 30.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DraftSeal(18)
            Text("今日提要", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion)
            Spacer(Modifier.weight(1f))
            if (showsClose) Box(
                Modifier.size(32.dp, 30.dp)
                    .then(if (dismiss != null) Modifier.clickable(role = Role.Button, onClick = dismiss) else Modifier)
                    .semantics { contentDescription = "今天不再显示提要" },
                contentAlignment = Alignment.Center,
            ) { MojiLineIcon(MojiIcon.Xmark, 17.dp, secondary.copy(alpha = 0.7f)) }
        }
        // The card keeps only 2 dp on the right for the close button; the text keeps 12 like the left.
        val textEnd = Modifier.padding(end = if (showsClose) 10.dp else 0.dp)
        Text(factsLine, textEnd, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary)
        if (isReady) {
            if (advice != null) Text(advice, textEnd, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            else if (isLoading) InkDraftWaiting("在排今天的先后")
        } else {
            Box(
                Modifier.heightIn(min = 28.dp).then(if (openIntro != null) Modifier.clickable(role = Role.Button, onClick = openIntro) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) { Text("开启后，墨记会替你排先后 ›", fontSize = 13.sp, color = secondary) }
        }
        if (showsSort || canBreakDown) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isOrdered) BriefCapsule("恢复原顺序", restore)
            else if (showsSort) BriefCapsule("按建议排序", sort)
            if (canBreakDown) BriefCapsule("拆小顺延那件", breakDown)
        }
    }
}

// MARK: - 顺延旁批

/** A note in the margin under a plan carried over for days. Nothing is asked of the model until it is tapped. */
@Composable
fun CarryOverNoteLine(daysLate: Int, open: (() -> Unit)?, dismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.heightIn(min = 30.dp).then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) { Text(AIDailyBrief.carryNoteText(daysLate), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = mojiVermilion) }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.heightIn(min = 30.dp)
                .then(if (dismiss != null) Modifier.clickable(role = Role.Button, onClick = dismiss) else Modifier)
                .semantics { contentDescription = "不再提醒这一条" }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text("不用", Modifier.clearAndSetSemantics {}, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    }
}

// MARK: - 介绍半屏

/**
 * Which page of the introduction is open, if any: "intro", "demo", or an AI
 * settings page ("ai…"). Every entry point opens the same one, hosted by
 * [AIIntroHost] in the app's root.
 */
object AIIntro {
    private val state = MutableStateFlow<String?>(null)
    val page: StateFlow<String?> = state.asStateFlow()

    /** Where 「返回」 on the 「AI 模型」 page goes: back to the intro or to the demo. */
    internal var settingsParent = "intro"

    /** Opened for something shared in while not set up: the sheet first says it is kept. */
    internal var forShare by mutableStateOf(false)
        private set

    fun open(start: String = "intro", forShare: Boolean = false) {
        this.forShare = forShare
        state.value = start
    }

    fun close() {
        state.value = null
        forShare = false
    }

    internal fun show(page: String) { state.value = page }

    internal fun openSettings(from: String) {
        settingsParent = from
        state.value = "ai"
    }
}

/** The introduction sheet and the pages it opens (演示, 「AI 模型」), on top of whatever is showing. */
@Composable
fun AIIntroHost() {
    val page by AIIntro.page.collectAsStateWithLifecycle()
    val current = page ?: return
    // Kept while the sheet is open, like the iOS demo under a pushed settings page.
    var demoShown by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = AIIntro::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        when (current) {
            "intro" -> AIIntroSheet()
            "demo" -> MojiScreen { AIDemoPage(demoShown) { demoShown = it } }
            else -> MojiScreen { AISettingsScreen(current, rootBackTitle = "返回") { AIIntro.show(it ?: AIIntro.settingsParent) } }
        }
    }
}

private val introRows = listOf(
    Triple(MojiIcon.Mic, "说一句话，拆成几条计划", "首页输入框"),
    Triple(MojiIcon.ListIndent, "大事拆成马上能开始的小步", "计划展开处、顺延印"),
    Triple(MojiIcon.Sunrise, "早上提要，晚上收尾", "今日计划上方"),
    Triple(MojiIcon.Envelope, "周日写好一封本周来信", "回顾页"),
    Triple(MojiIcon.TextViewfinder, "截图、聊天记录直接变计划", "首页 ＋ 菜单、分享"),
)

@Composable
private fun IntroPrimaryButton(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("去开启", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background) }
}

/**
 * 让墨记替你打下手: what the entry points would do, with the way to turn them
 * on and the way to hide them. Opening it sends nothing. The sheet is as tall
 * as its content; when that does not fit it opens at full height and scrolls.
 */
@Composable
private fun AIIntroSheet() {
    val store = AISettingsStore.shared
    val settings by store.settings.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var contentHeight by remember { mutableStateOf<Dp?>(null) }
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The same top and bottom MojiScreen leaves: below the status bar, above max(navigation bar, 34dp).
        val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val room = maxHeight - maxOf(69.3.dp, statusTop + 44.dp)
        val fitted = contentHeight?.let { Dp(kotlin.math.ceil((it + 56.dp + maxOf(navigationBottom, 34.dp)).value)) }
        val height = when {
            fitted == null -> minOf(640.dp, room)
            fitted <= room -> fitted
            else -> null
        }
        MojiScreen(sheetHeight = height) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth()) {
                    MojiSheetBar("让墨记替你打下手", "", "", {}, {})
                    Box(
                        Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(44.dp)
                            .clickable(role = Role.Button) { AIIntro.close() }.semantics { contentDescription = "关闭" },
                        contentAlignment = Alignment.CenterEnd,
                    ) { MojiLineIcon(MojiIcon.Xmark, 28.dp, secondary) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier.fillMaxWidth().onSizeChanged { contentHeight = with(density) { it.height.toDp() } }
                            .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // Something shared in waits in the inbox until AI 整理 is set up; the sheet says so first.
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (AIIntro.forShare) Text(
                                "分享来的内容先收着，开启 AI 整理后会整理成计划草稿。",
                                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = primary,
                            )
                            Text(
                                if (settings.isEnabled) "AI 整理已打开，还差填一个供应商的 API Key。填好后：" else "AI 整理现在是关着的。打开后：",
                                fontSize = 15.sp, lineHeight = 20.sp, color = secondary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            introRows.forEachIndexed { index, (icon, title, place) ->
                                Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    InkBrushMedallion(icon, primary, 38.dp, seed = (index + 3).toLong())
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = primary)
                                        Text(place, fontSize = 12.sp, color = secondary)
                                    }
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            IntroPrimaryButton { AIIntro.openSettings("intro") }
                            Text("两步：打开「使用 AI 整理」，再填一个供应商的 API Key。", Modifier.fillMaxWidth(), fontSize = 12.sp, color = secondary, textAlign = TextAlign.Center)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .clickable(role = Role.Button) { AIIntro.show("demo") },
                                contentAlignment = Alignment.Center,
                            ) { Text("先看演示", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primary) }
                            Text("结果都先以草稿出现，你确认后才写入。", Modifier.fillMaxWidth(), fontSize = 12.sp, color = secondary, textAlign = TextAlign.Center)
                        }
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(
                                Modifier.heightIn(min = 36.dp).clickable(role = Role.Button) {
                                    // A failed save keeps the sheet open with the reason under the button.
                                    notice = try {
                                        store.change { it.copy(showsEntriesWhenOff = false) }
                                        null
                                    } catch (error: Exception) {
                                        error.draftMessage
                                    }
                                    if (notice == null) AIIntro.close()
                                }.padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("不需要？隐藏这些入口", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primary) }
                            Text("隐藏后界面和原来一样，可在设置的「AI 模型」里重新打开。", fontSize = 11.sp, lineHeight = 15.sp, color = secondary, textAlign = TextAlign.Center)
                            notice?.let { Text(it, fontSize = 12.sp, color = mojiVermilion, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 演示

private val demoSteps = listOf("列出汇报的三个要点" to 15, "找齐三个月的数据" to 30, "做第一版图表" to 45, "请同事看一遍" to 15)

/** How many places the demo walks through. */
private const val demoSectionCount = 6

/**
 * The six places with example content. Nothing is sent and no plan is touched.
 * Each section waits 0.7 s with the blots, one after another; with motion off
 * everything shows at once. [shown] is how many sections are out.
 */
@Composable
private fun AIDemoPage(shown: Int, onShown: (Int) -> Unit) {
    BackHandler { AIIntro.show("intro") }
    val moves = aiMotionMoves()
    val currentShown by rememberUpdatedState(shown)
    LaunchedEffect(Unit) {
        if (currentShown >= demoSectionCount) return@LaunchedEffect
        if (!moves) {
            onShown(demoSectionCount)
            return@LaunchedEffect
        }
        for (step in currentShown + 1..demoSectionCount) {
            delay(700)
            onShown(step)
        }
    }
    val scroll = rememberScrollState()
    // Debug only: ai-demo-bottom photographs the end of the page once the last section is out.
    LaunchedEffect(shown) {
        if (!BuildConfig.DEBUG || shown != demoSectionCount || com.raydon.moji.android.ai.AIQAFixture.scenario != "ai-demo-bottom") return@LaunchedEffect
        delay(400)
        scroll.animateScrollTo(scroll.maxValue, tween(300, easing = EaseOut))
        delay(150)
        if (scroll.value < scroll.maxValue) scroll.scrollTo(scroll.maxValue)
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxSize()) {
        MojiSheetBar("演示", "返回", "", { AIIntro.show("intro") }, {})
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("用示例内容走一遍，不联网，也不改动你的计划。", fontSize = 15.sp, lineHeight = 20.sp, color = secondary)
            DemoSection(1, "写一句话", shown, moves) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.fillMaxWidth().inkPaperSurface(22.dp).padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("周三下午三点交周报 45分钟", fontSize = 17.sp, color = primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QuickEntryChip("周三", true)
                            QuickEntryChip("15:00", true)
                            QuickEntryChip("45 分钟", true)
                        }
                    }
                    DemoPlanLine("交周报", detail = "周三 · 15:00 · 45 分钟 · 工作")
                }
            }
            DemoSection(2, "拆成步骤", shown, moves) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("准备季度汇报", fontSize = 12.sp, color = secondary)
                    for ((title, minutes) in demoSteps) {
                        Row(
                            Modifier.fillMaxWidth().inkDraftSurface().padding(horizontal = 12.dp, vertical = 9.dp).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MojiLineIcon(MojiIcon.CheckCircleFill, 23.dp, primary, cutoutColor = MaterialTheme.colorScheme.background)
                            Text(title, Modifier.weight(1f), fontSize = 17.sp, color = primary)
                            Spacer(Modifier.width(6.dp))
                            Text(AIDrafting.durationText(minutes), fontSize = 12.sp, color = secondary)
                        }
                    }
                }
            }
            DemoSection(3, "今日提要", shown, moves) {
                DailyBriefCard(
                    factsLine = "今天 4 件，约 2 小时 30 分钟；离「项目交付」还有 2 天；「归纳上周待办」已顺延 3 天。",
                    advice = "宜先做「整理发布说明」，交付前要先定稿。",
                    isReady = true,
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    canBreakDown = true,
                    showsClose = false,
                )
            }
            DemoSection(4, "顺延旁批", shown, moves) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DemoPlanLine("归纳上周待办", seal = 3)
                    CarryOverNoteLine(3, null, null, Modifier.padding(start = 46.dp, end = 4.dp).semantics(mergeDescendants = true) {})
                }
            }
            DemoSection(5, "晚上收尾", shown, moves) {
                EveningWrapCard(
                    factsLine = "今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。",
                    secondLine = "剩下 2 件这样安排：",
                    isReady = true,
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    rows = listOf(
                        EveningWrapRow("demo-wrap-1", "阅读产品设计章节", com.raydon.moji.android.ai.WrapAction.Tomorrow, null),
                        EveningWrapRow("demo-wrap-2", "归纳上周待办", com.raydon.moji.android.ai.WrapAction.Split, "已经顺延 3 天"),
                    ),
                    showsClose = false,
                )
            }
            DemoSection(6, "本周来信", shown, moves) {
                WeeklyLetterCard(
                    range = "11月16日 – 22日",
                    content = WeeklyLetterContent.Letter(
                        com.raydon.moji.android.ai.WeeklyLetter(
                            week = "2026-11-16", title = "卡在第一步的一周",
                            body = "前半周很稳；周四起两件大事一直顺延，都卡在没拆开的第一步。",
                            nextFocus = "", facts = "",
                            evidence = listOf(
                                com.raydon.moji.android.ai.WeeklyLetter.Evidence("「归纳上周待办」顺延 4 次", emptyList()),
                                com.raydon.moji.android.ai.WeeklyLetter.Evidence("「准备季度汇报」还没开始", emptyList()),
                            ),
                            createdAt = 0.0,
                        ),
                        facts = "这周完成 9/14，专注 6 小时 20 分钟，比上周多 1 小时。",
                    ),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    showsClose = false,
                )
            }
            IntroPrimaryButton { AIIntro.openSettings("demo") }
        }
    }
}

@Composable
private fun DemoSection(index: Int, title: String, shown: Int, moves: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(300, easing = EaseOut)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        if (shown >= index) {
            val alpha = remember { Animatable(if (moves) 0f else 1f) }
            LaunchedEffect(Unit) { alpha.animateTo(1f, tween(300, easing = EaseOut)) }
            Box(Modifier.alpha(alpha.value)) { content() }
        } else if (shown == index - 1) InkDraftWaiting()
    }
}

@Composable
private fun DemoPlanLine(title: String, detail: String? = null, seal: Int? = null) {
    Row(
        Modifier.fillMaxWidth().inkPaperSurface(14.dp).padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MojiLineIcon(MojiIcon.Circle, 23.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            if (detail != null) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        if (seal != null) InkSeal(seal.toString(), 22)
    }
}
