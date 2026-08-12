package com.raydon.moji.android

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

enum class MojiIcon {
    Checklist, Note, Timer, Chart, Calendar, Plus, Edit, Gear, Search,
    ChevronRight, Ellipsis, Play, Pause, Stop, Pin, Backup, Hand, Grid, Brush, Info,
}

@Composable
fun MojiScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InkAsset(
            "InkFocusBloom",
            Modifier.size(410.dp).align(Alignment.TopEnd).offset(x = 175.dp, y = (-180).dp).alpha(0.128f),
        )
        InkAsset(
            "InkFocusBloom",
            Modifier.size(360.dp).align(Alignment.BottomStart).offset(x = (-190).dp, y = 155.dp).alpha(0.092f),
        )
        content()
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
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

@Composable
fun InkDivider(modifier: Modifier = Modifier, opacity: Float = 0.30f) {
    InkAsset("InkThinDivider", modifier.fillMaxWidth().height(7.dp).alpha(opacity))
}

@Composable
fun InkRing(modifier: Modifier = Modifier, active: Boolean = false) {
    InkAsset(
        "InkTimerRingDirectional",
        modifier,
        tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
    )
}

@Composable
fun MojiLargeTitle(
    title: String,
    leading: MojiIcon? = null,
    leadingAction: (() -> Unit)? = null,
    actions: List<Pair<MojiIcon, () -> Unit>> = emptyList(),
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().height(54.dp),
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
                ) { MojiLineIcon(icon, if (icon == MojiIcon.Plus) 30.dp else 26.dp) }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 17.dp),
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
) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            leading,
            modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onLeading).padding(vertical = 10.dp),
            fontSize = 17.sp,
        )
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
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
