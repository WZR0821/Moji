package com.raydon.moji.android

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
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
fun MojiScreen(sheetFraction: Float? = null, sheetHeight: Dp? = null, content: @Composable () -> Unit) {
    ConfigureDialogWindow(if (sheetFraction != null || sheetHeight != null) 0.12f else null)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    if (window != null) {
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        Box(Modifier.fillMaxSize().padding(top = if (sheetFraction == null && sheetHeight == null) maxOf(70.dp, topInset + 44.dp) else 0.dp), contentAlignment = Alignment.BottomCenter) {
            val sheetSize = if (sheetHeight != null) Modifier.height(sheetHeight) else Modifier.fillMaxHeight(sheetFraction ?: 1f)
            Box(Modifier.fillMaxWidth().then(sheetSize).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))) {
                MojiPaperScreen {
                    Box(Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .imePadding()) { content() }
                }
            }
        }
    } else {
        val density = LocalDensity.current
        val statusInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        // Keep the app's large-title baseline aligned with the iPhone reference,
        // even when Android's status bar is shorter than the iOS safe area.
        Box(Modifier.fillMaxSize().padding(top = maxOf(0.dp, 58.dp - statusInset))) { content() }
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
fun InkBrushMedallion(kind: MojiIcon, tint: Color, size: Dp = 31.dp, seed: Long = 0) {
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
        MojiLineIcon(kind, size * 0.34f, tint)
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
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                IconButton(onClick = { leadingAction?.invoke() }, enabled = leadingAction != null, modifier = Modifier.offset(x = (-10).dp)) {
                    MojiLineIcon(leading, 25.dp)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            actions.forEach { (icon, action) ->
                IconButton(
                    onClick = action,
                    modifier = Modifier.semantics {
                        contentDescription = when (icon) {
                            MojiIcon.Gear -> "打开设置"
                            MojiIcon.Plus -> "添加"
                            MojiIcon.Edit -> "新建备忘"
                            MojiIcon.Ellipsis -> "更多"
                            else -> icon.name
                        }
                    },
                ) { MojiLineIcon(icon, if (icon == MojiIcon.Plus) 23.dp else 22.dp) }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = bottomPadding),
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
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(enabled = leading.isNotEmpty(), role = Role.Button, onClick = onLeading).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIsBack) Box(Modifier.rotate(180f).padding(end = 4.dp)) { MojiLineIcon(MojiIcon.ChevronRight, 17.dp) }
            Text(leading, fontSize = 17.sp, maxLines = 1)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            trailing,
            modifier = Modifier.weight(1f).alpha(if (trailingEnabled) 1f else 0.35f)
                .clickable(enabled = trailingEnabled, role = Role.Button, onClick = onTrailing).padding(vertical = 10.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun MojiLineIcon(kind: MojiIcon, size: Dp = 26.dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    val cutout = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w / 24f
        val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, androidx.compose.ui.geometry.Offset(x1 * s, y1 * s), androidx.compose.ui.geometry.Offset(x2 * s, y2 * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) = drawCircle(color, r * s, androidx.compose.ui.geometry.Offset(x * s, y * s), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        when (kind) {
            MojiIcon.Checklist -> { circle(6f, 6f, 2.2f); line(4.8f,6f,5.8f,7f); line(5.8f,7f,7.4f,4.8f); line(11f,6f,21f,6f); circle(6f,17f,2.2f); line(11f,17f,21f,17f) }
            MojiIcon.Note -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,3f*s), androidx.compose.ui.geometry.Size(18f*s,18f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style=stroke); line(7f,8f,17f,8f); line(7f,12f,17f,12f); line(7f,16f,14f,16f) }
            MojiIcon.Timer -> { circle(12f,13f,8f); line(12f,13f,8f,9f); line(9f,2.5f,15f,2.5f); line(12f,2.5f,12f,5f) }
            MojiIcon.Chart -> { line(3f,21f,22f,21f); listOf(5f to 12f, 10f to 7f, 15f to 10f, 20f to 4f).forEach { (x,y) -> drawRoundRect(color, androidx.compose.ui.geometry.Offset((x-1.5f)*s,y*s), androidx.compose.ui.geometry.Size(3f*s,(21f-y)*s), androidx.compose.ui.geometry.CornerRadius(1f*s)) } }
            MojiIcon.Calendar -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,4.5f*s), androidx.compose.ui.geometry.Size(18f*s,16.5f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style=stroke); line(3f,9f,21f,9f); line(8f,2f,8f,6.5f); line(16f,2f,16f,6.5f); for(x in listOf(7f,12f,17f)) for(y in listOf(13f,17f)) circle(x,y,0.7f,true) }
            MojiIcon.Plus -> { line(12f,3f,12f,21f); line(3f,12f,21f,12f) }
            MojiIcon.Edit -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(3f*s,5f*s), androidx.compose.ui.geometry.Size(15f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style=stroke); line(9f,15f,20.5f,3.5f); line(17.5f,3.5f,20.5f,6.5f) }
            MojiIcon.PencilLine -> { line(4f,17f,16f,5f); line(16f,5f,20f,9f); line(20f,9f,8f,21f); line(4f,17f,4f,21f); line(4f,21f,8f,21f); line(12f,22f,22f,22f) }
            MojiIcon.Sliders -> { line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f); circle(8f,6f,2f,true); circle(16f,12f,2f,true); circle(10f,18f,2f,true) }
            MojiIcon.Repeat -> { line(4f,8f,20f,8f); line(16f,4f,20f,8f); line(20f,8f,16f,12f); line(20f,16f,4f,16f); line(8f,12f,4f,16f); line(4f,16f,8f,20f) }
            MojiIcon.Gear -> { circle(12f,12f,3.2f); circle(12f,12f,8.4f); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f); line(4.2f,4.2f,6.3f,6.3f); line(17.7f,17.7f,19.8f,19.8f); line(19.8f,4.2f,17.7f,6.3f); line(6.3f,17.7f,4.2f,19.8f) }
            MojiIcon.Search -> { circle(10f,10f,6f); line(14.4f,14.4f,21f,21f) }
            MojiIcon.ChevronRight -> { line(9f,4f,16f,12f); line(16f,12f,9f,20f) }
            MojiIcon.Ellipsis -> { circle(5f,12f,1.2f,true); circle(12f,12f,1.2f,true); circle(19f,12f,1.2f,true) }
            MojiIcon.Play -> { val p=Path();p.moveTo(8f*s,5f*s);p.lineTo(19f*s,12f*s);p.lineTo(8f*s,19f*s);p.close();drawPath(p,color) }
            MojiIcon.Pause -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(6f*s,4f*s), androidx.compose.ui.geometry.Size(4f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(s)); drawRoundRect(color, androidx.compose.ui.geometry.Offset(14f*s,4f*s), androidx.compose.ui.geometry.Size(4f*s,16f*s), androidx.compose.ui.geometry.CornerRadius(s)) }
            MojiIcon.Stop -> drawRoundRect(color, androidx.compose.ui.geometry.Offset(6f*s,6f*s), androidx.compose.ui.geometry.Size(12f*s,12f*s), androidx.compose.ui.geometry.CornerRadius(1.5f*s))
            MojiIcon.Pin -> { line(7f,4f,17f,4f); line(9f,4f,9f,10f); line(15f,4f,15f,10f); line(7f,10f,17f,10f); line(12f,10f,12f,21f) }
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
            MojiIcon.Work -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(2f*s,7f*s), androidx.compose.ui.geometry.Size(20f*s,14f*s), androidx.compose.ui.geometry.CornerRadius(2f*s)); drawRoundRect(color, androidx.compose.ui.geometry.Offset(8f*s,3f*s), androidx.compose.ui.geometry.Size(8f*s,6f*s), androidx.compose.ui.geometry.CornerRadius(s), style=stroke) }
            MojiIcon.Tag -> { val p=Path(); p.moveTo(3f*s,3f*s); p.lineTo(12f*s,3f*s); p.lineTo(22f*s,13f*s); p.lineTo(13f*s,22f*s); p.lineTo(3f*s,12f*s);p.close();drawPath(p,color) }
            MojiIcon.TextLines -> { line(3f,5f,20f,5f); line(3f,10f,15f,10f); line(3f,15f,20f,15f); line(3f,20f,12f,20f) }
            MojiIcon.Cup -> { drawRoundRect(color, androidx.compose.ui.geometry.Offset(4f*s,6f*s), androidx.compose.ui.geometry.Size(12f*s,12f*s), androidx.compose.ui.geometry.CornerRadius(4f*s)); circle(17f,11f,4f); line(3f,21f,20f,21f) }
            MojiIcon.Leaf -> { val p=Path(); p.moveTo(3f*s,4f*s); p.cubicTo(18f*s,3f*s,23f*s,10f*s,19f*s,19f*s);p.cubicTo(10f*s,22f*s,5f*s,17f*s,3f*s,4f*s);p.close();drawPath(p,color);drawLine(cutout, androidx.compose.ui.geometry.Offset(7f*s,8f*s), androidx.compose.ui.geometry.Offset(20f*s,19f*s), strokeWidth = s) }
        }
    }
}

@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
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
