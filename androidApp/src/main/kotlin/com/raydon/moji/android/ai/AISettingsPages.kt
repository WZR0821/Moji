package com.raydon.moji.android.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raydon.moji.android.FormChevron
import com.raydon.moji.android.FormChoiceRow
import com.raydon.moji.android.FormSection
import com.raydon.moji.android.FormSegmentedControl
import com.raydon.moji.android.FormSeparator
import com.raydon.moji.android.FormTextField
import com.raydon.moji.android.FormToggle
import com.raydon.moji.android.InkAsset
import com.raydon.moji.android.InkDraftWaiting
import com.raydon.moji.android.InkSeal
import com.raydon.moji.android.MojiIcon
import com.raydon.moji.android.MojiLineIcon
import com.raydon.moji.android.MojiScreen
import com.raydon.moji.android.MojiSheetBar
import com.raydon.moji.android.inkSeed
import com.raydon.moji.android.iosFill
import com.raydon.moji.android.iosSecondaryLabel
import com.raydon.moji.android.mojiDestructive
import com.raydon.moji.android.mojiVermilion
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// The pages under 设置 › AI 模型, ported from AISettingsViews.swift. iOS pushes
// views and opens sheets; here every page is a route of the settings dialog:
// "ai" is the root and "ai:<sub>" the pages under it, so the dialog keeps one
// page string and back always goes to aiSettingsParent(page).

// MARK: - Routes

private sealed interface AIRoute {
    object Root : AIRoute
    object Switcher : AIRoute
    object Add : AIRoute
    object AddCustom : AIRoute
    class AddPreset(val provider: AIProvider) : AIRoute
    object Help : AIRoute
    class Provider(val entryID: String) : AIRoute
    class Credentials(val entryID: String) : AIRoute
    class AddModel(val entryID: String) : AIRoute
    /** The model id is everything after the entry id: ids may contain ':'. */
    class Model(val entryID: String, val modelID: String) : AIRoute
}

private fun aiRoute(page: String): AIRoute? {
    when (page) {
        "ai" -> return AIRoute.Root
        "ai:switcher" -> return AIRoute.Switcher
        "ai:add" -> return AIRoute.Add
        "ai:add-custom" -> return AIRoute.AddCustom
        "ai:help" -> return AIRoute.Help
    }
    if (page.startsWith("ai:add:")) {
        val provider = AIProvider.from(page.removePrefix("ai:add:")) ?: return null
        return if (provider == AIProvider.custom) AIRoute.AddCustom else AIRoute.AddPreset(provider)
    }
    if (page.startsWith("ai:provider:")) {
        // Entry ids are preset names or "custom-<uuid>", never containing ':'.
        val rest = page.removePrefix("ai:provider:")
        val id = rest.substringBefore(':')
        if (id.isEmpty()) return null
        if (!rest.contains(':')) return AIRoute.Provider(id)
        return when (rest.substringAfter(':')) {
            "credentials" -> AIRoute.Credentials(id)
            "add-model" -> AIRoute.AddModel(id)
            else -> null
        }
    }
    if (page.startsWith("ai:model:")) {
        val rest = page.removePrefix("ai:model:")
        val id = rest.substringBefore(':')
        val model = rest.substringAfter(':', "")
        if (id.isEmpty() || model.isEmpty()) return null
        return AIRoute.Model(id, model)
    }
    return null
}

private fun providerPage(entryID: String) = "ai:provider:$entryID"
private fun modelPage(entryID: String, modelID: String) = "ai:model:$entryID:$modelID"

/** Where back goes from an AI page; null leaves the AI pages for the settings list. */
fun aiSettingsParent(page: String): String? = when (val route = aiRoute(page)) {
    AIRoute.Root -> null
    is AIRoute.AddPreset -> "ai:add"
    is AIRoute.Credentials -> providerPage(route.entryID)
    is AIRoute.AddModel -> providerPage(route.entryID)
    is AIRoute.Model -> providerPage(route.entryID)
    else -> "ai"
}

/** The 设置 row's detail, as RecordsView.aiDetail on iOS. */
fun aiSettingsSummary(settings: AISettings, keyed: Set<String>): String {
    if (!settings.isEnabled) return "未开启"
    val ready = settings.activeID in keyed && settings.configuration.model.isNotBlank()
    return if (ready) settings.engineLabel else "已开启 · 还没有填密钥"
}

/**
 * The page a debug QA launch opens first (the `moji_qa_ai_page` extra, like
 * MOJI_QA_AI_PAGE), or null for the root page.
 */
fun aiQaInitialPage(): String? {
    val settings = AISettingsStore.shared.settings.value
    return when (AIQAFixture.page) {
        "switcher" -> "ai:switcher"
        "add" -> "ai:add"
        "add-custom" -> "ai:add-custom"
        "add-preset" -> "ai:add:${AIProvider.kimi.rawValue}"
        "help" -> "ai:help"
        "provider" -> providerPage(settings.activeID)
        "model" -> settings.configuration.model.trim().let { model ->
            if (model.isEmpty()) providerPage(settings.activeID) else modelPage(settings.activeID, model)
        }
        else -> null
    }
}

// MARK: - Screen

/**
 * Every AI settings page, each drawing its own sheet bar. [onOpen] gets the
 * next page, or null to go back to the settings list.
 */
@Composable
fun AISettingsScreen(page: String, onOpen: (String?) -> Unit) {
    val store = AISettingsStore.shared
    val settings by store.settings.collectAsStateWithLifecycle()
    val keyed by store.keyedIDs.collectAsStateWithLifecycle()
    // Held here, not in the root page, so a message from 换模型 or 添加供应商
    // is still there when the root page comes back, as the iOS sheets' callbacks.
    var notice by remember { mutableStateOf<String?>(null) }
    val route = aiRoute(page)
    // The dialog's own back drops the last ':' part, which is not always a page here.
    BackHandler { onOpen(aiSettingsParent(page)) }
    if (route == null) LaunchedEffect(page) { onOpen("ai") }
    val added: (String) -> Unit = { message -> notice = message; onOpen("ai") }
    Column(Modifier.fillMaxSize()) {
        when (route) {
            null, AIRoute.Root -> AIModelSettingsPage(store, settings, keyed, notice, { notice = it }, onOpen)
            AIRoute.Switcher -> AIModelSwitcherPage(store, settings, keyed, { notice = it }, onOpen)
            AIRoute.Add -> AIAddProviderPage(keyed, onOpen)
            AIRoute.AddCustom -> AIProviderSetupForm(store, AIProvider.custom, added, onOpen)
            is AIRoute.AddPreset -> AIProviderSetupForm(store, route.provider, added, onOpen)
            AIRoute.Help -> AIHelpPage(onOpen)
            is AIRoute.Provider -> AIProviderDetailPage(store, settings, keyed, route.entryID, onOpen)
            is AIRoute.Credentials -> AIProviderCredentialsPage(store, settings, keyed, route.entryID, onOpen)
            is AIRoute.AddModel -> AIAddModelPage(store, settings, keyed, route.entryID, onOpen)
            is AIRoute.Model -> AIModelDetailPage(store, settings, keyed, route.entryID, route.modelID, onOpen)
        }
    }
}

// MARK: - Shared pieces

/** Applies a change and returns the reason when it could not be saved. */
private fun AISettingsStore.tryChange(
    keys: Map<String, String> = emptyMap(),
    transform: (AISettings) -> AISettings,
): String? = try {
    change(keys, transform)
    null
} catch (error: Exception) {
    error.draftMessage
}

/** Sends one short request and returns how long the answer took, in seconds. */
private suspend fun aiConnectionCheck(entryID: String, configuration: ProviderConfiguration, apiKey: String): Double {
    val start = System.nanoTime()
    AIClient().complete("只回复一个“好”字。", "你好", entryID, configuration, apiKey, 15.0)
    return (System.nanoTime() - start) / 1_000_000_000.0
}

private sealed class AICheckState {
    object Idle : AICheckState()
    object Running : AICheckState()
    data class Passed(val seconds: Double) : AICheckState()
    data class Failed(val message: String) : AICheckState()
}

private val aiStyleOptions: List<Pair<String, String>> = APIStyle.entries.map { it.rawValue to it.displayName }

/** The 思考强度 value standing for "no setting"; ReasoningEffort has no such case. */
private const val aiAutoReasoning = "auto"

private val aiURLKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

private val aiPlainKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)

@Composable
private fun aiMonoStyle(fontSize: TextUnit = 17.sp): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    color = MaterialTheme.colorScheme.onSurface,
    fontFamily = FontFamily.Monospace,
    fontSize = fontSize,
    lineHeight = fontSize * 1.3f,
)

private class AIFooter(val text: String, val warning: Boolean = false)

private class AIPlacement(val top: Dp, val first: Boolean)

/**
 * The gaps of an inset-grouped Form, as SettingsPages measures them: what sits
 * above a section depends on whether the one before it had a footer. A page
 * makes a new flow each time it composes and asks it once per section, in order.
 */
private class AIFormFlow {
    private var started = false
    private var lastHadFooter = false

    fun place(title: String?, hasFooter: Boolean): AIPlacement {
        val first = !started
        val gap = when {
            first -> 0f
            lastHadFooter -> if (title != null) 40f else 24f
            else -> if (title != null) 28f else 12f
        }
        started = true
        lastHadFooter = hasFooter
        return AIPlacement(gap.dp, first)
    }
}

/** A page's form: 15 below the bar when the first section has a header, 7 when not. */
@Composable
private fun AIFormColumn(firstHasHeader: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = if (firstHasHeader) 15.dp else 7.dp, bottom = 34.dp),
        content = content,
    )
}

/** FormSection with footers that may be 朱红 warnings, which FormSection's plain footer cannot be. */
@Composable
private fun AISection(
    place: AIPlacement,
    title: String? = null,
    footer: List<AIFooter> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (place.top > 0.dp) Spacer(Modifier.height(place.top))
        if (title != null) {
            Text(
                title,
                Modifier.padding(start = 40.dp, end = 40.dp, bottom = if (place.first) 13.7.dp else 5.5.dp),
                color = iosSecondaryLabel,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        FormSection(content = content)
        if (footer.isNotEmpty()) {
            Column(Modifier.padding(start = 40.dp, end = 40.dp, top = 5.5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                footer.forEach { line ->
                    Text(line.text, color = if (line.warning) mojiVermilion else iosSecondaryLabel, fontSize = 13.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/**
 * A provider's mark: one of the app's brush loops with a letter inside. The
 * loop is the existing artwork, turned and mirrored by the entry's own seed,
 * with the same transform as InkVariant on iOS.
 */
@Composable
private fun AIProviderMark(entryID: String, side: Dp = 34.dp) {
    val monogram = when (AIProvider.kind(entryID)) {
        AIProvider.claude -> "C"
        AIProvider.openAI -> "G"
        AIProvider.deepSeek -> "D"
        AIProvider.qwen -> "通"
        AIProvider.doubao -> "豆"
        AIProvider.kimi -> "K"
        AIProvider.zhipu -> "智"
        AIProvider.minimax -> "M"
        AIProvider.gemini -> "Ge"
        AIProvider.custom -> "自"
    }
    val seed = inkSeed("ai.provider.$entryID")
    var hash = (seed * 2654435761L).toULong()
    hash = hash xor (hash shr 13)
    hash *= 0x9E3779B97F4A7C15uL
    hash = hash xor (hash shr 7)
    val angle = ((hash and 255u).toFloat() / 255f * 2f - 1f) * 26f
    val scale = 1f + (((hash shr 8) and 255u).toFloat() / 255f * 2f - 1f) * 0.11f
    val opacity = 1f + (((hash shr 16) and 255u).toFloat() / 255f * 2f - 1f) * 0.16f
    val flips = ((hash shr 24) and 1u) == 1uL
    val artwork = listOf("InkIconLoop", "InkIconCrescent", "InkIconScholarSquare")[(seed.toULong() % 3u).toInt()]
    val primary = MaterialTheme.colorScheme.primary
    // iOS sizes the letter in points from the mark's side, not with Dynamic Type.
    val letterSize = with(LocalDensity.current) { (side * (if (monogram.length > 1) 0.30f else 0.38f)).toSp() }
    Box(Modifier.size(side).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        InkAsset(
            artwork,
            Modifier.fillMaxSize().graphicsLayer {
                rotationZ = angle
                scaleX = if (flips) -scale else scale
                scaleY = scale
            },
            primary.copy(alpha = (0.70f * opacity).coerceIn(0f, 1f)),
        )
        Text(monogram, fontSize = letterSize, lineHeight = letterSize, fontWeight = FontWeight.SemiBold, color = primary)
    }
}

/** A thin 朱红 label, like a small collector's stamp beside a title. */
@Composable
private fun InkTag(text: String, tint: Color = mojiVermilion) {
    Text(
        text,
        Modifier.border(0.8.dp, tint.copy(alpha = 0.72f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = tint,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun AIProviderRow(settings: AISettings, keyed: Set<String>, entryID: String, onClick: () -> Unit) {
    val count = settings.savedModels(entryID).size
    val detail = if (entryID in keyed) "$count 个模型" else "$count 个模型 · 未填密钥"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AIProviderMark(entryID)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    settings.displayName(entryID), Modifier.weight(1f, fill = false),
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (entryID == settings.activeID) InkTag("正在用")
            }
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f))
        }
        Spacer(Modifier.width(8.dp))
        FormChevron()
    }
}

@Composable
private fun AIModelLabel(provider: AIProvider, modelID: String, modifier: Modifier = Modifier) {
    val preset = provider.models.firstOrNull { it.id == modelID }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(preset?.title ?: modelID, fontSize = 17.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            preset?.let { "${it.detail} · $modelID" } ?: "自己添加的模型",
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun AIValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Text(
            value, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(8.dp))
        FormChevron()
    }
}

@Composable
private fun AIRowGlyph(icon: MojiIcon, color: Color) {
    // A SwiftUI Label in a Form row: the glyph sits in a 28pt slot.
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (icon == MojiIcon.Plus) MojiLineIcon(icon, 21.6.dp, color, strokeWidth = 1.67f)
        else MojiLineIcon(icon, if (icon.ordinal >= MojiIcon.TabChecklist.ordinal) 26.dp else 23.dp, color, strokeWidth = 1.5f)
    }
}

/** A Form button: tinted text, an optional Label glyph, and a waiting mark while it works. */
@Composable
private fun AIActionRow(
    title: String,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: MojiIcon? = null,
    waiting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A disabled Form button is grey on iOS whatever its tint, a destructive one too.
        val tint = if (enabled) color else MaterialTheme.colorScheme.onSurface
        Row(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AIRowGlyph(icon, tint)
                Spacer(Modifier.width(16.dp))
            }
            Text(title, fontSize = 17.sp, color = tint)
        }
        if (waiting != null) InkDraftWaiting(waiting)
    }
}

@Composable
private fun AILinkRow(title: String, icon: MojiIcon, url: String) {
    val uriHandler = LocalUriHandler.current
    AIActionRow(title, icon = icon) { runCatching { uriHandler.openUri(url) } }
}

/** DisclosureGroup's label row; the chevron turns down when open. */
@Composable
private fun AIDisclosureRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        FormChevron(Modifier.rotate(if (expanded) 90f else 0f), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** SecureField with an eye button; the key stays in this composition only, never in saved state. */
@Composable
private fun AIKeyField(key: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val style = aiMonoStyle()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = key,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            singleLine = true,
            textStyle = style,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (visible) KeyboardType.Ascii else KeyboardType.Password,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (key.isEmpty()) Text("API Key", style = style, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f))
                    inner()
                }
            },
        )
        val label = if (visible) "隐藏密钥" else "显示密钥"
        Box(
            Modifier.size(32.dp).clickable(role = Role.Button) { visible = !visible }.semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            AIEyeGlyph(slashed = visible, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun AICheckRow(state: AICheckState, disabled: Boolean, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AIActionRow(
            "测试连接",
            enabled = !disabled && state != AICheckState.Running,
            waiting = if (state == AICheckState.Running) "正在连接" else null,
            onClick = onRun,
        )
        when (state) {
            is AICheckState.Passed -> Row(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AICheckCircle(MaterialTheme.colorScheme.primary)
                Text(
                    String.format(Locale.ROOT, "连接正常，用时 %.1f 秒。", state.seconds),
                    fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.primary,
                )
            }
            is AICheckState.Failed -> Text(
                state.message,
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 11.dp),
                fontSize = 13.sp, lineHeight = 18.sp, color = mojiVermilion,
            )
            else -> Unit
        }
    }
}

// MARK: - Glyphs drawn here, for SF Symbols MojiIcon has no look-alike of

/** questionmark.circle */
@Composable
private fun AIHelpGlyph(color: Color) {
    Box(Modifier.size(22.dp).border(1.6.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text("?", fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** checkmark */
@Composable
private fun AICheckmark(color: Color, side: Dp = 17.dp) {
    Canvas(Modifier.size(side)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.55f)
            lineTo(w * 0.40f, h * 0.82f)
            lineTo(w * 0.88f, h * 0.18f)
        }
        drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** checkmark.circle */
@Composable
private fun AICheckCircle(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val s = this.size.minDimension
        val line = 1.3.dp.toPx()
        drawCircle(color, radius = s / 2f - line / 2f, style = Stroke(width = line))
        val path = Path().apply {
            moveTo(s * 0.30f, s * 0.52f)
            lineTo(s * 0.45f, s * 0.67f)
            lineTo(s * 0.71f, s * 0.36f)
        }
        drawPath(path, color, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** eye / eye.slash */
@Composable
private fun AIEyeGlyph(slashed: Boolean, color: Color) {
    Canvas(Modifier.size(22.dp, 16.dp)) {
        val w = this.size.width
        val h = this.size.height
        val line = 1.5.dp.toPx()
        val outline = Path().apply {
            moveTo(w * 0.04f, h * 0.5f)
            quadraticTo(w * 0.5f, -h * 0.25f, w * 0.96f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 1.25f, w * 0.04f, h * 0.5f)
            close()
        }
        drawPath(outline, color, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = h * 0.2f, center = Offset(w / 2f, h / 2f), style = Stroke(width = line))
        if (slashed) drawLine(color, Offset(w * 0.18f, h * 0.02f), Offset(w * 0.82f, h * 0.98f), strokeWidth = line, cap = StrokeCap.Round)
    }
}

// MARK: - AI 模型

@Composable
private fun AIModelSettingsPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    notice: String?,
    setNotice: (String?) -> Unit,
    onOpen: (String?) -> Unit,
) {
    val activeHasKey = settings.activeID in keyed
    val ready = settings.isEnabled && activeHasKey && settings.configuration.model.isNotBlank()
    Box(Modifier.fillMaxWidth()) {
        MojiSheetBar("AI 模型", "设置", "", { onOpen(null) }, {}, leadingIsBack = true)
        // MojiSheetBar has no icon slot; the help button sits over its empty trailing side.
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(44.dp)
                .clickable(role = Role.Button) { onOpen("ai:help") }
                .semantics { contentDescription = "用在哪里" },
            contentAlignment = Alignment.CenterEnd,
        ) {
            AIHelpGlyph(MaterialTheme.colorScheme.primary)
        }
    }
    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        val enabledFooter = listOf(
            AIFooter(
                if (settings.isEnabled) "开启后，“拆成步骤”“起草复盘”这些操作会把相关的计划或备忘文字发给下面选的模型，结果先以草稿出现，确认后才写入。"
                else "关闭时墨记不联网，界面和原来一样。"
            )
        )
        AISection(flow.place(null, true), footer = enabledFooter) {
            FormToggle("使用 AI 整理", settings.isEnabled, { value -> setNotice(store.tryChange { it.copy(isEnabled = value) }) })
            if (settings.isEnabled) {
                FormSeparator()
                FormToggle("识别首页写下的计划", settings.readsQuickEntry, { value -> setNotice(store.tryChange { it.copy(readsQuickEntry = value) }) })
            }
        }

        val activeFooter = if (activeHasKey) emptyList() else listOf(AIFooter("还没有填 API Key。点下面的供应商填写，或添加一个供应商。", warning = true))
        AISection(flow.place("墨记正在用", activeFooter.isNotEmpty()), "墨记正在用", activeFooter) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                AIProviderMark(settings.activeID, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        settings.displayName(settings.activeID),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        settings.configuration.model.ifEmpty { "还没有选模型" },
                        fontSize = 13.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (ready) {
                    Spacer(Modifier.width(8.dp))
                    InkSeal("用", 26, 2)
                }
            }
            FormSeparator()
            AIActionRow("换模型") { onOpen("ai:switcher") }
        }

        AISection(flow.place("供应商", false), "供应商") {
            settings.listedProviderIDs(keyed).forEach { id ->
                AIProviderRow(settings, keyed, id) { onOpen(providerPage(id)) }
                FormSeparator(start = 66.dp)
            }
            AIActionRow("添加供应商", icon = MojiIcon.Plus) { onOpen("ai:add") }
        }

        if (notice != null) {
            AISection(flow.place(null, false)) {
                Text(
                    notice, Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                    fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

// MARK: - 换模型

@Composable
private fun AIModelSwitcherPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    setNotice: (String?) -> Unit,
    onOpen: (String?) -> Unit,
) {
    fun choose(entryID: String, model: String) {
        val message = store.tryChange { it.activate(entryID, model) }
        setNotice(message ?: "已换成 ${store.settings.value.displayName(entryID)} 的 $model。")
        onOpen("ai")
    }
    MojiSheetBar("换模型", "", "完成", {}, { onOpen("ai") })
    AIFormColumn(firstHasHeader = true) {
        val flow = AIFormFlow()
        settings.listedProviderIDs(keyed).forEach { id ->
            val hasKey = id in keyed
            val title = settings.displayName(id)
            val footer = if (hasKey) emptyList() else listOf(AIFooter("还没有填密钥，填好后才能选用。"))
            AISection(flow.place(title, footer.isNotEmpty()), title, footer) {
                settings.savedModels(id).forEachIndexed { index, model ->
                    if (index > 0) FormSeparator()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(enabled = hasKey, role = Role.Button) { choose(id, model.id) }
                            .alpha(if (hasKey) 1f else 0.45f)
                            .padding(horizontal = 20.dp, vertical = 10.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIModelLabel(AIProvider.kind(id), model.id, Modifier.weight(1f))
                        if (settings.isInUse(model.id, id)) {
                            Spacer(Modifier.width(8.dp))
                            AICheckmark(mojiVermilion)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 添加供应商

@Composable
private fun AIModePicker(place: AIPlacement, custom: Boolean, onOpen: (String?) -> Unit) {
    if (place.top > 0.dp) Spacer(Modifier.height(place.top))
    // 添加方式: a bare segmented control, outside any card, as on iOS.
    FormSegmentedControl(listOf("preset" to "预设", "custom" to "自定义"), if (custom) "custom" else "preset") { raw ->
        onOpen(if (raw == "custom") "ai:add-custom" else "ai:add")
    }
}

@Composable
private fun AIAddProviderPage(keyed: Set<String>, onOpen: (String?) -> Unit) {
    MojiSheetBar("添加供应商", "取消", "", { onOpen("ai") }, {})
    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        AIModePicker(flow.place(null, false), custom = false, onOpen)
        val footer = listOf(AIFooter("密钥在各家的控制台申请，费用由供应商向你收取。墨记的整理都是短文字，用量很小。"))
        AISection(flow.place(null, true), footer = footer) {
            AIProvider.presets.forEachIndexed { index, provider ->
                if (index > 0) FormSeparator(start = 66.dp)
                val added = provider.rawValue in keyed
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable(enabled = !added, role = Role.Button) { onOpen("ai:add:${provider.rawValue}") }
                        .alpha(if (added) 0.5f else 1f)
                        .padding(horizontal = 20.dp, vertical = 13.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIProviderMark(provider.rawValue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).alpha(if (added) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(provider.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            if (added) InkTag("已添加", MaterialTheme.colorScheme.secondary)
                            else if (provider == AIProvider.deepSeek || provider == AIProvider.claude) InkTag("推荐")
                        }
                        Text(provider.subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f))
                    }
                    Spacer(Modifier.width(8.dp))
                    FormChevron()
                }
            }
        }
    }
}

/** Both the custom form ("ai:add-custom") and one preset's form ("ai:add:<provider>"). */
@Composable
private fun AIProviderSetupForm(
    store: AISettingsStore,
    provider: AIProvider,
    onAdded: (String) -> Unit,
    onOpen: (String?) -> Unit,
) {
    val isCustom = provider == AIProvider.custom
    var name by remember(provider) { mutableStateOf("") }
    var key by remember(provider) { mutableStateOf("") }
    var baseURL by remember(provider) { mutableStateOf(provider.defaultURL) }
    var style by remember(provider) { mutableStateOf(provider.defaultStyle) }
    var chosen by remember(provider) { mutableStateOf(listOfNotNull(provider.models.firstOrNull()?.id)) }
    var customModel by remember(provider) { mutableStateOf("") }
    var showsAdvanced by remember(provider) { mutableStateOf(false) }
    var check by remember(provider) { mutableStateOf<AICheckState>(AICheckState.Idle) }
    var failure by remember(provider) { mutableStateOf<String?>(null) }
    var fetched by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var isFetching by remember(provider) { mutableStateOf(false) }
    var showsFetched by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Presets keep their own order, whatever order they were ticked in.
    val modelIDs = if (isCustom) listOfNotNull(customModel.trim().ifEmpty { null })
    else provider.models.map { it.id }.filter { it in chosen }
    val base = ProviderConfiguration(provider)
    val configuration = base.copy(
        baseURL = baseURL.trim(),
        style = style,
        models = modelIDs.map { SavedModel(it, style) },
        model = modelIDs.firstOrNull() ?: "",
        name = if (isCustom) name.trim().ifEmpty { null } else base.name,
    )
    val cleanKey = key.trim()
    val canAdd = cleanKey.isNotEmpty() && modelIDs.isNotEmpty() && configuration.baseURL.isNotEmpty()

    fun add() {
        try {
            val result = store.addProvider(provider, configuration, cleanKey)
            val title = store.settings.value.displayName(result.id)
            onAdded(if (result.activated) "已添加 $title，现在用它整理。" else "已添加 $title。要用它，点“换模型”。")
        } catch (error: Exception) {
            failure = error.draftMessage
        }
    }

    fun runCheck() {
        val sent = configuration
        scope.launch {
            check = AICheckState.Running
            check = try {
                AICheckState.Passed(aiConnectionCheck(provider.rawValue, sent, cleanKey))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                AICheckState.Failed(error.draftMessage)
            }
        }
    }

    fun fetchModels() {
        val sent = configuration
        scope.launch {
            isFetching = true
            failure = null
            try {
                fetched = AIClient().models(sent, cleanKey)
                showsFetched = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error.draftMessage
            } finally {
                isFetching = false
            }
        }
    }

    if (isCustom) MojiSheetBar("添加供应商", "取消", "添加", { onOpen("ai") }, { add() }, trailingEnabled = canAdd)
    else MojiSheetBar(provider.title, "添加供应商", "添加", { onOpen("ai:add") }, { add() }, trailingEnabled = canAdd, leadingIsBack = true)

    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        if (isCustom) {
            AIModePicker(flow.place(null, false), custom = true, onOpen)
            AISection(flow.place("服务", true), "服务", listOf(AIFooter(provider.setupNote))) {
                FormTextField(name, { name = it }, "名称，例如 我的中转")
                FormSeparator()
                FormTextField(baseURL, { baseURL = it }, "https://…/v1", keyboardOptions = aiURLKeyboard)
                FormSeparator()
                FormChoiceRow("接口格式", style.rawValue, aiStyleOptions) { raw -> APIStyle.from(raw)?.let { style = it } }
            }
        } else {
            AISection(flow.place(null, false)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    AIProviderMark(provider.rawValue, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(provider.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(provider.subtitle, fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        AISection(flow.place("API Key", true), "API Key", listOf(AIFooter("密钥只保存在本机安全存储中，不进备份文件。"))) {
            AIKeyField(key) { key = it }
            provider.keyURL?.let { url ->
                FormSeparator()
                AILinkRow("去控制台申请密钥", MojiIcon.ArrowSquare, url)
            }
        }

        if (isCustom) {
            AISection(flow.place("模型", false), "模型") {
                FormTextField(customModel, { customModel = it }, "模型 ID", keyboardOptions = aiPlainKeyboard, textStyle = aiMonoStyle())
                FormSeparator()
                AIActionRow(
                    "获取可用模型",
                    enabled = cleanKey.isNotEmpty() && configuration.baseURL.isNotEmpty() && !isFetching,
                    waiting = if (isFetching) "正在获取" else null,
                ) { fetchModels() }
            }
        } else {
            AISection(flow.place("模型", true), "模型", listOf(AIFooter("可以多选，之后在“换模型”里切换。排在最前的一个先用上。"))) {
                provider.models.forEachIndexed { index, model ->
                    if (index > 0) FormSeparator()
                    val selected = model.id in chosen
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(role = Role.Checkbox) {
                                chosen = if (selected) chosen - model.id else chosen + model.id
                                check = AICheckState.Idle
                            }
                            .padding(horizontal = 20.dp, vertical = 10.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AIModelLabel(provider, model.id, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        MojiLineIcon(
                            if (selected) MojiIcon.CheckCircleFill else MojiIcon.Circle, 24.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            val advancedFooter = if (showsAdvanced) listOf(AIFooter(provider.setupNote)) else emptyList()
            AISection(flow.place(null, advancedFooter.isNotEmpty()), footer = advancedFooter) {
                AIDisclosureRow("高级", showsAdvanced) { showsAdvanced = !showsAdvanced }
                if (showsAdvanced) {
                    FormSeparator()
                    FormTextField(baseURL, { baseURL = it }, "API 地址", keyboardOptions = aiURLKeyboard, textStyle = aiMonoStyle(13.sp))
                    FormSeparator()
                    FormChoiceRow("接口格式", style.rawValue, aiStyleOptions) { raw -> APIStyle.from(raw)?.let { style = it } }
                }
            }
        }

        val checkFooter = listOfNotNull(failure?.let { AIFooter(it, warning = true) })
        AISection(flow.place(null, checkFooter.isNotEmpty()), footer = checkFooter) {
            AICheckRow(check, disabled = !canAdd) { runCheck() }
            if (!isCustom) {
                FormSeparator()
                AILinkRow("官方文档", MojiIcon.BookSF, provider.documentationURL)
            }
        }
    }

    if (showsFetched) AIFetchedModelPicker(fetched, { customModel = it }) { showsFetched = false }
}

@Composable
private fun AIFetchedModelPicker(models: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val clean = query.trim().lowercase()
    val shown = if (clean.isEmpty()) models else models.filter { it.lowercase().contains(clean) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MojiScreen {
            Column(Modifier.fillMaxSize()) {
                MojiSheetBar("可用模型", "取消", "", onDismiss, {})
                // .searchable: a rounded field under the bar.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(iosFill, RoundedCornerShape(10.dp)).heightIn(min = 36.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MojiLineIcon(MojiIcon.Search, 17.dp, iosSecondaryLabel, strokeWidth = 1.6f)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = aiPlainKeyboard,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (query.isEmpty()) Text("搜索模型 ID", fontSize = 17.sp, color = iosSecondaryLabel)
                                inner()
                            }
                        },
                    )
                }
                AIFormColumn(firstHasHeader = false) {
                    if (shown.isNotEmpty()) {
                        AISection(AIFormFlow().place(null, false)) {
                            shown.forEachIndexed { index, model ->
                                if (index > 0) FormSeparator()
                                Box(
                                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                        .clickable(role = Role.Button) { onPick(model); onDismiss() }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(model, style = aiMonoStyle())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 供应商

@Composable
private fun AIProviderDetailPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    entryID: String,
    onOpen: (String?) -> Unit,
) {
    val kind = AIProvider.kind(entryID)
    val configuration = settings.configuration(entryID)
    val hasKey = entryID in keyed
    val isActive = entryID == settings.activeID
    val host = remember(configuration.baseURL) {
        runCatching { java.net.URI(configuration.baseURL).host }.getOrNull() ?: configuration.baseURL
    }
    val name = settings.displayName(entryID)
    var confirmsRemoval by remember(entryID) { mutableStateOf(false) }
    var failure by remember(entryID) { mutableStateOf<String?>(null) }

    MojiSheetBar(name, "AI 模型", "", { onOpen("ai") }, {}, leadingIsBack = true)
    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        AISection(flow.place(null, false)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.5.dp), verticalAlignment = Alignment.CenterVertically) {
                AIProviderMark(entryID, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, Modifier.weight(1f, fill = false), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isActive) InkTag("正在用")
                    }
                    Text(
                        if (kind == AIProvider.custom) configuration.style.displayName else kind.subtitle,
                        fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        AISection(flow.place("密钥与地址", true), "密钥与地址", listOf(AIFooter("密钥只保存在本机安全存储中。"))) {
            AIValueRow("API Key", if (hasKey) "已填写" else "未填写") { onOpen("${providerPage(entryID)}:credentials") }
            FormSeparator()
            AIValueRow("地址", host) { onOpen("${providerPage(entryID)}:credentials") }
        }

        AISection(flow.place("模型", false), "模型") {
            settings.savedModels(entryID).forEach { model ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { onOpen(modelPage(entryID, model.id)) }
                        .padding(horizontal = 20.dp, vertical = 10.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIModelLabel(kind, model.id, Modifier.weight(1f))
                    if (settings.isInUse(model.id, entryID)) {
                        Spacer(Modifier.width(8.dp))
                        InkTag("正在用")
                    }
                    Spacer(Modifier.width(8.dp))
                    FormChevron()
                }
                FormSeparator()
            }
            AIActionRow("添加模型", icon = MojiIcon.Plus) { onOpen("${providerPage(entryID)}:add-model") }
        }

        if (kind != AIProvider.custom) {
            AISection(flow.place(null, false)) { AILinkRow("官方文档", MojiIcon.BookSF, kind.documentationURL) }
        }

        val currentFailure = failure
        val removalFooter = when {
            isActive -> listOf(AIFooter("正在用的供应商不能删除。先在“换模型”里换到别家。"))
            currentFailure != null -> listOf(AIFooter(currentFailure, warning = true))
            else -> emptyList()
        }
        AISection(flow.place(null, removalFooter.isNotEmpty()), footer = removalFooter) {
            AIActionRow("删除这个供应商", enabled = !isActive, color = mojiDestructive) { confirmsRemoval = true }
        }
    }

    if (confirmsRemoval) {
        AlertDialog(
            onDismissRequest = { confirmsRemoval = false },
            title = { Text("删除这个供应商？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsRemoval = false
                    try {
                        store.removeProvider(entryID)
                        onOpen("ai")
                    } catch (error: Exception) {
                        failure = error.draftMessage
                    }
                }) { Text("删除，并清除它的密钥", color = mojiDestructive) }
            },
            dismissButton = { TextButton(onClick = { confirmsRemoval = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AIProviderCredentialsPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    entryID: String,
    onOpen: (String?) -> Unit,
) {
    val kind = AIProvider.kind(entryID)
    // Read once when the page opens, as iOS does in onAppear.
    var name by remember(entryID) { mutableStateOf(settings.configuration(entryID).name ?: "") }
    var baseURL by remember(entryID) { mutableStateOf(settings.configuration(entryID).baseURL) }
    var key by remember(entryID) { mutableStateOf(store.apiKey(entryID)) }
    var failure by remember(entryID) { mutableStateOf<String?>(null) }

    fun save() {
        val cleanKey = key.trim()
        val cleanName = name.trim()
        val message = store.tryChange(mapOf(entryID to cleanKey)) { current ->
            var next = current.updateConfiguration(entryID) { configuration ->
                val moved = configuration.copy(baseURL = baseURL.trim())
                if (kind == AIProvider.custom) moved.copy(name = cleanName.ifEmpty { null }) else moved
            }
            if (next.providerList == null) next = next.copy(providerList = next.listedProviderIDs(keyed))
            next
        }
        if (message != null) failure = message else onOpen(providerPage(entryID))
    }

    MojiSheetBar("密钥与地址", "取消", "保存", { onOpen(providerPage(entryID)) }, { save() })
    AIFormColumn(firstHasHeader = true) {
        val flow = AIFormFlow()
        if (kind == AIProvider.custom) {
            AISection(flow.place("名称", false), "名称") {
                FormTextField(name, { name = it }, "名称")
            }
        }
        AISection(flow.place("API Key", true), "API Key", listOf(AIFooter("留空并保存会清除已存的密钥。"))) {
            AIKeyField(key) { key = it }
            kind.keyURL?.let { url ->
                FormSeparator()
                AILinkRow("去控制台申请密钥", MojiIcon.ArrowSquare, url)
            }
        }
        val addressFooter = listOfNotNull(AIFooter(kind.setupNote), failure?.let { AIFooter(it, warning = true) })
        AISection(flow.place("地址", true), "地址", addressFooter) {
            FormTextField(baseURL, { baseURL = it }, "API 地址", keyboardOptions = aiURLKeyboard, textStyle = aiMonoStyle(13.sp))
            if (kind != AIProvider.custom && baseURL != kind.defaultURL) {
                FormSeparator()
                AIActionRow("恢复官方地址") { baseURL = kind.defaultURL }
            }
        }
    }
}

@Composable
private fun AIAddModelPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    entryID: String,
    onOpen: (String?) -> Unit,
) {
    val kind = AIProvider.kind(entryID)
    var modelID by remember(entryID) { mutableStateOf("") }
    var fetched by remember(entryID) { mutableStateOf<List<String>>(emptyList()) }
    var isFetching by remember(entryID) { mutableStateOf(false) }
    var showsFetched by remember(entryID) { mutableStateOf(false) }
    var failure by remember(entryID) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cleanID = modelID.trim()
    val kept = settings.savedModels(entryID).map { it.id }.toSet()
    val suggestions = kind.models.filter { it.id !in kept }

    fun add() {
        val style = store.settings.value.configuration(entryID).style
        val message = store.tryChange { current ->
            current.updateConfiguration(entryID) { it.upsert(SavedModel(cleanID, style), kind) }
        }
        if (message != null) failure = message else onOpen(providerPage(entryID))
    }

    fun fetchModels() {
        scope.launch {
            isFetching = true
            failure = null
            try {
                fetched = AIClient().models(store.settings.value.configuration(entryID), store.apiKey(entryID))
                showsFetched = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = error.draftMessage
            } finally {
                isFetching = false
            }
        }
    }

    MojiSheetBar("添加模型", "取消", "添加", { onOpen(providerPage(entryID)) }, { add() }, trailingEnabled = cleanID.isNotEmpty())
    AIFormColumn(firstHasHeader = true) {
        val flow = AIFormFlow()
        val footer = listOf(failure?.let { AIFooter(it, warning = true) } ?: AIFooter("模型 ID 以供应商文档里写的为准。"))
        AISection(flow.place("模型", true), "模型", footer) {
            FormTextField(modelID, { modelID = it }, "模型 ID", keyboardOptions = aiPlainKeyboard, textStyle = aiMonoStyle())
            FormSeparator()
            AIActionRow(
                "获取可用模型",
                enabled = entryID in keyed && !isFetching,
                waiting = if (isFetching) "正在获取" else null,
            ) { fetchModels() }
        }
        if (suggestions.isNotEmpty()) {
            AISection(flow.place("常用", false), "常用") {
                suggestions.forEachIndexed { index, preset ->
                    if (index > 0) FormSeparator()
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { modelID = preset.id }
                            .padding(horizontal = 20.dp, vertical = 10.5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        AIModelLabel(kind, preset.id, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (showsFetched) AIFetchedModelPicker(fetched, { modelID = it }) { showsFetched = false }
}

// MARK: - 模型

@Composable
private fun AIModelDetailPage(
    store: AISettingsStore,
    settings: AISettings,
    keyed: Set<String>,
    entryID: String,
    modelID: String,
    onOpen: (String?) -> Unit,
) {
    val kind = AIProvider.kind(entryID)
    val models = settings.savedModels(entryID)
    val model = models.firstOrNull { it.id == modelID } ?: SavedModel(modelID, settings.configuration(entryID).style)
    val isInUse = settings.isInUse(modelID, entryID)
    val hasKey = entryID in keyed
    var check by remember(entryID, modelID) { mutableStateOf<AICheckState>(AICheckState.Idle) }
    var failure by remember(entryID, modelID) { mutableStateOf<String?>(null) }
    var confirmsRemoval by remember(entryID, modelID) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun update(change: (SavedModel) -> SavedModel) {
        val value = change(model)
        check = AICheckState.Idle
        failure = store.tryChange { current -> current.updateConfiguration(entryID) { it.upsert(value, kind) } }
    }

    fun runCheck() {
        val sent = settings.configuration(entryID).copy(model = model.id, style = model.style, reasoning = model.reasoning)
        scope.launch {
            check = AICheckState.Running
            check = try {
                AICheckState.Passed(aiConnectionCheck(entryID, sent, store.apiKey(entryID)))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                AICheckState.Failed(error.draftMessage)
            }
        }
    }

    MojiSheetBar("模型", settings.displayName(entryID), "", { onOpen(providerPage(entryID)) }, {}, leadingIsBack = true)
    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        AISection(flow.place(null, false)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        kind.models.firstOrNull { it.id == modelID }?.title ?: modelID, Modifier.weight(1f, fill = false),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (isInUse) InkTag("正在用")
                }
                SelectionContainer {
                    Text(modelID, fontSize = 13.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        AISection(flow.place(null, true), footer = listOf(AIFooter("不确定就保持默认。接口格式选错时，测试连接会提示返回格式不兼容。"))) {
            FormChoiceRow("接口格式", model.style.rawValue, aiStyleOptions) { raw ->
                APIStyle.from(raw)?.let { value -> update { it.copy(style = value) } }
            }
        }

        val reasoningFooter = if (kind.reasoningNote.isBlank()) emptyList() else listOf(AIFooter(kind.reasoningNote))
        AISection(flow.place("高级", reasoningFooter.isNotEmpty()), "高级", reasoningFooter) {
            FormChoiceRow(
                "思考强度",
                model.reasoning?.rawValue ?: aiAutoReasoning,
                listOf(aiAutoReasoning to "自动") + kind.reasoningOptions.map { it.rawValue to it.title },
            ) { raw -> update { it.copy(reasoning = ReasoningEffort.from(raw)) } }
        }

        val keyFooter = if (hasKey) emptyList() else listOf(AIFooter("先给这个供应商填好密钥。"))
        AISection(flow.place(null, keyFooter.isNotEmpty()), footer = keyFooter) {
            AICheckRow(check, disabled = !hasKey) { runCheck() }
        }

        val currentFailure = failure
        val actionFooter = when {
            currentFailure != null -> listOf(AIFooter(currentFailure, warning = true))
            models.size <= 1 -> listOf(AIFooter("供应商下至少保留一个模型。"))
            else -> emptyList()
        }
        AISection(flow.place(null, actionFooter.isNotEmpty()), footer = actionFooter) {
            AIActionRow("设为正在用", enabled = !isInUse && hasKey) {
                failure = store.tryChange { it.activate(entryID, modelID) }
            }
            FormSeparator()
            AIActionRow("删除这个模型", enabled = models.size > 1, color = mojiDestructive) { confirmsRemoval = true }
        }
    }

    if (confirmsRemoval) {
        AlertDialog(
            onDismissRequest = { confirmsRemoval = false },
            title = { Text("删除这个模型？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsRemoval = false
                    val message = store.tryChange { current ->
                        current.updateConfiguration(entryID) { it.removeModel(modelID, kind) }
                    }
                    if (message != null) failure = message else onOpen(providerPage(entryID))
                }) { Text("删除", color = mojiDestructive) }
            },
            dismissButton = { TextButton(onClick = { confirmsRemoval = false }) { Text("取消") } },
        )
    }
}

// MARK: - 用在哪里

private val aiHelpPlaces: List<Triple<MojiIcon, String, String>> = listOf(
    Triple(MojiIcon.Edit, "首页写下计划", "带有“明天下午”“30 分钟”这类线索时，在那一行下面给出建议，点了才应用。"),
    Triple(MojiIcon.TextLines, "计划 › 更多操作 › 拆成步骤", "把一件大的计划拆成几步；点顺延印也能“拆小一点”。"),
    Triple(MojiIcon.DocMagnify, "回顾 › 周复盘 › 起草复盘", "按这一周的记录起草两栏文字，并注明依据的计划；还可以拟下周草稿。"),
    Triple(MojiIcon.TabChecklist, "备忘 › 备忘操作 › 整理成清单", "把正文里要做的事整理成清单；转为计划时预填日期和类型。"),
    Triple(MojiIcon.TimerSF, "时刻 › 新建后 › 准备计划", "为一个重要的日子拟几条提前要做的事。"),
    Triple(MojiIcon.FilterLines, "计划库 › 搜索框", "写一句“上周没做完的学习计划”，替你设好筛选。"),
    Triple(MojiIcon.TextViewfinder, "首页 ＋ › 从文字或截图添加", "从粘贴的文字或一张截图里找出要做的事。截图的文字在本机识别。"),
)

@Composable
private fun AIParagraph(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AIHelpPage(onOpen: (String?) -> Unit) {
    MojiSheetBar("用在哪里", "", "完成", {}, { onOpen("ai") })
    AIFormColumn(firstHasHeader = false) {
        val flow = AIFormFlow()
        AISection(flow.place(null, false)) {
            aiHelpPlaces.forEachIndexed { index, (icon, title, detail) ->
                if (index > 0) FormSeparator(start = 56.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.5.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(24.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
                        MojiLineIcon(icon, if (icon.ordinal >= MojiIcon.TabChecklist.ordinal) 24.dp else 21.dp, MaterialTheme.colorScheme.primary, strokeWidth = 1.5f)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                        Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        AISection(flow.place("会发送什么", false), "会发送什么") {
            AIParagraph("只在你点了上面这些操作时，把那一处用到的文字（计划标题、备忘正文、一周的计划记录）发给你选的供应商。请求从手机直接发出，不经过墨记的服务器。")
            FormSeparator()
            AIParagraph("首页识别是例外：开着“识别首页写下的计划”时，带时间线索的新计划会在添加后自动发去识别。可以单独关掉。")
        }
        AISection(flow.place("不会做什么", false), "不会做什么") {
            AIParagraph("结果都先以草稿出现，带一枚“稿”字印；你确认后才写入，写入后可以撤销。")
            FormSeparator()
            AIParagraph("密钥只在本机安全存储里，不进备份文件；小组件不联网。")
        }
    }
}
