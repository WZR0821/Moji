package com.raydon.moji.android

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raydon.moji.ui.MojiPalette
import java.time.LocalDate
import java.time.LocalTime

val LocalPaperTexture = staticCompositionLocalOf { true }

val mojiVermilion: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MojiPalette.VermilionDark else MojiPalette.Vermilion

enum class MojiIcon {
    Checklist, Note, Timer, Chart, Calendar, Plus, Edit, Gear, Search, PencilLine, Sliders, Repeat,
    ChevronRight, Ellipsis, Play, Pause, Stop, Pin, Backup, Hand, Grid, Brush, Info,
    Clock, Sun, Book, Work, Tag, TextLines, Cup, Leaf, Bell,
    // SF Symbol look-alikes, drawn on a 28-unit box (1 unit = 1pt at 28.dp).
    TabChecklist, TabNote, TabTimer, TabChart, CalendarFill, GridSF, ChevronLeft, Disclosure,
    Pencil, RepeatLoop, BellPlain, TimerSF, Folder, FolderPlus, ShareUp, FolderGear, ShareDown, CalendarPlus, ArrowUpRight,
    Cpu, CheckCircleFill, Circle, Xmark, Photo, Clipboard,
    BookSF, ArrowSquare, DocMagnify, FilterLines, TextViewfinder,
}

@Composable
fun ConfigureDialogWindow(dimAmount: Float? = null) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        if (window != null) {
            if (dimAmount != null) window.setDimAmount(dimAmount)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
}

@Composable
fun MojiScreen(sheetFraction: Float? = null, sheetHeight: Dp? = null, opaqueTopInset: Boolean = false, content: @Composable () -> Unit) {
    ConfigureDialogWindow(if (sheetFraction != null || sheetHeight != null) 0.12f else null)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    if (window != null) {
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val bottomExtra = maxOf(0.dp, 34.dp - with(density) { WindowInsets.navigationBars.getBottom(density).toDp() })
        Box(Modifier.fillMaxSize().padding(top = if (sheetFraction == null && sheetHeight == null) maxOf(69.3.dp, topInset + 44.dp) else 0.dp), contentAlignment = Alignment.BottomCenter) {
            val sheetSize = if (sheetHeight != null) Modifier.height(sheetHeight) else Modifier.fillMaxHeight(sheetFraction ?: 1f)
            Box(Modifier.fillMaxWidth().then(sheetSize).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) {
                MojiPaperScreen {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(bottom = bottomExtra).imePadding()) { content() }
                }
            }
        }
    } else {
        val density = LocalDensity.current
        val statusInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        // Keep the app's large-title baseline aligned with the iPhone reference,
        // even when Android's status bar is shorter than the iOS safe area.
        Box(Modifier.fillMaxSize()) {
            if (opaqueTopInset) Box(Modifier.fillMaxWidth().height(maxOf(0.dp, 54.dp - statusInset)).background(MaterialTheme.colorScheme.background))
            Box(Modifier.fillMaxSize().padding(top = maxOf(0.dp, 54.dp - statusInset))) { content() }
        }
    }
}

@Composable
fun MojiPaperScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (LocalPaperTexture.current) PaperFibers(Modifier.fillMaxSize(), if (dark) 1.08f else 0.92f)
        InkAsset(
            "InkFocusBloom",
            Modifier.size(maxWidth * 1.10f).offset(x = maxWidth * 0.35f, y = maxHeight * 0.06f - maxWidth * 0.55f).alpha(if (dark) 0.150f else 0.128f),
        )
        InkAsset(
            "InkFocusBloom",
            Modifier.size(maxWidth * 0.94f).offset(x = maxWidth * -0.41f, y = maxHeight * 0.87f - maxWidth * 0.47f).rotate(24f).alpha(if (dark) 0.108f else 0.092f),
        )
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
fun PaperFibers(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val d = density; val w = size.width / d; val h = size.height / d
        val count = maxOf(6, (h / 26).toInt())
        repeat(count) { index ->
            val y = h * (index + 1) / (count + 1) + kotlin.math.sin(index * 2.13).toFloat() * 2.1f
            val p = Path(); p.moveTo(-8 * d, y * d)
            p.cubicTo(w * 0.3f * d, (y - 1.8f) * d, w * 0.71f * d, (y + 2.2f) * d, (w + 8) * d, (y + kotlin.math.cos(index * 1.7).toFloat() * 1.8f) * d)
            drawPath(p, color.copy(alpha = 0.010f * intensity), style = Stroke((if (index % 4 == 0) 0.48f else 0.24f) * d, cap = StrokeCap.Round))
        }
        repeat(maxOf(8, (w * h / 18000).toInt())) { index ->
            val seed = (index + 7).toDouble()
            val x = kotlin.math.abs(kotlin.math.sin(seed * 12.9898)).toFloat() * w
            val y = kotlin.math.abs(kotlin.math.cos(seed * 7.233)).toFloat() * h
            val length = 4 + kotlin.math.abs(kotlin.math.sin(seed * 3.17)).toFloat() * 12
            drawLine(color.copy(alpha = 0.012f * intensity), androidx.compose.ui.geometry.Offset(x * d, y * d), androidx.compose.ui.geometry.Offset((x + length) * d, (y + kotlin.math.sin(seed * 4.31).toFloat() * 2.2f) * d), strokeWidth = 0.30f * d, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun InkAsset(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val bitmap = remember(name) {
        runCatching {
            context.assets.open("$name.imageset/$name.png").use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clipToBounds(),
            contentScale = if (name.contains("Divider") || name in listOf("InkHeaderWash", "InkProgressBar", "InkTaskStrike")) ContentScale.Crop else ContentScale.Fit,
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** The same seeded brush medallion artwork and transform used by iOS. */
@Composable
fun InkBrushMedallion(kind: MojiIcon, tint: Color, size: Dp = 31.dp, seed: Long = 0, iconSize: Dp? = null) {
    var hash = (seed * 2654435761L).toULong()
    hash = hash xor (hash shr 13)
    hash *= 0x9E3779B97F4A7C15uL
    hash = hash xor (hash shr 7)
    val angle = ((hash and 255u).toFloat() / 255f * 2f - 1f) * 26f
    val scale = 1f + (((hash shr 8) and 255u).toFloat() / 255f * 2f - 1f) * 0.11f
    val opacity = 1f + (((hash shr 16) and 255u).toFloat() / 255f * 2f - 1f) * 0.16f
    val artwork = listOf("InkIconLoop", "InkIconCrescent", "InkIconScholarSquare")[(seed.toULong() % 3u).toInt()]
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        InkAsset(artwork, Modifier.fillMaxSize().graphicsLayer {
            rotationZ = angle
            scaleX = if (((hash shr 24) and 1u) == 1uL) -scale else scale
            scaleY = scale
        }, tint.copy(alpha = 0.72f * opacity))
        MojiLineIcon(kind, iconSize ?: (size * 0.34f), tint)
    }
}

@Composable
fun InkDivider(modifier: Modifier = Modifier, opacity: Float = 0.30f, seed: Long = 0) {
    val artworks = listOf("InkThinDivider", "InkDividerSweep", "InkDividerEcho", "InkDividerBroken", "InkDividerWash", "InkDividerHook", "InkDividerThread")
    var hash = (seed * 2654435761L).toULong(); hash = hash xor (hash shr 13); hash *= 0x9E3779B97F4A7C15uL; hash = hash xor (hash shr 7)
    val rotation = (((hash and 255u).toFloat() / 255 * 2 - 1) * 26 * 0.05f) - 0.5f
    val opacityScale = 1 + (((hash shr 16) and 255u).toFloat() / 255 * 2 - 1) * 0.16f * 0.05f
    Box(modifier.fillMaxWidth().height(7.dp).rotate(rotation).alpha(opacity)) {
        InkAsset("InkThinDivider", Modifier.fillMaxSize().alpha(0.14f))
        InkAsset(artworks[(seed.toULong() % artworks.size.toUInt()).toInt()], Modifier.fillMaxSize().graphicsLayer { scaleX = if (((hash shr 24) and 1u) == 1uL) -1f else 1f }.alpha(0.50f * opacityScale))
    }
}

fun inkSeed(text: String): Long {
    var hash = 0xCBF29CE484222325uL
    text.toByteArray(Charsets.UTF_8).forEach { hash = (hash xor it.toUByte().toULong()) * 0x100000001B3uL }
    return hash.toLong()
}

@Composable
fun InkRing(modifier: Modifier = Modifier, active: Boolean = false) {
    InkAsset(
        "InkTimerRingDirectional",
        modifier,
        tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
    )
}

/** Same authored bitmap and directional reveal as InkBrushRing on iOS. */
@Composable
fun PomodoroInkRing(progress: Float, active: Boolean, dryFibers: Boolean, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier.rotate(3f)) {
        InkAsset("InkTimerRingDirectional", Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }.alpha(if (active) 0.13f else 0.08f))
        if (clamped > 0.001f) InkAsset("InkTimerRingDirectional", Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val diameter = minOf(size.width, size.height); val radius = diameter * 0.365f
                val maskWidth = maxOf(diameter * 0.19f, 40.dp.toPx())
                fun arc(from: Float, to: Float): Path {
                    val p = Path(); val count = maxOf(2, kotlin.math.ceil((to - from) * 180).toInt())
                    for (i in 0..count) { val t = from + (to - from) * i / count; val angle = Math.toRadians((-84 + 344 * t).toDouble()); val x = center.x + kotlin.math.cos(angle).toFloat() * radius; val y = center.y + kotlin.math.sin(angle).toFloat() * radius; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    return p
                }
                // Build the mask offscreen, then apply it to the shared brush asset.
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), androidx.compose.ui.graphics.Paint().apply { blendMode = BlendMode.DstIn })
                drawPath(arc(0f, minOf(clamped, 0.012f)), Color.White, style = Stroke(maskWidth * if (dryFibers) 0.96f else 1f, cap = StrokeCap.Round))
                val transition = maxOf(0f, clamped - if (dryFibers) 0.060f else 0.040f)
                if (transition > 0.001f) drawPath(arc(0f, transition), Color.White, style = Stroke(maskWidth * if (dryFibers) 0.96f else 1f))
                val t = ((clamped - 0.86f) / 0.14f).coerceIn(0f, 1f); val smooth = t * t * (3 - 2 * t)
                val scale = if (dryFibers) 0.78f + (0.44f - 0.78f) * smooth else 0.94f + (0.52f - 0.94f) * smooth
                val tail = arc(maxOf(0f, transition - if (dryFibers) 0.014f else 0.010f), clamped)
                if (clamped > 0.82f) {
                    val isolation = Path(); isolation.moveTo(center.x, center.y)
                    for (i in 0..80) { val angle = Math.toRadians((-84 + 344 * 0.70 + (344 * 0.30 + 16 * 0.12) * i / 80).toDouble()); val r = diameter * 1.5f; isolation.lineTo(center.x + kotlin.math.cos(angle).toFloat() * r, center.y + kotlin.math.sin(angle).toFloat() * r) }; isolation.close()
                    clipPath(isolation) { drawPath(tail, Color.White, style = Stroke(maskWidth * scale, cap = StrokeCap.Round)) }
                } else drawPath(tail, Color.White, style = Stroke(maskWidth * scale, cap = StrokeCap.Round))
                drawContext.canvas.restore()
            }.graphicsLayer { scaleX = -1f }.alpha(0.92f))
    }
}

@Composable
fun MojiLargeTitle(
    title: String,
    leading: MojiIcon? = null,
    leadingAction: (() -> Unit)? = null,
    actions: List<Pair<MojiIcon, () -> Unit>> = emptyList(),
    bottomPadding: Dp = 14.dp,
    opaqueBackground: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().background(if (opaqueBackground) MaterialTheme.colorScheme.background else Color.Transparent).padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                IconButton(onClick = { leadingAction?.invoke() }, enabled = leadingAction != null, modifier = Modifier.offset(x = (-13).dp)) {
                    MojiLineIcon(leading, if (leading.ordinal >= MojiIcon.TabChecklist.ordinal) 28.dp else 25.dp)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            actions.forEach { (icon, action) ->
                IconButton(
                    onClick = action,
                    modifier = Modifier.offset(x = 14.dp).semantics {
                        contentDescription = when (icon) {
                            MojiIcon.Gear -> "打开设置"
                            MojiIcon.Plus -> "添加"
                            MojiIcon.Edit -> "新建备忘"
                            MojiIcon.Ellipsis -> "更多"
                            MojiIcon.Grid -> "计划库与模板"
                            else -> icon.name
                        }
                    },
                ) { MojiLineIcon(icon, if (icon.ordinal >= MojiIcon.TabChecklist.ordinal) 28.dp else if (icon == MojiIcon.Plus) 23.dp else 22.dp) }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.offset(x = (-2).dp).padding(bottom = bottomPadding),
        )
    }
}

@Composable
fun MojiSheetBar(
    title: String,
    leading: String,
    trailing: String,
    onLeading: () -> Unit,
    onTrailing: () -> Unit,
    trailingEnabled: Boolean = true,
    leadingIsBack: Boolean = leading == "返回",
    transparentBackground: Boolean = true,
    separator: Boolean = false,
) {
    // UIKit gives a navigation bar inside a sheet 56pt; a pushed full-screen page keeps 44pt.
    val inSheet = LocalView.current.parent is DialogWindowProvider
    Column {
    Row(
        Modifier.fillMaxWidth().height(if (inSheet) 56.dp else 44.dp).background(if (transparentBackground) Color.Transparent else MaterialTheme.colorScheme.background).padding(start = if (leadingIsBack) 0.65.dp else 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(enabled = leading.isNotEmpty(), role = Role.Button, onClick = onLeading).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIsBack) { MojiLineIcon(MojiIcon.ChevronLeft, 28.dp, MaterialTheme.colorScheme.primary); androidx.compose.foundation.layout.Spacer(Modifier.size(2.35.dp)) }
            Text(leading, fontSize = 17.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
        if (trailing == "+") Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.offset(x = 2.dp).clickable(enabled = trailingEnabled, role = Role.Button, onClick = onTrailing).padding(vertical = 10.dp)) { MojiLineIcon(MojiIcon.Plus, 21.dp, MaterialTheme.colorScheme.primary) }
        } else Text(
            trailing,
            modifier = Modifier.weight(1f).alpha(if (trailingEnabled) 1f else 0.35f)
                .clickable(enabled = trailingEnabled && trailing.isNotEmpty(), role = Role.Button, onClick = onTrailing).padding(vertical = 10.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (separator) NavigationBarHairline()
    }
}

@Composable
fun MojiLineIcon(kind: MojiIcon, size: Dp = 26.dp, color: Color = MaterialTheme.colorScheme.onSurface, strokeWidth: Float = 1.9f, cutoutColor: Color? = null) {
    val cutout = cutoutColor ?: MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w / 24f
        if (kind.ordinal >= MojiIcon.TabChecklist.ordinal) { drawSfGlyph(kind, color, cutout, w / 28f); return@Canvas }
        val stroke = Stroke(width = strokeWidth * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, androidx.compose.ui.geometry.Offset(x1 * s, y1 * s), androidx.compose.ui.geometry.Offset(x2 * s, y2 * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) = drawCircle(color, r * s, androidx.compose.ui.geometry.Offset(x * s, y * s), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        when (kind) {
            MojiIcon.Checklist -> { circle(6f, 6f, 2.2f); line(4.8f,6f,5.8f,7f); line(5.8f,7f,7.4f,4.8f); line(11f,6f,21f,6f); circle(6f,17f,2.2f); line(11f,17f,21f,17f) }
            MojiIcon.Note -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,3f*s), androidx.compose.ui.geometry.Size(18f*s,18f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style=stroke); line(7f,8f,17f,8f); line(7f,12f,17f,12f); line(7f,16f,14f,16f) }
            MojiIcon.Timer -> { circle(12f,13f,8f); line(12f,13f,8f,9f); line(9f,2.5f,15f,2.5f); line(12f,2.5f,12f,5f) }
            MojiIcon.Chart -> { line(3f,21f,22f,21f); listOf(5f to 12f, 10f to 7f, 15f to 10f, 20f to 4f).forEach { (x,y) -> drawRoundRect(color, androidx.compose.ui.geometry.Offset((x-1.5f)*s,y*s), androidx.compose.ui.geometry.Size(3f*s,(21f-y)*s), androidx.compose.ui.geometry.CornerRadius(1f*s)) } }
            MojiIcon.Calendar -> drawSfCalendar(color, s)
            MojiIcon.Plus -> { line(12f,3f,12f,21f); line(3f,12f,21f,12f) }
            MojiIcon.Edit -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,5f*s), androidx.compose.ui.geometry.Size(15f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style=stroke); line(9f,15f,20.5f,3.5f); line(17.5f,3.5f,20.5f,6.5f) }
            MojiIcon.PencilLine -> { line(4f,17f,16f,5f); line(16f,5f,20f,9f); line(20f,9f,8f,21f); line(4f,17f,4f,21f); line(4f,21f,8f,21f); line(12f,22f,22f,22f) }
            MojiIcon.Sliders -> { line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f); circle(8f,6f,2f,true); circle(16f,12f,2f,true); circle(10f,18f,2f,true) }
            MojiIcon.Repeat -> { line(4f,8f,20f,8f); line(16f,4f,20f,8f); line(20f,8f,16f,12f); line(20f,16f,4f,16f); line(8f,12f,4f,16f); line(4f,16f,8f,20f) }
            MojiIcon.Gear -> { circle(12f,12f,3.2f); circle(12f,12f,8.4f); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f); line(4.2f,4.2f,6.3f,6.3f); line(17.7f,17.7f,19.8f,19.8f); line(19.8f,4.2f,17.7f,6.3f); line(6.3f,17.7f,4.2f,19.8f) }
            MojiIcon.Search -> { circle(10f,10f,6f); line(14.4f,14.4f,21f,21f) }
            MojiIcon.ChevronRight -> { line(9f,4f,16f,12f); line(16f,12f,9f,20f) }
            MojiIcon.Ellipsis -> { circle(5f,12f,1.9f,true); circle(12f,12f,1.9f,true); circle(19f,12f,1.9f,true) }
            MojiIcon.Play -> { val p=Path();p.moveTo(8f*s,5f*s);p.lineTo(19f*s,12f*s);p.lineTo(8f*s,19f*s);p.close();drawPath(p,color) }
            MojiIcon.Pause -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(6f*s,4f*s), androidx.compose.ui.geometry.Size(4f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(s)); drawRoundRect(color, androidx.compose.ui.geometry.Offset(14f*s,4f*s), androidx.compose.ui.geometry.Size(4f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(s)) }
            MojiIcon.Stop -> drawRoundRect(color, androidx.compose.ui.geometry.Offset(6f*s,6f*s), androidx.compose.ui.geometry.Size(12f*s,12f*s), androidx.compose.ui.geometry.CornerRadius(1.5f*s))
            MojiIcon.Pin -> { // pin.fill
                val head = Path(); head.moveTo(8f*s,2.5f*s); head.lineTo(16f*s,2.5f*s); head.lineTo(15f*s,5f*s); head.lineTo(15.6f*s,10f*s); head.lineTo(18.2f*s,13.4f*s); head.lineTo(5.8f*s,13.4f*s); head.lineTo(8.4f*s,10f*s); head.lineTo(9f*s,5f*s); head.close(); drawPath(head, color)
                drawLine(color, androidx.compose.ui.geometry.Offset(12f*s,13f*s), androidx.compose.ui.geometry.Offset(12f*s,21.5f*s), strokeWidth = 1.7f*s, cap = StrokeCap.Round) }
            MojiIcon.Backup -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,5f*s), androidx.compose.ui.geometry.Size(18f*s,14f*s), androidx.compose.ui.geometry.CornerRadius(3f*s), style=stroke); line(7f,15f,17f,15f); circle(12f,15f,1f,true) }
            MojiIcon.Hand -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(6f*s,5f*s), androidx.compose.ui.geometry.Size(12f*s,15f*s), androidx.compose.ui.geometry.CornerRadius(5f*s), style=stroke); line(9f,4f,9f,11f); line(12f,3f,12f,11f); line(15f,4f,15f,11f) }
            MojiIcon.Grid -> { for(x in listOf(3f,13f)) for(y in listOf(3f,13f)) drawRoundRect(color, androidx.compose.ui.geometry.Offset(x*s,y*s), androidx.compose.ui.geometry.Size(8f*s,8f*s), androidx.compose.ui.geometry.CornerRadius(s),style=stroke) }
            MojiIcon.Brush -> { line(5f,19f,17f,7f); line(15f,5f,19f,9f); circle(5f,19f,2f) }
            MojiIcon.Info -> { circle(12f,12f,9f); circle(12f,7f,1f,true); line(12f,11f,12f,17f) }
            MojiIcon.Clock -> { circle(12f, 12f, 9f, true); drawLine(cutout, androidx.compose.ui.geometry.Offset(12f*s,5f*s), androidx.compose.ui.geometry.Offset(12f*s,12f*s), strokeWidth = 1.8f*s); drawLine(cutout, androidx.compose.ui.geometry.Offset(12f*s,12f*s), androidx.compose.ui.geometry.Offset(7f*s,12f*s), strokeWidth = 1.8f*s) }
            MojiIcon.Sun -> { circle(12f,12f,4f,true); repeat(8) { i -> val angle = Math.PI * i / 4; line(12f + kotlin.math.cos(angle).toFloat()*7,12f + kotlin.math.sin(angle).toFloat()*7,12f + kotlin.math.cos(angle).toFloat()*10,12f + kotlin.math.sin(angle).toFloat()*10) } }
            MojiIcon.Bell -> {
                val bell = Path(); bell.moveTo(8f*s, 8f*s); bell.quadraticTo(8f*s, 4f*s, 12f*s, 4f*s); bell.quadraticTo(16f*s, 4f*s, 16f*s, 8f*s); bell.lineTo(16f*s, 14f*s); bell.lineTo(18f*s, 17f*s); bell.lineTo(6f*s, 17f*s); bell.lineTo(8f*s, 14f*s); bell.close()
                drawPath(bell, color, style = stroke); line(10f, 20f, 14f, 20f)
                val waves = Path(); waves.moveTo(3f*s, 7f*s); waves.quadraticTo(0f, 12f*s, 3f*s, 17f*s); waves.moveTo(21f*s, 7f*s); waves.quadraticTo(24f*s, 12f*s, 21f*s, 17f*s); drawPath(waves, color, style = stroke)
            }
            MojiIcon.Book -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(5f*s,3f*s), androidx.compose.ui.geometry.Size(15f*s,18f*s), androidx.compose.ui.geometry.CornerRadius(2f*s)); line(3f,5f,3f,20f) }
            MojiIcon.Work -> drawSfBriefcase(color, s)
            MojiIcon.Tag -> drawSfTag(color, s)
            MojiIcon.TextLines -> { line(3f,5f,20f,5f); line(3f,10f,15f,10f); line(3f,15f,20f,15f); line(3f,20f,12f,20f) }
            MojiIcon.Cup -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(4f*s,6f*s), androidx.compose.ui.geometry.Size(12f*s,12f*s), androidx.compose.ui.geometry.CornerRadius(4f*s)); circle(17f,11f,4f); line(3f,21f,20f,21f) }
            MojiIcon.Leaf -> { val p=Path(); p.moveTo(3f*s,4f*s); p.cubicTo(18f*s,3f*s,23f*s,10f*s,19f*s,19f*s);p.cubicTo(10f*s,22f*s,5f*s,17f*s,3f*s,4f*s);p.close();drawPath(p,color);drawLine(cutout, androidx.compose.ui.geometry.Offset(7f*s,8f*s), androidx.compose.ui.geometry.Offset(20f*s,19f*s), strokeWidth = s) }
            else -> Unit // SF look-alikes are drawn by drawSfGlyph above.
        }
    }
}

/**
 * SF Symbol look-alikes measured from the iPhone screenshots (1 unit = 1pt at 28.dp).
 * Filled glyphs mirror the variants iOS picks for tab items and toolbars.
 */
// The three glyphs below copy the SF Symbols that iOS shows ("calendar", "briefcase.fill", "tag.fill").
// Coordinates are on the 24-unit grid and were measured from the iOS 1.7.0 simulator captures.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSfCalendar(color: Color, s: Float) {
    // Outline with a solid header band, then a 3/4/3 grid of dots.
    val ring = Path().apply {
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        addRoundRect(androidx.compose.ui.geometry.RoundRect(2.45f*s, 3.63f*s, 21.55f*s, 20.37f*s, androidx.compose.ui.geometry.CornerRadius(2.1f*s)))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(4.12f*s, 8.1f*s, 19.88f*s, 18.77f*s, androidx.compose.ui.geometry.CornerRadius(0.6f*s)))
    }
    drawPath(ring, color)
    val xs = listOf(7.1f, 10.15f, 13.2f, 16.25f)
    listOf(10.43f to xs.drop(1), 13.23f to xs, 16.3f to xs.dropLast(1)).forEach { (y, row) ->
        row.forEach { x -> drawCircle(color, 0.78f*s, androidx.compose.ui.geometry.Offset(x*s, y*s)) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSfBriefcase(color: Color, s: Float) {
    val p = Path().apply {
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        val zero = androidx.compose.ui.geometry.CornerRadius.Zero
        val outer = androidx.compose.ui.geometry.CornerRadius(1.58f*s); val inner = androidx.compose.ui.geometry.CornerRadius(0.4f*s)
        // Handle: a ring open at the bottom, sitting exactly on the body's top edge.
        addRoundRect(androidx.compose.ui.geometry.RoundRect(7.66f*s, 3.7f*s, 16.34f*s, 6.93f*s, outer, outer, zero, zero))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(9.41f*s, 5.23f*s, 14.59f*s, 6.93f*s, inner, inner, zero, zero))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(2f*s, 6.93f*s, 22f*s, 20.39f*s, androidx.compose.ui.geometry.CornerRadius(1.92f*s)))
        // Seam: a band between two parabolas across the body that opens into the latch below its middle.
        fun bow(x: Float) = 1f - ((x - 12f) / 10f).let { it * it }
        fun upper(x: Float) = 9.87f + 1.87f * bow(x)
        fun lower(x: Float) = 11.79f + 1.59f * bow(x)
        moveTo(2f*s, upper(2f)*s)
        for (i in 1..20) lineTo((2f + i)*s, upper(2f + i)*s)
        var x = 22f
        lineTo(x*s, lower(x)*s)
        while (x > 14.27f) { x = maxOf(14.26f, x - 1f); lineTo(x*s, lower(x)*s) }
        lineTo(14.26f*s, 14.32f*s); quadraticTo(14.26f*s, 15.02f*s, 13.56f*s, 15.02f*s)
        lineTo(10.43f*s, 15.02f*s); quadraticTo(9.73f*s, 15.02f*s, 9.73f*s, 14.32f*s)
        x = 9.73f
        lineTo(x*s, lower(x)*s)
        while (x > 2.01f) { x = maxOf(2f, x - 1f); lineTo(x*s, lower(x)*s) }
        close()
    }
    drawPath(p, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSfTag(color: Color, s: Float) {
    // A 45-degree tag: flat-topped head with the hole at the top right, rounded end at the bottom left.
    val points = listOf(12f to 2.46f, 18.06f to 2.46f, 21.6f to 6f, 21.6f to 11.26f, 10.49f to 22.38f, 1.29f to 13.18f)
    val cuts = listOf(0.45f, 0.5f, 0.5f, 0.45f, 2.4f, 2.4f)
    fun toward(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Float): Pair<Float, Float> {
        val dx = b.first - a.first; val dy = b.second - a.second; val len = kotlin.math.sqrt(dx * dx + dy * dy)
        return (a.first + dx / len * d) to (a.second + dy / len * d)
    }
    val p = Path().apply {
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        points.indices.forEach { i ->
            val cur = points[i]
            val start = toward(cur, points[(i + points.size - 1) % points.size], cuts[i])
            val end = toward(cur, points[(i + 1) % points.size], cuts[i])
            if (i == 0) moveTo(start.first*s, start.second*s) else lineTo(start.first*s, start.second*s)
            quadraticTo(cur.first*s, cur.second*s, end.first*s, end.second*s)
        }
        close()
        addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(15.26f*s, 8.23f*s), 1.2f*s))
    }
    drawPath(p, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSfGlyph(kind: MojiIcon, color: Color, cutout: Color, u: Float) {
    fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
    fun stroke(width: Float) = Stroke(width * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, c: Color = color) = drawLine(c, o(x1, y1), o(x2, y2), width * u, StrokeCap.Round)
    fun poly(width: Float, vararg pts: Float, c: Color = color) { val p = Path(); p.moveTo(pts[0] * u, pts[1] * u); var i = 2; while (i < pts.size) { p.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }; drawPath(p, c, style = stroke(width)) }
    fun rr(l: Float, t: Float, r: Float, b: Float, radius: Float) = androidx.compose.ui.geometry.RoundRect(l * u, t * u, r * u, b * u, androidx.compose.ui.geometry.CornerRadius(radius * u))
    fun frame(outer: androidx.compose.ui.geometry.RoundRect, hole: androidx.compose.ui.geometry.RoundRect) {
        val p = Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd; addRoundRect(outer); addRoundRect(hole) }; drawPath(p, color)
    }
    fun outline(l: Float, t: Float, r: Float, b: Float, radius: Float, width: Float) = drawPath(Path().apply { addRoundRect(rr(l, t, r, b, radius)) }, color, style = stroke(width))
    fun folder(l: Float = 3f, t: Float = 6.5f, r: Float = 25f, b: Float = 22.5f) {
        val p = Path(); p.moveTo((l + 2f) * u, t * u); p.lineTo((l + 7f) * u, t * u); p.lineTo((l + 9f) * u, (t + 2.4f) * u); p.lineTo((r - 2f) * u, (t + 2.4f) * u)
        p.quadraticTo(r * u, (t + 2.4f) * u, r * u, (t + 4.4f) * u); p.lineTo(r * u, (b - 2f) * u); p.quadraticTo(r * u, b * u, (r - 2f) * u, b * u)
        p.lineTo((l + 2f) * u, b * u); p.quadraticTo(l * u, b * u, l * u, (b - 2f) * u); p.lineTo(l * u, (t + 2f) * u); p.quadraticTo(l * u, t * u, (l + 2f) * u, t * u); p.close()
        drawPath(p, color, style = stroke(1.5f)); line(l, t + 5.2f, r, t + 5.2f, 1.5f)
    }
    when (kind) {
        MojiIcon.TabChecklist -> {
            drawCircle(color, 4.75f * u, o(6.1f, 7.75f)); poly(1.5f, 3.9f, 8.0f, 5.45f, 9.55f, 8.3f, 5.9f, c = cutout)
            drawCircle(color, 3.85f * u, o(6.1f, 20.2f), style = stroke(1.8f))
            line(14.4f, 7.5f, 25.95f, 7.5f, 1.8f); line(14.4f, 20.4f, 25.95f, 20.4f, 1.8f)
        }
        MojiIcon.TabNote -> {
            frame(rr(2.5f, 3.5f, 25.5f, 24.5f, 3f), rr(4.55f, 8.5f, 23.45f, 22.45f, 1.4f))
            line(8f, 12.25f, 19.85f, 12.25f, 1.2f); line(8f, 15.75f, 19.85f, 15.75f, 1.2f); line(8f, 19.1f, 15.5f, 19.1f, 1.2f)
        }
        MojiIcon.TabTimer -> {
            drawArc(color, -90f, 310f, false, o(3.5f, 3.5f), androidx.compose.ui.geometry.Size(21f * u, 21f * u), style = stroke(1.85f))
            line(14f, 3.5f, 14f, 8.3f, 1.85f)
            val needle = Path(); needle.moveTo(9.2f * u, 9.7f * u); needle.lineTo(10.0f * u, 8.9f * u); needle.lineTo(15.1f * u, 13.0f * u); needle.lineTo(12.9f * u, 15.0f * u); needle.close()
            drawPath(needle, color); drawCircle(color, 1.9f * u, o(14f, 14f))
        }
        MojiIcon.TabChart -> {
            listOf(2.4f to 8.6f, 8.55f to 3.6f, 14.7f to 6.1f, 20.85f to 11.1f).forEach { (x, top) -> drawRoundRect(color, o(x, top), androidx.compose.ui.geometry.Size(4.65f * u, (20.65f - top) * u), androidx.compose.ui.geometry.CornerRadius(1f * u)) }
            line(2.25f, 23.45f, 25.75f, 23.45f, 1.9f)
        }
        MojiIcon.CalendarFill -> {
            frame(rr(3.35f, 4.25f, 24.65f, 23.8f, 2.6f), rr(4.85f, 9.25f, 23.15f, 22.3f, 1.2f))
            val cols = listOf(8.45f, 12.1f, 15.75f, 19.3f)
            listOf(12.15f to 1..3, 15.75f to 0..3, 19.25f to 0..2).forEach { (y, range) -> for (i in range) drawRoundRect(color, o(cols[i] - 0.78f, y - 0.78f), androidx.compose.ui.geometry.Size(1.56f * u, 1.56f * u), androidx.compose.ui.geometry.CornerRadius(0.45f * u)) }
        }
        MojiIcon.GridSF -> for (x in listOf(8.45f, 19.55f)) for (y in listOf(8.45f, 19.55f)) outline(x - 3.33f, y - 3.33f, x + 3.33f, y + 3.33f, 1.6f, 1.55f)
        MojiIcon.ChevronLeft -> poly(2.3f, 18.2f, 6f, 9.8f, 14f, 18.2f, 22f)
        MojiIcon.Disclosure -> poly(2.2f, 10.7f, 8.4f, 17.3f, 14f, 10.7f, 19.6f)
        MojiIcon.Pencil -> {
            val p = Path(); p.moveTo(6f * u, 22f * u); p.lineTo(6.8f * u, 18.2f * u); p.lineTo(18.8f * u, 6.2f * u); p.quadraticTo(20.2f * u, 4.8f * u, 21.6f * u, 6.2f * u)
            p.quadraticTo(23f * u, 7.6f * u, 21.6f * u, 9f * u); p.lineTo(9.6f * u, 21f * u); p.close(); drawPath(p, color, style = stroke(1.6f)); line(17f, 8f, 19.8f, 10.8f, 1.6f)
        }
        MojiIcon.RepeatLoop -> {
            poly(1.7f, 5f, 15f, 5f, 12f, 8f, 9f, 21f, 9f); poly(1.7f, 18f, 6f, 21f, 9f, 18f, 12f)
            poly(1.7f, 23f, 13f, 23f, 16f, 20f, 19f, 7f, 19f); poly(1.7f, 10f, 22f, 7f, 19f, 10f, 16f)
        }
        MojiIcon.BellPlain -> {
            val p = Path(); p.moveTo(8.2f * u, 12f * u); p.cubicTo(8.2f * u, 7.6f * u, 10.6f * u, 5.4f * u, 14f * u, 5.4f * u); p.cubicTo(17.4f * u, 5.4f * u, 19.8f * u, 7.6f * u, 19.8f * u, 12f * u)
            p.lineTo(19.8f * u, 16.4f * u); p.lineTo(22f * u, 19.6f * u); p.lineTo(6f * u, 19.6f * u); p.lineTo(8.2f * u, 16.4f * u); p.close()
            drawPath(p, color, style = stroke(1.6f)); line(14f, 3.4f, 14f, 5.4f, 1.6f)
            drawArc(color, 0f, 180f, false, o(11.6f, 19.6f), androidx.compose.ui.geometry.Size(4.8f * u, 4.4f * u), style = stroke(1.6f))
        }
        MojiIcon.TimerSF -> {
            drawArc(color, -90f, 310f, false, o(4f, 4f), androidx.compose.ui.geometry.Size(20f * u, 20f * u), style = stroke(1.8f))
            line(14f, 4f, 14f, 8.2f, 1.8f); line(9.8f, 9.8f, 14f, 14f, 1.9f); drawCircle(color, 1.6f * u, o(14f, 14f))
        }
        MojiIcon.Folder -> folder()
        MojiIcon.FolderPlus -> { folder(r = 21f); drawCircle(cutout, 5.6f * u, o(21.5f, 20.5f)); line(21.5f, 17f, 21.5f, 24f, 1.6f); line(18f, 20.5f, 25f, 20.5f, 1.6f) }
        MojiIcon.FolderGear -> {
            folder(r = 21f); drawCircle(cutout, 5.8f * u, o(21.5f, 20.5f)); drawCircle(color, 2.9f * u, o(21.5f, 20.5f), style = stroke(1.5f))
            repeat(6) { i -> val a = Math.PI * i / 3; line(21.5f + kotlin.math.cos(a).toFloat() * 3.6f, 20.5f + kotlin.math.sin(a).toFloat() * 3.6f, 21.5f + kotlin.math.cos(a).toFloat() * 4.9f, 20.5f + kotlin.math.sin(a).toFloat() * 4.9f, 1.5f) }
        }
        MojiIcon.ShareUp -> { poly(1.6f, 10f, 11f, 6.5f, 11f, 6.5f, 24f, 21.5f, 24f, 21.5f, 11f, 18f, 11f); line(14f, 3.5f, 14f, 16.5f, 1.6f); poly(1.6f, 10.2f, 7.3f, 14f, 3.5f, 17.8f, 7.3f) }
        MojiIcon.ShareDown -> { poly(1.6f, 10f, 11f, 6.5f, 11f, 6.5f, 24f, 21.5f, 24f, 21.5f, 11f, 18f, 11f); line(14f, 3.5f, 14f, 16.5f, 1.6f); poly(1.6f, 10.2f, 12.7f, 14f, 16.5f, 17.8f, 12.7f) }
        MojiIcon.CalendarPlus -> {
            val p = Path(); p.moveTo(16f * u, 23f * u); p.lineTo(6f * u, 23f * u); p.quadraticTo(3.5f * u, 23f * u, 3.5f * u, 20.5f * u); p.lineTo(3.5f * u, 7.5f * u); p.quadraticTo(3.5f * u, 5f * u, 6f * u, 5f * u)
            p.lineTo(21f * u, 5f * u); p.quadraticTo(23.5f * u, 5f * u, 23.5f * u, 7.5f * u); p.lineTo(23.5f * u, 14f * u); drawPath(p, color, style = stroke(1.5f))
            line(3.5f, 9.8f, 23.5f, 9.8f, 1.5f); line(21.5f, 16.5f, 21.5f, 25f, 1.6f); line(17.25f, 20.75f, 25.75f, 20.75f, 1.6f)
        }
        MojiIcon.ArrowUpRight -> { line(9.5f, 18.5f, 18.3f, 9.7f, 1.8f); poly(1.8f, 11.2f, 9.7f, 18.3f, 9.7f, 18.3f, 16.8f) }
        // "cpu": a chip outline with three pins on every side and a square die inside.
        MojiIcon.Cpu -> {
            outline(7.4f, 7.4f, 20.6f, 20.6f, 1.6f, 1.55f)
            outline(10.9f, 10.9f, 17.1f, 17.1f, 0.9f, 1.45f)
            listOf(11.2f, 14f, 16.8f).forEach { p ->
                line(p, 5.2f, p, 7.4f, 1.45f); line(p, 20.6f, p, 22.8f, 1.45f)
                line(5.2f, p, 7.4f, p, 1.45f); line(20.6f, p, 22.8f, p, 1.45f)
            }
        }
        // "checkmark.circle.fill" / "circle" for draft rows.
        MojiIcon.CheckCircleFill -> { drawCircle(color, 11.5f * u, o(14f, 14f)); poly(2.2f, 9f, 14.3f, 12.6f, 17.8f, 19.2f, 10.4f, c = cutout) }
        MojiIcon.Circle -> drawCircle(color, 10.9f * u, o(14f, 14f), style = stroke(1.6f))
        MojiIcon.Xmark -> { line(8.5f, 8.5f, 19.5f, 19.5f, 2.2f); line(19.5f, 8.5f, 8.5f, 19.5f, 2.2f) }
        // "photo": a framed picture with a sun and two hills.
        MojiIcon.Photo -> {
            outline(3.5f, 5.5f, 24.5f, 22.5f, 2.6f, 1.6f)
            drawCircle(color, 1.9f * u, o(10f, 11f))
            poly(1.6f, 4.2f, 20.5f, 11f, 14.4f, 15.5f, 18.4f, 18.6f, 15.6f, 23.8f, 20.2f)
        }
        // "doc.on.clipboard": a clipboard with a clip.
        MojiIcon.Clipboard -> {
            outline(6f, 5f, 22f, 24.5f, 2.4f, 1.6f)
            drawRoundRect(color, o(10.5f, 3.2f), androidx.compose.ui.geometry.Size(7f * u, 3.8f * u), androidx.compose.ui.geometry.CornerRadius(1.2f * u))
            line(10f, 12f, 18f, 12f, 1.4f); line(10f, 15.5f, 18f, 15.5f, 1.4f); line(10f, 19f, 15f, 19f, 1.4f)
        }
        // "book": an open book, two pages bowed toward the spine.
        MojiIcon.BookSF -> {
            val p = Path()
            p.moveTo(14f * u, 8f * u); p.quadraticTo(9.5f * u, 5.4f * u, 3.6f * u, 6.2f * u); p.lineTo(3.6f * u, 21.4f * u); p.quadraticTo(9.5f * u, 20.6f * u, 14f * u, 23f * u)
            p.quadraticTo(18.5f * u, 20.6f * u, 24.4f * u, 21.4f * u); p.lineTo(24.4f * u, 6.2f * u); p.quadraticTo(18.5f * u, 5.4f * u, 14f * u, 8f * u)
            drawPath(p, color, style = stroke(1.5f)); line(14f, 8f, 14f, 23f, 1.5f)
        }
        // "arrow.up.right.square".
        MojiIcon.ArrowSquare -> { outline(4f, 4f, 24f, 24f, 4.6f, 1.6f); line(10.6f, 17.4f, 17.4f, 10.6f, 1.6f); poly(1.6f, 11.8f, 10.6f, 17.4f, 10.6f, 17.4f, 16.2f) }
        // "doc.text.magnifyingglass": a page with two lines and a lens over its corner.
        MojiIcon.DocMagnify -> {
            outline(5f, 3.5f, 19.5f, 24.5f, 2.6f, 1.5f); line(8.6f, 8.8f, 15.8f, 8.8f, 1.4f); line(8.6f, 12.4f, 12.8f, 12.4f, 1.4f)
            drawCircle(cutout, 6.2f * u, o(18.6f, 18.4f)); drawCircle(color, 4.1f * u, o(18.6f, 18.4f), style = stroke(1.6f)); line(21.6f, 21.4f, 24.6f, 24.4f, 1.8f)
        }
        // "line.3.horizontal.decrease".
        MojiIcon.FilterLines -> { line(4f, 8.5f, 24f, 8.5f, 1.7f); line(7.6f, 14f, 20.4f, 14f, 1.7f); line(11.2f, 19.5f, 16.8f, 19.5f, 1.7f) }
        // "text.viewfinder": four corner marks around three lines of text.
        MojiIcon.TextViewfinder -> {
            poly(1.6f, 4f, 9.5f, 4f, 6.5f, 4.5f, 4.5f, 6.5f, 4f, 9.5f, 4f); poly(1.6f, 18.5f, 4f, 21.5f, 4f, 23.5f, 4.5f, 24f, 6.5f, 24f, 9.5f)
            poly(1.6f, 4f, 18.5f, 4f, 21.5f, 4.5f, 23.5f, 6.5f, 24f, 9.5f, 24f); poly(1.6f, 18.5f, 24f, 21.5f, 24f, 23.5f, 23.5f, 24f, 21.5f, 24f, 18.5f)
            line(9f, 10.5f, 19f, 10.5f, 1.5f); line(9f, 14f, 19f, 14f, 1.5f); line(9f, 17.5f, 15f, 17.5f, 1.5f)
        }
        else -> Unit
    }
}

@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 8.dp),
        color = iosSecondaryLabel,
        fontSize = 13.sp,
    )
}

@Composable
fun rememberDatePicker(onDate: (LocalDate) -> Unit): (LocalDate) -> Unit {
    val context = LocalContext.current
    return { initial ->
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
}

@Composable
fun rememberTimePicker(onTime: (LocalTime) -> Unit): (LocalTime) -> Unit {
    val context = LocalContext.current
    return { initial ->
        TimePickerDialog(context, { _, hour, minute -> onTime(LocalTime.of(hour, minute)) }, initial.hour, initial.minute, true).show()
    }
}

fun categoryName(raw: String): String = when (raw) {
    "study" -> "学习"
    "work" -> "工作"
    else -> raw.removePrefix("custom:").ifBlank { "其他" }
}

fun phaseName(raw: String): String = when (raw) {
    "shortBreak" -> "短休息"
    "longBreak" -> "长休息"
    else -> "专注"
}
